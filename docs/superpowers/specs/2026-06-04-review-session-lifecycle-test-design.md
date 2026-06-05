# Review Session Lifecycle Test — Design Spec

**Issue:** casehubio/drafthouse#25
**Date:** 2026-06-04 (revised after code review)
**Branch:** issue-25-review-session-lifecycle-it

---

## Problem

`ReviewSessionLifecycleIT.java` has two bugs that make it worthless today:

1. **`*IT.java` naming** (GE-20260512-493c90) — Maven Failsafe collects the class instead of Surefire. `mvn test` reports 0 tests with no error. The class has never run.

2. **Wrong async model** — the existing test uses a `Thread.sleep()` busy-wait loop. In fact, `MessageService.dispatch()` calls `channelGateway.fanOut()` synchronously; `ReviewerChannelBackendFactory.onChannelInitialised()` uses synchronous `@Observes`. The full chain — QUERY dispatch → fanOut → backend.post() → mock review() → RESPONSE dispatch — completes before `dispatch()` returns.

`DebateRoundTripIT.java` has the same naming bug.

---

## Goals

- Make the lifecycle test actually run under `mvn test`
- Prove the QUERY→RESPONSE→Commitment FULFILLED lifecycle end-to-end through the real production entry point (`DraftHouseMcpTools.start_review`)
- Prove DECLINE path and exception-sanitization path with Commitment DECLINED
- Extract session-storage responsibility out of `ReviewerChannelBackendFactory` into a dedicated `ReviewSessionRegistryImpl`
- Separate registry-contract tests (no Quarkus) from lifecycle tests (Quarkus required)
- Fix `DebateRoundTripIT` naming in the same pass

---

## Production Code Change — `ReviewSessionRegistryImpl`

`ReviewerChannelBackendFactory` currently conflates two concerns: session storage (a `ConcurrentHashMap`) and channel-backend registration (an `@Observes ChannelInitialisedEvent` listener). Extracting session storage into its own class eliminates this coupling and gives the registry contract a proper home.

**New class:** `ReviewSessionRegistryImpl implements ReviewSessionRegistry`
- Location: `server/runtime/src/main/java/io/casehub/drafthouse/`
- Annotation: `@ApplicationScoped` (plain — single application-owned implementation; `@DefaultBean` is for library/framework fallback beans and does not apply here)
- Responsibility: owns the `ConcurrentHashMap<UUID, ReviewSession>`, implements all four registry methods (`find`, `put`, `remove`, `updateSelection`)
- No CDI event wiring, no gateway references

**`ReviewerChannelBackendFactory` after refactor:**
- Removes `implements ReviewSessionRegistry` and the `sessions` map
- Adds `@Inject ReviewSessionRegistry registry` and delegates all registry calls to it
- Retains `onChannelInitialised(@Observes ...)` — the backend-registration observer is its sole responsibility

**`DraftHouseMcpTools`:** already injects `ReviewSessionRegistry` — no change needed.

---

## File Changes

| File | Action |
|---|---|
| `ReviewSessionRegistryImpl.java` | New — `@ApplicationScoped`, runtime module |
| `ReviewerChannelBackendFactory.java` | Modify — inject + delegate, drop `implements` and map |
| `ReviewSessionLifecycleIT.java` | Delete |
| `ReviewSessionLifecycleTest.java` | New — `@QuarkusTest`, 4 lifecycle tests |
| `ReviewSessionRegistryTest.java` | New — plain JUnit, 3 registry-contract tests |
| `DebateRoundTripIT.java` | Rename → `DebateRoundTripTest.java` (content unchanged) |

---

## `ReviewSessionRegistryTest` — Design

**Annotation:** None — plain JUnit 5. `ReviewSessionRegistryImpl` has no CDI deps.

```java
class ReviewSessionRegistryTest {
    private final ReviewSessionRegistryImpl registry = new ReviewSessionRegistryImpl();
    ...
}
```

Three tests extracted from the deleted `ReviewSessionLifecycleIT`:
1. `find_returnsSession_afterPut`
2. `remove_clearsSession`
3. `updateSelection_replacesSelectionFields`

---

## `ReviewSessionLifecycleTest` — Design

### Setup

**Annotation:** `@QuarkusTest` — needs real H2 Qhorus datasource, ChannelGateway, CommitmentStore.

**Transaction strategy:** `@TestTransaction` on each test method. `MessageService.dispatch()` is `@Transactional`; without rollback, channels, messages, and commitments accumulate in H2 across tests. `@TestTransaction` rolls back all JPA state after each test. This is correct here because the assertions do not require visibility across transaction boundaries — all assertions happen within the same test transaction.

Note: `@TestTransaction` does not roll back the in-memory `sessions` map in `ReviewSessionRegistryImpl`. This is handled by cleanup (see `@AfterEach` below).

Note on ledger: `LedgerWriteService.record()` runs in `@Transactional(REQUIRES_NEW)` and commits independently of the outer test transaction. After each test rollback, `MessageLedgerEntry` rows remain in H2 — orphaned, with `message_id` values pointing to rolled-back messages. No JPA FK constraints are declared on those columns (`@Column` only), so H2 produces no constraint violations. Tests do not query the ledger directly, so correctness is unaffected. Ledger accumulation is the expected and acceptable side-effect of `@TestTransaction` in this context.

**Injections:**

```java
@TempDir Path tempDir;

@Inject DraftHouseMcpTools tools;
@Inject ChannelService channelService;
@Inject ChannelGateway gateway;
@Inject MessageService messageService;
@Inject CommitmentStore commitmentStore;
@InjectMock DocumentReviewer documentReviewer;

private Path docA, docB;
private String activeSessionId;
```

`@InjectMock DocumentReviewer` replaces the CDI bean at container level. When `onChannelInitialised()` constructs a `ReviewerChannelBackend`, it receives the mock from the injected `ReviewSessionRegistryImpl`-backed factory.

**`@BeforeEach`:** Writes two temp files (`a.md` = "Original text", `b.md` = "Revised text"). Sets the default stub: `when(documentReviewer.review(...)).thenReturn(new ReviewResult(false, "Good revision."))`. Resets `activeSessionId = null`.

**`@AfterEach`:** Calls `tools.endReview(activeSessionId, false)` if `activeSessionId != null`. This removes the session from the in-memory `ReviewSessionRegistryImpl` map — state not covered by `@TestTransaction` rollback.

**Note on `maxDocChars`:** `ReviewerChannelBackend` uses the live `casehub.drafthouse.reviewer.max-doc-chars` from `application.properties` (default 100,000). Test content ("Original text", "Revised text") is well under this limit. If the config were misconfigured, `post()` would silently return a DECLINE. This is acceptable for lifecycle tests.

**Session setup via MCP tools:** Tests 1, 3, and 4 call `tools.startReview(docA.toString(), docB.toString())` and extract `sessionId` from the returned JSON. `startReview()` returns hand-built JSON in the fixed format `{"sessionId":"<uuid>","channel":"<name>"}` with no escaping. Extract with: `result.replaceFirst(".*\"sessionId\":\"([^\"]+)\".*", "$1")`. No JSON library needed. The production code path is exercised fully — file reading, channel creation, instance registration, registry put, and gateway init — without a parallel reimplementation that could drift.

**Session setup for Test 2 (orphaned channel):** Uses `channelService.create()` + `gateway.initChannel()` directly, without calling `registry.put()`. This is intentional — the test exercises the case where a channel exists but has no registered session. The MCP tools path is not used here because it always registers a session before initialising the channel.

### Stub placement

`@BeforeEach` sets the default happy-path stub. Tests 3 and 4 override it with `when(documentReviewer.review(...)).thenReturn(ReviewResult.decline(...))` and `thenThrow(...)` respectively within the test body. Mockito override-after-setup works in this direction.

### Test 1 — Happy path

```
stub: default (@BeforeEach) → ReviewResult(false, "Good revision.")
result = tools.startReview(docA, docB)
sessionId = parse(result)
activeSessionId = sessionId
channelId = UUID.fromString(sessionId)
correlationId = UUID.randomUUID().toString()
dispatch QUERY(channelId, correlationId, "Is this revision clear?")

msg = messageService.findResponseByCorrelationId(channelId, correlationId)
assertThat(msg).isPresent()
assertThat(msg.get().content).isEqualTo("Good revision.")
assertThat(msg.get().sender).isEqualTo("drafthouse-reviewer-" + sessionId)
```

`messageType == RESPONSE` is omitted — `findResponseByCorrelationId` already filters on `MessageType.RESPONSE`; asserting it is vacuous.

CommitmentStore assertion omitted — if `findResponseByCorrelationId` returns a message, `commitmentService.fulfill()` has necessarily been called in the same transaction. The two are not independent observations; the response presence alone is sufficient.

### Test 2 — Orphaned channel drops query

```
channel = channelService.create("drafthouse/orphan-" + UUID, ..., APPEND, null)
gateway.initChannel(channel.id, new ChannelRef(channel.id, channel.name))
// no registry.put() — onChannelInitialised skips, no backend registered

correlationId = UUID.randomUUID().toString()
dispatch QUERY(channel.id, correlationId, "Hello?")

assertThat(messageService.findResponseByCorrelationId(channel.id, correlationId)).isEmpty()
```

No CommitmentStore assertion — no backend fires, so the commitment lifecycle is not exercised.

### Test 3 — Reviewer declines

```
stub override: documentReviewer.review(...) → ReviewResult.decline("Out of scope.")
result = tools.startReview(docA, docB)
sessionId = parse(result); activeSessionId = sessionId
channelId = UUID.fromString(sessionId)
correlationId = UUID.randomUUID().toString()
dispatch QUERY(channelId, correlationId, "Off-topic question")

messages = messageService.findAllByCorrelationId(correlationId)
decline = messages.stream().filter(m -> m.messageType == DECLINE).findFirst()
assertThat(decline).isPresent()
assertThat(decline.get().content).isEqualTo("Out of scope.")
assertThat(commitmentStore.findByCorrelationId(correlationId).get().state).isEqualTo(DECLINED)
```

CommitmentStore assertion is retained here — the `DECLINED` state is independently meaningful. Finding a DECLINE message proves a message was dispatched; the `DECLINED` commitment state proves the speech-act machinery (QUERY → obligation → DECLINE → DECLINED) is correctly wired end-to-end in the normative layer.

### Test 4 — Reviewer throws (exception sanitization)

```
stub override: documentReviewer.review(...) → throws RuntimeException("sk-ant-api03-SECRET-KEY")
result = tools.startReview(docA, docB)
sessionId = parse(result); activeSessionId = sessionId
channelId = UUID.fromString(sessionId)
correlationId = UUID.randomUUID().toString()
dispatch QUERY(channelId, correlationId, "Anything")

messages = messageService.findAllByCorrelationId(correlationId)
decline = messages.stream().filter(m -> m.messageType == DECLINE).findFirst()
assertThat(decline).isPresent()
assertThat(decline.get().content).isEqualTo("Reviewer encountered an error.")
assertThat(decline.get().content).doesNotContain("sk-ant-api03-SECRET-KEY")
assertThat(commitmentStore.findByCorrelationId(correlationId).get().state).isEqualTo(DECLINED)
```

---

## `DebateRoundTripTest`

Rename only. `DebateRoundTripIT` tests pure domain objects (`RoundParser`, `DebateParser`, `SummaryProjector`, `SummaryRenderer`) — all instantiated directly, no CDI, no DB. The `@QuarkusTest` annotation is unnecessary overhead. Removing it is a separate cleanup issue to be filed before closing #25.

---

## Delivery Order

1. Create `ReviewSessionRegistryImpl` in `server/runtime/`
2. Modify `ReviewerChannelBackendFactory` to inject + delegate
3. Delete `ReviewSessionLifecycleIT.java`
4. Create `ReviewSessionRegistryTest.java` (plain JUnit)
5. Create `ReviewSessionLifecycleTest.java` (`@QuarkusTest`)
6. Rename `DebateRoundTripIT.java` → `DebateRoundTripTest.java`
7. File GitHub issue: remove unnecessary `@QuarkusTest` from `DebateRoundTripTest`
8. Run `mvn -f server/pom.xml install -DskipTests && mvn -f server/pom.xml test -pl runtime` — all tests pass

---

## Design Decisions Summary

| Decision | Rationale |
|---|---|
| No Awaitility | Delivery is synchronous — `fanOut()` called in the same thread as `dispatch()` |
| `DraftHouseMcpTools` + `@TempDir` | Exercises real production path; eliminates drift risk from a parallel `startSession()` helper |
| `@TestTransaction` | Rolls back JPA state (channels, messages, commitments) after each test; UUID isolation prevents collision |
| `@AfterEach endReview()` | Cleans up in-memory session map not covered by `@TestTransaction` rollback |
| Drop CommitmentStore from Test 1 | Tautological — RESPONSE found implies FULFILLED; not independent |
| Keep CommitmentStore in Tests 3/4 | DECLINED state is independently meaningful: proves normative layer wiring, not just message dispatch |
| `findAllByCorrelationId` for Tests 3/4 | `findResponseByCorrelationId` hard-codes `MessageType.RESPONSE` filter; won't find DECLINE messages |
| `CommitmentState.DECLINED` (not CANCELLED) | Enum values: OPEN, ACKNOWLEDGED, FULFILLED, DECLINED, FAILED, DELEGATED, EXPIRED — no CANCELLED |
