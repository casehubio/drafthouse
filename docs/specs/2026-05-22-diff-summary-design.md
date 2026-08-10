# Diff Summary — Design Spec

**Date:** 2026-05-22
**Issue:** #9
**Epic:** #1 — Diff viewer completeness
**Branch:** issue-9-diff-summary

---

## Overview

Add a compact diff breakdown summary to the topbar. After both files are loaded and `updateDiffMap()` runs, a `~3 −1 +1` label appears immediately to the right of the nav position counter. Hovering over it shows a CSS tooltip explaining each symbol. The summary gives instant orientation — how large is this diff, and what kind of changes? — without requiring navigation.

---

## Display Format

```
~N  −N  +N
```

- `~N` — N modified blocks (both A and B sides changed; shown in red on A, green on B)
- `−N` — N blocks deleted from A (A-only, no corresponding B block)
- `+N` — N blocks inserted into B (B-only, no corresponding A block)

Only non-zero components are shown. If all three are zero (no diffs), the element is empty. Examples:
- Five changed blocks, all modifications: `~5`
- Three modified, one deleted, one inserted: `~3 −1 +1`
- Two insertions only: `+2`

The minus sign used for deletions is the Unicode minus sign (U+2212, `−`) not a hyphen-minus, for visual clarity at small sizes.

---

## Tooltip

On hover, a CSS `::after` tooltip appears below the summary element:

```
~ modified · − only in A · + only in B
```

This is set via `data-tooltip` attribute and rendered purely in CSS — no JS event listeners required. When the summary is empty, `data-tooltip` is also empty and the pseudo-element renders nothing.

---

## HTML

Added to the topbar after `#diff-counter`, before `#topbar-spacer`:

```html
<span id="diff-summary" data-tooltip="" style="font-size:11px;color:#a09080;padding:0 6px;cursor:default;position:relative"></span>
```

---

## CSS (in `styles.css`)

```css
#diff-summary::after {
  content: attr(data-tooltip);
  position: absolute;
  top: calc(100% + 6px);
  left: 50%;
  transform: translateX(-50%);
  background: #1a1814;
  color: #c8baa0;
  border: 1px solid #4a4640;
  border-radius: 4px;
  padding: 4px 10px;
  font-size: 11px;
  white-space: nowrap;
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.15s;
  z-index: 100;
}
#diff-summary:hover::after {
  opacity: 1;
}
```

When `data-tooltip=""` (empty), `content: attr(data-tooltip)` renders an empty string and the tooltip box is invisible regardless of opacity.

---

## JS: `updateDiffSummary()`

```js
function updateDiffSummary() {
  const mod = lastChunks.filter(c => c.op === 'mod').length;
  const del = lastChunks.filter(c => c.op === 'del').length;
  const ins = lastChunks.filter(c => c.op === 'ins').length;
  const el = $('diff-summary');
  if (mod + del + ins === 0) {
    el.textContent = '';
    el.dataset.tooltip = '';
    return;
  }
  const parts = [];
  if (mod) parts.push(`~${mod}`);
  if (del) parts.push(`−${del}`);
  if (ins) parts.push(`+${ins}`);
  el.textContent = parts.join(' ');
  el.dataset.tooltip = '~ modified · − only in A · + only in B';
}
```

---

## Integration: `updateDiffMap()`

`updateDiffSummary()` is called in two places within `updateDiffMap()`:

**Early-return guard (no content):** Reset summary and nav state, then return:

```js
function updateDiffMap() {
  if (!panels.a.content || !panels.b.content) {
    lastChunks = [];
    updateDiffSummary();
    updateNavButtons();
    return;
  }
  const { a, b, chunks } = lineDiff(panels.a.content, panels.b.content);
  lastChunks = chunks; lastTotalA = a.length; lastTotalB = b.length;
  drawDiffMap(a.length, b.length, chunks);
  annotateRendered('a', panels.a.content, chunks);
  annotateRendered('b', panels.b.content, chunks);
  currentChunkIdx = -1;
  updateDiffSummary();   // ← new
  updateNavButtons();
}
```

Resetting `lastChunks = []` in the early-return guard also fixes the pre-existing issue where stale chunk data persisted when `updateDiffMap` was called while one panel had no content (e.g., from the ResizeObserver or after a load error).

---

## Testing

New spec: `electron-tests/e2e/diff-summary.spec.js`

| Test | What it verifies |
|---|---|
| Summary visible after loading differing files | `#diff-summary` text is non-empty and matches `/[~−+]\d/` |
| Summary clears when files are identical | Set `panels.b.content = panels.a.content; updateDiffMap()`; verify `textContent === ''` |
| Summary updates after swap | Note summary before swap; swap; verify same text (symmetric diff count) |
| Tooltip attribute set | `data-tooltip` is non-empty when summary is non-empty |

---

## Out of Scope

- Animated transition when summary changes
- Click on summary to jump to first diff of that type
- Per-type colour coding in the summary text
