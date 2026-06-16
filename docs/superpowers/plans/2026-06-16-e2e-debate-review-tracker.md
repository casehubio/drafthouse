# E2E Debate Panel, Review Tracker, Cross-Panel Tests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 38 Playwright E2E tests covering debate panel rendering, review tracker status derivation, and cross-panel event routing — driven by `DebateMcpTools` through the full SSE delivery chain.

**Architecture:** Each test class combines `@QuarkusTest @WithPlaywright` with CDI-injected `DebateMcpTools`. Server-side tools build Qhorus channel state; Playwright navigates with `?debate=<sessionId>` and asserts SSE-delivered entries render correct DOM structure. A `DebateE2EFixtures` static utility provides shared helpers.

**Tech Stack:** Quarkus 3.34.3, quarkus-playwright, Playwright Java, Awaitility, AssertJ

**Spec:** `docs/superpowers/specs/2026-06-16-e2e-debate-review-tracker-design.md`

---

### Task 0: File issue for `respondTo("declined")` API gap

**Files:**
- None (GitHub issue only)

- [ ] **Step 1: Create GitHub issue**

Run:
```bash
gh issue create --repo casehubio/drafthouse --title "respondTo() does not accept 'declined' — DECLINED entry type unreachable via MCP API" --body "## Context

\`EntryType.DECLINED\` exists in the domain model and \`DebateChannelProjection\` handles it (maps to \`ReviewStatus.DECLINED\`), but \`respondTo()\` only accepts \`agree\`, \`dispute\`, \`qualify\`, \`counter\`. The #51 spec (line 674) flagged this as a prerequisite: 'including DECLINED after prerequisite fix.'

## Fix

Add \`\"declined\"\` to \`respondTo()\`'s switch statement:

\`\`\`java
case \"declined\" -> MessageType.DECLINE;
\`\`\`

Update the error message and \`@ToolArg\` description to include \`declined\`. This encodes \`entryType=DECLINED\` in the META header and dispatches as \`MessageType.DECLINE\` — the same Qhorus type used by \`dispute\`, but with the correct \`entryType\` encoding.

DECLINED is semantically distinct from DISPUTED (ARC42STORIES line 818): 'DECLINED is terminal (LLM declined to answer; point closed)' vs 'DISPUTED is non-terminal (thread continues).'

## References
- ARC42STORIES.MD line 818
- #51 spec line 674
- Blocked by: #55 E2E tests need this to test DECLINED rendering" --label enhancement
```

Note the issue number — it will be referenced in Task 1's commit.

---

### Task 1: Add `declined` to `respondTo()` API

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java:160,169-176`

- [ ] **Step 1: Write the failing test**

Add a test to `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java`. This test verifies that `respondTo("declined")` dispatches a message with `entryType=DECLINED` and `MessageType.DECLINE`.

```java
@Test
void respondTo_declined_dispatchesDeclinedEntry() {
    UUID chId = stubChannel.id;
    DebateSession session = new DebateSession(chId, chId.toString(), stubChannel.name, "spec.md");
    when(registry.find(chId)).thenReturn(Optional.of(session));
    when(instanceService.register(anyString(), anyString(), anyList()))
            .thenReturn(new Instance(chId, "inst", "desc", List.of()));
    when(messageService.findByCorrelationId("pt-1")).thenReturn(Optional.of(
            new Message(chId, 1L, "sender", MessageType.QUERY, "content", null, null, null)));

    String result = tools.respondTo(chId.toString(), "IMP", 2, "pt-1", "declined", "Cannot engage with this point.");

    assertThat(result).contains("dispatched");
    ArgumentCaptor<MessageDispatch> captor = ArgumentCaptor.forClass(MessageDispatch.class);
    verify(messageService).dispatch(captor.capture());
    MessageDispatch dispatched = captor.getValue();
    assertThat(dispatched.type()).isEqualTo(MessageType.DECLINE);
    assertThat(dispatched.content()).contains("entryType=DECLINED");
    assertThat(dispatched.content()).contains("Cannot engage with this point.");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest#respondTo_declined_dispatchesDeclinedEntry`

Expected: FAIL — `respondTo` returns error for unknown entryType `"declined"`.

- [ ] **Step 3: Implement the fix**

In `DebateMcpTools.java`, modify the `respondTo` method:

Change the `@ToolArg` description on line 160 from:
```java
@ToolArg(description = "Response type: agree, dispute, qualify, counter") String entryType,
```
to:
```java
@ToolArg(description = "Response type: agree, dispute, qualify, counter, declined") String entryType,
```

Change the switch on lines 169-173 from:
```java
MessageType qhorusType = switch (entryType) {
    case "agree"   -> MessageType.DONE;
    case "dispute" -> MessageType.DECLINE;
    case "qualify", "counter" -> MessageType.RESPONSE;
    default -> null;
};
```
to:
```java
MessageType qhorusType = switch (entryType) {
    case "agree"    -> MessageType.DONE;
    case "dispute"  -> MessageType.DECLINE;
    case "declined" -> MessageType.DECLINE;
    case "qualify", "counter" -> MessageType.RESPONSE;
    default -> null;
};
```

Change the error message on line 176 from:
```java
return "error: invalid entryType '" + entryType + "' — must be agree, dispute, qualify, or counter";
```
to:
```java
return "error: invalid entryType '" + entryType + "' — must be agree, dispute, qualify, counter, or declined";
```

- [ ] **Step 4: Run test to verify it passes**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest#respondTo_declined_dispatchesDeclinedEntry`

Expected: PASS

- [ ] **Step 5: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`

Expected: All tests pass (no regressions).

- [ ] **Step 6: Commit**

```
git add server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java
git commit -m "feat: add 'declined' to respondTo() — DECLINED entry type now reachable via MCP API

Closes #<issue-from-task-0>
Refs #55"
```

---

### Task 2: Create `DebateE2EFixtures` utility class

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/DebateE2EFixtures.java`

- [ ] **Step 1: Create the utility class**

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.casehub.drafthouse.DebateMcpTools;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DebateE2EFixtures {

    private static final Pattern SESSION_ID_PATTERN =
            Pattern.compile("\"debateSessionId\":\"([^\"]+)\"");
    private static final Pattern NEW_SESSION_ID_PATTERN =
            Pattern.compile("\"newDebateSessionId\":\"([^\"]+)\"");
    private static final Pattern POINT_ID_PATTERN =
            Pattern.compile("\"pointId\":\"([^\"]+)\"");

    private DebateE2EFixtures() {}

    public static String startDebateSession(DebateMcpTools tools) {
        return startDebateSession(tools, "test-spec.md");
    }

    public static String startDebateSession(DebateMcpTools tools, String specPath) {
        String result = tools.startDebate(specPath);
        String sessionId = extractSessionId(result);
        if (sessionId.isBlank()) {
            throw new AssertionError("startDebate failed: " + result);
        }
        return sessionId;
    }

    public static void loadWithDebate(Page page, URL base, String sessionId) {
        String a = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-a.md"), StandardCharsets.UTF_8);
        String b = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-b.md"), StandardCharsets.UTF_8);
        page.navigate(base + "?a=" + a + "&b=" + b + "&debate=" + sessionId);
        PlaywrightFixtures.waitForRender(page);
    }

    public static void waitForDebateEntries(Page page, int count) {
        page.locator("drafthouse-debate .entry").nth(count - 1).waitFor();
    }

    public static void waitForTrackerPoints(Page page, int count) {
        page.locator("drafthouse-review-tracker .point-item").nth(count - 1).waitFor();
    }

    public static void listenForPointSelected(Page page) {
        page.evaluate("() => {"
                + "window.__pointSelectedDetail = null;"
                + "document.addEventListener('point-selected',"
                + "  e => window.__pointSelectedDetail = e.detail);"
                + "}");
    }

    public static Object getPointSelectedDetail(Page page) {
        return page.evaluate("() => window.__pointSelectedDetail");
    }

    public static String extractSessionId(String mcpResult) {
        return extractGroup(SESSION_ID_PATTERN, mcpResult);
    }

    public static String extractNewSessionId(String mcpResult) {
        return extractGroup(NEW_SESSION_ID_PATTERN, mcpResult);
    }

    public static String extractPointId(String mcpResult) {
        return extractGroup(POINT_ID_PATTERN, mcpResult);
    }

    private static String extractGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : "";
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests`

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```
git add server/runtime/src/test/java/io/casehub/drafthouse/e2e/DebateE2EFixtures.java
git commit -m "test: add DebateE2EFixtures utility for debate E2E tests

Refs #55"
```

---

### Task 3: `DebatePanelE2ETest` — placeholder and basic entry rendering (tests 1–11)

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/DebatePanelE2ETest.java`

- [ ] **Step 1: Create test class with placeholder and empty state tests**

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.casehub.drafthouse.DebateMcpTools;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.DebateE2EFixtures.*;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;

@QuarkusTest
@WithPlaywright
class DebatePanelE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Inject
    DebateMcpTools tools;

    private Page page;
    private String sessionId;

    @BeforeEach
    void setUp() {
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        if (sessionId != null) {
            tools.endDebate(sessionId, false);
            sessionId = null;
        }
        if (page != null) page.close();
    }

    @Test
    void placeholder_whenNoDebateSession() {
        PlaywrightFixtures.loadFilePair(page, index,
                fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("drafthouse-debate .placeholder"))
                .containsText("Waiting for debate session");
    }

    @Test
    void emptyState_whenDebateStartedButNoEntries() {
        sessionId = startDebateSession(tools);
        loadWithDebate(page, index, sessionId);
        assertThat(page.locator("drafthouse-debate .placeholder"))
                .containsText("No entries yet");
    }

    @Test
    void roundDivider_appearsOnFirstEntry() {
        sessionId = startDebateSession(tools);
        tools.raisePoint(sessionId, "REV", 1, "Test point.", "P1", "ISOLATED", null);
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 1);
        assertThat(page.locator("drafthouse-debate .round-divider"))
                .hasText("Round 1");
    }

    @Test
    void raiseEntry_rendersWithCorrectStructure() {
        sessionId = startDebateSession(tools);
        tools.raisePoint(sessionId, "REV", 1,
                "The API contract is underspecified.", "P1", "ISOLATED", "§3.2");
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 1);
        assertThat(page.locator("drafthouse-debate .entry-raise")).hasCount(1);
        assertThat(page.locator("drafthouse-debate .badge-priority-p1")).hasCount(1);
        assertThat(page.locator("drafthouse-debate .badge-scope")).hasText("ISOLATED");
        assertThat(page.locator("drafthouse-debate .badge-location")).hasText("§3.2");
        assertThat(page.locator("drafthouse-debate .entry-agent")).hasText("Reviewer");
    }

    @Test
    void agreeEntry_hasCorrectClass() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Point.", "P1", "ISOLATED", null));
        tools.respondTo(sessionId, "IMP", 1, pointId, "agree", "Agreed.");
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 2);
        assertThat(page.locator("drafthouse-debate .entry-agree")).hasCount(1);
    }

    @Test
    void counterEntry_hasCorrectClass() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Point.", "P1", "ISOLATED", null));
        tools.respondTo(sessionId, "IMP", 1, pointId, "counter", "Countered.");
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 2);
        assertThat(page.locator("drafthouse-debate .entry-counter")).hasCount(1);
    }

    @Test
    void disputeEntry_hasCorrectClass() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Point.", "P1", "ISOLATED", null));
        tools.respondTo(sessionId, "IMP", 1, pointId, "dispute", "Disputed.");
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 2);
        assertThat(page.locator("drafthouse-debate .entry-dispute")).hasCount(1);
    }

    @Test
    void qualifyEntry_hasCorrectClass() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Point.", "P1", "ISOLATED", null));
        tools.respondTo(sessionId, "IMP", 1, pointId, "qualify", "Qualified.");
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 2);
        assertThat(page.locator("drafthouse-debate .entry-qualify")).hasCount(1);
    }

    @Test
    void declinedEntry_hasReducedOpacity() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Point.", "P1", "ISOLATED", null));
        tools.respondTo(sessionId, "IMP", 1, pointId, "declined", "Cannot engage.");
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 2);
        assertThat(page.locator("drafthouse-debate .entry-declined")).hasCount(1);
    }

    @Test
    void flagHumanEntry_rendersWarningBanner() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Point.", "P1", "ISOLATED", null));
        tools.flagHuman(sessionId, "REV", 1, pointId, "Need human input.");
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 2);
        assertThat(page.locator("drafthouse-debate .entry-flag_human")).hasCount(1);
        String pseudoContent = (String) page.evaluate("() => {"
                + "const el = document.querySelector('drafthouse-debate')"
                + "  .shadowRoot.querySelector('.entry-flag_human');"
                + "return getComputedStyle(el, '::before').content;"
                + "}");
        org.junit.jupiter.api.Assertions.assertTrue(
                pseudoContent.contains("HUMAN ATTENTION REQUIRED"),
                "::before should contain 'HUMAN ATTENTION REQUIRED', got: " + pseudoContent);
    }

    @Test
    void memoEntry_rendersWithMemoClass() {
        sessionId = startDebateSession(tools);
        tools.postMemo(sessionId, "IMP", 1, "A memo note.");
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 1);
        assertThat(page.locator("drafthouse-debate .entry-memo")).hasCount(1);
    }
}
```

- [ ] **Step 2: Run tests to verify**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebatePanelE2ETest`

Expected: All 11 tests PASS. If any fail, debug — the most likely issue is SSE timing (adjust waitForDebateEntries count or use explicit `page.waitForTimeout(1000)` as a last resort).

- [ ] **Step 3: Commit**

```
git add server/runtime/src/test/java/io/casehub/drafthouse/e2e/DebatePanelE2ETest.java
git commit -m "test: debate panel E2E — placeholder, entry type rendering, badges

Refs #55"
```

---

### Task 4: `DebatePanelE2ETest` — advanced tests (tests 12–18)

**Files:**
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/DebatePanelE2ETest.java`

- [ ] **Step 1: Add restartContext test**

This test needs a second session ID for cleanup. Add a `branchedSessionId` field and update `tearDown()`:

Add field:
```java
private String branchedSessionId;
```

Update `tearDown()`:
```java
@AfterEach
void tearDown() {
    if (branchedSessionId != null) {
        tools.endDebate(branchedSessionId, false);
        branchedSessionId = null;
    }
    if (sessionId != null) {
        tools.endDebate(sessionId, false);
        sessionId = null;
    }
    if (page != null) page.close();
}
```

Add test:
```java
@Test
void restartContext_rendersCenteredBranchMarker() {
    sessionId = startDebateSession(tools);
    String pointId = extractPointId(
            tools.raisePoint(sessionId, "REV", 1, "Point.", "P1", "ISOLATED", null));
    tools.respondTo(sessionId, "IMP", 2, pointId, "counter", "Countered.");

    String restartResult = tools.restartFromRound(sessionId, 1);
    branchedSessionId = extractNewSessionId(restartResult);

    loadWithDebate(page, index, branchedSessionId);
    waitForDebateEntries(page, 1);
    assertThat(page.locator("drafthouse-debate .entry-restart_context")).hasCount(1);
    assertThat(page.locator("drafthouse-debate .entry-restart_context"))
            .containsText("session branched");
}
```

- [ ] **Step 2: Add multipleRounds test**

```java
@Test
void multipleRounds_showsSeparateDividers() {
    sessionId = startDebateSession(tools);
    String pointId = extractPointId(
            tools.raisePoint(sessionId, "REV", 1, "Point.", "P1", "ISOLATED", null));
    tools.respondTo(sessionId, "IMP", 2, pointId, "counter", "Countered.");
    loadWithDebate(page, index, sessionId);
    waitForDebateEntries(page, 2);
    assertThat(page.locator("drafthouse-debate .round-divider")).hasCount(2);
    assertThat(page.locator("drafthouse-debate .round-divider").first()).hasText("Round 1");
    assertThat(page.locator("drafthouse-debate .round-divider").last()).hasText("Round 2");
}
```

- [ ] **Step 3: Add autoScroll test**

```java
@Test
void autoScroll_scrollsToLatestEntry() {
    sessionId = startDebateSession(tools);
    for (int i = 0; i < 6; i++) {
        tools.raisePoint(sessionId, "REV", 1,
                "Point number " + i + " with enough text to take space.", "P2", "ISOLATED", null);
    }
    loadWithDebate(page, index, sessionId);
    waitForDebateEntries(page, 6);
    page.waitForTimeout(500);
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> scroll = (java.util.Map<String, Object>) page.evaluate("() => {"
            + "const c = document.querySelector('drafthouse-debate')"
            + "  .shadowRoot.querySelector('.debate-container');"
            + "return { scrollTop: c.scrollTop, scrollHeight: c.scrollHeight,"
            + "  clientHeight: c.clientHeight };"
            + "}");
    double scrollTop = ((Number) scroll.get("scrollTop")).doubleValue();
    double scrollHeight = ((Number) scroll.get("scrollHeight")).doubleValue();
    double clientHeight = ((Number) scroll.get("clientHeight")).doubleValue();
    org.junit.jupiter.api.Assertions.assertTrue(
            scrollTop + clientHeight >= scrollHeight - 50,
            "debate container should be scrolled to bottom, scrollTop=" + scrollTop
                    + " clientHeight=" + clientHeight + " scrollHeight=" + scrollHeight);
}
```

- [ ] **Step 4: Add pointSelected test**

```java
@Test
void pointSelected_firesCustomEvent() {
    sessionId = startDebateSession(tools);
    String pointId = extractPointId(
            tools.raisePoint(sessionId, "REV", 1, "Point.", "P1", "ISOLATED", "§3.2"));
    loadWithDebate(page, index, sessionId);
    waitForDebateEntries(page, 1);
    listenForPointSelected(page);
    page.locator("drafthouse-debate .entry-raise").click();
    page.waitForTimeout(200);
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> detail =
            (java.util.Map<String, Object>) getPointSelectedDetail(page);
    org.junit.jupiter.api.Assertions.assertNotNull(detail, "point-selected event should have fired");
    org.junit.jupiter.api.Assertions.assertEquals(pointId, detail.get("pointId"));
    org.junit.jupiter.api.Assertions.assertEquals("§3.2", detail.get("location"));
}
```

- [ ] **Step 5: Add sub-agent tests**

```java
@Test
void subTaskRequest_rendersIndented() {
    sessionId = startDebateSession(tools);
    String pointId = extractPointId(
            tools.raisePoint(sessionId, "REV", 1, "Point.", "P1", "ISOLATED", null));
    tools.requestSubagent(sessionId, "REV", "VERIFY", pointId, 1, null);
    loadWithDebate(page, index, sessionId);
    waitForDebateEntries(page, 2);
    assertThat(page.locator("drafthouse-debate .entry-sub_task_request")).hasCount(1);
}

@Test
void subTaskFinding_rendersWithPointBadge() {
    sessionId = startDebateSession(tools);
    String pointId = extractPointId(
            tools.raisePoint(sessionId, "REV", 1, "Point.", "P1", "ISOLATED", null));
    tools.requestSubagent(sessionId, "REV", "VERIFY", pointId, 1, null);
    loadWithDebate(page, index, sessionId);
    // Wait for MockDebateAgentProvider async completion
    page.locator("drafthouse-debate .entry-sub_task_finding").waitFor();
    assertThat(page.locator("drafthouse-debate .entry-sub_task_finding")).hasCount(1);
}

@Test
void subTaskError_rendersWithErrorStyling() {
    sessionId = startDebateSession(tools);
    String pointId = extractPointId(
            tools.raisePoint(sessionId, "REV", 1, "Point.", "P1", "ISOLATED", null));
    tools.requestSubagent(sessionId, "REV", "NONEXISTENT", pointId, 1, null);
    loadWithDebate(page, index, sessionId);
    page.locator("drafthouse-debate .entry-sub_task_error").waitFor();
    assertThat(page.locator("drafthouse-debate .entry-sub_task_error")).hasCount(1);
    assertThat(page.locator("drafthouse-debate .entry-sub_task_error .entry-content"))
            .containsText("No handler matched");
}
```

- [ ] **Step 6: Run tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebatePanelE2ETest`

Expected: All 18 tests PASS.

- [ ] **Step 7: Commit**

```
git add server/runtime/src/test/java/io/casehub/drafthouse/e2e/DebatePanelE2ETest.java
git commit -m "test: debate panel E2E — restart context, rounds, auto-scroll, point-selected, sub-agents

Refs #55"
```

---

### Task 5: `ReviewTrackerE2ETest` — status derivation and rendering (all 16 tests)

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/ReviewTrackerE2ETest.java`

- [ ] **Step 1: Create test class with all tests**

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.casehub.drafthouse.DebateMcpTools;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.DebateE2EFixtures.*;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@WithPlaywright
class ReviewTrackerE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Inject
    DebateMcpTools tools;

    private Page page;
    private String sessionId;

    @BeforeEach
    void setUp() {
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        if (sessionId != null) {
            tools.endDebate(sessionId, false);
            sessionId = null;
        }
        if (page != null) page.close();
    }

    @Test
    void placeholder_whenNoDebateSession() {
        PlaywrightFixtures.loadFilePair(page, index,
                fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("drafthouse-review-tracker .placeholder"))
                .containsText("Waiting for debate session");
    }

    @Test
    void emptyState_showsNoPoints() {
        sessionId = startDebateSession(tools);
        loadWithDebate(page, index, sessionId);
        assertThat(page.locator("drafthouse-review-tracker .placeholder"))
                .containsText("No review points yet");
    }

    @Test
    void raisedPoint_showsOpenStatus() {
        sessionId = startDebateSession(tools);
        tools.raisePoint(sessionId, "REV", 1, "Open point.", "P1", "ISOLATED", null);
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-open")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-open .point-icon"))
                .hasText("○");
    }

    @Test
    void agreedPoint_showsStrikethrough() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Will agree.", "P1", "ISOLATED", null));
        tools.respondTo(sessionId, "IMP", 1, pointId, "agree", "Agreed.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-agreed")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-agreed .point-icon"))
                .hasText("✓");
        String textDeco = (String) page.evaluate("() => {"
                + "const el = document.querySelector('drafthouse-review-tracker')"
                + "  .shadowRoot.querySelector('.point-item.status-agreed .point-summary');"
                + "return getComputedStyle(el).textDecorationLine;"
                + "}");
        assertTrue(textDeco.contains("line-through"),
                "agreed point summary should have line-through, got: " + textDeco);
    }

    @Test
    void declinedPoint_showsStrikethrough() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Will decline.", "P1", "ISOLATED", null));
        tools.respondTo(sessionId, "IMP", 1, pointId, "declined", "Cannot engage.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-declined")).hasCount(1);
        String textDeco = (String) page.evaluate("() => {"
                + "const el = document.querySelector('drafthouse-review-tracker')"
                + "  .shadowRoot.querySelector('.point-item.status-declined .point-summary');"
                + "return getComputedStyle(el).textDecorationLine;"
                + "}");
        assertTrue(textDeco.contains("line-through"),
                "declined point summary should have line-through, got: " + textDeco);
    }

    @Test
    void counteredPoint_showsActiveStatus() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Counter me.", "P1", "ISOLATED", null));
        tools.respondTo(sessionId, "IMP", 1, pointId, "counter", "Countered.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-active")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-active .point-icon"))
                .hasText("⟳");
    }

    @Test
    void disputedPoint_showsDisputedStatus() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Dispute me.", "P1", "ISOLATED", null));
        tools.respondTo(sessionId, "IMP", 1, pointId, "dispute", "Disputed.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-disputed")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-disputed .point-icon"))
                .hasText("✕");
    }

    @Test
    void qualifiedPoint_showsActiveWithAccentBorder() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Qualify me.", "P1", "ISOLATED", null));
        tools.respondTo(sessionId, "IMP", 1, pointId, "qualify", "Qualified.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.qualify-active")).hasCount(1);
    }

    @Test
    void flagHuman_showsPendingHumanStatus() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Flag me.", "P1", "ISOLATED", null));
        tools.flagHuman(sessionId, "REV", 1, pointId, "Need human.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-pending_human")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-pending_human .point-icon"))
                .hasText("⚑");
    }

    @Test
    void progressBar_reflectsResolutionRatio() {
        sessionId = startDebateSession(tools);
        String p1 = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Point 1.", "P1", "ISOLATED", null));
        tools.raisePoint(sessionId, "REV", 1, "Point 2.", "P2", "ISOLATED", null);
        tools.raisePoint(sessionId, "REV", 1, "Point 3.", "P2", "ISOLATED", null);
        tools.respondTo(sessionId, "IMP", 1, p1, "agree", "Agreed.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 3);
        assertThat(page.locator("drafthouse-review-tracker .progress-label"))
                .hasText("1 of 3 resolved");
        double width = (double) page.evaluate("() => {"
                + "const el = document.querySelector('drafthouse-review-tracker')"
                + "  .shadowRoot.querySelector('.progress-fill');"
                + "return parseFloat(el.style.width);"
                + "}");
        assertTrue(width >= 33 && width <= 34,
                "progress fill width should be ~33%, got: " + width + "%");
    }

    @Test
    void hideResolvedFilter_hidesAgreedAndDeclined() {
        sessionId = startDebateSession(tools);
        String p1 = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Agree me.", "P1", "ISOLATED", null));
        String p2 = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Decline me.", "P2", "ISOLATED", null));
        tools.raisePoint(sessionId, "REV", 1, "Stay open.", "P2", "ISOLATED", null);
        tools.respondTo(sessionId, "IMP", 1, p1, "agree", "Agreed.");
        tools.respondTo(sessionId, "IMP", 1, p2, "declined", "Declined.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 3);
        page.locator("drafthouse-review-tracker .filter-toggle input[type='checkbox']").check();
        page.waitForTimeout(300);
        assertThat(page.locator("drafthouse-review-tracker .point-item")).hasCount(1);
        assertThat(page.locator("drafthouse-review-tracker .point-item.status-open")).hasCount(1);
    }

    @Test
    void hideResolvedFilter_allResolved_showsMessage() {
        sessionId = startDebateSession(tools);
        String p1 = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Agree 1.", "P1", "ISOLATED", null));
        String p2 = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Agree 2.", "P2", "ISOLATED", null));
        tools.respondTo(sessionId, "IMP", 1, p1, "agree", "Agreed 1.");
        tools.respondTo(sessionId, "IMP", 1, p2, "agree", "Agreed 2.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 2);
        page.locator("drafthouse-review-tracker .filter-toggle input[type='checkbox']").check();
        page.waitForTimeout(300);
        assertThat(page.locator("drafthouse-review-tracker .placeholder"))
                .containsText("All points resolved");
    }

    @Test
    void sortOrder_openBeforeAgreed() {
        sessionId = startDebateSession(tools);
        String p1 = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "First raised, will agree.", "P1", "ISOLATED", null));
        tools.raisePoint(sessionId, "REV", 1, "Second raised, stays open.", "P2", "ISOLATED", null);
        tools.respondTo(sessionId, "IMP", 1, p1, "agree", "Agreed first.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 2);
        assertThat(page.locator("drafthouse-review-tracker .point-item").first())
                .hasClass(java.util.regex.Pattern.compile(".*status-open.*"));
        assertThat(page.locator("drafthouse-review-tracker .point-item").last())
                .hasClass(java.util.regex.Pattern.compile(".*status-agreed.*"));
    }

    @Test
    void agentTrail_showsActionSequence() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Trail test.", "P1", "ISOLATED", null));
        tools.respondTo(sessionId, "IMP", 1, pointId, "counter", "Countered.");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);
        assertThat(page.locator("drafthouse-review-tracker .point-trail"))
                .containsText("REV raised");
        assertThat(page.locator("drafthouse-review-tracker .point-trail"))
                .containsText("IMP countered");
    }

    @Test
    void locationReference_displayedOnPoint() {
        sessionId = startDebateSession(tools);
        tools.raisePoint(sessionId, "REV", 1, "Has location.", "P1", "ISOLATED", "§3.2");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);
        assertThat(page.locator("drafthouse-review-tracker .point-location"))
                .hasText("§3.2");
    }

    @Test
    void pointSelected_firesCustomEvent() {
        sessionId = startDebateSession(tools);
        String pointId = extractPointId(
                tools.raisePoint(sessionId, "REV", 1, "Click me.", "P1", "ISOLATED", "§3.2"));
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);
        listenForPointSelected(page);
        page.locator("drafthouse-review-tracker .point-item").click();
        page.waitForTimeout(200);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> detail =
                (java.util.Map<String, Object>) getPointSelectedDetail(page);
        assertNotNull(detail, "point-selected event should have fired");
        assertEquals(pointId, detail.get("pointId"));
    }
}
```

- [ ] **Step 2: Run tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=ReviewTrackerE2ETest`

Expected: All 16 tests PASS.

- [ ] **Step 3: Commit**

```
git add server/runtime/src/test/java/io/casehub/drafthouse/e2e/ReviewTrackerE2ETest.java
git commit -m "test: review tracker E2E — status derivation, progress bar, filter, sort, trail

Refs #55"
```

---

### Task 6: `CrossPanelE2ETest` — event routing (4 tests)

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/CrossPanelE2ETest.java`

- [ ] **Step 1: Create test class**

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.casehub.drafthouse.DebateMcpTools;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static io.casehub.drafthouse.e2e.DebateE2EFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@WithPlaywright
class CrossPanelE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Inject
    DebateMcpTools tools;

    private Page page;
    private String sessionId;

    @BeforeEach
    void setUp() {
        page = context.newPage();
    }

    @AfterEach
    void tearDown() {
        if (sessionId != null) {
            tools.endDebate(sessionId, false);
            sessionId = null;
        }
        if (page != null) page.close();
    }

    private int scrollTopA() {
        return (int) page.evaluate("() => document.querySelector('drafthouse-diff')"
                + ".shadowRoot.getElementById('body-a').scrollTop");
    }

    private int scrollTopB() {
        return (int) page.evaluate("() => document.querySelector('drafthouse-diff')"
                + ".shadowRoot.getElementById('body-b').scrollTop");
    }

    @Test
    void debateEntry_scrollsDiffToSectionRef() {
        sessionId = startDebateSession(tools);
        tools.raisePoint(sessionId, "REV", 1, "Section ref.", "P1", "ISOLATED", "§3");
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 1);
        int beforeA = scrollTopA();
        int beforeB = scrollTopB();
        page.locator("drafthouse-debate .entry-raise").click();
        page.waitForTimeout(500);
        assertTrue(scrollTopA() > beforeA,
                "side A should have scrolled (§3 → 'Features')");
        assertTrue(scrollTopB() > beforeB,
                "side B should have scrolled (§3 → 'Scroll Sync')");
    }

    @Test
    void trackerPoint_scrollsDiffToSectionRef() {
        sessionId = startDebateSession(tools);
        tools.raisePoint(sessionId, "REV", 1, "Section ref.", "P1", "ISOLATED", "§3");
        loadWithDebate(page, index, sessionId);
        waitForTrackerPoints(page, 1);
        int beforeA = scrollTopA();
        int beforeB = scrollTopB();
        page.locator("drafthouse-review-tracker .point-item").click();
        page.waitForTimeout(500);
        assertTrue(scrollTopA() > beforeA,
                "side A should have scrolled (§3 → 'Features')");
        assertTrue(scrollTopB() > beforeB,
                "side B should have scrolled (§3 → 'Scroll Sync')");
    }

    @Test
    void pointWithoutLocation_noScroll() {
        sessionId = startDebateSession(tools);
        tools.raisePoint(sessionId, "REV", 1, "No location.", "P1", "ISOLATED", null);
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 1);
        int beforeA = scrollTopA();
        int beforeB = scrollTopB();
        page.locator("drafthouse-debate .entry-raise").click();
        page.waitForTimeout(500);
        assertEquals(beforeA, scrollTopA(), "side A should not have scrolled");
        assertEquals(beforeB, scrollTopB(), "side B should not have scrolled");
    }

    @Test
    void textReference_scrollsToMatchingHeading() {
        sessionId = startDebateSession(tools);
        tools.raisePoint(sessionId, "REV", 1, "Text ref.", "P1", "ISOLATED", "Scroll Sync");
        loadWithDebate(page, index, sessionId);
        waitForDebateEntries(page, 1);
        int beforeA = scrollTopA();
        int beforeB = scrollTopB();
        page.locator("drafthouse-debate .entry-raise").click();
        page.waitForTimeout(500);
        assertTrue(scrollTopA() > beforeA,
                "side A should have scrolled to 'Scroll Sync' heading");
        assertTrue(scrollTopB() > beforeB,
                "side B should have scrolled to 'Scroll Sync' heading");
    }
}
```

- [ ] **Step 2: Run tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=CrossPanelE2ETest`

Expected: All 4 tests PASS.

- [ ] **Step 3: Commit**

```
git add server/runtime/src/test/java/io/casehub/drafthouse/e2e/CrossPanelE2ETest.java
git commit -m "test: cross-panel E2E — point-selected routes to scrollToLocation

Refs #55"
```

---

### Task 7: Full test suite verification

**Files:**
- None (verification only)

- [ ] **Step 1: Run all tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`

Expected: All tests pass — existing tests unregressed, 38 new E2E tests green.

- [ ] **Step 2: Verify test counts**

The new tests should add:
- `DebatePanelE2ETest`: 18 tests
- `ReviewTrackerE2ETest`: 16 tests
- `CrossPanelE2ETest`: 4 tests

Total new: 38. Combined with existing ~236 tests, expect ~274 total.

---
