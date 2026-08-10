# Swap Panels (A↔B) — Design Spec

**Date:** 2026-05-20
**Issue:** #2
**Epic:** #1 — Diff viewer completeness
**Branch:** issue-2-swap-panels

---

## Overview

Add a Swap button to the topbar that swaps the A and B panels: their file paths, rendered content, and user-assigned labels all move together. The swap is the catalyst for a state model refactor — the current scattered parallel objects (`filePaths`, `contents`, labels in DOM) are replaced with a single `panels` object that correctly models the domain.

---

## State Model Refactor

### Before

```js
const filePaths   = { a: null, b: null };   // panel path
const contents    = { a: null, b: null };   // raw markdown text
const watcherRefs = {};                     // path → Set<panel> — manual bookkeeping
// labels live only in the DOM: label-a.value, label-b.value
```

### After

```js
const panels = {
  a: { path: null, content: null, label: 'File A' },
  b: { path: null, content: null, label: 'File B' }
};
```

`watcherRefs` is eliminated. "Which panels watch this path?" is a derivable query:

```js
function panelsWatching(path) {
  return ['a', 'b'].filter(p => panels[p].path === path);
}
```

Labels are state-owned. An `input` listener keeps state and DOM in sync when the user edits a label:

```js
['a', 'b'].forEach(p =>
  $(`label-${p}`).addEventListener('input',
    () => { panels[p].label = $(`label-${p}`).value; }));
```

### Migration

All existing references are updated mechanically:

| Before | After |
|---|---|
| `filePaths[panel]` | `panels[panel].path` |
| `contents[panel]` | `panels[panel].content` |
| `watcherRefs[path].add/delete` | removed — use `panelsWatching(path)` |
| `label-x.value` (read) | `panels[x].label` |

---

## DOM Sync Helper

A new `syncPanelDOM(panel)` renders state → DOM. It is the single point where panel state is applied to the UI:

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

`renderMarkdown` is simplified to:

```js
function renderMarkdown(panel, content) {
  panels[panel].content = content;
  syncPanelDOM(panel);
  updateDiffMap();
}
```

`loadFile` sets `panels[panel].path` and resets `panels[panel].label` to the filename (basename of path), then calls `syncPanelDOM` and `watchFile`. If the panel previously had a file, it calls `unwatchFile(oldPath)` before updating state. The updated `loadFile` skeleton:

```js
async function loadFile(panel, path) {
  const prev = panels[panel].path;
  panels[panel].path = path;
  panels[panel].label = path.split('/').pop();  // basename as default label
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

---

## Watcher Lifecycle

`watchFile` and `unwatchFile` are updated to use `panelsWatching`:

```js
function watchFile(panel, path) {
  if (watchers[path]) return;          // EventSource already open
  const es = new EventSource(apiUrl(`/api/watch?path=${encodeURIComponent(path)}`));
  es.onmessage = async () => {
    for (const p of panelsWatching(path)) {
      try {
        const content = await fetchFile(path);
        renderMarkdown(p, content);
      } catch (_) {}
    }
  };
  watchers[path] = es;
}

function unwatchFile(path) {
  if (panelsWatching(path).length === 0 && watchers[path]) {
    watchers[path].close();
    delete watchers[path];
  }
}
```

`unwatchFile` no longer takes a `panel` argument — it closes the EventSource when no panel references the path.

---

## Swap Implementation

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

Watcher EventSources are path-keyed and remain open unchanged. `panelsWatching()` derives the correct panel-path associations from `panels[p].path` automatically after the destructure.

---

## Button

Added to the topbar between the Sync button and the spacer (Option A — left cluster):

```html
<button id="btn-swap" onclick="swapPanels()" title="Swap panels A↔B">⇄ Swap</button>
```

Enabled only when both panels have a file loaded:

```js
function updateSwapButton() {
  const enabled = !!(panels.a.path && panels.b.path);
  $('btn-swap').disabled = !enabled;
  $('btn-swap').style.opacity = enabled ? '' : '0.4';
}
```

`updateSwapButton()` is called from `loadFile` after state is updated.

---

## Scroll Reset

Both panel bodies scroll to top on swap. Swapping mid-scroll would disorient — the new content starts from the beginning.

---

## Testing

One new Playwright E2E spec: `electron-tests/e2e/swap-panels.spec.js`

| Test | What it verifies |
|---|---|
| Swap reverses panel content | Load A.md and B.md; click Swap; verify `path-a` shows B's path, `path-b` shows A's path; verify rendered content matches |
| Swap disabled with one file | Load only panel A; assert `btn-swap` is disabled |
| Swap re-enables after second file | Load panel B; assert `btn-swap` is enabled |
| Labels follow content | Rename label-a to "Draft"; click Swap; verify label on right side reads "Draft" |
| Double-swap restores original | Swap twice; verify state is identical to before first swap |
| Diff map redraws after swap | After swap, minimap reflects new A/B orientation (red on left = A-side deletions) |

Existing scroll-sync and happy-path Playwright tests must continue to pass unchanged.

---

## Out of Scope

- Swap when only one panel is loaded (disabled, not handled)
- Animation or transition effect on swap (future enhancement)
- Keyboard shortcut for swap (can be added later alongside next/prev nav shortcuts)
