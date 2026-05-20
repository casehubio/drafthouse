# Swap Panels (A↔B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a ⇄ Swap button to the topbar that swaps the A and B panels — their file paths, rendered content, and labels all move together — driven by a refactor of the scattered state model into a single `panels` object.

**Architecture:** Replace `filePaths { a, b }`, `contents { a, b }`, and `watcherRefs` with `panels = { a: { path, content, label }, b: { path, content, label } }`. Eliminate `watcherRefs` by deriving panel-path associations from `panels` at query time via `panelsWatching(path)`. Add `syncPanelDOM(panel)` as the single point where state is rendered to DOM. Swap is then one destructure assignment plus two `syncPanelDOM` calls.

**Tech Stack:** Vanilla JS (ES2022), marked.js (markdown parsing), Playwright (E2E tests), Electron + Quarkus (app runtime)

---

## File Map

| File | Change |
|---|---|
| `index.html` | All state + feature changes — single-file app |
| `electron-tests/e2e/swap-panels.spec.js` | New — 7 Playwright E2E tests |

---

## Task 1: Write Failing Playwright Tests

**Files:**
- Create: `electron-tests/e2e/swap-panels.spec.js`

- [ ] **Step 1.1: Create the test file**

```js
// electron-tests/e2e/swap-panels.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { _electron: electron } = require('playwright');
const path = require('path');

const ELECTRON_BIN = process.env.ELECTRON_BIN ||
  '/Users/mdproctor/claude/sparge/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron';
const APP_PATH = path.join(__dirname, '..', '..');

// Launch with both files — waits for both panels to render an h1
async function launchBothFiles(fileA, fileB) {
  const app = await electron.launch({ executablePath: ELECTRON_BIN, args: [APP_PATH, fileA, fileB] });
  const window = await app.firstWindow();
  await window.waitForFunction(() => document.querySelector('#render-a h1') !== null, { timeout: 55_000 });
  await window.waitForFunction(() => document.querySelector('#render-b h1') !== null, { timeout: 55_000 });
  return { app, window };
}

// Launch with one file — waits for panel A only
async function launchOneFile(fileA) {
  const app = await electron.launch({ executablePath: ELECTRON_BIN, args: [APP_PATH, fileA] });
  const window = await app.firstWindow();
  await window.waitForFunction(() => document.querySelector('#render-a h1') !== null, { timeout: 55_000 });
  return { app, window };
}

// ── Both files loaded ────────────────────────────────────────────────────────

test.describe('swap panels — both files loaded', () => {
  let app, window, originalPathA, originalPathB;

  test.beforeAll(async () => {
    ({ app, window } = await launchBothFiles(process.env.TEST_FILE_A, process.env.TEST_FILE_B));
    originalPathA = await window.locator('#path-a').textContent();
    originalPathB = await window.locator('#path-b').textContent();
  });

  test.afterAll(async () => { if (app) await app.close(); });

  test('swap button is enabled when both panels are loaded', async () => {
    await expect(window.locator('#btn-swap')).toBeEnabled();
  });

  test('swap reverses panel paths', async () => {
    await window.locator('#btn-swap').click();
    await expect(window.locator('#path-a')).toHaveText(originalPathB);
    await expect(window.locator('#path-b')).toHaveText(originalPathA);
    await window.locator('#btn-swap').click(); // restore
  });

  test('double-swap restores original state', async () => {
    await window.locator('#btn-swap').click();
    await window.locator('#btn-swap').click();
    await expect(window.locator('#path-a')).toHaveText(originalPathA);
    await expect(window.locator('#path-b')).toHaveText(originalPathB);
  });

  test('labels follow content after swap', async () => {
    const originalLabel = await window.locator('#label-a').inputValue();
    await window.locator('#label-a').fill('My Draft');
    await window.locator('#btn-swap').click();
    await expect(window.locator('#label-b')).toHaveValue('My Draft');
    await window.locator('#btn-swap').click(); // restore
    await window.locator('#label-a').fill(originalLabel); // restore label
  });

  test('diff markers remain present on both sides after swap', async () => {
    await window.locator('#btn-swap').click();
    expect(await window.locator('#render-a .diff-del').count()).toBeGreaterThan(0);
    expect(await window.locator('#render-b .diff-ins').count()).toBeGreaterThan(0);
    await window.locator('#btn-swap').click(); // restore
  });

  test('scroll positions reset to top after swap', async () => {
    await window.evaluate(() => {
      document.getElementById('body-a').scrollTop = 200;
      document.getElementById('body-b').scrollTop = 200;
    });
    await window.locator('#btn-swap').click();
    const scrollA = await window.evaluate(() => document.getElementById('body-a').scrollTop);
    const scrollB = await window.evaluate(() => document.getElementById('body-b').scrollTop);
    expect(scrollA).toBe(0);
    expect(scrollB).toBe(0);
    await window.locator('#btn-swap').click(); // restore
  });
});

// ── One file loaded ──────────────────────────────────────────────────────────

test.describe('swap panels — single file loaded', () => {
  let app, window;

  test.beforeAll(async () => {
    ({ app, window } = await launchOneFile(process.env.TEST_FILE_A));
  });

  test.afterAll(async () => { if (app) await app.close(); });

  test('swap button is disabled when only one panel is loaded', async () => {
    await expect(window.locator('#btn-swap')).toBeDisabled();
  });
});
```

- [ ] **Step 1.2: Run the tests — confirm they all fail**

```bash
./node_modules/.bin/playwright test electron-tests/e2e/swap-panels.spec.js --reporter=list
```

Expected: all 7 tests FAIL. Typical error: `locator('#btn-swap')` — element not found, or timeout because `launchBothFiles` waits for h1 but the test runner can't find `#btn-swap`.

- [ ] **Step 1.3: Commit the failing tests**

```bash
git add electron-tests/e2e/swap-panels.spec.js
git commit -m "test(swap): add failing Playwright E2E tests for swap panels

Refs #2"
```

---

## Task 2: Refactor State Model in index.html

All edits are to `index.html`. After this task the app works exactly as before — no new features, just the internal state model corrected. Run existing tests at the end to verify no regressions.

**Files:**
- Modify: `index.html`

- [ ] **Step 2.1: Replace state declarations (lines 101–107 area)**

Find this block:
```js
const filePaths = { a: null, b: null };
const contents  = { a: null, b: null };
const watchers    = {};   // path → EventSource
const watcherRefs = {};   // path → Set of panels currently watching it
```

Replace with:
```js
const panels = {
  a: { path: null, content: null, label: 'File A' },
  b: { path: null, content: null, label: 'File B' }
};
const watchers = {};   // path → EventSource
```

- [ ] **Step 2.2: Add `panelsWatching` helper immediately after the `watchers` declaration**

```js
function panelsWatching(path) {
  return ['a', 'b'].filter(p => panels[p].path === path);
}
```

- [ ] **Step 2.3: Replace `watchFile`**

Find the existing `watchFile` function and replace it entirely:
```js
function watchFile(panel, filePath) {
  if (watchers[filePath]) return; // EventSource already open
  const es = new EventSource(apiUrl(`/api/watch?path=${encodeURIComponent(filePath)}`));
  es.onmessage = async () => {
    for (const p of panelsWatching(filePath)) {
      try {
        const content = await fetchFile(filePath);
        renderMarkdown(p, content);
      } catch (_) {}
    }
  };
  watchers[filePath] = es;
}
```

- [ ] **Step 2.4: Replace `unwatchFile`**

Find the existing `unwatchFile` function and replace it entirely:
```js
function unwatchFile(filePath) {
  if (panelsWatching(filePath).length === 0 && watchers[filePath]) {
    watchers[filePath].close();
    delete watchers[filePath];
  }
}
```

Note: the `panel` parameter is dropped — the function now derives watching panels from `panels` state.

- [ ] **Step 2.5: Add `syncPanelDOM` helper (add after `unwatchFile`)**

```js
function syncPanelDOM(panel) {
  const s = panels[panel];
  $(`label-${panel}`).value = s.label;
  $(`path-${panel}`).textContent = s.path || 'No file selected';
  $(`path-${panel}`).classList.toggle('loaded', !!s.path);
  if (s.content) {
    $(`render-${panel}`).innerHTML = marked.parse(s.content);
    $(`empty-${panel}`).classList.add('hidden');
  } else {
    $(`render-${panel}`).innerHTML = '';
    $(`empty-${panel}`).classList.remove('hidden');
  }
}
```

- [ ] **Step 2.6: Replace `loadFile`**

Find the existing `loadFile` function and replace it entirely:
```js
async function loadFile(panel, path) {
  const prev = panels[panel].path;
  panels[panel].path = path;
  panels[panel].label = path.split('/').pop();
  $(`path-${panel}`).textContent = path;
  $(`path-${panel}`).classList.add('loaded');
  if (prev && prev !== path) unwatchFile(prev);
  try {
    const content = await fetchFile(path);
    renderMarkdown(panel, content);
  } catch (err) {
    $(`render-${panel}`).innerHTML =
      `<p style="color:var(--error);padding:24px">Could not read file: ${err.message}</p>`;
  }
  watchFile(panel, path);
  updateSwapButton();
}
```

Note: `updateSwapButton` will be defined in Task 3. Add a temporary stub now immediately after `loadFile` so the app doesn't crash:
```js
function updateSwapButton() { /* filled in Task 3 */ }
```

- [ ] **Step 2.7: Replace `renderMarkdown`**

Find the existing `renderMarkdown` function and replace it entirely:
```js
function renderMarkdown(panel, content) {
  panels[panel].content = content;
  syncPanelDOM(panel);
  updateDiffMap();
}
```

- [ ] **Step 2.8: Update `updateDiffMap`**

Find this block inside `updateDiffMap`:
```js
if (!contents.a || !contents.b) return;
const { a, b, chunks } = lineDiff(contents.a, contents.b);
```

Replace with:
```js
if (!panels.a.content || !panels.b.content) return;
const { a, b, chunks } = lineDiff(panels.a.content, panels.b.content);
```

Find the `annotateRendered` calls at the bottom of `updateDiffMap`:
```js
annotateRendered('a', contents.a, chunks);
annotateRendered('b', contents.b, chunks);
```

Replace with:
```js
annotateRendered('a', panels.a.content, chunks);
annotateRendered('b', panels.b.content, chunks);
```

- [ ] **Step 2.9: Add label input listeners in `DOMContentLoaded`**

Inside the `document.addEventListener('DOMContentLoaded', ...)` block, after `setupScrollSync()`, add:
```js
['a', 'b'].forEach(p =>
  $(`label-${p}`).addEventListener('input',
    () => { panels[p].label = $(`label-${p}`).value; }));
```

- [ ] **Step 2.10: Run existing Playwright tests — verify no regressions**

```bash
./node_modules/.bin/playwright test electron-tests/e2e/happy-path.spec.js electron-tests/e2e/regression.spec.js --reporter=list
```

Expected: all previously-passing tests still pass (10 pass, 2 skip). The swap-panels tests still fail (btn-swap doesn't exist yet).

- [ ] **Step 2.11: Commit the refactor**

```bash
git add index.html
git commit -m "refactor: replace filePaths/contents/watcherRefs with panels object

Introduces panels = { a: { path, content, label }, b: { ... } } as the
single source of truth for per-panel state. Eliminates watcherRefs — panel
membership is now derived via panelsWatching(). Adds syncPanelDOM() as the
single point where state is applied to the DOM.

Refs #2"
```

---

## Task 3: Add Swap Feature

**Files:**
- Modify: `index.html`

- [ ] **Step 3.1: Replace the `updateSwapButton` stub with the real implementation**

Find the stub added in Step 2.6:
```js
function updateSwapButton() { /* filled in Task 3 */ }
```

Replace with:
```js
function updateSwapButton() {
  const enabled = !!(panels.a.path && panels.b.path);
  $('btn-swap').disabled = !enabled;
  $('btn-swap').style.opacity = enabled ? '' : '0.4';
}
```

- [ ] **Step 3.2: Add `swapPanels` function (add after `updateSwapButton`)**

```js
function swapPanels() {
  if (!panels.a.path || !panels.b.path) return;
  [panels.a, panels.b] = [panels.b, panels.a];
  syncPanelDOM('a');
  syncPanelDOM('b');
  $('body-a').scrollTop = 0;
  $('body-b').scrollTop = 0;
  updateDiffMap();
}
```

- [ ] **Step 3.3: Add the Swap button to the topbar HTML**

Find:
```html
<button id="btn-sync" class="active" onclick="toggleSync()" title="Sync scroll between panels">⟺ Sync</button>
```

Replace with:
```html
<button id="btn-sync" class="active" onclick="toggleSync()" title="Sync scroll between panels">⟺ Sync</button>
<button id="btn-swap" onclick="swapPanels()" title="Swap panels A↔B" disabled style="opacity:.4">⇄ Swap</button>
```

The button starts disabled and dim — `updateSwapButton()` enables it once both files are loaded.

- [ ] **Step 3.4: Add `.superpowers/` to `.gitignore`**

Open `.gitignore` (or create it if absent). Add:
```
.superpowers/
```

- [ ] **Step 3.5: Run the full test suite**

```bash
./node_modules/.bin/playwright test --reporter=list
```

Expected output:
- `swap panels — both files loaded` → 6 pass
- `swap panels — single file loaded` → 1 pass
- `happy path` → 8 pass
- `regression` → 2 pass, 2 skip (scroll-sync tests skip when content fits viewport — correct behaviour)

If any test fails, investigate before committing. Common issues:
- `#btn-swap` not found → check HTML step 3.3 was saved correctly
- Label test fails → verify `panels[p].label` is set in the input listener (step 2.9) and `syncPanelDOM` writes it to the DOM (step 2.5)
- Diff markers absent after swap → verify `updateDiffMap()` call in `swapPanels` and that `panels.a.content` / `panels.b.content` are swapped correctly

- [ ] **Step 3.6: Commit**

```bash
git add index.html .gitignore
git commit -m "feat(swap): add A↔B swap button — panels object drives DOM sync

Button sits beside Sync in the topbar. Disabled until both panels are loaded.
Swap is one destructure assignment on panels + two syncPanelDOM calls +
scroll reset + updateDiffMap.

Closes #2"
```

- [ ] **Step 3.7: Push the branch**

```bash
git push -u origin issue-2-swap-panels
```
