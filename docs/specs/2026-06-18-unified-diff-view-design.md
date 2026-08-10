# Unified Diff View Mode — Design Spec

**Issue:** #66
**Scale:** M | **Complexity:** Med

Toggle between side-by-side and unified diff views in `<drafthouse-diff>`. The LCS diff engine already computes line-level changes — unified view is a different rendering of the same data.

## View mode state

`drafthouse-diff.js` adds a `_viewMode` property: `'split'` (default) or `'unified'`. A public `setViewMode(mode)` method toggles between them. A public getter `get viewMode()` returns the current value.

When switching modes:
- **Split → Unified:** Hide `panel-b` and `#divider`. Clear `render-b.innerHTML` and hide `empty-b` (the file is loaded — `_panels.b.content` is populated — we're just not rendering it in panel B; showing `empty-b` would display "No file selected" which is factually wrong). Re-render using the unified rendering pipeline.
- **Unified → Split:** Show `panel-b` and `#divider`. Call `_syncPanelContent('a')` and `_syncPanelContent('b')` to restore both panels to their individually-rendered markdown before calling `_updateDiffMap()`. This is critical: `_annotateRendered` walks `marked.lexer(content)` tokens and maps them positionally to render element children — after unified mode, `render-a.children` are unified containers (del/ins blocks), not the per-token elements that `_annotateRendered` expects. Without restoring, annotations land on wrong elements.

Both modes consume the same `{ a, b, chunks }` output from `_lineDiff`. `setViewMode` sets `_viewMode`, performs the mode-specific setup above, then calls `_updateDiffMap()` which dispatches to the correct rendering pipeline.

## Unified rendering pipeline

A new private method `_renderUnified(aLines, bLines, chunks)` replaces `_annotateRendered` in unified mode. It builds a single interleaved document in `render-a`:

1. Clear `render-b.innerHTML = ''` and add `hidden` class to `empty-b`. This prevents stale `data-diff-chunk` attributes in render-b from poisoning `_chunkOutOfView`, `nextDiff`/`prevDiff`, and `scrollToLocation` — all of which query both render panels via `||` or iteration.
2. Walk the chunks array
3. For `eq` chunks: render B-side lines as normal markdown. B-side is chosen by convention — content is identical; unified diffs traditionally show the newer version for context lines.
4. For `del` chunks: render A-side lines wrapped in `div.diff-unified-del` — red left border, `−` gutter marker, light red background tint
5. For `ins` chunks: render B-side lines wrapped in `div.diff-unified-ins` — green left border, `+` gutter marker, light green background tint
6. For `mod` chunks: render A-side lines as a `del` block followed by B-side lines as an `ins` block (old-then-new, interleaved at the point of change)

Each change block carries `data-diff-chunk` attributes matching the chunk index, so diff navigation (`nextDiff`/`prevDiff`) works unchanged — it scrolls within panel A only.

### Rendering approach

Each section (eq, del, ins, mod) is rendered as a markdown fragment via `marked.parse()`, then wrapped in the appropriate container div. The full unified output is assembled as HTML and set as `render-a.innerHTML`.

**Known limitation:** Per-chunk `marked.parse()` fragments multi-line markdown structures that span chunk boundaries. A numbered list where item 2 is modified produces three separate `<ol>` elements instead of one continuous list. The same applies to tables, blockquotes, and nested lists. This is an acceptable tradeoff for a first implementation — rendered-but-fragmented is better than raw text. Traditional unified diffs show raw text, so this is strictly superior. If it matters in practice, a follow-up could build a virtual unified document, render it as one `marked.parse()` call, and annotate post-hoc (similar to how `_annotateRendered` works for split mode).

### Word-level highlights in unified mode

Word-level diff applies inside `mod` blocks. For each `mod` chunk, after the markdown is rendered within the del and ins sub-blocks, compute `_wordDiff` on the **rendered `textContent`** of the del and ins elements (not the raw source lines — matching how `_annotateWordDiffs` works in split mode at line 705: `this._wordDiff(elA.textContent, elB.textContent)`). Apply `diff-word-a` marks in the del sub-block and `diff-word-b` marks in the ins sub-block using the existing `_applyWordHighlights` method.

## CSS additions

New styles in the component's adopted stylesheet:

```css
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

## DOM changes

The constructor's HTML stays the same — both panels always exist in the DOM. Switching modes toggles CSS visibility:
- `panel-b`: `display: none` in unified mode
- `#divider`: `display: none` in unified mode
- `panel-a`: already `flex: 1`, fills available width when siblings hide

No DOM creation or destruction — just show/hide.

## _updateDiffMap structure

`_updateDiffMap()` is the single entry point for re-rendering after content changes. Both modes share a common head and tail with branched rendering in between:

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

This ensures `diff-updated` fires in both modes — the shell's `updateDiffUI()` handler always receives chunk data for the diff counter, summary, and nav button state.

## Selection events in unified mode

In unified mode, the mouseup handler on `render-a` must detect which document side the selected text belongs to. Walk from `range.startContainer` up the DOM to the nearest `.diff-unified-del` or `.diff-unified-ins` ancestor:
- `.diff-unified-del` → report `side: 'A'` (A-side content)
- `.diff-unified-ins` → report `side: 'B'` (B-side content)
- Neither (eq block) → report `side: 'A'` (content is identical in both documents)

This prevents the `POST /api/debate/{id}/selection` endpoint from grounding the review in the wrong document.

**Edge case:** If a user drags selection across a del→ins boundary in a mod chunk, `startContainer` is in the del block, so `side: 'A'` is reported even though the selection includes B-side text. This is an unlikely interaction and the debate system can ground on `selectedText` regardless of side. Acceptable for first implementation.

## Topbar integration (index.html)

A new button in the topbar between the existing Sync and Swap buttons:

```html
<button id="btn-view-mode" title="Toggle split/unified view (u)">⫏ Split</button>
```

Click handler toggles the mode and updates the button label:

```js
$('btn-view-mode').addEventListener('click', () => {
  const mode = diffPanel.viewMode === 'split' ? 'unified' : 'split';
  diffPanel.setViewMode(mode);
  $('btn-view-mode').textContent = mode === 'split' ? '⫏ Split' : '☰ Unified';
});
```

Keyboard shortcut `u` (lowercase — consistent with existing `n`, `p`, `s` shortcuts) registered in the existing shortcut handler, mapped to the same toggle. Must respect the Shadow DOM input guard (GE-20260617-cc0834).

The shortcuts overlay table needs a new row:
```html
<tr><td><kbd>u</kbd></td><td>Toggle split/unified view</td></tr>
```

## Panel header in unified mode

In unified mode, panel A's header continues to show the A-side label and path. The panel now displays interleaved content from both files, but the topbar view-mode button (`☰ Unified`) provides mode context. Intentionally left as-is — adding a second path to the header adds visual noise for a single toggle state that the button already communicates.

## What doesn't change

- `_lineDiff`, `_wordDiff`, `_tokenize` — shared by both modes, untouched
- `_fetchFile`, `_watchFile`, `_unwatchFile` — untouched
- `loadFile`, `configure` — work in both modes (trigger `_updateDiffMap` which dispatches)
- `swapPanels` — works in both modes (swaps internal state, `_updateDiffMap` re-renders in the correct mode)
- `getDiffSummary` — returns same chunk-based summary regardless of mode
- `diff-updated` custom event — same payload, guaranteed to fire in both modes
- `currentPath(slot)` — returns stored paths regardless of mode

## What adapts

| Method | Unified mode behaviour |
|--------|----------------------|
| `_updateDiffMap` | Shared head/tail, branched rendering: `_renderUnified` vs dual `_annotateRendered` + minimap |
| `setViewMode` | New method — show/hide panels, restore panel content on unified→split, call `_updateDiffMap` |
| `_renderUnified` | New method — interleaved del/ins rendering in panel A, clears render-b |
| `_scrollToChunk` | Scrolls panel A only (panel B hidden) |
| Scroll sync (`_setupScrollSync`) | Naturally suppressed — `body-b` is `display: none`, emits no scroll events, and `scrollTop` assignment is harmless on a hidden element. No code change needed. |
| `nextDiff`/`prevDiff` | Same chunk index logic, scrolls panel A only |
| `_chunkOutOfView` | Checks panel A only (render-b cleared, no stale matches) |
| `scrollToLocation` | Searches headings in panel A only (unified content rendered there) |
| Selection `mouseup` handler | Detects side from `.diff-unified-del`/`.diff-unified-ins` ancestor |

## Files changed

| File | Change |
|------|--------|
| `panels/drafthouse-diff.js` | `_viewMode`, `setViewMode()`, `viewMode` getter, `_renderUnified()`, CSS additions, `_updateDiffMap` refactor, selection side detection, adapt navigation |
| `index.html` | View mode toggle button, `u` keyboard shortcut, shortcuts overlay row |

## Testing

**E2E tests** (new test file `UnifiedDiffE2ETest.java`):
- Toggle button renders in topbar
- Click toggles to unified mode — panel B and divider hidden, panel A full width
- Changes visible with correct diff styling (del/ins blocks with gutter markers)
- Word-level highlights in unified mode: verify `mark.diff-word-a` and `mark.diff-word-b` elements exist inside `diff-unified-del`/`diff-unified-ins` containers
- Diff navigation (`nextDiff`/`prevDiff`) works in unified mode — scrolls to change blocks
- Click toggle again — restores side-by-side view with both panels visible and diff annotations correct
- Mode round-trip: split → unified → split → verify diff annotations are correct (regression test for render-a content restoration)
- `swapPanels` in unified mode: verify the unified view updates correctly after swap
- `u` keyboard shortcut toggles mode

**Manual verification:**
- Verify on narrow viewport that unified mode renders readably
