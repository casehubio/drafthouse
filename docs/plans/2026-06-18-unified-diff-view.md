# Unified Diff View Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a unified diff view mode to `<drafthouse-diff>` — toggle between side-by-side and interleaved rendering via topbar button and keyboard shortcut.

**Architecture:** The LCS diff engine (`_lineDiff`) and word-level diff (`_wordDiff`) are shared by both modes. A `_viewMode` flag dispatches `_updateDiffMap` to either the existing side-by-side pipeline or a new `_renderUnified` pipeline. The DOM structure doesn't change — panel B and the divider are shown/hidden via CSS. Panel content is restored via `_syncPanelContent` when switching back to split mode.

**Tech Stack:** JavaScript (Web Components, Shadow DOM), Playwright E2E tests (Java)

**Spec:** `docs/superpowers/specs/2026-06-18-unified-diff-view-design.md`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `panels/drafthouse-diff.js` | `_viewMode` state, `setViewMode()`, `viewMode` getter, `_renderUnified()`, CSS additions, `_updateDiffMap` refactor, selection side detection |
| `index.html` | View mode toggle button, `u` keyboard shortcut, shortcuts overlay row |
| `server/runtime/src/test/java/io/casehub/drafthouse/e2e/UnifiedDiffE2ETest.java` | E2E tests for unified mode |

---

### Task 1: CSS for unified diff blocks

**Files:**
- Modify: `panels/drafthouse-diff.js` (stylesheet at top of file)

- [ ] **Step 1: Add unified diff CSS to the adopted stylesheet**

In `panels/drafthouse-diff.js`, add these styles to the `sheet.replaceSync()` call, before the closing backtick+`)` at line 201:

```css

  /* ── Unified diff blocks ── */
  .diff-unified-del {
    border-left: 3px solid #ef4444;
    padding: 4px 12px 4px 28px;
    margin: 4px 0;
    background: rgba(239, 68, 68, 0.06);
    position: relative;
  }
  .diff-unified-ins {
    border-left: 3px solid #22c55e;
    padding: 4px 12px 4px 28px;
    margin: 4px 0;
    background: rgba(34, 197, 94, 0.06);
    position: relative;
  }
  .diff-unified-del::before {
    content: '−';
    position: absolute;
    left: 10px;
    top: 4px;
    color: #ef4444;
    font-weight: 700;
    font-size: 11px;
  }
  .diff-unified-ins::before {
    content: '+';
    position: absolute;
    left: 10px;
    top: 4px;
    color: #22c55e;
    font-weight: 700;
    font-size: 11px;
  }
```

- [ ] **Step 2: Commit**

```
git add panels/drafthouse-diff.js
git commit -m "feat: CSS for unified diff del/ins blocks

Refs #66"
```

---

### Task 2: View mode state + setViewMode + _renderUnified

**Files:**
- Modify: `panels/drafthouse-diff.js`

This is the core task. It adds the mode state, the rendering pipeline, and the mode-switch logic.

- [ ] **Step 1: Add `_viewMode` to internal state**

At line 210 (after `_syncEnabled = true;`), add:

```js
  _viewMode = 'split';
```

- [ ] **Step 2: Add `viewMode` getter and `setViewMode` method**

After the `currentPath(slot)` method (around line 493), add:

```js
  get viewMode() { return this._viewMode; }

  setViewMode(mode) {
    if (mode !== 'split' && mode !== 'unified') return;
    if (mode === this._viewMode) return;
    this._viewMode = mode;

    const panelB = this._$('panel-b');
    const divider = this._$('divider');

    if (mode === 'unified') {
      panelB.style.display = 'none';
      divider.style.display = 'none';
    } else {
      panelB.style.display = '';
      divider.style.display = '';
      this._syncPanelContent('a');
      this._syncPanelContent('b');
    }
    this._updateDiffMap();
  }
```

- [ ] **Step 3: Add `_renderUnified` method**

After `_renderMarkdown` (around line 575), add:

```js
  _renderUnified(aLines, bLines, chunks) {
    const renderA = this._$('render-a');
    const renderB = this._$('render-b');

    // Clear render-b to prevent stale data-diff-chunk attributes from
    // poisoning _chunkOutOfView, nextDiff/prevDiff, and scrollToLocation
    renderB.innerHTML = '';
    this._$('empty-b').classList.add('hidden');

    // Hide empty-a since we are rendering content
    this._$('empty-a').classList.add('hidden');

    let html = '';
    chunks.forEach((c, ci) => {
      if (c.op === 'eq') {
        // B-side lines — content identical; convention: show newer version
        const text = bLines.slice(c.bStart, c.bEnd).join('\n');
        html += marked.parse(text);
      } else if (c.op === 'del') {
        const text = aLines.slice(c.aStart, c.aEnd).join('\n');
        html += `<div class="diff-unified-del" data-diff-chunk="${ci}">${marked.parse(text)}</div>`;
      } else if (c.op === 'ins') {
        const text = bLines.slice(c.bStart, c.bEnd).join('\n');
        html += `<div class="diff-unified-ins" data-diff-chunk="${ci}">${marked.parse(text)}</div>`;
      } else if (c.op === 'mod') {
        const delText = aLines.slice(c.aStart, c.aEnd).join('\n');
        const insText = bLines.slice(c.bStart, c.bEnd).join('\n');
        html += `<div class="diff-unified-del" data-diff-chunk="${ci}">${marked.parse(delText)}</div>`;
        html += `<div class="diff-unified-ins" data-diff-chunk="${ci}">${marked.parse(insText)}</div>`;
      }
    });
    renderA.innerHTML = html;

    // Word-level highlights inside mod blocks
    chunks.forEach((c, ci) => {
      if (c.op !== 'mod') return;
      const els = renderA.querySelectorAll(`[data-diff-chunk="${ci}"]`);
      if (els.length < 2) return;
      const elDel = els[0]; // diff-unified-del
      const elIns = els[1]; // diff-unified-ins
      if (elDel.tagName === 'PRE' || elIns.tagName === 'PRE') return;
      const { rangesA, rangesB } = this._wordDiff(elDel.textContent, elIns.textContent);
      this._applyWordHighlights(elDel, rangesA, 'diff-word-a');
      this._applyWordHighlights(elIns, rangesB, 'diff-word-b');
    });
  }
```

- [ ] **Step 4: Refactor `_updateDiffMap` to dispatch by mode**

Replace the entire `_updateDiffMap()` method (lines 769-791) with:

```js
  _updateDiffMap() {
    if (!this._panels.a.content || !this._panels.b.content) {
      this._lastChunks = [];
      this._scrollAnchors = [];
      return;
    }
    const { a, b, chunks } = this._lineDiff(this._panels.a.content, this._panels.b.content);
    this._lastChunks = chunks;
    this._lastTotalA = a.length;
    this._lastTotalB = b.length;

    if (this._viewMode === 'unified') {
      this._renderUnified(a, b, chunks);
    } else {
      this._drawDiffMap(a.length, b.length, chunks);
      this._annotateRendered('a', this._panels.a.content, chunks);
      this._annotateRendered('b', this._panels.b.content, chunks);
      this._annotateWordDiffs(chunks);
      this._buildScrollAnchors();
    }

    this._currentChunkIdx = -1;
    this.dispatchEvent(new CustomEvent('diff-updated', {
      bubbles: true,
      detail: { chunks, totalA: a.length, totalB: b.length },
    }));
  }
```

- [ ] **Step 5: Update selection mouseup handler for unified mode side detection**

In the `connectedCallback` method, find the mouseup handler (around line 316). Replace the event dispatch line:

```js
        this.dispatchEvent(new CustomEvent('selection-changed', {
          bubbles: true,
          detail: { side: side.toUpperCase(), startLine, endLine, selectedText: sel.toString() },
        }));
```

with:

```js
        let reportedSide = side.toUpperCase();
        if (this._viewMode === 'unified') {
          let node = range.startContainer;
          while (node && node !== render) {
            if (node.classList) {
              if (node.classList.contains('diff-unified-del')) { reportedSide = 'A'; break; }
              if (node.classList.contains('diff-unified-ins')) { reportedSide = 'B'; break; }
            }
            node = node.parentNode;
          }
        }
        this.dispatchEvent(new CustomEvent('selection-changed', {
          bubbles: true,
          detail: { side: reportedSide, startLine, endLine, selectedText: sel.toString() },
        }));
```

- [ ] **Step 6: Run existing E2E tests to verify no regression**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest="io.casehub.drafthouse.e2e.HappyPathE2ETest"`

Expected: ALL PASS (existing split-mode tests unaffected).

- [ ] **Step 7: Commit**

```
git add panels/drafthouse-diff.js
git commit -m "feat: unified diff rendering pipeline and view mode toggle

- _viewMode state with setViewMode() / viewMode getter
- _renderUnified() builds interleaved del/ins/eq document in panel A
- _updateDiffMap() dispatches to correct pipeline, shared diff-updated event
- Selection side detection from CSS class ancestors in unified mode
- Panel content restored via _syncPanelContent on unified→split switch

Refs #66"
```

---

### Task 3: Topbar button, keyboard shortcut, shortcuts overlay

**Files:**
- Modify: `index.html`

- [ ] **Step 1: Add view mode toggle button to topbar**

In `index.html`, after the `btn-swap` button (line 39), add:

```html
  <button id="btn-view-mode" title="Toggle split/unified view (u)">⫏ Split</button>
```

- [ ] **Step 2: Add click handler for the toggle button**

In the script section, after the `btn-next` click handler (line 145), add:

```js
$('btn-view-mode').addEventListener('click', () => {
  const mode = diffPanel.viewMode === 'split' ? 'unified' : 'split';
  diffPanel.setViewMode(mode);
  $('btn-view-mode').textContent = mode === 'split' ? '⫏ Split' : '☰ Unified';
});
```

- [ ] **Step 3: Add `u` keyboard shortcut**

In the keyboard handler (after line 401 `if (e.key === 'p')`), add:

```js
  if (e.key === 'u') { $('btn-view-mode').click(); }
```

- [ ] **Step 4: Add shortcut to the overlay table**

In the shortcuts overlay table (after line 432 `Previous diff`), add:

```html
    <tr><td><kbd>u</kbd></td><td>Toggle split/unified view</td></tr>
```

- [ ] **Step 5: Manual verification**

Build and run:
```
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests
java -Dui.dir=/Users/mdproctor/claude/casehub/drafthouse -jar server/runtime/target/drafthouse-server-runner.jar
```

Open `http://localhost:9001/?a=sample-a.md&b=sample-b.md` in a browser. Verify:
1. The `⫏ Split` button appears in the topbar
2. Clicking it switches to unified mode — panel B and divider disappear, panel A shows interleaved diff
3. Button label changes to `☰ Unified`
4. Clicking again restores split view with both panels and correct diff annotations
5. Press `u` — toggles mode
6. Press `?` — shortcuts overlay shows the `u` shortcut
7. Word-level highlights visible in mod blocks (changed words highlighted red/green)
8. Next/Prev diff navigation works in unified mode

- [ ] **Step 6: Commit**

```
git add index.html
git commit -m "feat: topbar view mode toggle + u keyboard shortcut

Button between Swap and Nav buttons. Label updates to reflect current mode.
Shortcut u registered in existing handler with Shadow DOM input guard.
Shortcuts overlay updated.

Refs #66"
```

---

### Task 4: E2E tests for unified diff mode

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/e2e/UnifiedDiffE2ETest.java`

- [ ] **Step 1: Create UnifiedDiffE2ETest**

```java
package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;

@QuarkusTest
@WithPlaywright
class UnifiedDiffE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    private Page page;

    @BeforeEach
    void openPage() {
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        if (page != null) page.close();
    }

    @Test
    void toggleButton_rendersInTopbar() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#btn-view-mode")).isVisible();
        assertThat(page.locator("#btn-view-mode")).hasText("⫏ Split");
    }

    @Test
    void clickToggle_switchesToUnifiedMode() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.locator("#btn-view-mode").click();

        // Panel B and divider hidden
        assertThat(page.locator("drafthouse-diff").locator("#panel-b")).not().isVisible();
        assertThat(page.locator("drafthouse-diff").locator("#divider")).not().isVisible();

        // Unified diff blocks visible in panel A
        assertThat(page.locator("drafthouse-diff").locator(".diff-unified-del").first()).isVisible();
        assertThat(page.locator("drafthouse-diff").locator(".diff-unified-ins").first()).isVisible();

        // Button label updated
        assertThat(page.locator("#btn-view-mode")).hasText("☰ Unified");
    }

    @Test
    void clickToggleTwice_restoresSplitMode() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));

        // Switch to unified
        page.locator("#btn-view-mode").click();
        assertThat(page.locator("drafthouse-diff").locator("#panel-b")).not().isVisible();

        // Switch back to split
        page.locator("#btn-view-mode").click();
        assertThat(page.locator("drafthouse-diff").locator("#panel-b")).isVisible();
        assertThat(page.locator("drafthouse-diff").locator("#divider")).isVisible();

        // Split-mode diff annotations restored
        assertThat(page.locator("drafthouse-diff").locator("#render-a [data-diff-chunk]").first()).isVisible();
        assertThat(page.locator("drafthouse-diff").locator("#render-b [data-diff-chunk]").first()).isVisible();

        // Button label restored
        assertThat(page.locator("#btn-view-mode")).hasText("⫏ Split");
    }

    @Test
    void wordHighlights_visibleInUnifiedMode() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.locator("#btn-view-mode").click();

        // Word-level marks inside unified containers
        Locator delMarks = page.locator("drafthouse-diff").locator(".diff-unified-del mark.diff-word-a");
        Locator insMarks = page.locator("drafthouse-diff").locator(".diff-unified-ins mark.diff-word-b");
        assertThat(delMarks.first()).isVisible();
        assertThat(insMarks.first()).isVisible();
    }

    @Test
    void diffNavigation_worksInUnifiedMode() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.locator("#btn-view-mode").click();

        // Next diff button enabled
        assertThat(page.locator("#btn-next")).isEnabled();

        // Click next — counter updates
        page.locator("#btn-next").click();
        String counter = page.locator("#diff-counter").textContent();
        org.junit.jupiter.api.Assertions.assertTrue(
                counter.matches("\\d+ / \\d+"),
                "Counter should show position: " + counter);
    }

    @Test
    void modeRoundTrip_splitUnifiedSplit_annotationsCorrect() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));

        // Start in split — verify annotations
        assertThat(page.locator("drafthouse-diff").locator("#render-a [data-diff-chunk]").first()).isVisible();
        assertThat(page.locator("drafthouse-diff").locator("#render-b [data-diff-chunk]").first()).isVisible();

        // Switch to unified
        page.locator("#btn-view-mode").click();
        assertThat(page.locator("drafthouse-diff").locator(".diff-unified-del").first()).isVisible();

        // Switch back to split
        page.locator("#btn-view-mode").click();

        // Verify split annotations are correct (not stale unified DOM)
        assertThat(page.locator("drafthouse-diff").locator("#render-a .diff-del").first()).isVisible();
        assertThat(page.locator("drafthouse-diff").locator("#render-b .diff-ins").first()).isVisible();

        // No unified blocks should remain
        assertThat(page.locator("drafthouse-diff").locator(".diff-unified-del")).hasCount(0);
        assertThat(page.locator("drafthouse-diff").locator(".diff-unified-ins")).hasCount(0);
    }

    @Test
    void keyboardShortcut_u_togglesMode() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.keyboard().press("u");

        // Should be in unified mode
        assertThat(page.locator("drafthouse-diff").locator("#panel-b")).not().isVisible();
        assertThat(page.locator("#btn-view-mode")).hasText("☰ Unified");
    }

    @Test
    void swapPanels_worksInUnifiedMode() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        page.locator("#btn-view-mode").click();

        // Swap should not error — unified view re-renders
        page.locator("#btn-swap").click();

        // Unified diff blocks still visible after swap
        assertThat(page.locator("drafthouse-diff").locator(".diff-unified-del").first()).isVisible();
        assertThat(page.locator("drafthouse-diff").locator(".diff-unified-ins").first()).isVisible();
    }
}
```

- [ ] **Step 2: Run E2E tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=UnifiedDiffE2ETest`

Expected: ALL PASS (8 tests).

- [ ] **Step 3: Run full test suite for regression**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`

Expected: ALL PASS (no regressions in existing tests).

- [ ] **Step 4: Commit**

```
git add server/runtime/src/test/java/io/casehub/drafthouse/e2e/UnifiedDiffE2ETest.java
git commit -m "test: E2E tests for unified diff view mode

8 tests: toggle button, unified/split switch, word highlights,
diff navigation, mode round-trip, keyboard shortcut, swapPanels.

Refs #66"
```
