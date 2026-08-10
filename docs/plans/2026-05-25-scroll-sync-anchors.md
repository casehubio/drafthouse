# Scroll Sync Anchors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace pure-percentage scroll sync with piecewise-linear interpolation using matched heading pairs as control points, falling back to percentage when no headings match.

**Architecture:** Add `normHead`, `buildScrollAnchors`, `interp`, and `getScrollAnchors` as module-level functions in `index.html`. Call `buildScrollAnchors()` at the end of `updateDiffMap()` so anchors rebuild on every content change, resize, and swap. Update `setupScrollSync` and `toggleSync` to use `interp` instead of `applyPercent(scrollPercent())`.

**Tech Stack:** Vanilla JS in `index.html`, Playwright E2E tests (no build step — `index.html` is served directly from disk).

---

## File Structure

| File | Change |
|---|---|
| `index.html` | Add state var, 4 functions, update 3 existing functions |
| `electron-tests/e2e/scroll-anchors.spec.js` | New spec: 5 tests |

---

### Task 1: State, normHead, getScrollAnchors

**Files:**
- Modify: `index.html` — state block, new functions
- Create: `electron-tests/e2e/scroll-anchors.spec.js`

- [ ] **Step 1: Create the spec file with the normHead test**

```js
// electron-tests/e2e/scroll-anchors.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { launchApp }    = require('./helpers');

test.describe('scroll anchors', () => {
  let app, window, jsErrors;

  test.beforeAll(async () => {
    ({ app, window, jsErrors } = await launchApp(
      process.env.TEST_FILE_A,
      process.env.TEST_FILE_B
    ));
  });

  test.afterAll(async () => {
    if (jsErrors) expect(jsErrors).toHaveLength(0);
    if (app) await app.close();
  });

  // ── normHead ─────────────────────────────────────────────────────────

  test('normHead normalises punctuation, case, and caps at 6 words', async () => {
    const r1 = await window.evaluate(() => normHead('Hello, World!'));
    expect(r1).toBe('hello world');

    const r2 = await window.evaluate(
      () => normHead('One Two Three Four Five Six Seven Eight')
    );
    expect(r2).toBe('one two three four five six');

    const r3 = await window.evaluate(() => normHead('  Leading   Spaces  '));
    expect(r3).toBe('leading spaces');
  });
});
```

- [ ] **Step 2: Run to confirm FAIL**

```bash
./node_modules/.bin/playwright test --reporter=list electron-tests/e2e/scroll-anchors.spec.js
```

Expected: FAIL — `ReferenceError: normHead is not defined`

- [ ] **Step 3: Add state var, normHead, and getScrollAnchors to index.html**

In `index.html`, find the existing state block (line ~117):
```js
let API_PORT    = null;
let syncEnabled = true, syncing = false, dragging = false;
let lastChunks = [], lastTotalA = 0, lastTotalB = 0;
let currentChunkIdx = -1;
```

Add `scrollAnchors` after `currentChunkIdx`:
```js
let API_PORT    = null;
let syncEnabled = true, syncing = false, dragging = false;
let lastChunks = [], lastTotalA = 0, lastTotalB = 0;
let currentChunkIdx = -1;
let scrollAnchors = [];  // [{a: scrollTopPx, b: scrollTopPx}], sorted by .a
```

Then add these two functions immediately before `setupScrollSync` (around line 325 in the original):
```js
// ── Scroll anchors ───────────────────────────────────────────────────
function normHead(t) {
  return t.toLowerCase().replace(/[^\w\s]/g, '').trim().split(/\s+/).slice(0, 6).join(' ');
}

function getScrollAnchors() { return scrollAnchors; }
```

- [ ] **Step 4: Run to confirm PASS**

```bash
./node_modules/.bin/playwright test --reporter=list electron-tests/e2e/scroll-anchors.spec.js
```

Expected: PASS (1 test, 0 failures)

- [ ] **Step 5: Commit**

```bash
git add index.html electron-tests/e2e/scroll-anchors.spec.js
git commit -m "test(scroll-anchors): normHead test + state + getScrollAnchors stub

Refs #3"
```

---

### Task 2: buildScrollAnchors — boundary anchors

**Files:**
- Modify: `index.html` — add `buildScrollAnchors`, update `updateDiffMap`
- Modify: `electron-tests/e2e/scroll-anchors.spec.js` — add test

- [ ] **Step 1: Add the boundary-anchors test to the spec**

Add inside the `test.describe` block, after the normHead test:

```js
  // ── buildScrollAnchors ───────────────────────────────────────────────

  test('buildScrollAnchors with no shared headings returns boundary-only anchors', async () => {
    const filler = Array.from({ length: 100 },
      (_, i) => `Paragraph ${i + 1} of filler content for scroll testing.`
    ).join('\n\n');
    const contentA = `# Title A\n\n${filler}`;
    const contentB = `# Title B\n\n${filler}`;

    await window.evaluate(([a, b]) => {
      renderMarkdown('a', a);
      renderMarkdown('b', b);
    }, [contentA, contentB]);

    const anchors = await window.evaluate(() => getScrollAnchors());
    expect(anchors.length).toBe(2);
    expect(anchors[0]).toEqual({ a: 0, b: 0 });
    expect(anchors[1].a).toBeGreaterThan(0);
    expect(anchors[1].b).toBeGreaterThan(0);
  });
```

- [ ] **Step 2: Run to confirm FAIL**

```bash
./node_modules/.bin/playwright test --reporter=list electron-tests/e2e/scroll-anchors.spec.js
```

Expected: FAIL — `expect(anchors.length).toBe(2)` fails: received `0` (scrollAnchors never populated)

- [ ] **Step 3: Add buildScrollAnchors (boundary logic only) to index.html**

Add after `getScrollAnchors()`:

```js
function buildScrollAnchors() {
  const bodyA = $('body-a'), bodyB = $('body-b');
  const maxA = bodyA.scrollHeight - bodyA.clientHeight;
  const maxB = bodyB.scrollHeight - bodyB.clientHeight;
  if (maxA <= 0 || maxB <= 0) { scrollAnchors = []; return; }
  scrollAnchors = [{ a: 0, b: 0 }, { a: maxA, b: maxB }];
}
```

- [ ] **Step 4: Call buildScrollAnchors in both paths of updateDiffMap**

Find `updateDiffMap` (around line 475 in original). It has an early-return path and a success path.

Early-return path — add `scrollAnchors = [];`:
```js
function updateDiffMap() {
  if (!panels.a.content || !panels.b.content) {
    lastChunks = [];
    scrollAnchors = [];
    updateDiffSummary();
    updateNavButtons();
    return;
  }
```

Success path — add `buildScrollAnchors()` after `annotateWordDiffs`:
```js
  annotateWordDiffs(chunks);
  buildScrollAnchors();
  currentChunkIdx = -1;
  updateDiffSummary();
  updateNavButtons();
}
```

- [ ] **Step 5: Run to confirm PASS**

```bash
./node_modules/.bin/playwright test --reporter=list electron-tests/e2e/scroll-anchors.spec.js
```

Expected: PASS (2 tests, 0 failures)

- [ ] **Step 6: Commit**

```bash
git add index.html electron-tests/e2e/scroll-anchors.spec.js
git commit -m "feat(scroll-anchors): buildScrollAnchors with boundary anchors

Refs #3"
```

---

### Task 3: buildScrollAnchors — heading matching, sort, deduplicate

**Files:**
- Modify: `index.html` — complete `buildScrollAnchors`
- Modify: `electron-tests/e2e/scroll-anchors.spec.js` — add test

- [ ] **Step 1: Add the heading-match test to the spec**

Add inside the `test.describe` block, after the boundary-anchors test:

```js
  test('buildScrollAnchors with shared headings returns sorted interior anchors', async () => {
    const shortFill = Array.from({ length: 30 },
      (_, i) => `Short filler paragraph ${i + 1}.`
    ).join('\n\n');
    const longFill = Array.from({ length: 60 },
      (_, i) => `Long filler paragraph ${i + 1}.`
    ).join('\n\n');

    // A: headings after 30 paragraphs of filler
    const contentA =
      `# Doc A\n\n${shortFill}\n\n## Shared Section\n\n${shortFill}\n\n## Second Heading\n\n${shortFill}`;
    // B: same headings but after 60 paragraphs — positions differ from A
    const contentB =
      `# Doc B\n\n${longFill}\n\n## Shared Section\n\n${shortFill}\n\n## Second Heading\n\n${shortFill}`;

    await window.evaluate(([a, b]) => {
      renderMarkdown('a', a);
      renderMarkdown('b', b);
    }, [contentA, contentB]);

    const anchors = await window.evaluate(() => getScrollAnchors());

    // More than just boundary anchors
    expect(anchors.length).toBeGreaterThan(2);

    // Sorted by .a with strictly increasing values (deduplication working)
    for (let i = 1; i < anchors.length; i++) {
      expect(anchors[i].a).toBeGreaterThan(anchors[i - 1].a);
    }

    // First and last are boundary anchors
    expect(anchors[0]).toEqual({ a: 0, b: 0 });
    expect(anchors[anchors.length - 1].a).toBeGreaterThan(0);
    expect(anchors[anchors.length - 1].b).toBeGreaterThan(0);
  });
```

- [ ] **Step 2: Run to confirm FAIL**

```bash
./node_modules/.bin/playwright test --reporter=list electron-tests/e2e/scroll-anchors.spec.js
```

Expected: FAIL — `expect(anchors.length).toBeGreaterThan(2)` fails: received `2` (only boundary anchors)

- [ ] **Step 3: Replace buildScrollAnchors stub with the complete implementation**

Replace the current `buildScrollAnchors` body in `index.html`:

```js
function buildScrollAnchors() {
  const bodyA = $('body-a'), bodyB = $('body-b');
  const maxA = bodyA.scrollHeight - bodyA.clientHeight;
  const maxB = bodyB.scrollHeight - bodyB.clientHeight;
  if (maxA <= 0 || maxB <= 0) { scrollAnchors = []; return; }

  const brA = bodyA.getBoundingClientRect();
  const brB = bodyB.getBoundingClientRect();

  const aHds = [...$('render-a').querySelectorAll('h2,h3,h4')]
    .map(el => ({ text: normHead(el.textContent),
                  pos: el.getBoundingClientRect().top - brA.top + bodyA.scrollTop }));
  const bHds = [...$('render-b').querySelectorAll('h2,h3,h4')]
    .map(el => ({ text: normHead(el.textContent),
                  pos: el.getBoundingClientRect().top - brB.top + bodyB.scrollTop }));

  const anchors = [{ a: 0, b: 0 }];
  for (const ah of aHds) {
    const match = bHds.find(bh => bh.text === ah.text)
               ?? bHds.find(bh => bh.text.startsWith(ah.text.slice(0, 18))
                                || ah.text.startsWith(bh.text.slice(0, 18)));
    if (match) anchors.push({ a: ah.pos, b: match.pos });
  }
  anchors.push({ a: maxA, b: maxB });

  anchors.sort((x, y) => x.a - y.a);
  scrollAnchors = anchors.filter((an, i) => i === 0 || an.a > anchors[i - 1].a);
}
```

- [ ] **Step 4: Run to confirm PASS**

```bash
./node_modules/.bin/playwright test --reporter=list electron-tests/e2e/scroll-anchors.spec.js
```

Expected: PASS (3 tests, 0 failures)

- [ ] **Step 5: Commit**

```bash
git add index.html electron-tests/e2e/scroll-anchors.spec.js
git commit -m "feat(scroll-anchors): heading match, sort, deduplicate in buildScrollAnchors

Refs #3"
```

---

### Task 4: interp + sync handler integration

**Files:**
- Modify: `index.html` — add `interp`, update `setupScrollSync`, update `toggleSync`
- Modify: `electron-tests/e2e/scroll-anchors.spec.js` — add behavioural test

- [ ] **Step 1: Add the scroll-divergence behavioural test**

Add inside the `test.describe` block, after the heading-match test:

```js
  // ── Behavioural sync ─────────────────────────────────────────────────

  test('scroll sync uses heading anchors, diverging from pure percentage', async () => {
    // A: heading near the top; B: heading near the bottom.
    // Pure-% sync would put B at ~0%. Anchor sync puts B near its heading (~75%+).
    const longFill = Array.from({ length: 80 },
      (_, i) => `Filler paragraph ${i + 1} for divergence test.`
    ).join('\n\n');
    const shortFill = Array.from({ length: 10 },
      (_, i) => `Short tail ${i + 1}.`
    ).join('\n\n');

    const contentA = `## Anchor\n\n${longFill}`;
    const contentB = `${longFill}\n\n## Anchor\n\n${shortFill}`;

    await window.evaluate(([a, b]) => {
      renderMarkdown('a', a);
      renderMarkdown('b', b);
    }, [contentA, contentB]);

    // Verify anchors were built with an interior point (heading match found)
    const anchors = await window.evaluate(() => getScrollAnchors());
    expect(anchors.length).toBeGreaterThan(2);

    // Scroll A to its heading and read where B ends up
    const { scrollB, maxB } = await window.evaluate(() => {
      const bodyA = document.getElementById('body-a');
      const headingA = document.querySelector('#render-a h2');
      bodyA.scrollTop = headingA.offsetTop;
      bodyA.dispatchEvent(new Event('scroll'));
      const bodyB = document.getElementById('body-b');
      return {
        scrollB: bodyB.scrollTop,
        maxB: bodyB.scrollHeight - bodyB.clientHeight
      };
    });

    // With anchor sync, B should be well into the second half (near its heading).
    // Pure-% from A's small scrollTop would give < 5% of maxB.
    expect(scrollB).toBeGreaterThan(maxB * 0.4);
  });
```

- [ ] **Step 2: Run to confirm FAIL**

```bash
./node_modules/.bin/playwright test --reporter=list electron-tests/e2e/scroll-anchors.spec.js
```

Expected: FAIL — `expect(scrollB).toBeGreaterThan(maxB * 0.4)` fails: `scrollB` is near 0 because `interp` doesn't exist and `setupScrollSync` still uses pure `%`.

- [ ] **Step 3: Add interp to index.html**

Add after `getScrollAnchors()`:

```js
function interp(pos, fk, tk) {
  const a = scrollAnchors;
  if (a.length < 2) return pos;
  let i = a.length - 2;
  while (i > 0 && a[i][fk] > pos) i--;
  const lo = a[i], hi = a[i + 1];
  if (hi[fk] === lo[fk]) return lo[tk];
  return lo[tk] + Math.max(0, Math.min(1, (pos - lo[fk]) / (hi[fk] - lo[fk])))
                * (hi[tk] - lo[tk]);
}
```

- [ ] **Step 4: Update setupScrollSync to use interp**

Replace the existing `setupScrollSync` function body:

```js
function setupScrollSync() {
  const bodyA = $('body-a'), bodyB = $('body-b');
  bodyA.addEventListener('scroll', () => {
    if (!syncEnabled || syncing) return;
    syncing = true;
    bodyB.scrollTop = scrollAnchors.length >= 2
      ? interp(bodyA.scrollTop, 'a', 'b')
      : scrollPercent(bodyA) * (bodyB.scrollHeight - bodyB.clientHeight);
    requestAnimationFrame(() => requestAnimationFrame(() => { syncing = false; }));
  }, { passive: true });
  bodyB.addEventListener('scroll', () => {
    if (!syncEnabled || syncing) return;
    syncing = true;
    bodyA.scrollTop = scrollAnchors.length >= 2
      ? interp(bodyB.scrollTop, 'b', 'a')
      : scrollPercent(bodyB) * (bodyA.scrollHeight - bodyA.clientHeight);
    requestAnimationFrame(() => requestAnimationFrame(() => { syncing = false; }));
  }, { passive: true });
}
```

- [ ] **Step 5: Update toggleSync to use interp**

Replace the existing `toggleSync` function body:

```js
function toggleSync() {
  syncEnabled = !syncEnabled;
  $('btn-sync').classList.toggle('active', syncEnabled);
  if (syncEnabled) {
    $('body-b').scrollTop = scrollAnchors.length >= 2
      ? interp($('body-a').scrollTop, 'a', 'b')
      : scrollPercent($('body-a')) * ($('body-b').scrollHeight - $('body-b').clientHeight);
  }
}
```

- [ ] **Step 6: Run scroll-anchors spec to confirm PASS**

```bash
./node_modules/.bin/playwright test --reporter=list electron-tests/e2e/scroll-anchors.spec.js
```

Expected: PASS (4 tests, 0 failures)

- [ ] **Step 7: Run the full suite — confirm no regressions**

```bash
./node_modules/.bin/playwright test --reporter=list
```

Expected: 46 passing, 2 skipped (the existing scroll-sync self-skip tests). The new spec adds 4 tests → 50 passing total (or 48 if the 2 self-skip tests are counted differently).

Note: If both scroll-sync tests in `regression.spec.js` skip themselves (fixture fits in viewport), total stays 46 passing + 4 new = 50 passing, 2 skipped. If they run, all should pass — boundary-only anchors reproduce exact percentage behaviour.

- [ ] **Step 8: Commit**

```bash
git add index.html electron-tests/e2e/scroll-anchors.spec.js
git commit -m "feat(scroll-anchors): interp + sync handler integration

Closes #3"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task |
|---|---|
| `normHead` function | Task 1 |
| `scrollAnchors` state var | Task 1 |
| `getScrollAnchors()` test helper | Task 1 |
| `buildScrollAnchors` boundary anchors | Task 2 |
| `buildScrollAnchors` in both paths of `updateDiffMap` | Task 2 |
| `buildScrollAnchors` heading scan + exact match | Task 3 |
| `buildScrollAnchors` prefix fallback match | Task 3 |
| `buildScrollAnchors` sort + deduplication | Task 3 |
| `interp` function | Task 4 |
| `setupScrollSync` uses `interp` | Task 4 |
| `toggleSync` uses `interp` | Task 4 |
| Behavioural test: anchor sync diverges from % | Task 4 |
| Full regression suite check | Task 4 |

**No placeholders:** All steps have complete code.

**Type consistency:** `scrollAnchors` is `{a: number, b: number}[]` throughout. `interp(pos, fk, tk)` uses string keys `'a'`/`'b'` consistently. `getScrollAnchors()` returns `scrollAnchors` directly. All consistent.
