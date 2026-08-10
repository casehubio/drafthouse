# Next/Prev Diff Navigation — Design Spec

**Date:** 2026-05-21
**Issue:** #7
**Epic:** #1 — Diff viewer completeness
**Branch:** issue-7-next-prev-nav

---

## Overview

Add keyboard (`n`/`p`) and topbar button (↑/↓) navigation that jumps between diff blocks. Shows a position counter (`N / M`) to the right of the buttons. Uses a hybrid index/viewport strategy: sequential from the last navigated chunk when it's still visible, viewport-based recalibration when the user has scrolled away.

Also fixes the existing minimap click handler which only scrolls one panel — replaced with a shared `scrollToChunk` helper used by both navigation and the minimap.

---

## State

One new variable:

```js
let currentChunkIdx = -1;   // index into lastChunks; -1 = no active chunk
```

Reset to `-1` in:
- `updateDiffMap()` — diff recomputed (new file or live reload)
- `swapPanels()` — orientation changed; old index is meaningless

---

## Helpers

### `nonEqIndices()`

Returns indices into `lastChunks` where `op !== 'eq'` — the navigable chunks.

```js
function nonEqIndices() {
  return lastChunks.reduce((acc, c, i) => { if (c.op !== 'eq') acc.push(i); return acc; }, []);
}
```

Called fresh on each navigation — never cached, since `lastChunks` can change.

### `chunkOutOfView(ci)`

Returns `true` if the annotated element for chunk `ci` is completely outside its panel's scroll viewport.

```js
function chunkOutOfView(ci) {
  for (const p of ['a', 'b']) {
    const el = $(`render-${p}`).querySelector(`[data-diff-chunk="${ci}"]`);
    if (!el) continue;
    const br = $(`body-${p}`).getBoundingClientRect();
    const er = el.getBoundingClientRect();
    return er.bottom < br.top || er.top > br.bottom;
  }
  return true;  // no element found → treat as out of view
}
```

Used to detect whether the user has scrolled away from the last navigated chunk and recalibration is needed.

### `scrollToChunk(ci)`

Scrolls **both** panels to the annotated element for chunk `ci`. Replaces the existing minimap click scroll loop (which only scrolled one panel).

```js
function scrollToChunk(ci) {
  for (const p of ['a', 'b']) {
    const el = $(`render-${p}`).querySelector(`[data-diff-chunk="${ci}"]`);
    if (!el) continue;
    const body = $(`body-${p}`);
    body.scrollBy({
      top: el.getBoundingClientRect().top - body.getBoundingClientRect().top - 24,
      behavior: 'smooth'
    });
  }
}
```

For `del` chunks (A-side only), only panel A scrolls. For `ins` (B-side only), only panel B. For `mod`, both scroll. This is correct — there's no element on the side that has no change.

---

## Navigation Logic

### Strategy: index with viewport recalibration

- If `currentChunkIdx === -1` or `chunkOutOfView(currentChunkIdx)`: **recalibrate** — find the nearest non-eq chunk relative to the viewport centre, jump there. No sequential step on this press.
- Otherwise: **sequential** — increment or decrement the position in `nonEqIndices`, wrap at ends.

### `nextDiff()`

```js
function nextDiff() {
  const idx = nonEqIndices();
  if (!idx.length) return;
  if (currentChunkIdx === -1 || chunkOutOfView(currentChunkIdx)) {
    // Recalibrate: nearest non-eq chunk at or below panel A's viewport centre
    const bodyA = $('body-a');
    const centre = bodyA.getBoundingClientRect().top + bodyA.clientHeight / 2;
    const found = idx.find(ci => {
      const el = $('render-a').querySelector(`[data-diff-chunk="${ci}"]`) ||
                 $('render-b').querySelector(`[data-diff-chunk="${ci}"]`);
      return el && el.getBoundingClientRect().top >= centre;
    });
    currentChunkIdx = found ?? idx[0];  // wrap to first if none below centre
  } else {
    const pos = idx.indexOf(currentChunkIdx);
    currentChunkIdx = idx[(pos + 1) % idx.length];
  }
  scrollToChunk(currentChunkIdx);
  updateNavCounter(idx);
}
```

### `prevDiff()`

```js
function prevDiff() {
  const idx = nonEqIndices();
  if (!idx.length) return;
  if (currentChunkIdx === -1 || chunkOutOfView(currentChunkIdx)) {
    // Recalibrate: nearest non-eq chunk at or above panel A's viewport centre
    const bodyA = $('body-a');
    const centre = bodyA.getBoundingClientRect().top + bodyA.clientHeight / 2;
    const found = [...idx].reverse().find(ci => {
      const el = $('render-a').querySelector(`[data-diff-chunk="${ci}"]`) ||
                 $('render-b').querySelector(`[data-diff-chunk="${ci}"]`);
      return el && el.getBoundingClientRect().bottom <= centre;
    });
    currentChunkIdx = found ?? idx[idx.length - 1];  // wrap to last if none above centre
  } else {
    const pos = idx.indexOf(currentChunkIdx);
    currentChunkIdx = idx[(pos - 1 + idx.length) % idx.length];
  }
  scrollToChunk(currentChunkIdx);
  updateNavCounter(idx);
}
```

---

## Counter and Button State

```js
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
```

`updateNavButtons()` is called from `updateDiffMap()` after chunks are computed. `updateDiffMap()` also resets `currentChunkIdx = -1` before calling `updateNavButtons()` — any previously active navigation position is invalidated when the diff changes.

---

## Topbar HTML

Added after `btn-swap`, before `topbar-spacer`:

```html
<button id="btn-prev" onclick="prevDiff()" title="Previous diff (p)" disabled style="opacity:.4">↑</button>
<button id="btn-next" onclick="nextDiff()" title="Next diff (n)" disabled style="opacity:.4">↓</button>
<span id="diff-counter" style="font-size:11px;color:#a09080;padding:0 4px;min-width:36px;display:inline-block;text-align:center">— / —</span>
```

---

## Keyboard Handler

Added to the existing `keydown` listener:

```js
if (e.key === 'n') nextDiff();
if (e.key === 'p') prevDiff();
```

Already guarded by `if (e.target.tagName === 'INPUT') return;` — safe for panel label editing.

---

## Minimap Click Fix

The existing loop in the minimap click handler `break`s after the first panel, so it only scrolls one panel. Replace with `scrollToChunk(ci)`:

```js
// Before (lines 428–436):
for (const p of ['a', 'b']) {
  const el = $(`render-${p}`).querySelector(`[data-diff-chunk="${ci}"]`);
  if (el) {
    const body = $(`body-${p}`);
    const relTop = el.getBoundingClientRect().top - body.getBoundingClientRect().top;
    body.scrollBy({ top: relTop - 24, behavior: 'smooth' });
    break;  // ← only scrolls one panel
  }
}

// After:
scrollToChunk(ci);
```

---

## Testing

New Playwright E2E spec: `electron-tests/e2e/nav.spec.js`

| Test | What it verifies |
|---|---|
| Next button navigates to first diff | Load two differing files; click ↓; body-a scrollTop changes from 0 |
| n key navigates forward | Same via keyboard `n` |
| p key navigates backward | After pressing n twice, p goes back one |
| Counter shows correct position | After navigating, `#diff-counter` text matches `N / M` pattern |
| Buttons disabled with no diff | Load identical files; btn-next is disabled |
| Wraps from last to first | Navigate to last chunk; press n; counter shows `1 / M` |
| Minimap click now scrolls both panels | Click minimap; both body-a and body-b scrollTop change |

---

## Out of Scope

- Arrow key (↑↓) navigation — conflicts with native scroll; keyboard shortcuts `n`/`p` are sufficient
- Animated highlight on current diff block — future enhancement
- Navigation memory across file reloads
