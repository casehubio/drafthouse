# Word-Level Diff — Design Spec

**Date:** 2026-05-22
**Issue:** #11
**Epic:** #1 — Diff viewer completeness
**Branch:** issue-11-word-level-diff

---

## Overview

Within each annotated `mod` diff block, highlight the specific words that differ rather than styling the whole block. Uses a word-level LCS diff on the rendered `textContent` of matching elements in panels A and B, then applies highlights by walking each element's text nodes — preserving inline formatting (bold, italic, inline code, links) without touching element nodes.

`del` and `ins` chunks already show the whole block as changed; word-level diff only applies to `mod` chunks where both sides have content to compare.

---

## Highlight Style

Changed words get a semi-transparent filled background — distinct from but harmonious with the existing block-level red/green borders:

```css
mark.diff-word-a { background: rgba(239,68,68,0.35); border-radius: 2px; padding: 0 1px; color: inherit; }
mark.diff-word-b { background: rgba(34,197,94,0.35); border-radius: 2px; padding: 0 1px; color: inherit; }
```

`color: inherit` overrides the `<mark>` element's default yellow background behaviour. The `<mark>` element is used (rather than `<span>`) for semantic correctness — it denotes highlighted/marked text.

---

## Algorithm

### `tokenize(text)`

Splits text into word and non-word (whitespace/separator) segments, each with character positions in the original text:

```js
function tokenize(text) {
  const tokens = [];
  let pos = 0;
  for (const m of text.matchAll(/\S+/g)) {
    if (m.index > pos) tokens.push({ text: text.slice(pos, m.index), word: false });
    tokens.push({ text: m[0], word: true, start: m.index, end: m.index + m[0].length });
    pos = m.index + m[0].length;
  }
  if (pos < text.length) tokens.push({ text: text.slice(pos), word: false });
  return tokens;
}
```

Words are whitespace-delimited — punctuation attached to a word (`"Hello,"`, `"world!"`) is treated as part of that word. This produces stable word boundaries that match natural reading units.

### `wordDiff(textA, textB)`

Runs LCS on the word tokens of each text, returning changed character ranges for each side:

```js
function wordDiff(textA, textB) {
  const ta = tokenize(textA).filter(t => t.word);
  const tb = tokenize(textB).filter(t => t.word);
  const m = ta.length, n = tb.length;
  const dp = Array.from({ length: m + 1 }, () => new Uint32Array(n + 1));
  for (let i = m - 1; i >= 0; i--)
    for (let j = n - 1; j >= 0; j--)
      dp[i][j] = ta[i].text === tb[j].text ? dp[i+1][j+1] + 1
                                            : Math.max(dp[i+1][j], dp[i][j+1]);
  const rangesA = [], rangesB = [];
  let i = 0, j = 0;
  while (i < m || j < n) {
    if (i < m && j < n && ta[i].text === tb[j].text) { i++; j++; }
    else if (j >= n || (i < m && (dp[i+1]?.[j] ?? 0) >= (dp[i]?.[j+1] ?? 0))) {
      rangesA.push([ta[i].start, ta[i].end]); i++;
    } else {
      rangesB.push([tb[j].start, tb[j].end]); j++;
    }
  }
  return { rangesA, rangesB };
}
```

The LCS DP is identical to `lineDiff` but operates on word strings rather than lines. The resulting `rangesA` and `rangesB` are arrays of `[start, end]` character offsets into the respective `textContent` strings.

### `applyWordHighlights(el, changedRanges, markClass)`

Walks the element's text nodes (preserving all element nodes), splits text nodes at changed-range boundaries, wraps changed portions in `<mark>`:

```js
function applyWordHighlights(el, changedRanges, markClass) {
  if (!changedRanges.length) return;
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  const nodes = [];
  let node, off = 0;
  while ((node = walker.nextNode())) {
    nodes.push({ node, start: off, end: off + node.length });
    off += node.length;
  }
  // Process in reverse to avoid offset invalidation from earlier DOM mutations
  for (let i = nodes.length - 1; i >= 0; i--) {
    const { node, start, end } = nodes[i];
    const overlaps = changedRanges.filter(([rs, re]) => re > start && rs < end);
    if (!overlaps.length) continue;
    const segs = [];
    let pos = start;
    for (const [rs, re] of overlaps) {
      if (rs > pos) segs.push({ t: node.data.slice(pos - start, rs - start), ch: false });
      segs.push({ t: node.data.slice(Math.max(rs,start) - start, Math.min(re,end) - start), ch: true });
      pos = re;
    }
    if (pos < end) segs.push({ t: node.data.slice(pos - start), ch: false });
    const frag = document.createDocumentFragment();
    for (const s of segs) {
      if (s.ch) {
        const mark = document.createElement('mark');
        mark.className = markClass;
        mark.textContent = s.t;
        frag.appendChild(mark);
      } else {
        frag.appendChild(document.createTextNode(s.t));
      }
    }
    node.parentNode.replaceChild(frag, node);
  }
}
```

Processing text nodes in reverse order means DOM mutations (inserting `<mark>` + text node fragments) for later text nodes do not shift the character offsets of earlier text nodes that haven't been processed yet.

### `annotateWordDiffs(chunks)`

Orchestrates word diff for all `mod` chunks:

```js
function annotateWordDiffs(chunks) {
  chunks.forEach((c, ci) => {
    if (c.op !== 'mod') return;
    const elA = $('render-a').querySelector(`[data-diff-chunk="${ci}"]`);
    const elB = $('render-b').querySelector(`[data-diff-chunk="${ci}"]`);
    if (!elA || !elB || elA.tagName === 'PRE' || elB.tagName === 'PRE') return;
    const { rangesA, rangesB } = wordDiff(elA.textContent, elB.textContent);
    applyWordHighlights(elA, rangesA, 'diff-word-a');
    applyWordHighlights(elB, rangesB, 'diff-word-b');
  });
}
```

`<pre>` elements (code blocks) are skipped — word diff on code is noisy and the block-level border is sufficient. All other `mod`-annotated element types (paragraphs `<p>`, headings `<h1>`–`<h6>`, blockquotes `<blockquote>`) receive word highlights.

---

## Integration

Called from `updateDiffMap()` after both `annotateRendered` calls (so `data-diff-chunk` attributes are set):

```js
function updateDiffMap() {
  // ... existing code ...
  annotateRendered('a', panels.a.content, chunks);
  annotateRendered('b', panels.b.content, chunks);
  annotateWordDiffs(chunks);   // ← new
  currentChunkIdx = -1;
  updateDiffSummary();
  updateNavButtons();
}
```

**Cleanup is automatic:** `syncPanelDOM` calls `marked.parse` and replaces `render.innerHTML` entirely on every file reload, live-reload, or swap — clearing all `<mark>` elements without any explicit cleanup code.

---

## CSS

Two rules added to `styles.css`:

```css
mark.diff-word-a {
  background: rgba(239, 68, 68, 0.35);
  border-radius: 2px;
  padding: 0 1px;
  color: inherit;
}
mark.diff-word-b {
  background: rgba(34, 197, 94, 0.35);
  border-radius: 2px;
  padding: 0 1px;
  color: inherit;
}
```

---

## Testing

New spec: `electron-tests/e2e/word-diff.spec.js`

| Test | What it verifies |
|---|---|
| Changed words highlighted in mod chunks | Load two files with one-word differences; `#render-a mark.diff-word-a` exists |
| Unchanged words have no mark | Text content of element has some words without `<mark>` |
| del/ins blocks have no word highlights | Pure deletion/insertion chunks: no `<mark>` inside those elements |
| pre blocks have no word highlights | Code blocks that change: no `<mark>` inside `<pre>` |
| Word highlights update after swap | Swap panels; `diff-word-a` now appears on the other panel |

---

## Out of Scope

- Character-level diff within words (e.g. "colour" → "color" showing only "ou"/"o")
- Approach C (markdown-level diff with custom renderer) — filed as future enhancement if needed
- Word diff for `del`/`ins` blocks (no counterpart to diff against)
- Animated transition on word highlight changes
