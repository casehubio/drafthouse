# UI Batch + Document Sets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship keyboard shortcuts overlay (#63), multi-document working sets (#59), and export debate summary (#65) on a single branch.

**Architecture:** Browser-only chrome for shortcuts; pure-Java `DocumentSet` in `server/api/` replacing `specPath` on `DebateSession`; four new MCP tools + REST endpoints on `DebateEventResource`; SSE metadata events for browser sync; one new MCP export tool.

**Tech Stack:** Java 17 (server/api/, server/runtime/), vanilla JS (index.html, styles.css), Quarkus MCP tools, JAX-RS REST, SSE via `DebateEventBus`

**Spec:** `docs/superpowers/specs/2026-06-17-ui-batch-and-document-sets-design.md`

---

## File Map

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `index.html` | Keyboard overlay HTML + `?`/`Escape` handler + fix input guard + document list dropdown + `comparison-changed` SSE listener |
| Modify | `styles.css` | Overlay/backdrop styles + document dropdown styles |
| Create | `server/api/src/main/java/io/casehub/drafthouse/DocumentSet.java` | Thread-safe document collection + `DocumentEntry`/`ComparisonPair` records |
| Create | `server/api/src/test/java/io/casehub/drafthouse/DocumentSetTest.java` | Unit tests for DocumentSet |
| Modify | `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java` | Replace `specPath` with `DocumentSet`, derived `specPath()` accessor |
| Modify | `server/api/src/test/java/io/casehub/drafthouse/DebateSessionTest.java` | Migrate constructor calls, verify derived `specPath()` |
| Modify | `server/runtime/src/main/java/io/casehub/drafthouse/handler/AbstractDebateSubAgentHandler.java` | Update `requireSpecPath()` to use `Optional`-based `primary()` |
| Modify | `server/runtime/src/test/java/io/casehub/drafthouse/handler/VerifyHandlerTest.java` | Migrate constructor calls, update null-specPath assertion |
| Modify | `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java` | Add 4 document tools + `export_debate_summary` + working set in summary + update `startDebate`/`restartFromRound` |
| Modify | `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java` | Migrate `sessionFor()` helper, add document tool tests |
| Modify | `server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java` | Add `GET /documents`, `POST /comparison`, push document/comparison SSE events |
| Create | `server/runtime/src/test/java/io/casehub/drafthouse/e2e/KeyboardShortcutsE2ETest.java` | E2E for `?` overlay |

---

### Task 1: Keyboard Shortcuts Overlay — HTML + CSS + Handler (#63)

**Files:**
- Modify: `index.html`
- Modify: `styles.css`
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/KeyboardShortcutsE2ETest.java`

- [ ] **Step 1: Write E2E test**

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Locator;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

@QuarkusTest
class KeyboardShortcutsE2ETest extends AbstractPlaywrightTest {

    @TestHTTPResource("/")
    URL base;

    @Test
    void questionMark_togglesOverlay() {
        page.navigate(base + "?a=" + PlaywrightFixtures.fixturePath("sample-a.md")
                + "&b=" + PlaywrightFixtures.fixturePath("sample-b.md"));
        PlaywrightFixtures.waitForRender(page);

        Locator overlay = page.locator("#shortcuts-overlay");
        assertThat(overlay).isHidden();

        page.keyboard().press("?");
        assertThat(overlay).isVisible();
        assertThat(overlay).containsText("Next diff");
        assertThat(overlay).containsText("Previous diff");

        page.keyboard().press("Escape");
        assertThat(overlay).isHidden();
    }

    @Test
    void questionMark_dismissedByClickingBackdrop() {
        page.navigate(base + "?a=" + PlaywrightFixtures.fixturePath("sample-a.md")
                + "&b=" + PlaywrightFixtures.fixturePath("sample-b.md"));
        PlaywrightFixtures.waitForRender(page);

        page.keyboard().press("?");
        assertThat(page.locator("#shortcuts-overlay")).isVisible();

        page.locator("#shortcuts-backdrop").click();
        assertThat(page.locator("#shortcuts-overlay")).isHidden();
    }

    @Test
    void shortcutKeys_suppressedInTextarea() {
        page.navigate(base + "?a=" + PlaywrightFixtures.fixturePath("sample-a.md")
                + "&b=" + PlaywrightFixtures.fixturePath("sample-b.md"));
        PlaywrightFixtures.waitForRender(page);

        // Focus a panel label input (existing INPUT element inside shadow DOM)
        Locator label = page.locator("drafthouse-diff").locator("input.panel-label").first();
        label.focus();
        page.keyboard().press("?");
        // Overlay should NOT open when focus is in an input
        assertThat(page.locator("#shortcuts-overlay")).isHidden();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=KeyboardShortcutsE2ETest`
Expected: FAIL — `#shortcuts-overlay` not found

- [ ] **Step 3: Add overlay HTML and keyboard handler to index.html**

Add before `</body>`:

```html
<!-- ── Keyboard shortcuts overlay ── -->
<div id="shortcuts-backdrop" class="hidden"></div>
<div id="shortcuts-overlay" class="hidden">
  <h3>Keyboard Shortcuts</h3>
  <table>
    <tr><td><kbd>n</kbd></td><td>Next diff</td></tr>
    <tr><td><kbd>p</kbd></td><td>Previous diff</td></tr>
    <tr><td><kbd>⌘/Ctrl+S</kbd></td><td>Toggle scroll sync</td></tr>
    <tr><td><kbd>?</kbd></td><td>Show/hide shortcuts</td></tr>
  </table>
</div>
```

Replace the existing keyboard handler (the `document.addEventListener('keydown', ...)` block) with:

```javascript
function isEditable(el) {
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
}

function toggleShortcutsOverlay() {
  $('shortcuts-overlay').classList.toggle('hidden');
  $('shortcuts-backdrop').classList.toggle('hidden');
}

document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && !$('shortcuts-overlay').classList.contains('hidden')) {
    toggleShortcutsOverlay();
    return;
  }
  if (isEditable(e.target)) return;
  if (e.key === '?') { toggleShortcutsOverlay(); return; }
  if (e.key === 's' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); diffPanel.toggleSync(); updateSyncButton(); }
  if (e.key === 'n') { diffPanel.nextDiff(); updateDiffUI(); }
  if (e.key === 'p') { diffPanel.prevDiff(); updateDiffUI(); }
});

$('shortcuts-backdrop').addEventListener('click', toggleShortcutsOverlay);
```

- [ ] **Step 4: Add overlay styles to styles.css**

```css
/* ── Keyboard shortcuts overlay ── */
#shortcuts-backdrop {
  position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 999;
}
#shortcuts-overlay {
  position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%);
  background: var(--chrome); border: 2px solid var(--ink); border-radius: 6px;
  padding: 24px 32px; z-index: 1000; min-width: 280px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
}
#shortcuts-overlay.hidden, #shortcuts-backdrop.hidden { display: none; }
#shortcuts-overlay h3 {
  font-family: Georgia, serif; font-style: italic; font-size: 15px;
  color: var(--ink); margin-bottom: 14px; border-bottom: 1px solid var(--border); padding-bottom: 8px;
}
#shortcuts-overlay table { width: 100%; }
#shortcuts-overlay td { padding: 4px 0; font-size: 13px; color: var(--sepia); }
#shortcuts-overlay td:first-child { width: 100px; }
#shortcuts-overlay kbd {
  background: var(--bg); border: 1px solid var(--border); border-radius: 3px;
  padding: 2px 6px; font-family: 'SFMono-Regular', Consolas, monospace; font-size: 11px;
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=KeyboardShortcutsE2ETest`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add index.html styles.css server/runtime/src/test/java/io/casehub/drafthouse/e2e/KeyboardShortcutsE2ETest.java
git commit -m "feat: keyboard shortcuts overlay — ? to toggle, Escape/backdrop to dismiss

Fix input focus guard: suppress shortcuts in TEXTAREA, SELECT,
and contenteditable, not just INPUT.

Closes #63"
```

---

### Task 2: DocumentSet Domain Model (#59 — Part 1)

**Files:**
- Create: `server/api/src/main/java/io/casehub/drafthouse/DocumentSet.java`
- Create: `server/api/src/test/java/io/casehub/drafthouse/DocumentSetTest.java`

- [ ] **Step 1: Write DocumentSet unit tests**

```java
package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class DocumentSetTest {

    @Test
    void add_returnsTrue_andDocumentIsRetrievable() {
        var set = new DocumentSet();
        assertThat(set.add("/a.md", "spec")).isTrue();
        assertThat(set.documents()).hasSize(1);
        assertThat(set.documents().get(0).path()).isEqualTo("/a.md");
        assertThat(set.documents().get(0).label()).isEqualTo("spec");
    }

    @Test
    void add_duplicatePath_returnsFalse() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        assertThat(set.add("/a.md", "different-label")).isFalse();
        assertThat(set.documents()).hasSize(1);
    }

    @Test
    void remove_existingPath_returnsTrue() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        assertThat(set.remove("/b.md")).isTrue();
        assertThat(set.documents()).hasSize(1);
    }

    @Test
    void remove_nonexistentPath_returnsFalse() {
        var set = new DocumentSet();
        assertThat(set.remove("/no-such.md")).isFalse();
    }

    @Test
    void primary_returnsFirstDocument() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        assertThat(set.primary()).isPresent();
        assertThat(set.primary().get().path()).isEqualTo("/a.md");
    }

    @Test
    void primary_emptySet_returnsEmpty() {
        var set = new DocumentSet();
        assertThat(set.primary()).isEmpty();
    }

    @Test
    void setComparison_storesCurrentPair() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        set.setComparison("/a.md", "/b.md");
        assertThat(set.currentComparison()).isNotNull();
        assertThat(set.currentComparison().pathA()).isEqualTo("/a.md");
        assertThat(set.currentComparison().pathB()).isEqualTo("/b.md");
    }

    @Test
    void clearComparison_setsToNull() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        set.setComparison("/a.md", "/b.md");
        set.clearComparison();
        assertThat(set.currentComparison()).isNull();
    }

    @Test
    void remove_pathInComparison_clearsComparison() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        set.setComparison("/a.md", "/b.md");
        set.remove("/b.md");
        assertThat(set.currentComparison()).isNull();
    }

    @Test
    void documentEntry_rejectsNullPath() {
        assertThatThrownBy(() -> new DocumentSet.DocumentEntry(null, "label"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("path");
    }

    @Test
    void documentEntry_rejectsBlankPath() {
        assertThatThrownBy(() -> new DocumentSet.DocumentEntry("  ", "label"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
    }

    @Test
    void documentEntry_rejectsNullLabel() {
        assertThatThrownBy(() -> new DocumentSet.DocumentEntry("/a.md", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("label");
    }

    @Test
    void copyOf_deepCopiesDocumentsAndComparison() {
        var original = new DocumentSet();
        original.add("/a.md", "spec");
        original.add("/b.md", "impl");
        original.setComparison("/a.md", "/b.md");

        var copy = DocumentSet.copyOf(original);
        assertThat(copy.documents()).hasSize(2);
        assertThat(copy.currentComparison().pathA()).isEqualTo("/a.md");

        // Mutations on copy don't affect original
        copy.add("/c.md", "test");
        assertThat(original.documents()).hasSize(2);
    }

    @Test
    void documents_returnsDefensiveCopy() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        var list = set.documents();
        assertThatThrownBy(() -> list.add(new DocumentSet.DocumentEntry("/b.md", "x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void setComparison_allowsSamePathForBothSides() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.setComparison("/a.md", "/a.md");
        assertThat(set.currentComparison().pathA()).isEqualTo("/a.md");
        assertThat(set.currentComparison().pathB()).isEqualTo("/a.md");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DocumentSetTest`
Expected: FAIL — `DocumentSet` class not found

- [ ] **Step 3: Implement DocumentSet**

```java
package io.casehub.drafthouse;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class DocumentSet {

    private final CopyOnWriteArrayList<DocumentEntry> documents = new CopyOnWriteArrayList<>();
    private volatile ComparisonPair currentComparison;

    public record DocumentEntry(String path, String label) {
        public DocumentEntry {
            Objects.requireNonNull(path, "path");
            if (path.isBlank()) throw new IllegalArgumentException("path must be non-blank");
            Objects.requireNonNull(label, "label");
        }
    }

    public record ComparisonPair(String pathA, String pathB) {}

    public boolean add(String path, String label) {
        for (DocumentEntry e : documents) {
            if (e.path().equals(path)) return false;
        }
        documents.add(new DocumentEntry(path, label));
        return true;
    }

    public boolean remove(String path) {
        boolean removed = documents.removeIf(e -> e.path().equals(path));
        if (removed) {
            ComparisonPair cp = currentComparison;
            if (cp != null && (path.equals(cp.pathA()) || path.equals(cp.pathB()))) {
                currentComparison = null;
            }
        }
        return removed;
    }

    public List<DocumentEntry> documents() {
        return List.copyOf(documents);
    }

    public Optional<DocumentEntry> primary() {
        return documents.isEmpty() ? Optional.empty() : Optional.of(documents.get(0));
    }

    public void setComparison(String pathA, String pathB) {
        currentComparison = new ComparisonPair(pathA, pathB);
    }

    public void clearComparison() {
        currentComparison = null;
    }

    public ComparisonPair currentComparison() {
        return currentComparison;
    }

    public static DocumentSet copyOf(DocumentSet source) {
        var copy = new DocumentSet();
        for (DocumentEntry e : source.documents) {
            copy.documents.add(e);
        }
        copy.currentComparison = source.currentComparison;
        return copy;
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DocumentSetTest`
Expected: PASS (14 tests)

- [ ] **Step 5: Commit**

```bash
git add server/api/src/main/java/io/casehub/drafthouse/DocumentSet.java server/api/src/test/java/io/casehub/drafthouse/DocumentSetTest.java
git commit -m "feat: DocumentSet domain model — thread-safe document collection with validation

Records: DocumentEntry (validated path+label), ComparisonPair.
CopyOnWriteArrayList + volatile for concurrent access.
Path uniqueness enforced. remove() auto-clears comparison.

Refs #59"
```

---

### Task 3: Migrate DebateSession from specPath to DocumentSet (#59 — Part 2)

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DebateSession.java`
- Modify: `server/api/src/test/java/io/casehub/drafthouse/DebateSessionTest.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/handler/AbstractDebateSubAgentHandler.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/handler/VerifyHandlerTest.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java`

- [ ] **Step 1: Update DebateSession — remove specPath, add DocumentSet**

Replace the `specPath` field and constructor in `DebateSession.java`:

```java
// Remove this field:
// private final String specPath;

// Add this field (alongside existing fields):
private final DocumentSet documentSet;

// Replace constructor with two forms:
public DebateSession(final UUID channelId, final String debateSessionId,
                     final String channelName) {
    this.channelId       = channelId;
    this.debateSessionId = debateSessionId;
    this.channelName     = channelName;
    this.documentSet     = new DocumentSet();
}

public DebateSession(final UUID channelId, final String debateSessionId,
                     final String channelName, final DocumentSet documentSet) {
    this.channelId       = channelId;
    this.debateSessionId = debateSessionId;
    this.channelName     = channelName;
    this.documentSet     = documentSet;
}

// Replace specPath() getter:
public String specPath() {
    return documentSet.primary().map(DocumentSet.DocumentEntry::path).orElse(null);
}

// Add documentSet() getter:
public DocumentSet documentSet() { return documentSet; }
```

- [ ] **Step 2: Update DebateSessionTest**

Replace every 4-arg constructor call `new DebateSession(CHANNEL_ID, SESSION_ID, NAME, "spec.md")` with:

```java
private static DebateSession sessionWithSpec(String specPath) {
    var session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    if (specPath != null) session.documentSet().add(specPath, "spec");
    return session;
}
```

Update each test to use `sessionWithSpec("spec.md")` or `sessionWithSpec(null)` or `new DebateSession(CHANNEL_ID, SESSION_ID, NAME)` as appropriate.

Update the getter test:
```java
@Test
void specPath_derivedFromDocumentSetPrimary() {
    DebateSession session = sessionWithSpec("my-spec.md");
    assertThat(session.specPath()).isEqualTo("my-spec.md");
}

@Test
void specPath_nullWhenNoDocuments() {
    DebateSession session = new DebateSession(CHANNEL_ID, SESSION_ID, NAME);
    assertThat(session.specPath()).isNull();
}
```

- [ ] **Step 3: Update AbstractDebateSubAgentHandler.requireSpecPath()**

```java
protected String requireSpecPath(DebateSession session) {
    return session.documentSet().primary()
            .orElseThrow(() -> new IllegalArgumentException(taskType()
                    + " requires a document in the working set — start_debate must receive a spec path"))
            .path();
}
```

- [ ] **Step 4: Update VerifyHandlerTest constructor calls**

Replace `new DebateSession(channelId, channelId.toString(), "ch", specFile.toString())` with:
```java
private DebateSession sessionWithSpec(String specPath) {
    var session = new DebateSession(channelId, channelId.toString(), "ch");
    if (specPath != null) session.documentSet().add(specPath, "spec");
    return session;
}
```

Update `throws_on_null_specPath` test:
```java
@Test
void throws_on_empty_document_set() {
    when(registry.find(channelId)).thenReturn(Optional.of(
            new DebateSession(channelId, channelId.toString(), "ch")));
    setupState("pt-1", "Some claim.");
    assertThatThrownBy(() -> handler.prepareTask(requestFor("pt-1")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("document in the working set");
}
```

- [ ] **Step 5: Update DebateMcpToolsTest.sessionFor()**

```java
private DebateSession sessionFor(final UUID channelId) {
    final DebateSession session = new DebateSession(channelId, channelId.toString(),
            "drafthouse/debate/d-" + channelId);
    session.documentSet().add("spec.md", "spec");
    session.registerIfAbsent(AgentType.REV,
            () -> DebateSession.instanceId(AgentType.REV, channelId.toString()));
    session.registerIfAbsent(AgentType.IMP,
            () -> DebateSession.instanceId(AgentType.IMP, channelId.toString()));
    return session;
}
```

- [ ] **Step 6: Run all tests to verify migration is clean**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: PASS — all existing tests green

- [ ] **Step 7: Commit**

```bash
git add server/api/src/main/java/io/casehub/drafthouse/DebateSession.java \
  server/api/src/test/java/io/casehub/drafthouse/DebateSessionTest.java \
  server/runtime/src/main/java/io/casehub/drafthouse/handler/AbstractDebateSubAgentHandler.java \
  server/runtime/src/test/java/io/casehub/drafthouse/handler/VerifyHandlerTest.java \
  server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java
git commit -m "refactor: replace DebateSession.specPath with DocumentSet

specPath() is now a derived accessor via documentSet.primary().
requireSpecPath() uses Optional — no secondary null check.
All tests migrated to new constructor form.

Refs #59"
```

---

### Task 4: MCP Document Tools + startDebate/restartFromRound Migration (#59 — Part 3)

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java`

- [ ] **Step 1: Write tests for add_document, remove_document, list_documents, set_comparison**

Add to `DebateMcpToolsTest.java`:

```java
// ── add_document ─────────────────────────────────────────────────────

@Test
void addDocument_addsToWorkingSet() {
    DebateSession session = sessionFor(channelId);
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    String result = tools.addDocument(channelId.toString(), "/impl.java", "impl");
    assertThat(result).contains("\"status\":\"added\"");
    assertThat(result).contains("\"documentCount\":2"); // spec.md + impl.java
}

@Test
void addDocument_duplicatePath_returnsError() {
    DebateSession session = sessionFor(channelId);
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    String result = tools.addDocument(channelId.toString(), "spec.md", "duplicate");
    assertThat(result).startsWith("error:");
}

// ── remove_document ──────────────────────────────────────────────────

@Test
void removeDocument_removesNonPrimary() {
    DebateSession session = sessionFor(channelId);
    session.documentSet().add("/impl.java", "impl");
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    String result = tools.removeDocument(channelId.toString(), "/impl.java");
    assertThat(result).contains("\"status\":\"removed\"");
}

@Test
void removeDocument_primaryDocument_returnsError() {
    DebateSession session = sessionFor(channelId);
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    String result = tools.removeDocument(channelId.toString(), "spec.md");
    assertThat(result).startsWith("error:");
}

// ── list_documents ───────────────────────────────────────────────────

@Test
void listDocuments_returnsWrapperObject() {
    DebateSession session = sessionFor(channelId);
    session.documentSet().add("/impl.java", "impl");
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    String result = tools.listDocuments(channelId.toString());
    assertThat(result).contains("\"documents\":");
    assertThat(result).contains("\"currentComparison\":");
    assertThat(result).contains("spec.md");
    assertThat(result).contains("/impl.java");
}

// ── set_comparison ───────────────────────────────────────────────────

@Test
void setComparison_setsActivePair() {
    DebateSession session = sessionFor(channelId);
    session.documentSet().add("/impl.java", "impl");
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    String result = tools.setComparison(channelId.toString(), "spec.md", "/impl.java");
    assertThat(result).contains("\"status\":\"set\"");
    assertThat(session.documentSet().currentComparison().pathA()).isEqualTo("spec.md");
}

@Test
void setComparison_pathNotInSet_returnsError() {
    DebateSession session = sessionFor(channelId);
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    String result = tools.setComparison(channelId.toString(), "spec.md", "/not-in-set.md");
    assertThat(result).startsWith("error:");
}
```

- [ ] **Step 2: Write test for working set section in get_debate_summary**

```java
@Test
void getDebateSummary_includesWorkingSetSection() {
    DebateSession session = sessionFor(channelId);
    session.documentSet().add("/impl.java", "impl");
    session.documentSet().setComparison("spec.md", "/impl.java");
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    when(projectionService.project(eq(channelId), any()))
            .thenReturn(new ProjectionResult<>(emptyState(), null));
    when(debateProjection.render(any())).thenReturn("No debate activity yet.");

    String result = tools.getDebateSummary(channelId.toString());
    assertThat(result).contains("## Working Set");
    assertThat(result).contains("**spec**");
    assertThat(result).contains("**impl**");
    assertThat(result).contains("Comparing:");
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest`
Expected: FAIL — methods `addDocument`, `removeDocument`, `listDocuments`, `setComparison` not found

- [ ] **Step 4: Update startDebate() to use DocumentSet**

In `DebateMcpTools.startDebate()`, replace the constructor call:

```java
// Old:
session = new DebateSession(channel.id, debateSessionId, resolvedName, specPath);

// New:
session = new DebateSession(channel.id, debateSessionId, resolvedName);
session.documentSet().add(specPath, "spec");
```

- [ ] **Step 5: Update restartFromRound() to deep-copy DocumentSet**

In `DebateMcpTools.restartFromRound()`, replace the constructor call:

```java
// Old:
newSession = new DebateSession(newChannel.id, newSessionId, newChannel.name, original.specPath());

// New:
newSession = new DebateSession(newChannel.id, newSessionId, newChannel.name,
        DocumentSet.copyOf(original.documentSet()));
```

- [ ] **Step 6: Implement the four document MCP tools**

Add to `DebateMcpTools.java`:

```java
@Tool(name = "add_document",
      description = "Add a document to the debate session's working set. Returns error if path already exists.")
public String addDocument(
        @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
        @ToolArg(description = "Absolute path to the document") String path,
        @ToolArg(description = "Label for this document (e.g. 'spec', 'impl', 'test')") String label) {

    DebateSession session = resolveSession(debateSessionId);
    if (session == null) return sessionError(debateSessionId);

    if (!session.documentSet().add(path, label)) {
        return "error: path already in document set: " + path;
    }
    pushDocumentsChanged(session);
    return "{\"status\":\"added\",\"documentCount\":" + session.documentSet().documents().size() + "}";
}

@Tool(name = "remove_document",
      description = "Remove a document from the working set. Cannot remove the primary (first) document.")
public String removeDocument(
        @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
        @ToolArg(description = "Path of the document to remove") String path) {

    DebateSession session = resolveSession(debateSessionId);
    if (session == null) return sessionError(debateSessionId);

    var primary = session.documentSet().primary();
    if (primary.isPresent() && primary.get().path().equals(path)) {
        return "error: cannot remove primary document";
    }

    boolean hadComparison = session.documentSet().currentComparison() != null;
    if (!session.documentSet().remove(path)) {
        return "error: path not in document set: " + path;
    }
    pushDocumentsChanged(session);
    if (hadComparison && session.documentSet().currentComparison() == null) {
        pushComparisonChanged(session);
    }
    return "{\"status\":\"removed\",\"documentCount\":" + session.documentSet().documents().size() + "}";
}

@Tool(name = "list_documents",
      description = "List all documents in the working set and the current comparison pair.")
public String listDocuments(
        @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId) {

    DebateSession session = resolveSession(debateSessionId);
    if (session == null) return sessionError(debateSessionId);
    return buildDocumentsJson(session);
}

@Tool(name = "set_comparison",
      description = "Set which two documents the browser diff viewer should compare. Both paths must be in the working set.")
public String setComparison(
        @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
        @ToolArg(description = "Path for the A (left) side") String pathA,
        @ToolArg(description = "Path for the B (right) side") String pathB) {

    DebateSession session = resolveSession(debateSessionId);
    if (session == null) return sessionError(debateSessionId);

    var docs = session.documentSet().documents();
    boolean hasA = docs.stream().anyMatch(d -> d.path().equals(pathA));
    boolean hasB = docs.stream().anyMatch(d -> d.path().equals(pathB));
    if (!hasA) return "error: path not in document set: " + pathA;
    if (!hasB) return "error: path not in document set: " + pathB;

    session.documentSet().setComparison(pathA, pathB);
    pushComparisonChanged(session);
    return "{\"status\":\"set\",\"pathA\":" + jsonString(pathA) + ",\"pathB\":" + jsonString(pathB) + "}";
}
```

- [ ] **Step 7: Add working set section to getDebateSummary()**

In `getDebateSummary()`, after the selection section (before the final `return`), append:

```java
private String appendWorkingSet(String summary, DebateSession session) {
    var docs = session.documentSet().documents();
    if (docs.size() <= 1) return summary;
    StringBuilder sb = new StringBuilder(summary);
    sb.append("\n\n## Working Set\n");
    for (var doc : docs) {
        sb.append("- **").append(doc.label()).append("** — `").append(doc.path()).append("`\n");
    }
    var cp = session.documentSet().currentComparison();
    if (cp != null) {
        String labelA = docs.stream().filter(d -> d.path().equals(cp.pathA()))
                .map(DocumentSet.DocumentEntry::label).findFirst().orElse(cp.pathA());
        String labelB = docs.stream().filter(d -> d.path().equals(cp.pathB()))
                .map(DocumentSet.DocumentEntry::label).findFirst().orElse(cp.pathB());
        sb.append("\n**Comparing:** ").append(labelA).append(" ↔ ").append(labelB).append("\n");
    }
    return sb.toString();
}
```

Update `getDebateSummary()` to call `appendWorkingSet()` before returning.

- [ ] **Step 8: Add SSE push helpers**

```java
private void pushDocumentsChanged(DebateSession session) {
    debateEventResource.pushDocumentsChanged(session.channelId(), session.documentSet());
}

private void pushComparisonChanged(DebateSession session) {
    debateEventResource.pushComparisonChanged(session.channelId(), session.documentSet().currentComparison());
}

private String buildDocumentsJson(DebateSession session) {
    var docs = session.documentSet().documents();
    StringBuilder sb = new StringBuilder("{\"documents\":[");
    for (int i = 0; i < docs.size(); i++) {
        if (i > 0) sb.append(",");
        sb.append("{\"path\":").append(jsonString(docs.get(i).path()))
          .append(",\"label\":").append(jsonString(docs.get(i).label())).append("}");
    }
    sb.append("],\"currentComparison\":");
    var cp = session.documentSet().currentComparison();
    if (cp != null) {
        sb.append("{\"pathA\":").append(jsonString(cp.pathA()))
          .append(",\"pathB\":").append(jsonString(cp.pathB())).append("}");
    } else {
        sb.append("null");
    }
    sb.append("}");
    return sb.toString();
}
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest`
Expected: PASS

- [ ] **Step 10: Commit**

```bash
git add server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java \
  server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java
git commit -m "feat: MCP document tools — add_document, remove_document, list_documents, set_comparison

Migrate startDebate/restartFromRound to DocumentSet.
Working set section appended to get_debate_summary.
SSE push for documents-changed and comparison-changed.

Refs #59"
```

---

### Task 5: REST Endpoints + SSE Push Infrastructure (#59 — Part 4)

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java`

- [ ] **Step 1: Add SSE push methods and REST endpoints to DebateEventResource**

Add pending maps alongside existing ones:
```java
private final ConcurrentHashMap<UUID, String> pendingDocuments = new ConcurrentHashMap<>();
private final ConcurrentHashMap<UUID, String> pendingComparisons = new ConcurrentHashMap<>();
```

Add push methods:
```java
public void pushDocumentsChanged(UUID channelId, DocumentSet documentSet) {
    try {
        StringBuilder sb = new StringBuilder("{\"type\":\"documents-changed\",\"documents\":[");
        var docs = documentSet.documents();
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"path\":\"").append(escapeJson(docs.get(i).path()))
              .append("\",\"label\":\"").append(escapeJson(docs.get(i).label())).append("\"}");
        }
        sb.append("]}");
        pendingDocuments.put(channelId, sb.toString());
    } catch (Exception e) {
        LOG.warning("Failed to build documents-changed JSON: " + e.getMessage());
    }
}

public void pushComparisonChanged(UUID channelId, DocumentSet.ComparisonPair cp) {
    try {
        String json;
        if (cp != null) {
            json = "{\"type\":\"comparison-changed\",\"pathA\":\"" + escapeJson(cp.pathA())
                    + "\",\"pathB\":\"" + escapeJson(cp.pathB()) + "\"}";
        } else {
            json = "{\"type\":\"comparison-changed\",\"pathA\":null,\"pathB\":null}";
        }
        pendingComparisons.put(channelId, json);
    } catch (Exception e) {
        LOG.warning("Failed to build comparison-changed JSON: " + e.getMessage());
    }
}
```

In the `live` Multi tick (inside `events()`), add draining for both maps after the existing `pendingSel` drain:
```java
String pendingDoc = pendingDocuments.remove(channelId);
if (pendingDoc != null) items.add(pendingDoc);

String pendingComp = pendingComparisons.remove(channelId);
if (pendingComp != null) items.add(pendingComp);
```

Add REST endpoints:
```java
record ComparisonRequest(String pathA, String pathB) {}

@GET
@Path("/{debateSessionId}/documents")
@Produces(MediaType.APPLICATION_JSON)
public jakarta.ws.rs.core.Response getDocuments(
        @PathParam("debateSessionId") String debateSessionId) {
    UUID channelId = parseSessionId(debateSessionId);
    DebateSession session = registry.find(channelId)
            .orElseThrow(() -> new NotFoundException("No active debate session: " + debateSessionId));

    var docs = session.documentSet().documents();
    StringBuilder sb = new StringBuilder("{\"documents\":[");
    for (int i = 0; i < docs.size(); i++) {
        if (i > 0) sb.append(",");
        sb.append("{\"path\":\"").append(escapeJson(docs.get(i).path()))
          .append("\",\"label\":\"").append(escapeJson(docs.get(i).label())).append("\"}");
    }
    sb.append("],\"currentComparison\":");
    var cp = session.documentSet().currentComparison();
    if (cp != null) {
        sb.append("{\"pathA\":\"").append(escapeJson(cp.pathA()))
          .append("\",\"pathB\":\"").append(escapeJson(cp.pathB())).append("\"}");
    } else {
        sb.append("null");
    }
    sb.append("}");
    return jakarta.ws.rs.core.Response.ok(sb.toString()).build();
}

@POST
@Path("/{debateSessionId}/comparison")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public jakarta.ws.rs.core.Response postComparison(
        @PathParam("debateSessionId") String debateSessionId,
        ComparisonRequest request) {
    UUID channelId = parseSessionId(debateSessionId);
    DebateSession session = registry.find(channelId)
            .orElseThrow(() -> new NotFoundException("No active debate session: " + debateSessionId));

    var docs = session.documentSet().documents();
    boolean hasA = docs.stream().anyMatch(d -> d.path().equals(request.pathA()));
    boolean hasB = docs.stream().anyMatch(d -> d.path().equals(request.pathB()));
    if (!hasA) return jakarta.ws.rs.core.Response.status(400)
            .entity("{\"error\":\"path not in document set: " + escapeJson(request.pathA()) + "\"}").build();
    if (!hasB) return jakarta.ws.rs.core.Response.status(400)
            .entity("{\"error\":\"path not in document set: " + escapeJson(request.pathB()) + "\"}").build();

    session.documentSet().setComparison(request.pathA(), request.pathB());
    pushComparisonChanged(channelId, session.documentSet().currentComparison());
    return jakarta.ws.rs.core.Response.ok("{\"status\":\"ok\"}").build();
}

private UUID parseSessionId(String debateSessionId) {
    try {
        return UUID.fromString(debateSessionId);
    } catch (IllegalArgumentException e) {
        throw new NotFoundException("Invalid session id: " + debateSessionId);
    }
}
```

- [ ] **Step 2: Run all tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java
git commit -m "feat: REST endpoints + SSE push for document sets

GET /api/debate/{id}/documents — list documents + current comparison
POST /api/debate/{id}/comparison — browser-initiated comparison change
SSE: documents-changed and comparison-changed metadata events

Refs #59"
```

---

### Task 6: Browser — Comparison SSE Listener + Document Dropdown (#59 — Part 5)

**Files:**
- Modify: `index.html`
- Modify: `styles.css`

- [ ] **Step 1: Add comparison-changed SSE listener to shell**

In the shell script (after the existing `selection-changed` listener), add a `DebateEventBus` subscriber for comparison metadata:

```javascript
debateEventBus.subscribe({
  onEntries() {},
  onMeta(data) {
    if (data.type === 'comparison-changed') {
      if (data.pathA && data.pathB) {
        diffPanel.loadFile('a', data.pathA);
        diffPanel.loadFile('b', data.pathB);
      }
    }
    if (data.type === 'documents-changed') {
      updateDocBadge(data.documents || []);
    }
  }
});
```

- [ ] **Step 2: Add document list dropdown to topbar**

Add after `#diff-legend` in HTML:
```html
<span id="doc-badge" class="hidden" title="Working set documents">
  <span id="doc-count"></span>
  <div id="doc-dropdown" class="hidden"></div>
</span>
```

Add the dropdown logic:
```javascript
let currentDocuments = [];

function updateDocBadge(docs) {
  currentDocuments = docs;
  const badge = $('doc-badge');
  const count = $('doc-count');
  if (docs.length > 1) {
    badge.classList.remove('hidden');
    count.textContent = docs.length + ' docs';
  } else {
    badge.classList.add('hidden');
  }
}

$('doc-badge').addEventListener('click', (e) => {
  e.stopPropagation();
  const dd = $('doc-dropdown');
  dd.classList.toggle('hidden');
  if (!dd.classList.contains('hidden')) {
    dd.innerHTML = '';
    for (const doc of currentDocuments) {
      const row = document.createElement('div');
      row.className = 'doc-row';
      row.innerHTML = '<span class="doc-label">' + doc.label + '</span>'
        + '<span class="doc-path">' + doc.path + '</span>'
        + '<button class="doc-assign" data-slot="a">A</button>'
        + '<button class="doc-assign" data-slot="b">B</button>';
      row.querySelectorAll('.doc-assign').forEach(btn => {
        btn.addEventListener('click', async (ev) => {
          ev.stopPropagation();
          const slot = btn.dataset.slot;
          const otherSlot = slot === 'a' ? 'b' : 'a';
          const otherPath = diffPanel.getDiffSummary().paths?.[otherSlot] || '';
          const pathA = slot === 'a' ? doc.path : otherPath;
          const pathB = slot === 'b' ? doc.path : otherPath;
          diffPanel.loadFile(slot, doc.path);
          if (debateEventBus.sessionId && pathA && pathB) {
            await fetch('/api/debate/' + debateEventBus.sessionId + '/comparison', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ pathA, pathB })
            });
          }
          dd.classList.add('hidden');
        });
      });
      dd.appendChild(row);
    }
  }
});

document.addEventListener('click', () => {
  $('doc-dropdown').classList.add('hidden');
});
```

- [ ] **Step 3: Update session connect to fetch initial documents**

In `connectDebateSession()`, after connecting the event bus:
```javascript
function connectDebateSession(sessionId) {
  debateEventBus.connect(sessionId);
  debatePanel.configure({ debateSessionId: sessionId });
  reviewPanel.configure({ debateSessionId: sessionId });
  // Fetch initial document set
  fetch('/api/debate/' + sessionId + '/documents')
    .then(r => r.ok ? r.json() : null)
    .then(data => {
      if (!data) return;
      updateDocBadge(data.documents || []);
      if (data.currentComparison) {
        diffPanel.loadFile('a', data.currentComparison.pathA);
        diffPanel.loadFile('b', data.currentComparison.pathB);
      }
    })
    .catch(() => {});
}
```

- [ ] **Step 4: Add dropdown styles to styles.css**

```css
/* ── Document badge + dropdown ── */
#doc-badge {
  position: relative; cursor: pointer; font-size: 11px; color: var(--sepia);
  padding: 4px 8px; border: 1px solid var(--border); border-radius: 2px;
  background: var(--chrome);
}
#doc-badge.hidden { display: none; }
#doc-dropdown {
  position: absolute; top: calc(100% + 4px); right: 0; min-width: 320px;
  background: var(--chrome); border: 1px solid var(--border); border-radius: 4px;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15); z-index: 200; padding: 4px 0;
}
#doc-dropdown.hidden { display: none; }
.doc-row {
  display: flex; align-items: center; gap: 8px; padding: 6px 12px; font-size: 12px;
}
.doc-row:hover { background: var(--bg); }
.doc-label { font-weight: 600; color: var(--ink); min-width: 60px; }
.doc-path {
  flex: 1; color: var(--muted); font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.doc-assign {
  padding: 2px 8px; font-size: 10px; min-width: 24px;
  background: var(--bg); border: 1px solid var(--border); border-radius: 2px;
  cursor: pointer; color: var(--sepia);
}
.doc-assign:hover { background: var(--accent-tint); border-color: var(--accent); color: var(--accent); }
```

- [ ] **Step 5: Run full E2E suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: PASS — all existing E2E tests still green

- [ ] **Step 6: Commit**

```bash
git add index.html styles.css
git commit -m "feat: browser document set UI — comparison SSE listener + document dropdown

comparison-changed SSE reloads diff panel.
documents-changed SSE updates doc badge.
Dropdown with A/B assignment buttons syncs via POST /comparison.
Initial document fetch on session connect.

Closes #59"
```

---

### Task 7: Export Debate Summary MCP Tool (#65)

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`
- Modify: `server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java`

- [ ] **Step 1: Write test for export_debate_summary**

```java
@Test
void exportDebateSummary_writesFileAndReturnsPath(@TempDir Path dir) throws Exception {
    Path outputFile = dir.resolve("summary.md");
    DebateSession session = sessionFor(channelId);
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    when(projectionService.project(eq(channelId), any()))
            .thenReturn(new ProjectionResult<>(emptyState(), null));
    when(debateProjection.render(any())).thenReturn("# Review Summary\n**Updated:** now\n");

    String result = tools.exportDebateSummary(channelId.toString(), outputFile.toString());
    assertThat(result).contains("\"status\":\"exported\"");
    assertThat(result).contains("\"bytes\":");
    assertThat(java.nio.file.Files.readString(outputFile)).contains("# Review Summary");
}

@Test
void exportDebateSummary_createsParentDirectories(@TempDir Path dir) throws Exception {
    Path outputFile = dir.resolve("sub/dir/summary.md");
    DebateSession session = sessionFor(channelId);
    when(registry.find(channelId)).thenReturn(Optional.of(session));
    when(projectionService.project(eq(channelId), any()))
            .thenReturn(new ProjectionResult<>(emptyState(), null));
    when(debateProjection.render(any())).thenReturn("summary");

    String result = tools.exportDebateSummary(channelId.toString(), outputFile.toString());
    assertThat(result).contains("\"status\":\"exported\"");
    assertThat(java.nio.file.Files.exists(outputFile)).isTrue();
}

@Test
void exportDebateSummary_invalidSession_returnsError() {
    String result = tools.exportDebateSummary(UUID.randomUUID().toString(), "/tmp/out.md");
    assertThat(result).startsWith("error:");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest#exportDebateSummary*`
Expected: FAIL — `exportDebateSummary` method not found

- [ ] **Step 3: Implement export_debate_summary**

Add to `DebateMcpTools.java`:

```java
@Tool(name = "export_debate_summary",
      description = "Export the current debate summary to a markdown file on disk. Creates parent directories if needed.")
public String exportDebateSummary(
        @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId,
        @ToolArg(description = "Absolute path for the output markdown file") String outputPath) {

    DebateSession session = resolveSession(debateSessionId);
    if (session == null) return sessionError(debateSessionId);

    try {
        var result = projectionService.project(session.channelId(), debateProjection);
        String summary = debateProjection.render(result);

        SelectionScope sel = session.currentSelection();
        if (sel != null) {
            StringBuilder sb = new StringBuilder(summary);
            sb.append("\n\n## Active Selection\n");
            sb.append("**Document ").append(sel.side().name()).append("**");
            if (sel.startLine() > 0) {
                sb.append(", lines ").append(sel.startLine()).append("–").append(sel.endLine());
            }
            sb.append(":\n> ").append(sel.selectedText()).append("\n");
            summary = sb.toString();
        }

        summary = appendWorkingSet(summary, session);

        java.nio.file.Path path = java.nio.file.Path.of(outputPath);
        java.nio.file.Files.createDirectories(path.getParent());
        byte[] bytes = summary.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(path, bytes);

        return "{\"status\":\"exported\",\"path\":" + jsonString(path.toAbsolutePath().toString())
                + ",\"bytes\":" + bytes.length + "}";
    } catch (Exception e) {
        LOG.warning("export_debate_summary failed: " + e.getMessage());
        return "error: " + e.getMessage();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DebateMcpToolsTest`
Expected: PASS

- [ ] **Step 5: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: PASS — all tests green

- [ ] **Step 6: Commit**

```bash
git add server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java \
  server/runtime/src/test/java/io/casehub/drafthouse/DebateMcpToolsTest.java
git commit -m "feat: export_debate_summary MCP tool — write summary to markdown file

Same render path as get_debate_summary. Includes selection +
working set sections. Creates parent dirs. UTF-8 byte count.

Closes #65"
```

---

### Task 8: Final Verification + FEATURES.md Update

**Files:**
- Modify: `docs/FEATURES.md`

- [ ] **Step 1: Run the complete test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`
Expected: PASS — all tests green

- [ ] **Step 2: Update FEATURES.md**

Move completed items from Planned to Done:

```markdown
## Done — recent

- [x] **Keyboard shortcuts overlay** (#63) — `?` toggles overlay; Escape/backdrop dismiss; INPUT/TEXTAREA/SELECT/contenteditable guard
- [x] **Multi-document working sets** (#59) — DocumentSet on DebateSession; add/remove/list/set_comparison MCP tools; REST + SSE for browser; topbar document dropdown
- [x] **Export debate summary** (#65) — export_debate_summary MCP tool writes summary to markdown file
```

Remove from Planned sections.

- [ ] **Step 3: Commit**

```bash
git add docs/FEATURES.md
git commit -m "docs: update FEATURES.md — #63, #59, #65 complete

Refs #63
Refs #59
Refs #65"
```
