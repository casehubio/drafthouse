---
layout: post
title: "Scroll sync, two invisible bugs, and why event ordering matters"
date: 2026-05-25
type: phase-update
entry_type: note
subtype: diary
projects: [md-compare]
tags: [electron, playwright, scroll-sync, testing, race-condition]
---

The plan was to build heading-anchor scroll sync. Percentage-based sync breaks when two documents have different content distributions — scroll to a shared heading in panel A and panel B lands somewhere unrelated. The fix is piecewise linear interpolation between matched heading pairs.

The algorithm fell out quickly. Match headings by normalised text, build an anchor list of `{a: scrollTopPx, b: scrollTopPx}` pairs, interpolate linearly between them. Boundary anchors at `{0,0}` and `{maxA,maxB}` mean zero matches degrades to percentage sync with no special case. I ported the approach from Sparge, which uses the same pattern for same-document two-view sync.

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

Seven lines. Call as `interp(scrollTop, 'a', 'b')` for A-to-B, swap the keys for B-to-A. The clamp handles float imprecision at segment edges.

What I didn't expect was the test infrastructure collapsing under me. Two separate bugs — both silent, both intermittent, both in Electron's runtime rather than my code.

## The window that wouldn't wake up

Playwright's `waitForFunction` hung indefinitely. No timeout, no error — just silence. The predicate was correct; the element existed in the DOM. Increasing timeouts changed nothing.

The root cause: Chromium suppresses `requestAnimationFrame` callbacks in hidden windows. Electron's `show: false` pattern — create the window hidden, register `ready-to-show`, show it on first paint — means the window is invisible until the event fires. Playwright's default `polling: 'raf'` relies on RAF to evaluate predicates. No RAF, no evaluation.

The fix is `polling: 100` — timer-based polling that fires regardless of visibility. Two characters in the options object. The debugging took considerably longer.

## The IPC messages that never arrived

With the polling fix in place, tests still hung. The Quarkus server was running and responding to health checks. The Electron window loaded. But `waitForFunction(() => document.querySelector('h1'))` never resolved — the h1 never appeared because the renderer never received its init data.

The bug was in `main.js`:

```js
// BUG: ready-to-show may have already fired
await mainWindow.loadURL(`http://127.0.0.1:${port}/`);
mainWindow.once('ready-to-show', () => {
  mainWindow.show();
  mainWindow.webContents.send('init:config', { port });
  mainWindow.webContents.send('init:files', fileA, fileB);
});
```

`loadURL` returns a Promise that resolves on `did-finish-load`. `ready-to-show` fires after first meaningful paint. With CDN resources — highlight.js loaded from a CDN in this case — first paint can happen before all resources finish loading. When that happens, `ready-to-show` fires before `loadURL` resolves, and the handler registered after `await` misses the event entirely. Electron doesn't queue or replay missed `once()` events.

The fix: register the handler before `loadURL`. Four lines moved up, and the intermittent hang that had been haunting the test suite since the project started disappeared.

## Code review sharpened the matching

Claude caught two issues in the heading matcher during code review. First: the 18-character prefix fallback applied `slice(0, 18)` to all headings regardless of length. A 5-character heading like "Setup" sliced to its full text, falsely matching "Setup Instructions." The guard is simple — require the heading to be at least 18 characters before attempting a prefix match.

Second: nothing prevented two A-headings from consuming the same B-heading. Two "Overview" sections in A would both match the single "Overview" in B, creating degenerate interpolation segments. A `Set` tracking consumed B-heading indices fixed it.

Both were the kind of thing you don't see until someone else reads the code.
