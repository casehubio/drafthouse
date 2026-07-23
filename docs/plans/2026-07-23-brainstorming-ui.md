# Brainstorming UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> subagent-driven-development (recommended) or executing-plans to
> implement this plan task-by-task. Each task follows TDD
> (test-driven-development) and uses ide-tooling for structural
> editing. Steps use checkbox (`- [ ]`) syntax for tracking.

**Focal issue:** #53 — Brainstorming UI — richer option exploration beyond terminal
**Issue group:** #53

**Goal:** Add interactive brainstorming option cards, a session switcher, and
browser-initiated state changes to DraftHouse's brainstorm mode.

**Architecture:** Extract `BrainstormService` from `BrainstormMcpTools` to own
mutation + event logic. Add `BrainstormResource` REST endpoints for browser
actions. Create `<brainstorm-options>` and `<brainstorm-picker>` Lit panels.
Wire into the brainstorm layout.

**Tech Stack:** Java 21 / Quarkus 3.34.3 / JAX-RS / CDI / Lit 3 / TypeScript

## Global Constraints

- State transitions on `BrainstormOption` are guarded — invalid transitions
  throw `IllegalStateException`
- All `BrainstormService` mutations synchronize on the session object
- Only one option can be RECOMMENDED at a time
- ELIMINATED and SELECTED are terminal states (no transitions out)
- Browser REST accepts only `{ELIMINATED, RECOMMENDED, SELECTED}` statuses
- Panels use Shadow DOM, `static styles = css`, `_cleanups[]` teardown,
  `@property`/`@state` decorators, `configure(props)` for pages-runtime

---

### Task 1: State transition guards on BrainstormOption

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/BrainstormOption.java`
- Modify: `server/api/src/test/java/io/casehub/drafthouse/BrainstormSessionTest.java`

**Interfaces:**
- Produces: `BrainstormOption.transitionTo(Status target)` — guarded status
  transition method. Replaces `setStatus()`.

- [ ] **Step 1: Write failing tests for guarded transitions**

Add to `BrainstormSessionTest.java`:

```java
@Test
void transitionTo_liveToRecommended_succeeds() {
    var option = new BrainstormOption("A", "A", "A", "A");
    option.transitionTo(BrainstormOption.Status.RECOMMENDED);
    assertThat(option.status()).isEqualTo(BrainstormOption.Status.RECOMMENDED);
}

@Test
void transitionTo_liveToExplored_succeeds() {
    var option = new BrainstormOption("A", "A", "A", "A");
    option.transitionTo(BrainstormOption.Status.EXPLORED);
    assertThat(option.status()).isEqualTo(BrainstormOption.Status.EXPLORED);
}

@Test
void transitionTo_liveToEliminated_succeeds() {
    var option = new BrainstormOption("A", "A", "A", "A");
    option.transitionTo(BrainstormOption.Status.ELIMINATED);
    assertThat(option.status()).isEqualTo(BrainstormOption.Status.ELIMINATED);
}

@Test
void transitionTo_liveToSelected_succeeds() {
    var option = new BrainstormOption("A", "A", "A", "A");
    option.transitionTo(BrainstormOption.Status.SELECTED);
    assertThat(option.status()).isEqualTo(BrainstormOption.Status.SELECTED);
}

@Test
void transitionTo_eliminatedToAnything_throws() {
    var option = new BrainstormOption("A", "A", "A", "A");
    option.transitionTo(BrainstormOption.Status.ELIMINATED);
    assertThatThrownBy(() -> option.transitionTo(BrainstormOption.Status.RECOMMENDED))
            .isInstanceOf(IllegalStateException.class);
}

@Test
void transitionTo_selectedToAnything_throws() {
    var option = new BrainstormOption("A", "A", "A", "A");
    option.transitionTo(BrainstormOption.Status.SELECTED);
    assertThatThrownBy(() -> option.transitionTo(BrainstormOption.Status.LIVE))
            .isInstanceOf(IllegalStateException.class);
}

@Test
void transitionTo_exploredToExplored_isNoOp() {
    var option = new BrainstormOption("A", "A", "A", "A");
    option.transitionTo(BrainstormOption.Status.EXPLORED);
    option.transitionTo(BrainstormOption.Status.EXPLORED);
    assertThat(option.status()).isEqualTo(BrainstormOption.Status.EXPLORED);
}

@Test
void transitionTo_recommendedToExplored_succeeds() {
    var option = new BrainstormOption("A", "A", "A", "A");
    option.transitionTo(BrainstormOption.Status.RECOMMENDED);
    option.transitionTo(BrainstormOption.Status.EXPLORED);
    assertThat(option.status()).isEqualTo(BrainstormOption.Status.EXPLORED);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=BrainstormSessionTest`
Expected: FAIL — `transitionTo` method doesn't exist

- [ ] **Step 3: Implement guarded transitions**

Replace `setStatus()` in `BrainstormOption` with:

```java
private static final java.util.EnumSet<Status> TERMINAL =
        java.util.EnumSet.of(Status.ELIMINATED, Status.SELECTED);

public void transitionTo(Status target) {
    if (target == this.status) return; // idempotent no-op
    if (TERMINAL.contains(this.status)) {
        throw new IllegalStateException(
                "Cannot transition from terminal status " + this.status);
    }
    this.status = target;
}
```

Remove `setStatus()`. This will break callers — fix in later steps.

- [ ] **Step 4: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=BrainstormSessionTest`
Expected: PASS

- [ ] **Step 5: Update existing tests that used setStatus()**

The `optionStatusTransitions` test in `BrainstormSessionTest` calls `setStatus()` —
update it to use `transitionTo()`. Remove or update accordingly.

- [ ] **Step 6: Fix BrainstormSession.markSelected() to use transitionTo()**

In `BrainstormSession.markSelected()`, change:
```java
option.setStatus(BrainstormOption.Status.SELECTED);
```
to:
```java
option.transitionTo(BrainstormOption.Status.SELECTED);
```

- [ ] **Step 7: Run full api module tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl api`
Expected: PASS

- [ ] **Step 8: Add setRecommendation() to BrainstormSession**

Add test first:

```java
@Test
void setRecommendation_setsRecommended_clearsPrevious() {
    var session = new BrainstormSession("bs-1");
    session.addOption(new BrainstormOption("A", "A", "A", "A"));
    session.addOption(new BrainstormOption("B", "B", "B", "B"));

    session.setRecommendation("A");
    assertThat(session.findOption("A").get().status())
            .isEqualTo(BrainstormOption.Status.RECOMMENDED);

    session.setRecommendation("B");
    assertThat(session.findOption("B").get().status())
            .isEqualTo(BrainstormOption.Status.RECOMMENDED);
    assertThat(session.findOption("A").get().status())
            .isEqualTo(BrainstormOption.Status.EXPLORED);
}
```

Then implement in `BrainstormSession`:

```java
public void setRecommendation(String optionId) {
    if (state != State.ACTIVE) {
        throw new IllegalStateException("Cannot recommend in a " + state + " session");
    }
    BrainstormOption target = findOption(optionId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown option: " + optionId));
    options.stream()
            .filter(o -> o.status() == BrainstormOption.Status.RECOMMENDED)
            .forEach(o -> o.transitionTo(BrainstormOption.Status.EXPLORED));
    target.transitionTo(BrainstormOption.Status.RECOMMENDED);
    touch();
}
```

- [ ] **Step 9: Run full api module tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl api`
Expected: PASS

- [ ] **Step 10: Verify with ide_diagnostics and commit**

Run: `ide_diagnostics` on both changed files.

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/api/
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(#53): guarded state transitions on BrainstormOption + setRecommendation

Refs #53"
```

---

### Task 2: Extract BrainstormService

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/BrainstormService.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/BrainstormServiceTest.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/BrainstormMcpTools.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/BrainstormMcpToolsTest.java`

**Interfaces:**
- Consumes: `BrainstormOption.transitionTo()`, `BrainstormSession.setRecommendation()`
  (from Task 1)
- Produces: `BrainstormService` CDI bean with `startSession()`, `presentOptions()`,
  `updateOption()`, `setRecommendation()`, `markEliminated()`, `markSelected()`,
  `endSession()` — all synchronized on the session object.

- [ ] **Step 1: Write failing BrainstormServiceTest**

```java
@QuarkusTest
class BrainstormServiceTest {

    @Inject BrainstormService service;
    @Inject BrainstormSessionRegistry registry;

    private String activeSessionId;

    @AfterEach
    void tearDown() {
        if (activeSessionId != null) {
            registry.remove(activeSessionId);
        }
    }

    @Test
    void startSession_createsActiveSession() {
        activeSessionId = service.startSession();
        assertThat(activeSessionId).startsWith("bs-");
        assertThat(registry.find(activeSessionId)).isPresent();
    }

    @Test
    void presentOptions_addsToSession() {
        activeSessionId = service.startSession();
        service.presentOptions(activeSessionId, List.of(
                new BrainstormService.OptionInput("A", "Title A", "Desc A", "Trade A"),
                new BrainstormService.OptionInput("B", "Title B", "Desc B", "Trade B")));
        assertThat(registry.find(activeSessionId).get().options()).hasSize(2);
    }

    @Test
    void markEliminated_setsTerminalStatus() {
        activeSessionId = service.startSession();
        service.presentOptions(activeSessionId, List.of(
                new BrainstormService.OptionInput("A", "A", "A", "A")));
        service.markEliminated(activeSessionId, "A");
        assertThat(registry.find(activeSessionId).get().findOption("A").get().status())
                .isEqualTo(BrainstormOption.Status.ELIMINATED);
    }

    @Test
    void markEliminated_onTerminalStatus_throws() {
        activeSessionId = service.startSession();
        service.presentOptions(activeSessionId, List.of(
                new BrainstormService.OptionInput("A", "A", "A", "A")));
        service.markEliminated(activeSessionId, "A");
        assertThatThrownBy(() -> service.markEliminated(activeSessionId, "A"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setRecommendation_singleRecommendation() {
        activeSessionId = service.startSession();
        service.presentOptions(activeSessionId, List.of(
                new BrainstormService.OptionInput("A", "A", "A", "A"),
                new BrainstormService.OptionInput("B", "B", "B", "B")));
        service.setRecommendation(activeSessionId, "A");
        service.setRecommendation(activeSessionId, "B");
        var session = registry.find(activeSessionId).get();
        assertThat(session.findOption("A").get().status()).isEqualTo(BrainstormOption.Status.EXPLORED);
        assertThat(session.findOption("B").get().status()).isEqualTo(BrainstormOption.Status.RECOMMENDED);
    }

    @Test
    void markSelected_convergesSession() {
        activeSessionId = service.startSession();
        service.presentOptions(activeSessionId, List.of(
                new BrainstormService.OptionInput("A", "A", "A", "A")));
        service.markSelected(activeSessionId, "A");
        assertThat(registry.find(activeSessionId).get().state())
                .isEqualTo(BrainstormSession.State.CONVERGED);
        activeSessionId = null;
    }

    @Test
    void endSession_removesFromRegistry() {
        activeSessionId = service.startSession();
        service.endSession(activeSessionId);
        assertThat(registry.find(activeSessionId)).isEmpty();
        activeSessionId = null;
    }

    @Test
    void unknownSession_throws() {
        assertThatThrownBy(() -> service.markEliminated("nonexistent", "A"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=BrainstormServiceTest`
Expected: FAIL — `BrainstormService` doesn't exist

- [ ] **Step 3: Implement BrainstormService**

Create `BrainstormService.java` with `ide_create_file`:

```java
package io.casehub.drafthouse;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class BrainstormService {

    private static final Logger LOG = Logger.getLogger(BrainstormService.class.getName());

    public record OptionInput(String id, String title, String description, String tradeoffs) {}

    @Inject BrainstormSessionRegistry registry;
    @Inject WebSocketEventBus eventBus;

    public String startSession() {
        String sessionId = "bs-" + UUID.randomUUID();
        BrainstormSession session = new BrainstormSession(sessionId);
        registry.put(session);
        eventBus.broadcast("brainstorm-session-created", Map.of("sessionId", sessionId));
        return sessionId;
    }

    public void presentOptions(String sessionId, List<OptionInput> inputs) {
        BrainstormSession session = resolve(sessionId);
        synchronized (session) {
            for (OptionInput input : inputs) {
                session.addOption(new BrainstormOption(
                        input.id(), input.title(),
                        input.description() != null ? input.description() : "",
                        input.tradeoffs() != null ? input.tradeoffs() : ""));
            }
            session.touch();
        }
        pushOptionsEvent(session);
    }

    public void updateOption(String sessionId, String optionId,
                             String description, String tradeoffs) {
        BrainstormSession session = resolve(sessionId);
        synchronized (session) {
            BrainstormOption option = resolveOption(session, optionId);
            option.setDescription(description);
            option.setTradeoffs(tradeoffs);
            option.transitionTo(BrainstormOption.Status.EXPLORED);
            session.touch();
        }
        pushOptionsEvent(session);
    }

    public void setRecommendation(String sessionId, String optionId) {
        BrainstormSession session = resolve(sessionId);
        synchronized (session) {
            session.setRecommendation(optionId);
        }
        pushOptionsEvent(session);
    }

    public void markEliminated(String sessionId, String optionId) {
        BrainstormSession session = resolve(sessionId);
        synchronized (session) {
            BrainstormOption option = resolveOption(session, optionId);
            option.transitionTo(BrainstormOption.Status.ELIMINATED);
            session.touch();
        }
        pushOptionsEvent(session);
    }

    public void markSelected(String sessionId, String optionId) {
        BrainstormSession session = resolve(sessionId);
        synchronized (session) {
            session.markSelected(optionId);
        }
        pushConvergedEvent(session);
    }

    public void endSession(String sessionId) {
        BrainstormSession session = resolve(sessionId);
        synchronized (session) {
            if (session.state() == BrainstormSession.State.ACTIVE) {
                session.abandon();
            }
        }
        eventBus.pushBrainstormEvent(sessionId, "brainstorm-ended",
                Map.of("sessionId", sessionId, "state", session.state().name()));
        registry.remove(sessionId);
    }

    BrainstormSession resolve(String sessionId) {
        return registry.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Brainstorm session not found: " + sessionId));
    }

    private BrainstormOption resolveOption(BrainstormSession session, String optionId) {
        return session.findOption(optionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown option: " + optionId));
    }

    void pushOptionsEvent(BrainstormSession session) {
        var optionMaps = buildOptionMaps(session);
        eventBus.pushBrainstormEvent(session.sessionId(), "brainstorm-options",
                Map.of("sessionId", session.sessionId(),
                       "options", optionMaps,
                       "state", session.state().name()));
    }

    private void pushConvergedEvent(BrainstormSession session) {
        var optionMaps = buildOptionMaps(session);
        eventBus.pushBrainstormEvent(session.sessionId(), "brainstorm-converged",
                Map.of("sessionId", session.sessionId(),
                       "options", optionMaps,
                       "state", session.state().name(),
                       "selectedOptionId", session.options().stream()
                               .filter(o -> o.status() == BrainstormOption.Status.SELECTED)
                               .map(BrainstormOption::id)
                               .findFirst().orElse("")));
    }

    private List<Map<String, String>> buildOptionMaps(BrainstormSession session) {
        synchronized (session) {
            return session.options().stream().map(o -> Map.of(
                    "id", o.id(),
                    "title", o.title(),
                    "description", o.description(),
                    "tradeoffs", o.tradeoffs(),
                    "status", o.status().name()
            )).toList();
        }
    }
}
```

- [ ] **Step 4: Run BrainstormServiceTest**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=BrainstormServiceTest`
Expected: PASS

- [ ] **Step 5: Refactor BrainstormMcpTools to delegate to BrainstormService**

Replace all mutation logic in `BrainstormMcpTools` with delegation to
`BrainstormService`. Each `@Tool` method becomes a thin wrapper:
- Parse args (JSON for `presentOptions`, simple params for others)
- Call `service.method()`
- Catch exceptions, return `"error: ..."` strings
- Return success strings

Remove `WebSocketEventBus` and `ObjectMapper` injects — only `BrainstormService`
and `ObjectMapper` (for JSON parsing in `presentOptions`) are needed.

Remove `pushOptionsEvent()`, `pushConvergedEvent()`, `resolveSession()`,
`sessionError()` private methods — logic moved to service.

- [ ] **Step 6: Run existing BrainstormMcpToolsTest**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=BrainstormMcpToolsTest`
Expected: PASS — behaviour unchanged

- [ ] **Step 7: Verify with ide_diagnostics and commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "refactor(#53): extract BrainstormService from BrainstormMcpTools

Refs #53"
```

---

### Task 3: BrainstormResource REST endpoints

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/BrainstormResource.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/BrainstormResourceTest.java`

**Interfaces:**
- Consumes: `BrainstormService.markEliminated()`, `.setRecommendation()`,
  `.markSelected()`, `.resolve()`, `BrainstormSessionRegistry.activeSessions()`
  (from Task 2)
- Produces: `PATCH /api/brainstorm/{sessionId}/options/{optionId}` with
  `{"status": "ELIMINATED|RECOMMENDED|SELECTED"}` body.
  `GET /api/brainstorm/sessions` returning session list.

- [ ] **Step 1: Write failing BrainstormResourceTest**

```java
@QuarkusTest
class BrainstormResourceTest {

    @Inject BrainstormService service;
    @Inject BrainstormSessionRegistry registry;

    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = service.startSession();
        service.presentOptions(sessionId, List.of(
                new BrainstormService.OptionInput("A", "Option A", "Desc A", "Trade A"),
                new BrainstormService.OptionInput("B", "Option B", "Desc B", "Trade B")));
    }

    @AfterEach
    void tearDown() {
        if (sessionId != null) registry.remove(sessionId);
    }

    @Test
    void patchOption_eliminate_returns200() {
        given()
            .contentType("application/json")
            .body("{\"status\":\"ELIMINATED\"}")
        .when()
            .patch("/api/brainstorm/" + sessionId + "/options/A")
        .then()
            .statusCode(200);

        assertThat(registry.find(sessionId).get().findOption("A").get().status())
                .isEqualTo(BrainstormOption.Status.ELIMINATED);
    }

    @Test
    void patchOption_recommend_returns200() {
        given()
            .contentType("application/json")
            .body("{\"status\":\"RECOMMENDED\"}")
        .when()
            .patch("/api/brainstorm/" + sessionId + "/options/A")
        .then()
            .statusCode(200);
    }

    @Test
    void patchOption_select_convergesSession() {
        given()
            .contentType("application/json")
            .body("{\"status\":\"SELECTED\"}")
        .when()
            .patch("/api/brainstorm/" + sessionId + "/options/A")
        .then()
            .statusCode(200);

        assertThat(registry.find(sessionId).get().state())
                .isEqualTo(BrainstormSession.State.CONVERGED);
        sessionId = null;
    }

    @Test
    void patchOption_invalidStatus_returns400() {
        given()
            .contentType("application/json")
            .body("{\"status\":\"EXPLORED\"}")
        .when()
            .patch("/api/brainstorm/" + sessionId + "/options/A")
        .then()
            .statusCode(400);
    }

    @Test
    void patchOption_unknownSession_returns404() {
        given()
            .contentType("application/json")
            .body("{\"status\":\"ELIMINATED\"}")
        .when()
            .patch("/api/brainstorm/nonexistent/options/A")
        .then()
            .statusCode(404);
    }

    @Test
    void patchOption_invalidTransition_returns409() {
        service.markEliminated(sessionId, "A");

        given()
            .contentType("application/json")
            .body("{\"status\":\"RECOMMENDED\"}")
        .when()
            .patch("/api/brainstorm/" + sessionId + "/options/A")
        .then()
            .statusCode(409);
    }

    @Test
    void getSessions_returnsActiveSessions() {
        given()
        .when()
            .get("/api/brainstorm/sessions")
        .then()
            .statusCode(200)
            .body("size()", org.hamcrest.Matchers.greaterThanOrEqualTo(1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=BrainstormResourceTest`
Expected: FAIL — endpoints don't exist

- [ ] **Step 3: Implement BrainstormResource**

Create `BrainstormResource.java` with `ide_create_file`:

```java
package io.casehub.drafthouse;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/api/brainstorm")
public class BrainstormResource {

    private static final Set<String> BROWSER_STATUSES =
            Set.of("ELIMINATED", "RECOMMENDED", "SELECTED");

    record StatusRequest(String status) {}
    record SessionInfo(String sessionId, String state, int optionCount) {}

    @Inject BrainstormService service;
    @Inject BrainstormSessionRegistry registry;
    @Inject WebSocketEventBus eventBus;

    @PATCH
    @Path("/{sessionId}/options/{optionId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response patchOption(
            @PathParam("sessionId") String sessionId,
            @PathParam("optionId") String optionId,
            StatusRequest request) {

        if (request == null || request.status() == null
                || !BROWSER_STATUSES.contains(request.status())) {
            return Response.status(400)
                    .entity(Map.of("error", "status must be one of: " + BROWSER_STATUSES))
                    .build();
        }

        BrainstormSession session;
        try {
            session = service.resolve(sessionId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage());
        }

        BrainstormOption option = session.findOption(optionId).orElse(null);
        if (option == null) {
            throw new NotFoundException("Unknown option: " + optionId);
        }

        try {
            switch (request.status()) {
                case "ELIMINATED" -> service.markEliminated(sessionId, optionId);
                case "RECOMMENDED" -> service.setRecommendation(sessionId, optionId);
                case "SELECTED" -> service.markSelected(sessionId, optionId);
            }
        } catch (IllegalStateException e) {
            return Response.status(409)
                    .entity(Map.of("error", e.getMessage())).build();
        }

        String optionTitle = option.title();
        eventBus.broadcast("brainstorm-user-action", Map.of(
                "sessionId", sessionId,
                "optionId", optionId,
                "optionTitle", optionTitle,
                "action", request.status()));

        return Response.ok(Map.of("status", "ok")).build();
    }

    @GET
    @Path("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    public List<SessionInfo> sessions() {
        return registry.activeSessions().stream()
                .map(s -> new SessionInfo(s.sessionId(), s.state().name(), s.options().size()))
                .toList();
    }
}
```

- [ ] **Step 4: Run BrainstormResourceTest**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=BrainstormResourceTest`
Expected: PASS

- [ ] **Step 5: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: PASS

- [ ] **Step 6: Verify with ide_diagnostics and commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(#53): BrainstormResource REST endpoints with terminal notification

Refs #53"
```

---

### Task 4: Brainstorm session list on WebSocket connect

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateWebSocket.java`

**Interfaces:**
- Consumes: `BrainstormSessionRegistry.activeSessions()` (existing)
- Produces: `brainstorm-sessions` event sent on `onOpen()` alongside existing
  `sessions` (debate) event

- [ ] **Step 1: Update DebateWebSocket.onOpen()**

Add after the existing `sendEvent(connection, "sessions", sessions);` line:

```java
var brainstormSessions = brainstormRegistry.activeSessions().stream()
        .map(s -> Map.of(
                "sessionId", s.sessionId(),
                "state", s.state().name(),
                "optionCount", String.valueOf(s.options().size())))
        .toList();
sendEvent(connection, "brainstorm-sessions", brainstormSessions);
```

- [ ] **Step 2: Run full test suite to verify no regression**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/java/
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(#53): send brainstorm-sessions on WebSocket connect

Refs #53"
```

---

### Task 5: `<brainstorm-options>` Lit panel

**Files:**
- Create: `server/runtime/src/main/webui/src/panels/brainstorm-options.ts`

**Interfaces:**
- Consumes: `brainstorm-options` WebSocket event (option list + statuses),
  `brainstorm-converged` event, `brainstorm-ended` event, `reconnected` event.
  `PATCH /api/brainstorm/{sessionId}/options/{optionId}` (from Task 3).
- Produces: `<brainstorm-options>` custom element with `configure({ sessionId })`

- [ ] **Step 1: Create the panel**

Create `brainstorm-options.ts` following channel-feed and doc-picker patterns:

```typescript
import { LitElement, html, css, nothing } from 'lit';
import { customElement, property, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

interface BrainstormOptionData {
  id: string;
  title: string;
  description: string;
  tradeoffs: string;
  status: string;
}

interface OptionsPayload {
  sessionId: string;
  options: BrainstormOptionData[];
  state: string;
}

interface ConvergedPayload extends OptionsPayload {
  selectedOptionId: string;
}

@customElement('brainstorm-options')
export class BrainstormOptions extends LitElement {
  @property() sessionId: string | null = null;

  @state() private _options: BrainstormOptionData[] = [];
  @state() private _sessionState: string = 'ACTIVE';
  @state() private _ended = false;
  @state() private _errorMessage: string | null = null;

  private _cleanups: (() => void)[] = [];
  private _errorTimeout: ReturnType<typeof setTimeout> | null = null;

  configure(props: Record<string, unknown>): void {
    if (props.sessionId !== undefined) {
      this.sessionId = props.sessionId as string;
      this._options = [];
      this._sessionState = 'ACTIVE';
      this._ended = false;
    }
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this._cleanups.push(
      onPagesEvent<OptionsPayload>(document, 'brainstorm-options', (payload) => {
        if (this.sessionId && payload.sessionId === this.sessionId) {
          this._options = payload.options;
          this._sessionState = payload.state;
        }
      }),
      onPagesEvent<ConvergedPayload>(document, 'brainstorm-converged', (payload) => {
        if (this.sessionId && payload.sessionId === this.sessionId) {
          this._options = payload.options;
          this._sessionState = payload.state;
        }
      }),
      onPagesEvent<{ sessionId: string }>(document, 'brainstorm-ended', (payload) => {
        if (this.sessionId && payload.sessionId === this.sessionId) {
          this._ended = true;
        }
      }),
      onPagesEvent(document, 'reconnected', () => {
        this._options = [];
        this._sessionState = 'ACTIVE';
        this._ended = false;
      }),
    );
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
    if (this._errorTimeout) clearTimeout(this._errorTimeout);
  }

  private async _patchStatus(optionId: string, status: string): Promise<void> {
    if (!this.sessionId) return;
    try {
      const res = await fetch(
        `/api/brainstorm/${this.sessionId}/options/${optionId}`,
        {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ status }),
        });
      if (!res.ok) {
        const body = await res.json().catch(() => ({ error: 'Request failed' }));
        this._showError(body.error || `Status ${res.status}`);
      }
    } catch {
      this._showError('Network error');
    }
  }

  private _showError(msg: string): void {
    if (this._errorTimeout) clearTimeout(this._errorTimeout);
    this._errorMessage = msg;
    this._errorTimeout = setTimeout(() => {
      this._errorMessage = null;
      this._errorTimeout = null;
    }, 3000);
  }

  private _isActionable(status: string): boolean {
    return !this._ended && this._sessionState === 'ACTIVE'
        && status !== 'ELIMINATED' && status !== 'SELECTED';
  }

  static override styles = css`
    :host {
      display: flex;
      flex-direction: column;
      height: 100%;
      overflow-y: auto;
      padding: 12px;
      gap: 12px;
      background: var(--bg);
      color: var(--ink);
      font-family: var(--font-sans, system-ui, sans-serif);
    }

    .summary {
      font-size: 12px;
      color: var(--muted);
      padding: 4px 0;
    }

    .card {
      border: 1px solid var(--border);
      border-radius: 6px;
      padding: 12px;
      background: var(--chrome);
    }

    .card.recommended {
      border-color: var(--accent, #2563eb);
      box-shadow: 0 0 0 1px var(--accent, #2563eb);
    }

    .card.eliminated {
      opacity: 0.5;
    }

    .card.eliminated .card-title { text-decoration: line-through; }

    .card.selected {
      border-color: var(--success, #16a34a);
      box-shadow: 0 0 0 2px var(--success, #16a34a);
    }

    .card-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
    }

    .card-title {
      font-weight: 600;
      font-size: 14px;
      flex: 1;
    }

    .status-badge {
      font-size: 10px;
      padding: 2px 6px;
      border-radius: 3px;
      text-transform: uppercase;
      font-weight: 600;
    }

    .status-badge.live { background: var(--chrome); color: var(--muted); }
    .status-badge.recommended { background: var(--accent, #2563eb); color: #fff; }
    .status-badge.explored { background: var(--info-bg, #dbeafe); color: var(--info, #1d4ed8); }
    .status-badge.eliminated { background: var(--error-bg, #fef2f2); color: var(--error, #dc2626); }
    .status-badge.selected { background: var(--success, #16a34a); color: #fff; }

    .card-body {
      font-size: 13px;
      line-height: 1.5;
    }

    .card-body p { margin: 4px 0; }

    .tradeoffs {
      font-size: 12px;
      color: var(--muted);
      margin-top: 8px;
      padding-top: 8px;
      border-top: 1px solid var(--border);
    }

    .actions {
      display: flex;
      gap: 6px;
      margin-top: 10px;
    }

    .actions button {
      font-size: 11px;
      padding: 3px 10px;
      border-radius: 3px;
      border: 1px solid var(--border);
      background: var(--chrome);
      color: var(--ink);
      cursor: pointer;
    }

    .actions button:hover:not(:disabled) { background: var(--accent-light); }
    .actions button:disabled { opacity: 0.4; cursor: default; }

    .banner {
      padding: 8px 12px;
      border-radius: 4px;
      font-size: 12px;
      text-align: center;
    }

    .banner.ended { background: var(--warning-bg, #fefce8); color: var(--warning, #a16207); }
    .banner.converged { background: var(--success-bg, #f0fdf4); color: var(--success, #16a34a); }

    .error-flash {
      padding: 6px 12px;
      font-size: 12px;
      color: var(--error, #dc2626);
      background: var(--error-bg, #fef2f2);
      border-radius: 4px;
    }

    .empty {
      display: flex;
      align-items: center;
      justify-content: center;
      height: 100%;
      color: var(--muted);
      font-size: 13px;
    }
  `;

  override render() {
    if (this._options.length === 0) {
      return html`<div class="empty">Waiting for options…</div>`;
    }

    const live = this._options.filter(o => o.status === 'LIVE').length;
    const eliminated = this._options.filter(o => o.status === 'ELIMINATED').length;
    const recommended = this._options.filter(o => o.status === 'RECOMMENDED').length;
    const selected = this._options.filter(o => o.status === 'SELECTED').length;

    const parts: string[] = [];
    parts.push(`${this._options.length} options`);
    if (eliminated) parts.push(`${eliminated} eliminated`);
    if (recommended) parts.push(`${recommended} recommended`);
    if (selected) parts.push(`${selected} selected`);

    return html`
      <div class="summary">${parts.join(' · ')}</div>

      ${this._ended ? html`<div class="banner ended">Session ended</div>` : nothing}
      ${this._sessionState === 'CONVERGED' && !this._ended
        ? html`<div class="banner converged">Converged</div>` : nothing}

      ${this._options.map(o => html`
        <div class="card ${o.status.toLowerCase()}">
          <div class="card-header">
            <span class="card-title">${o.title}</span>
            <span class="status-badge ${o.status.toLowerCase()}">${o.status}</span>
          </div>
          <div class="card-body">
            <p>${o.description}</p>
          </div>
          ${o.tradeoffs ? html`<div class="tradeoffs">${o.tradeoffs}</div>` : nothing}
          ${this._isActionable(o.status) ? html`
            <div class="actions">
              ${o.status !== 'RECOMMENDED' ? html`
                <button @click=${() => this._patchStatus(o.id, 'RECOMMENDED')}>Recommend</button>
              ` : nothing}
              <button @click=${() => this._patchStatus(o.id, 'ELIMINATED')}>Eliminate</button>
              <button @click=${() => this._patchStatus(o.id, 'SELECTED')}>Select</button>
            </div>
          ` : nothing}
        </div>
      `)}

      ${this._errorMessage ? html`<div class="error-flash">${this._errorMessage}</div>` : nothing}
    `;
  }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests -pl runtime`
Expected: BUILD SUCCESS (Quinoa bundles TypeScript)

- [ ] **Step 3: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/webui/
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(#53): <brainstorm-options> Lit panel with interactive cards

Refs #53"
```

---

### Task 6: `<brainstorm-picker>` topbar element + layout wiring

**Files:**
- Create: `server/runtime/src/main/webui/src/panels/brainstorm-picker.ts`
- Modify: `server/runtime/src/main/webui/src/index.ts`

**Interfaces:**
- Consumes: `brainstorm-sessions`, `brainstorm-session-created`,
  `brainstorm-ended` WebSocket events. `connectBrainstormSession()` function
  (defined in this task).
- Produces: `<brainstorm-picker>` custom element. Updated `buildBrainstormLayout()`
  with options panel. `connectBrainstormSession()` function.

- [ ] **Step 1: Create `<brainstorm-picker>`**

Create `brainstorm-picker.ts`:

```typescript
import { LitElement, html, css, nothing } from 'lit';
import { customElement, state } from 'lit/decorators.js';
import { onPagesEvent } from '@casehubio/pages-component';

interface BrainstormSessionInfo {
  sessionId: string;
  state: string;
  optionCount: string;
}

@customElement('brainstorm-picker')
export class BrainstormPicker extends LitElement {
  @state() private _sessions: BrainstormSessionInfo[] = [];
  @state() private _currentSessionId: string | null = null;
  @state() private _open = false;

  private _cleanups: (() => void)[] = [];

  configure(_props: Record<string, unknown>): void {}

  override connectedCallback(): void {
    super.connectedCallback();
    this._cleanups.push(
      onPagesEvent<BrainstormSessionInfo[]>(document, 'brainstorm-sessions', (payload) => {
        this._sessions = payload;
      }),
      onPagesEvent<{ sessionId: string }>(document, 'brainstorm-session-created', (payload) => {
        if (!this._sessions.some(s => s.sessionId === payload.sessionId)) {
          this._sessions = [...this._sessions,
            { sessionId: payload.sessionId, state: 'ACTIVE', optionCount: '0' }];
        }
      }),
      onPagesEvent<{ sessionId: string }>(document, 'brainstorm-ended', (payload) => {
        this._sessions = this._sessions.filter(s => s.sessionId !== payload.sessionId);
        if (this._currentSessionId === payload.sessionId) {
          this._currentSessionId = null;
        }
      }),
      onPagesEvent(document, 'reconnected', () => {
        this._sessions = [];
        this._currentSessionId = null;
      }),
    );

    const onOutsideClick = (e: MouseEvent) => {
      if (!this.contains(e.target as Node)) this._open = false;
    };
    const onEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') this._open = false;
    };
    document.addEventListener('click', onOutsideClick);
    document.addEventListener('keydown', onEscape);
    this._cleanups.push(
      () => document.removeEventListener('click', onOutsideClick),
      () => document.removeEventListener('keydown', onEscape),
    );
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this._cleanups.forEach(fn => fn());
    this._cleanups = [];
  }

  private _selectSession(sessionId: string, e: Event): void {
    e.stopPropagation();
    this._currentSessionId = sessionId;
    this._open = false;
    this.dispatchEvent(new CustomEvent('brainstorm-session-selected', {
      bubbles: true, composed: true,
      detail: { sessionId },
    }));
  }

  private _toggleOpen(e: Event): void {
    e.stopPropagation();
    this._open = !this._open;
  }

  private _shortId(id: string): string {
    return id.length > 10 ? id.substring(0, 10) + '…' : id;
  }

  static override styles = css`
    :host {
      display: inline-flex;
      position: relative;
      align-items: center;
      font-size: 12px;
    }

    .badge {
      cursor: pointer;
      user-select: none;
      padding: 2px 6px;
      border-radius: 3px;
    }
    .badge:hover { background: var(--accent-light); }

    .dropdown {
      position: absolute;
      top: 100%;
      left: 0;
      margin-top: 4px;
      min-width: 220px;
      background: var(--bg);
      border: 1px solid var(--border);
      border-radius: 4px;
      box-shadow: 0 4px 12px rgba(0,0,0,0.15);
      z-index: 1000;
      padding: 8px 0;
    }

    .header {
      padding: 4px 12px 8px;
      font-weight: 600;
      font-size: 11px;
      color: var(--muted);
      text-transform: uppercase;
      letter-spacing: 0.5px;
    }

    .session-row {
      display: flex;
      align-items: center;
      padding: 4px 12px;
      gap: 6px;
      cursor: pointer;
    }
    .session-row:hover { background: var(--chrome); }
    .session-row.active { background: var(--accent-light); }

    .session-id {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      font-size: 12px;
    }

    .option-count {
      font-size: 11px;
      color: var(--muted);
    }
  `;

  override render() {
    if (this._sessions.length === 0) return nothing;

    return html`
      <span class="badge" @click=${this._toggleOpen}>
        Sessions (${this._sessions.length})
      </span>
      ${this._open ? html`
        <div class="dropdown">
          <div class="header">Brainstorm Sessions</div>
          ${this._sessions.map(s => html`
            <div class="session-row ${s.sessionId === this._currentSessionId ? 'active' : ''}"
                 @click=${(e: Event) => this._selectSession(s.sessionId, e)}>
              <span class="session-id">${this._shortId(s.sessionId)}</span>
              <span class="option-count">${s.optionCount} opts</span>
            </div>
          `)}
        </div>
      ` : nothing}
    `;
  }
}
```

- [ ] **Step 2: Update index.ts — imports, registrations, layout, and wiring**

Add side-effect imports alongside existing panel imports:

```typescript
import "./panels/brainstorm-options.js";
import "./panels/brainstorm-picker.js";
```

Add panel registration:

```typescript
registerPanel("brainstorm-options", "brainstorm-options");
```

Update `buildBrainstormLayout()`:

```typescript
function buildBrainstormLayout() {
  const wsProto = location.protocol === "https:" ? "wss:" : "ws:";
  const terminalWsUrl = `${wsProto}//${location.host}/api/terminal?cols={cols}&rows={rows}`;

  return rows(
    html(`<div id="topbar" style="display:flex; align-items:center; gap:8px; padding:4px 12px; background:var(--topbar-bg); color:var(--topbar-fg);">
      <strong>DraftHouse</strong>
      <span style="font-size:12px; color:var(--muted);">Brainstorm</span>
      <brainstorm-picker></brainstorm-picker>
      <span style="flex:1"></span>
    </div>`),
    split("horizontal", [
      hostPanel("terminal", { wsUrl: terminalWsUrl }),
      hostPanel("brainstorm-options", {}),
    ], { ratio: [50, 50] }),
  );
}
```

Add `connectBrainstormSession()` after existing `connectDebateSession()`:

```typescript
function connectBrainstormSession(sessionId: string): void {
  if (currentBrainstormSessionId) {
    wsSource.unsubscribe(("brainstorm:" + currentBrainstormSessionId) as any);
  }
  currentBrainstormSessionId = sessionId;
  wsSource.subscribe(("brainstorm:" + sessionId) as any,
    { uuid: ("brainstorm:" + sessionId) as any } as any, noOpListener, noOpError);

  const optionsEl = document.querySelector("brainstorm-options") as any;
  if (optionsEl) optionsEl.configure({ sessionId });
}
```

Add `currentBrainstormSessionId` variable near `currentSessionId`:

```typescript
let currentBrainstormSessionId: string | null = null;
```

Add event listeners for brainstorm session lifecycle (in the brainstorm mode block):

```typescript
if (mode === "brainstorm") {
  document.addEventListener("pages-event", ((e: CustomEvent) => {
    const { topic, payload } = e.detail || {};
    if (topic === "brainstorm-sessions" && Array.isArray(payload) && payload.length === 1) {
      connectBrainstormSession(payload[0].sessionId);
    }
    if (topic === "brainstorm-session-created" && payload?.sessionId) {
      connectBrainstormSession(payload.sessionId);
    }
    if (topic === "brainstorm-user-action" && payload) {
      const terminal = document.querySelector("pages-component-terminal") as any;
      if (terminal) {
        terminal.sendInput(`[User ${payload.action.toLowerCase()} '${payload.optionTitle}' via browser]\n`);
      }
    }
  }) as EventListener);
}
```

Update the existing `terminal-inject` bridge to also handle `brainstorm-user-action`.

- [ ] **Step 3: Build to verify compilation**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests -pl runtime`
Expected: BUILD SUCCESS

- [ ] **Step 4: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/main/webui/ server/runtime/src/main/java/
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "feat(#53): brainstorm-picker, layout wiring, and session lifecycle events

Refs #53"
```

---

### Task 7: Playwright E2E test

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/BrainstormOptionsE2ETest.java`

**Interfaces:**
- Consumes: All previous tasks — BrainstormService, BrainstormResource,
  brainstorm-options panel, brainstorm-picker panel, layout wiring.

- [ ] **Step 1: Write E2E test**

```java
@QuarkusTest
class BrainstormOptionsE2ETest extends PlaywrightBase {

    @Inject BrainstormService service;
    @Inject BrainstormSessionRegistry registry;

    @Test
    void brainstormMode_showsOptionsPanel_andInteracts() {
        String sessionId = service.startSession();
        try {
            service.presentOptions(sessionId, List.of(
                    new BrainstormService.OptionInput("A", "Option Alpha", "First approach", "Slower but safer"),
                    new BrainstormService.OptionInput("B", "Option Beta", "Second approach", "Faster but riskier")));

            page.navigate(baseUrl() + "?mode=brainstorm");
            page.waitForSelector("brainstorm-options");

            // Verify options appear via shadow DOM
            var optionsEl = page.locator("brainstorm-options");
            assertThat(optionsEl.locator(".card")).hasCount(2);

            // Click eliminate on option A
            optionsEl.locator(".card").first().locator("button", new Locator.LocatorOptions().setHasText("Eliminate")).click();

            // Verify option A is eliminated
            page.waitForFunction("document.querySelector('brainstorm-options').shadowRoot.querySelector('.card.eliminated') !== null");

        } finally {
            registry.remove(sessionId);
        }
    }
}
```

Note: This test depends on the existing `PlaywrightBase` pattern used in the
project. Check `server/runtime/src/test/java/io/casehub/drafthouse/e2e/` for
the base class and adapt accordingly (may need `@WithPlaywright`, page lifecycle
setup, shadow DOM piercing via `evaluate()`).

- [ ] **Step 2: Run E2E test**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=BrainstormOptionsE2ETest`
Expected: PASS

- [ ] **Step 3: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git -C /Users/mdproctor/claude/casehub/drafthouse add server/runtime/src/test/
git -C /Users/mdproctor/claude/casehub/drafthouse commit -m "test(#53): Playwright E2E test for brainstorm options panel

Refs #53"
```
