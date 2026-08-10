# SSE Debate Events Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stream debate channel events to the browser via SSE using cursor-based polling.

**Architecture:** Cursor-based polling SSE following Claudony's `MeshResource.channelEvents()` pattern. The SSE endpoint polls `MessageService.pollAfter()` every 500ms, parses each message into a `DebateStreamEntry` record, and emits it as a JSON SSE data frame. No event bus, no observer backend — polling resolves both the Mutiny lazy-subscription gap and the cross-thread SSE flushing issue.

**Tech Stack:** Quarkus 3.34.3, RESTEasy Reactive (`@Blocking`), Mutiny `Multi`, Qhorus `MessageService`, Jackson (default Quarkus config)

**Spec:** `docs/superpowers/specs/2026-06-11-sse-debate-events-design.md`

---

### Task 1: Add RESTART_CONTEXT to EntryType enum

Prerequisite — changes existing code. Separate commit.

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java`
- Modify: `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java`

- [ ] **Step 1: Add RESTART_CONTEXT to EntryType**

In `server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java`, add `RESTART_CONTEXT` after `SUB_TASK_ERROR`:

```java
public enum EntryType {
    RAISE, AGREE, COUNTER, DISPUTE, QUALIFY, FLAG_HUMAN, DECLINED,
    MEMO,               // per-round reasoning memo
    SUB_TASK_REQUEST,   // request for focused sub-agent analysis
    SUB_TASK_FINDING,   // sub-agent result (provenance: fresh context)
    SUB_TASK_ERROR,     // sub-agent execution failure
    RESTART_CONTEXT     // session branch marker (infrastructure provenance)
}
```

- [ ] **Step 2: Update DebateChannelProjection.apply()**

In `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java`, remove the string comparison block (lines ~43-46):

```java
        // REMOVE these lines:
        // RESTART_CONTEXT is infrastructure provenance — not a domain EntryType.
        // Intercepted here to avoid polluting the EntryType enum and breaking
        // SummaryRenderer's exhaustive switch.
        if ("RESTART_CONTEXT".equals(entryTypeStr)) return state;
```

Then add `RESTART_CONTEXT` to the exhaustive switch (after the `SUB_TASK_ERROR` case):

```java
        return switch (entryType) {
            case RAISE            -> handleRaise(state, message, meta);
            case AGREE            -> handleAgree(state, message, meta);
            case COUNTER          -> handleCounter(state, message, meta);
            case DISPUTE          -> handleDispute(state, message, meta);
            case QUALIFY          -> handleQualify(state, message, meta);
            case FLAG_HUMAN       -> handleFlagHuman(state, message, meta);
            case DECLINED         -> state;
            case MEMO             -> handleMemo(state, message, meta);
            case SUB_TASK_REQUEST -> handleSubTaskRequest(state, message, meta);
            case SUB_TASK_FINDING -> handleSubTaskFinding(state, message, meta);
            case SUB_TASK_ERROR   -> handleSubTaskError(state, message, meta);
            case RESTART_CONTEXT  -> state;
        };
```

- [ ] **Step 3: Update SummaryRenderer.render()**

In `server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java`, add `RESTART_CONTEXT` to the multi-label throw case (line ~54):

```java
                String typeLabel = switch (entry.type()) {
                    case RAISE      -> "raise";
                    case AGREE      -> "agree";
                    case COUNTER    -> "counter";
                    case DISPUTE    -> "dispute";
                    case QUALIFY    -> "qualify";
                    case FLAG_HUMAN -> "flag";
                    case DECLINED   -> "declined";
                    case MEMO, SUB_TASK_REQUEST, SUB_TASK_FINDING, SUB_TASK_ERROR, RESTART_CONTEXT ->
                        throw new IllegalStateException("entry type " + entry.type()
                            + " must not appear in ThreadEntry");
                };
```

- [ ] **Step 4: Build to verify compilation**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests`

Expected: BUILD SUCCESS — all three exhaustive switches now handle RESTART_CONTEXT.

- [ ] **Step 5: Run tests to verify no regressions**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`

Expected: All tests pass. The `restart_from_round` tests exercise RESTART_CONTEXT messages — they should continue to work because `DebateChannelProjection.apply()` now handles it via `case RESTART_CONTEXT -> state` (same behavior as the removed string comparison).

- [ ] **Step 6: Commit**

```
git add server/api/src/main/java/io/casehub/drafthouse/debate/EntryType.java server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateChannelProjection.java server/api/src/main/java/io/casehub/drafthouse/debate/SummaryRenderer.java
git commit -m "refactor: promote RESTART_CONTEXT to EntryType enum

Replace string comparison in DebateChannelProjection.apply() with
compiler-checked switch case. Add to SummaryRenderer throw group.

Refs #50"
```

---

### Task 2: Add activeSessions() to DebateSessionRegistry

Prerequisite — interface change. Separate commit.

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DebateSessionRegistry.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateSessionRegistryImpl.java`
- Create: `server/api/src/test/java/io/casehub/drafthouse/DebateSessionRegistryContractTest.java`

- [ ] **Step 1: Write the failing test**

Create `server/api/src/test/java/io/casehub/drafthouse/DebateSessionRegistryContractTest.java`:

```java
package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DebateSessionRegistryContractTest {

    private final DebateSessionRegistryImpl registry = new DebateSessionRegistryImpl();

    @Test
    void activeSessions_empty_returnsEmptyCollection() {
        Collection<DebateSession> sessions = registry.activeSessions();
        assertThat(sessions).isEmpty();
    }

    @Test
    void activeSessions_afterPut_containsSession() {
        UUID channelId = UUID.randomUUID();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                "drafthouse/debate/d-test", "test-spec.md");
        registry.put(session);

        Collection<DebateSession> sessions = registry.activeSessions();
        assertThat(sessions).hasSize(1);
        assertThat(sessions.iterator().next().channelId()).isEqualTo(channelId);
    }

    @Test
    void activeSessions_afterRemove_doesNotContainSession() {
        UUID channelId = UUID.randomUUID();
        DebateSession session = new DebateSession(channelId, channelId.toString(),
                "drafthouse/debate/d-test", "test-spec.md");
        registry.put(session);
        registry.remove(channelId);

        Collection<DebateSession> sessions = registry.activeSessions();
        assertThat(sessions).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DebateSessionRegistryContractTest`

Expected: FAIL — `activeSessions()` method does not exist on `DebateSessionRegistry`.

- [ ] **Step 3: Add method to interface**

In `server/api/src/main/java/io/casehub/drafthouse/DebateSessionRegistry.java`:

```java
package io.casehub.drafthouse;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Registry of active debate sessions, keyed by Qhorus channel ID.
 * Thread-safety: implementations must be safe for concurrent access.
 */
public interface DebateSessionRegistry {

    /** Returns the session for the given channel, or empty if no session is active. */
    Optional<DebateSession> find(UUID channelId);

    /** Registers a new session. Replaces any existing session for the same channelId. */
    void put(DebateSession session);

    /** Removes the session for the given channel. No-op if not found. */
    void remove(UUID channelId);

    /** Returns a snapshot of all active sessions. Safe to iterate concurrently. */
    Collection<DebateSession> activeSessions();
}
```

- [ ] **Step 4: Implement in DebateSessionRegistryImpl**

In `server/runtime/src/main/java/io/casehub/drafthouse/DebateSessionRegistryImpl.java`, add:

```java
    @Override
    public Collection<DebateSession> activeSessions() {
        return List.copyOf(sessions.values());
    }
```

Add `import java.util.Collection;` and `import java.util.List;` to the imports.

- [ ] **Step 5: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DebateSessionRegistryContractTest`

Expected: PASS — all three tests.

- [ ] **Step 6: Run full test suite to verify no regressions**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`

Expected: All tests pass.

- [ ] **Step 7: Commit**

```
git add server/api/src/main/java/io/casehub/drafthouse/DebateSessionRegistry.java server/runtime/src/main/java/io/casehub/drafthouse/DebateSessionRegistryImpl.java server/api/src/test/java/io/casehub/drafthouse/DebateSessionRegistryContractTest.java
git commit -m "feat: add activeSessions() to DebateSessionRegistry

Returns a snapshot of all active sessions for the active-sessions REST endpoint.

Refs #50"
```

---

### Task 3: Create DebateStreamEntry record with from(Message) factory

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateStreamEntry.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateStreamEntryTest.java`

- [ ] **Step 1: Write the failing tests**

Create `server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateStreamEntryTest.java`:

```java
package io.casehub.drafthouse.debate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;

class DebateStreamEntryTest {

    private static Message makeMessage(String content, String correlationId,
                                       Long inReplyTo, MessageType type) {
        Message m = new Message();
        m.id = 42L;
        m.channelId = UUID.randomUUID();
        m.sender = "drafthouse-rev-abc123";
        m.messageType = type;
        m.actorType = ActorType.AGENT;
        m.content = content;
        m.correlationId = correlationId;
        m.inReplyTo = inReplyTo;
        m.createdAt = Instant.parse("2026-06-11T10:00:00Z");
        return m;
    }

    @Test
    void from_raise_extractsAllFields() {
        String content = "DHMETA:entryType=RAISE|agent=REV|round=1"
                + "|priority=P1|scope=ISOLATED|location=§3.2\n\nThe API is ambiguous.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.RAISE);
        assertThat(entry.agentRole()).isEqualTo(AgentType.REV);
        assertThat(entry.round()).isEqualTo(1);
        assertThat(entry.content()).isEqualTo("The API is ambiguous.");
        assertThat(entry.pointId()).isEqualTo("point-1");
        assertThat(entry.subTaskId()).isNull();
        assertThat(entry.priority()).isEqualTo(Priority.P1);
        assertThat(entry.scope()).isEqualTo(Scope.ISOLATED);
        assertThat(entry.location()).isEqualTo("§3.2");
        assertThat(entry.sender()).isEqualTo("drafthouse-rev-abc123");
        assertThat(entry.timestamp()).isEqualTo(Instant.parse("2026-06-11T10:00:00Z"));
    }

    @Test
    void from_agree_setsPointIdFromCorrelationId() {
        String content = "DHMETA:entryType=AGREE|agent=IMP|round=2\n\nCorrect.";
        Message msg = makeMessage(content, "point-1", 10L, MessageType.DONE);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.AGREE);
        assertThat(entry.agentRole()).isEqualTo(AgentType.IMP);
        assertThat(entry.pointId()).isEqualTo("point-1");
        assertThat(entry.subTaskId()).isNull();
    }

    @Test
    void from_subTaskRequest_extractsPointIdFromMeta_subTaskIdFromCorrelationId() {
        String content = "DHMETA:entryType=SUB_TASK_REQUEST|agent=REV"
                + "|taskType=VERIFY|subTaskId=sub-1|round=3|pointId=point-1\n\nVerify this.";
        Message msg = makeMessage(content, "sub-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.SUB_TASK_REQUEST);
        assertThat(entry.pointId()).isEqualTo("point-1");
        assertThat(entry.subTaskId()).isEqualTo("sub-1");
    }

    @Test
    void from_subTaskError_pointIdIsNull() {
        String content = "DHMETA:entryType=SUB_TASK_ERROR|agent=REV"
                + "|taskType=VERIFY|subTaskId=sub-1\n\nAgent timed out.";
        Message msg = makeMessage(content, "sub-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.SUB_TASK_ERROR);
        assertThat(entry.pointId()).isNull();
        assertThat(entry.subTaskId()).isEqualTo("sub-1");
    }

    @Test
    void from_memo_bothIdsNull() {
        String content = "DHMETA:entryType=MEMO|agent=REV|round=2\n\nMy reasoning.";
        Message msg = makeMessage(content, null, null, MessageType.STATUS);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.entryType()).isEqualTo(EntryType.MEMO);
        assertThat(entry.pointId()).isNull();
        assertThat(entry.subTaskId()).isNull();
    }

    @Test
    void from_noMetaSentinel_returnsNull() {
        Message msg = makeMessage("plain text no sentinel", "c-1", null, MessageType.QUERY);

        assertThat(DebateStreamEntry.from(msg)).isNull();
    }

    @Test
    void from_unknownEntryType_returnsNull() {
        String content = "DHMETA:entryType=UNKNOWN_TYPE|agent=REV|round=1\n\nBody.";
        Message msg = makeMessage(content, "c-1", null, MessageType.QUERY);

        assertThat(DebateStreamEntry.from(msg)).isNull();
    }

    @Test
    void from_missingAgent_returnsNull() {
        String content = "DHMETA:entryType=RAISE|round=1\n\nBody.";
        Message msg = makeMessage(content, "c-1", null, MessageType.QUERY);

        assertThat(DebateStreamEntry.from(msg)).isNull();
    }

    @Test
    void from_restartContext_returnsNull() {
        String content = "DHMETA:entryType=RESTART_CONTEXT|originChannelId="
                + UUID.randomUUID() + "|originRound=3\n\n# Full summary...";
        Message msg = makeMessage(content, null, null, MessageType.STATUS);

        assertThat(DebateStreamEntry.from(msg)).isNull();
    }

    @Test
    void from_missingOptionalFields_defaultsToNull() {
        String content = "DHMETA:entryType=RAISE|agent=REV|round=1\n\nBody.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.priority()).isNull();
        assertThat(entry.scope()).isNull();
        assertThat(entry.location()).isNull();
    }

    @Test
    void from_malformedRound_defaultsToZero() {
        String content = "DHMETA:entryType=RAISE|agent=REV|round=abc\n\nBody.";
        Message msg = makeMessage(content, "point-1", null, MessageType.QUERY);

        DebateStreamEntry entry = DebateStreamEntry.from(msg);

        assertThat(entry).isNotNull();
        assertThat(entry.round()).isEqualTo(0);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateStreamEntryTest`

Expected: FAIL — `DebateStreamEntry` class does not exist.

- [ ] **Step 3: Create DebateStreamEntry**

Create `server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateStreamEntry.java`:

```java
package io.casehub.drafthouse.debate;

import java.time.Instant;
import java.util.Map;

import io.casehub.qhorus.runtime.message.Message;

public record DebateStreamEntry(
        EntryType entryType,
        AgentType agentRole,
        int round,
        String content,
        String pointId,
        String subTaskId,
        Priority priority,
        Scope scope,
        String location,
        String sender,
        Instant timestamp) {

    public static DebateStreamEntry from(Message msg) {
        Map<String, String> meta = DebateProtocol.parseMeta(msg.content);
        String entryTypeStr = meta.get("entryType");
        if (entryTypeStr == null) return null;

        EntryType entryType;
        try {
            entryType = EntryType.valueOf(entryTypeStr);
        } catch (IllegalArgumentException e) {
            return null;
        }

        String agentStr = meta.get("agent");
        if (agentStr == null) return null;

        AgentType agentRole;
        try {
            agentRole = AgentType.valueOf(agentStr);
        } catch (IllegalArgumentException e) {
            return null;
        }

        int round = DebateProtocol.parseRound(meta);
        String body = DebateProtocol.bodyContent(msg.content);

        boolean isSubTask = entryType == EntryType.SUB_TASK_REQUEST
                || entryType == EntryType.SUB_TASK_FINDING
                || entryType == EntryType.SUB_TASK_ERROR;

        String pointId = isSubTask ? meta.get("pointId") : msg.correlationId;
        String subTaskId = isSubTask ? msg.correlationId : null;

        Priority priority = parsePriority(meta.get("priority"));
        Scope scope = parseScope(meta.get("scope"));
        String location = meta.get("location");

        return new DebateStreamEntry(
                entryType, agentRole, round, body,
                pointId, subTaskId,
                priority, scope,
                location != null && !location.isBlank() ? location : null,
                msg.sender,
                msg.createdAt != null ? msg.createdAt : Instant.now());
    }

    private static Priority parsePriority(String s) {
        if (s == null) return null;
        try { return Priority.valueOf(s.toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }

    private static Scope parseScope(String s) {
        if (s == null) return null;
        try { return Scope.valueOf(s.toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateStreamEntryTest`

Expected: All 11 tests pass.

- [ ] **Step 5: Commit**

```
git add server/runtime/src/main/java/io/casehub/drafthouse/debate/DebateStreamEntry.java server/runtime/src/test/java/io/casehub/drafthouse/debate/DebateStreamEntryTest.java
git commit -m "feat: DebateStreamEntry record with from(Message) factory

Transport DTO for SSE delivery. Parses DebateProtocol META headers,
extracts pointId/subTaskId by entry type. Returns null for unparseable
messages (no sentinel, unknown type, missing agent).

Refs #50"
```

---

### Task 4: Create DebateEventResource — SSE endpoint

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/DebateEventResourceTest.java`

- [ ] **Step 1: Write the integration test**

Create `server/runtime/src/test/java/io/casehub/drafthouse/DebateEventResourceTest.java`:

```java
package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

@QuarkusTest
class DebateEventResourceTest {

    private static final Pattern DEBATE_ID_PATTERN =
            Pattern.compile("\"debateSessionId\":\"([^\"]+)\"");

    @Inject DebateMcpTools tools;

    private String activeDebateSessionId;

    @BeforeEach
    void setUp() {
        activeDebateSessionId = null;
    }

    @AfterEach
    void tearDown() {
        if (activeDebateSessionId != null) {
            tools.endDebate(activeDebateSessionId, false);
        }
    }

    @Test
    void invalidSessionId_returns404() {
        RestAssured.given()
                .accept("text/event-stream")
                .when()
                .get("/api/debate/00000000-0000-0000-0000-000000000000/events")
                .then()
                .statusCode(404);
    }

    @Test
    void catchUp_deliversHistoricalEvents() {
        String startResult = tools.startDebate("test-spec.md");
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        tools.raisePoint(sessionId, "REV", 1,
                "The API is ambiguous.", "P1", "ISOLATED", "§3.2");
        tools.respondTo(sessionId, "IMP", 2,
                extractPointId(tools.raisePoint(sessionId, "REV", 1,
                        "Missing validation.", "P2", "SYSTEMIC", null)),
                "agree", "Will add.");

        String body = RestAssured.given()
                .accept("text/event-stream")
                .when()
                .get("/api/debate/" + sessionId + "/events")
                .then()
                .statusCode(200)
                .contentType("text/event-stream")
                .extract().body().asString();

        assertThat(body).contains("\"entryType\":\"RAISE\"");
        assertThat(body).contains("\"agentRole\":\"REV\"");
        assertThat(body).contains("The API is ambiguous.");
    }

    @Test
    void activeSessions_returnsCurrentDebates() {
        String startResult = tools.startDebate("test-spec.md");
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        String body = RestAssured.given()
                .accept(MediaType.APPLICATION_JSON)
                .when()
                .get("/api/debate/sessions")
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(body).contains(sessionId);
        assertThat(body).contains("test-spec.md");
    }

    @Test
    void activeSessions_emptyWhenNoDebates() {
        String body = RestAssured.given()
                .accept(MediaType.APPLICATION_JSON)
                .when()
                .get("/api/debate/sessions")
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(body).isEqualTo("[]");
    }

    private static String extractGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : "";
    }

    private String extractPointId(String raiseResult) {
        Matcher m = Pattern.compile("\"pointId\":\"([^\"]+)\"").matcher(raiseResult);
        return m.find() ? m.group(1) : "";
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateEventResourceTest`

Expected: FAIL — 404 on all endpoints (resource doesn't exist).

- [ ] **Step 3: Create DebateEventResource**

Create `server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java`:

```java
package io.casehub.drafthouse;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.drafthouse.debate.DebateStreamEntry;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

@ApplicationScoped
@Path("/api/debate")
public class DebateEventResource {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(DebateEventResource.class.getName());

    @Inject DebateSessionRegistry registry;
    @Inject MessageService messageService;
    @Inject ObjectMapper mapper;

    record SessionInfo(String debateSessionId, String channelName, String specPath) {}

    @GET
    @Path("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<SessionInfo> activeSessions() {
        return registry.activeSessions().stream()
                .map(s -> new SessionInfo(s.debateSessionId(), s.channelName(), s.specPath()))
                .toList();
    }

    @GET
    @Path("/{debateSessionId}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @io.smallrye.common.annotation.Blocking
    public Multi<String> events(@PathParam("debateSessionId") String debateSessionId) {
        UUID channelId;
        try {
            channelId = UUID.fromString(debateSessionId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Invalid session id: " + debateSessionId);
        }

        DebateSession session = registry.find(channelId).orElse(null);
        if (session == null) {
            throw new NotFoundException("No active debate session: " + debateSessionId);
        }

        AtomicLong lastSentId = new AtomicLong(0L);

        Multi<String> catchUp = Multi.createFrom().uni(
                Uni.createFrom().item(() -> {
                    List<Message> messages = messageService.pollAfter(channelId, 0L, 500);
                    return serializeMessages(messages, lastSentId);
                })
        ).filter(Objects::nonNull);

        Multi<String> live = Multi.createFrom().ticks().every(Duration.ofMillis(500))
                .onItem().transformToUniAndConcatenate(tick ->
                        Uni.createFrom().item(() -> {
                            List<Message> messages = messageService.pollAfter(
                                    channelId, lastSentId.get(), 50);
                            if (messages.isEmpty()) return "{\"type\":\"heartbeat\"}";
                            return serializeMessages(messages, lastSentId);
                        })
                        .onFailure().invoke(e -> LOG.warning(
                                "SSE tick failed for " + debateSessionId + ": " + e.getMessage()))
                        .onFailure().recoverWithItem("{\"type\":\"heartbeat\"}")
                );

        return Multi.createBy().concatenating().streams(catchUp, live);
    }

    private String serializeMessages(List<Message> messages, AtomicLong lastSentId) {
        List<DebateStreamEntry> entries = messages.stream()
                .map(DebateStreamEntry::from)
                .filter(Objects::nonNull)
                .toList();

        if (!messages.isEmpty()) {
            long maxId = messages.stream()
                    .mapToLong(m -> m.id)
                    .max()
                    .orElse(lastSentId.get());
            lastSentId.set(maxId);
        }

        if (entries.isEmpty()) return null;

        try {
            return mapper.writeValueAsString(entries);
        } catch (Exception e) {
            LOG.warning("Failed to serialize debate events: " + e.getMessage());
            return null;
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateEventResourceTest`

Expected: All 4 tests pass.

- [ ] **Step 5: Run full test suite to check for regressions**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`

Expected: All tests pass.

- [ ] **Step 6: Commit**

```
git add server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java server/runtime/src/test/java/io/casehub/drafthouse/DebateEventResourceTest.java
git commit -m "feat: SSE endpoint for debate channel events

GET /api/debate/{id}/events — cursor-based polling via MessageService.pollAfter()
GET /api/debate/sessions — active session discovery for browser

@Blocking endpoint with 500ms polling, heartbeat on idle ticks.
Follows Claudony MeshResource.channelEvents() pattern.

Refs #50"
```

---

### Task 5: Browser-side EventSource wiring

**Files:**
- Modify: `index.html`

- [ ] **Step 1: Add EventSource connection function**

In `index.html`, add the following JavaScript before the closing `</script>` tag (or at the end of the existing script block):

```javascript
// ── Debate SSE (sp2 — data pipe only, sp3 adds rendering) ────────────
let debateEventSource = null;

function connectDebateSSE(debateSessionId) {
    if (debateEventSource) {
        debateEventSource.close();
    }
    debateEventSource = new EventSource(
        '/api/debate/' + encodeURIComponent(debateSessionId) + '/events');
    debateEventSource.onmessage = function(e) {
        var events;
        try {
            events = JSON.parse(e.data);
        } catch (err) {
            return;
        }
        if (events.type === 'heartbeat') return;
        if (!Array.isArray(events)) events = [events];
        events.forEach(function(event) {
            document.dispatchEvent(
                new CustomEvent('debate-event', { detail: event }));
        });
    };
    debateEventSource.onerror = function() {
        // EventSource auto-reconnects; sp3 contract: clear accumulated
        // events on reconnect (replay-from-start)
    };
    return debateEventSource;
}

function disconnectDebateSSE() {
    if (debateEventSource) {
        debateEventSource.close();
        debateEventSource = null;
    }
}
```

- [ ] **Step 2: Verify the app starts and loads**

Run the app:
```
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests && java -Dui.dir=/Users/mdproctor/claude/casehub/drafthouse -jar server/runtime/target/drafthouse-server-runner.jar
```

Open `http://localhost:9001/` in a browser. Verify the page loads without JS errors. Open browser console and confirm `connectDebateSSE` is defined: type `typeof connectDebateSSE` — should return `"function"`.

- [ ] **Step 3: Commit**

```
git add index.html
git commit -m "feat: browser EventSource wiring for debate SSE

connectDebateSSE(id) / disconnectDebateSSE() functions.
Emits CustomEvent('debate-event') for sp3 to subscribe to.
Data pipe only — no rendering in this sub-project.

Refs #50"
```

---

### Task 6: Update CLAUDE.md and ARC42STORIES.MD

**Files:**
- Modify: `CLAUDE.md`
- Modify: `ARC42STORIES.MD`

- [ ] **Step 1: Update CLAUDE.md architecture section**

Add the SSE endpoint to the architecture diagram in `CLAUDE.md`:

In the `Quarkus Server` section, add after the MCP tools line:

```
  ├── GET /api/debate/{id}/events  ← SSE debate event stream
  ├── GET /api/debate/sessions     ← active debate session list
```

In the `Browser UI` section, add:

```
  ├── EventSource /api/debate/{id}/events  ← live debate events
```

In the Key Directories table, add `DebateEventResource` to the runtime row's contents list.

- [ ] **Step 2: Update ARC42STORIES.MD**

In §2 Constraints, update the stale reference:

Change:
```
| Java 17 | Sealed types required for `DebateEvent` |
```
To:
```
| Java 17 | Sealed types, exhaustive switches on `EntryType` |
```

In §13 Glossary, update the `DebateEvent` entry:

Change:
```
| **DebateEvent** | Sealed Java 17 interface with six record variants representing one event in a structured review debate |
```
To:
```
| **DebateEvent** | *(Superseded in C5)* Originally a sealed Java 17 interface. Now replaced by `EntryType` enum + `DebateProtocol.parseMeta()` for flexible debate entry parsing. `DebateStreamEntry` is the transport DTO for SSE delivery. |
```

- [ ] **Step 3: Commit**

```
git add CLAUDE.md ARC42STORIES.MD
git commit -m "docs: update architecture for SSE debate events

Add SSE endpoints to CLAUDE.md architecture diagram.
Fix stale DebateEvent sealed-type references in ARC42 §2 and §13.

Refs #50"
```

---

## Self-Review

**Spec coverage:**
- DebateStreamEntry record with from(Message) factory → Task 3 ✓
- EntryType.RESTART_CONTEXT prerequisite → Task 1 ✓
- DebateSessionRegistry.activeSessions() prerequisite → Task 2 ✓
- SSE endpoint (cursor-based polling, @Blocking, heartbeat, catch-up) → Task 4 ✓
- Active sessions endpoint → Task 4 ✓
- Browser EventSource wiring → Task 5 ✓
- ARC42 stale references → Task 6 ✓
- CLAUDE.md update → Task 6 ✓
- pointId/subTaskId by entry type → Task 3 tests verify ✓
- pollAfter EVENT exclusion (default overload) → Task 4 uses default overload ✓
- sp3 contract (clear on reconnect) → Task 5 comment documents ✓
- RESTART_CONTEXT filtered by factory → Task 3 test `from_restartContext_returnsNull` ✓

**Placeholder scan:** No TBD/TODO. All code blocks are complete.

**Type consistency:** `DebateStreamEntry`, `DebateEventResource`, `SessionInfo`, `DebateSessionRegistry.activeSessions()` — names match across all tasks. `from(Message)` factory signature consistent between Task 3 definition and Task 4 usage (`DebateStreamEntry::from`).
