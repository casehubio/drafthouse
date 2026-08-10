# Next/Prev Diff Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `n`/`p` keyboard and ↑/↓ topbar button navigation between diff blocks, with a `N / M` position counter, plus fix the minimap click to scroll both panels.

**Architecture:** All changes are in `index.html` (single-file vanilla JS app). New state variable `currentChunkIdx` tracks the active chunk. Navigation uses a hybrid strategy: sequential from the last active chunk when still visible, viewport-based recalibration when the user has scrolled away. A new `scrollToChunk(ci)` helper replaces the existing single-panel minimap scroll loop.

**Tech Stack:** Vanilla JS (ES2022), Playwright (E2E tests), Electron + Quarkus (app runtime)

---

## File Map

| File | Change |
|---|---|
| `index.html` | All state + feature changes |
| `electron-tests/e2e/nav.spec.js` | New — 7 Playwright E2E tests |

---

## Task 1: Write Failing Playwright Tests

**Files:**
- Create: `electron-tests/e2e/nav.spec.js`

- [ ] **Step 1.1: Create the test file**

```js
// electron-tests/e2e/nav.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { launchApp }    = require('./helpers');

test.describe('diff navigation', () => {
  let app, window, originalContentB;

  test.beforeAll(async () => {
    ({ app, window } = await launchApp(process.env.TEST_FILE_A, process.env.TEST_FILE_B));
    originalContentB = await window.evaluate(() => panels.b.content);
  });

  test.afterAll(async () => { if (app) await app.close(); });

  test('nav buttons are enabled when both panels have diffs', async () => {
    await expect(window.locator('#btn-next')).toBeEnabled();
    await expect(window.locator('#btn-prev')).toBeEnabled();
  });

  test('counter shows dash before any navigation', async () => {
    await expect(window.locator('#diff-counter')).toHaveText('— / —');
  });

  test('clicking next updates counter to 1/M', async () => {
    await window.locator('#btn-next').click();
    await window.waitForFunction(
      () => document.getElementById('diff-counter').textContent !== '— / —',
      undefined, { timeout: 3000 }
    );
    const text = await window.locator('#diff-counter').textContent();
    expect(text).toMatch(/^1 \/ \d+$/);
  });

  test('n key advances counter', async () => {
    const before = await window.locator('#diff-counter').textContent();
    await window.keyboard.press('n');
    await window.waitForFunction(
      before => document.getElementById('diff-counter').textContent !== before,
      before, { timeout: 3000 }
    );
    const after = await window.locator('#diff-counter').textContent();
    expect(after).not.toBe(before);
    expect(after).toMatch(/^\d+ \/ \d+$/);
  });

  test('p key decrements counter', async () => {
    // Ensure we are at chunk >= 2 so p has somewhere to go
    await window.locator('#btn-next').click();
    await window.waitForFunction(
      () => /^[2-9]/.test(document.getElementById('diff-counter').textContent),
      undefined, { timeout: 3000 }
    );
    const before = await window.locator('#diff-counter').textContent();
    await window.keyboard.press('p');
    await window.waitForFunction(
      before => document.getElementById('diff-counter').textContent !== before,
      before, { timeout: 3000 }
    );
    const after = await window.locator('#diff-counter').textContent();
    expect(after).not.toBe(before);
    expect(after).toMatch(/^\d+ \/ \d+$/);
  });

  test('nav buttons disabled and counter dashes when files are identical', async () => {
    await window.evaluate(() => { panels.b.content = panels.a.content; updateDiffMap(); });
    await expect(window.locator('#btn-next')).toBeDisabled();
    await expect(window.locator('#btn-prev')).toBeDisabled();
    await expect(window.locator('#diff-counter')).toHaveText('— / —');
    // Restore
    await window.evaluate(c => { panels.b.content = c; updateDiffMap(); }, originalContentB);
    await expect(window.locator('#btn-next')).toBeEnabled();
  });

  test('minimap click scrolls at least one panel to a diff', async () => {
    await window.evaluate(() => {
      document.getElementById('body-a').scrollTop = 0;
      document.getElementById('body-b').scrollTop = 0;
    });
    const coords = await window.evaluate(() => {
      const canvas = document.getElementById('diff-map');
      const firstDiff = lastChunks.find(c => c.op !== 'eq');
      if (!firstDiff) return null;
      const rect = canvas.getBoundingClientRect();
      return {
        pageX: rect.left + canvas.width / 4,
        pageY: rect.top + (firstDiff.aStart / lastTotalA) * canvas.height + 1
      };
    });
    if (!coords) return;
    await window.mouse.click(coords.pageX, coords.pageY);
    await window.waitForFunction(
      () => document.getElementById('body-a').scrollTop > 0 ||
            document.getElementById('body-b').scrollTop > 0,
      undefined, { timeout: 2000 }
    ).catch(() => {});
    const scrollA = await window.evaluate(() => document.getElementById('body-a').scrollTop);
    const scrollB = await window.evaluate(() => document.getElementById('body-b').scrollTop);
    expect(Math.max(scrollA, scrollB)).toBeGreaterThan(0);
  });
});
```

- [ ] **Step 1.2: Run to confirm all 7 tests fail**

```bash
cd /Users/mdproctor/claude/md-compare && ./node_modules/.bin/playwright test electron-tests/e2e/nav.spec.js --reporter=list
```

Expected: all 7 fail. `#btn-next` not found, or counter doesn't exist yet.

- [ ] **Step 1.3: Commit the failing tests**

```bash
git -C /Users/mdproctor/claude/md-compare add electron-tests/e2e/nav.spec.js
git -C /Users/mdproctor/claude/md-compare commit -m "test(nav): add failing Playwright E2E tests for next/prev navigation

Refs #7"
```

---

## Task 2: Implement Navigation Feature in index.html

All edits are to `/Users/mdproctor/claude/md-compare/index.html`. After this task the full test suite passes.

**Files:**
- Modify: `index.html`

- [ ] **Step 2.1: Add `currentChunkIdx` to the state block (around line 114)**

Find:
```js
let lastChunks = [], lastTotalA = 0, lastTotalB = 0;
```

Replace with:
```js
let lastChunks = [], lastTotalA = 0, lastTotalB = 0;
let currentChunkIdx = -1;   // index into lastChunks; -1 = no active chunk
```

- [ ] **Step 2.2: Add navigation helpers after `updateNavButtons` (add as a block after `swapPanels`, before `renderMarkdown`)**

The existing code at line 203 starts with `function swapPanels()`. After the closing `}` of `swapPanels` and before `function renderMarkdown`, insert this entire block:

```js
// ── Diff navigation ──────────────────────────────────────────────────
function nonEqIndices() {
  return lastChunks.reduce((acc, c, i) => { if (c.op !== 'eq') acc.push(i); return acc; }, []);
}

function chunkOutOfView(ci) {
  for (const p of ['a', 'b']) {
    const el = $(`render-${p}`).querySelector(`[data-diff-chunk="${ci}"]`);
    if (!el) continue;
    const br = $(`body-${p}`).getBoundingClientRect();
    const er = el.getBoundingClientRect();
    return er.bottom < br.top || er.top > br.bottom;
  }
  return true;
}

function scrollToChunk(ci) {
  for (const p of ['a', 'b']) {
    const el = $(`render-${p}`).querySelector(`[data-diff-chunk="${ci}"]`);
    if (!el) continue;
    const body = $(`body-${p}`);
    body.scrollBy({ top: el.getBoundingClientRect().top - body.getBoundingClientRect().top - 24, behavior: 'smooth' });
  }
}

function updateNavCounter(idx) {
  const pos = idx.indexOf(currentChunkIdx);
  $('diff-counter').textContent = pos >= 0 ? `${pos + 1} / ${idx.length}` : '— / —';
}

function updateNavButtons() {
  const has = nonEqIndices().length > 0;
  $('btn-prev').disabled = !has;
  $('btn-next').disabled = !has;
  $('btn-prev').style.opacity = has ? '' : '0.4';
  $('btn-next').style.opacity = has ? '' : '0.4';
  if (!has) $('diff-counter').textContent = '— / —';
}

function nextDiff() {
  const idx = nonEqIndices();
  if (!idx.length) return;
  if (currentChunkIdx === -1 || chunkOutOfView(currentChunkIdx)) {
    const bodyA = $('body-a');
    const centre = bodyA.getBoundingClientRect().top + bodyA.clientHeight / 2;
    const found = idx.find(ci => {
      const el = $('render-a').querySelector(`[data-diff-chunk="${ci}"]`) ||
                 $('render-b').querySelector(`[data-diff-chunk="${ci}"]`);
      return el && el.getBoundingClientRect().top >= centre;
    });
    currentChunkIdx = found ?? idx[0];
  } else {
    const pos = idx.indexOf(currentChunkIdx);
    currentChunkIdx = idx[(pos + 1) % idx.length];
  }
  scrollToChunk(currentChunkIdx);
  updateNavCounter(idx);
}

function prevDiff() {
  const idx = nonEqIndices();
  if (!idx.length) return;
  if (currentChunkIdx === -1 || chunkOutOfView(currentChunkIdx)) {
    const bodyA = $('body-a');
    const centre = bodyA.getBoundingClientRect().top + bodyA.clientHeight / 2;
    const found = [...idx].reverse().find(ci => {
      const el = $('render-a').querySelector(`[data-diff-chunk="${ci}"]`) ||
                 $('render-b').querySelector(`[data-diff-chunk="${ci}"]`);
      return el && el.getBoundingClientRect().bottom <= centre;
    });
    currentChunkIdx = found ?? idx[idx.length - 1];
  } else {
    const pos = idx.indexOf(currentChunkIdx);
    currentChunkIdx = idx[(pos - 1 + idx.length) % idx.length];
  }
  scrollToChunk(currentChunkIdx);
  updateNavCounter(idx);
}
```

- [ ] **Step 2.3: Update `updateDiffMap()` to reset index and update buttons**

Find the end of `updateDiffMap()`:
```js
function updateDiffMap() {
  if (!panels.a.content || !panels.b.content) return;
  const { a, b, chunks } = lineDiff(panels.a.content, panels.b.content);
  lastChunks = chunks; lastTotalA = a.length; lastTotalB = b.length;
  drawDiffMap(a.length, b.length, chunks);
  annotateRendered('a', panels.a.content, chunks);
  annotateRendered('b', panels.b.content, chunks);
}
```

Replace with:
```js
function updateDiffMap() {
  if (!panels.a.content || !panels.b.content) return;
  const { a, b, chunks } = lineDiff(panels.a.content, panels.b.content);
  lastChunks = chunks; lastTotalA = a.length; lastTotalB = b.length;
  drawDiffMap(a.length, b.length, chunks);
  annotateRendered('a', panels.a.content, chunks);
  annotateRendered('b', panels.b.content, chunks);
  currentChunkIdx = -1;
  updateNavButtons();
}
```

- [ ] **Step 2.4: Update `swapPanels()` to reset navigation index**

Find:
```js
function swapPanels() {
  if (!panels.a.path || !panels.b.path) return;
  [panels.a, panels.b] = [panels.b, panels.a];
```

Replace with:
```js
function swapPanels() {
  if (!panels.a.path || !panels.b.path) return;
  currentChunkIdx = -1;
  [panels.a, panels.b] = [panels.b, panels.a];
```

- [ ] **Step 2.5: Fix minimap click handler to scroll both panels**

Find the scroll loop inside the `diff-map` click handler (lines ~428–436):
```js
    for (const p of ['a', 'b']) {
      const el = $(`render-${p}`).querySelector(`[data-diff-chunk="${ci}"]`);
      if (el) {
        const body = $(`body-${p}`);
        const relTop = el.getBoundingClientRect().top - body.getBoundingClientRect().top;
        body.scrollBy({ top: relTop - 24, behavior: 'smooth' });
        break;
      }
    }
```

Replace with:
```js
    scrollToChunk(ci);
```

- [ ] **Step 2.6: Add nav buttons and counter to the topbar HTML**

Find:
```html
  <button id="btn-swap" onclick="swapPanels()" title="Swap panels A↔B" disabled style="opacity:.4">⇄ Swap</button>
  <div id="topbar-spacer"></div>
```

Replace with:
```html
  <button id="btn-swap" onclick="swapPanels()" title="Swap panels A↔B" disabled style="opacity:.4">⇄ Swap</button>
  <button id="btn-prev" onclick="prevDiff()" title="Previous diff (p)" disabled style="opacity:.4">↑</button>
  <button id="btn-next" onclick="nextDiff()" title="Next diff (n)" disabled style="opacity:.4">↓</button>
  <span id="diff-counter" style="font-size:11px;color:#a09080;padding:0 4px;min-width:36px;display:inline-block;text-align:center">— / —</span>
  <div id="topbar-spacer"></div>
```

- [ ] **Step 2.7: Add `n`/`p` keyboard shortcuts**

Find the existing keydown handler:
```js
document.addEventListener('keydown', e => {
  if (e.target.tagName === 'INPUT') return;
  if (e.key === 's' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); toggleSync(); }
});
```

Replace with:
```js
document.addEventListener('keydown', e => {
  if (e.target.tagName === 'INPUT') return;
  if (e.key === 's' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); toggleSync(); }
  if (e.key === 'n') nextDiff();
  if (e.key === 'p') prevDiff();
});
```

- [ ] **Step 2.8: Run the full test suite — verify all tests pass**

```bash
cd /Users/mdproctor/claude/md-compare && ./node_modules/.bin/playwright test --reporter=list
```

Expected:
- `diff navigation` → 7 pass
- `happy path` → 8 pass
- `regression` → 2 pass, 2 skip
- `swap panels` → 7 pass

Total: 24 pass, 2 skip.

If any nav test fails, common causes:
- `#btn-next` not found → check step 2.6 HTML was saved
- Counter stays `— / —` after click → check `updateNavCounter` is called in `nextDiff` and `updateNavButtons` is called in `updateDiffMap`
- `p` key test fails at "chunk >= 2" wait → the fixture files may have only 1 diff chunk; check by evaluating `nonEqIndices().length` in the browser console. If 1 diff, the test logic needs adjustment (n wraps back to 1, so counter stays `1/1`)
- Minimap test scroll stays 0 → check `scrollToChunk` replaced the old loop (step 2.5)

- [ ] **Step 2.9: Commit**

```bash
git -C /Users/mdproctor/claude/md-compare add index.html
git -C /Users/mdproctor/claude/md-compare commit -m "feat(nav): add next/prev diff navigation with position counter

n/p keyboard shortcuts and ↑/↓ topbar buttons navigate between diff
blocks. Counter shows 'N / M' position. Hybrid viewport-recalibration
strategy: sequential when current diff is visible, nearest-to-viewport
when user has scrolled away. Also fixes minimap click to scroll both
panels via shared scrollToChunk().

Closes #7"
```

- [ ] **Step 2.10: Push the branch**

```bash
git -C /Users/mdproctor/claude/md-compare push -u origin issue-7-next-prev-nav
```
