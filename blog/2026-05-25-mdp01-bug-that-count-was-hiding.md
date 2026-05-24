---
layout: post
title: "The bug that count > 0 was hiding"
date: 2026-05-25
type: phase-update
entry_type: note
subtype: diary
projects: [md-compare]
tags: [testing, diff, marked, javascript]
---

A cleanup branch — five small fixes, nothing architectural — uncovered the most interesting bug since the word-level diff itself.

After shipping swap panels, navigation, the diff summary, and word-level highlighting, I wanted to tighten the tests before moving to scroll sync. The existing word-diff tests checked `count > 0`. Not much of an assertion. We added a fixture-specific check: the word "evaluates" must appear inside a `<mark.diff-word-a>` element. The word is in fixture A but not B — the diff algorithm should mark it.

The test failed immediately.

A diagnostic `window.evaluate` showed exactly one marked word: "Limitations." The heading. Every paragraph was invisible to the word annotator.

The word-diff runs in two phases. First, `annotateRendered` walks the marked.js token stream and tags each DOM element with the line-level chunk it belongs to. Second, `annotateWordDiffs` runs the word diff on each tagged element's text. If `annotateRendered` silently skips a paragraph, no words get highlighted — no error, no output, just silence.

The bug was in how `annotateRendered` tracked its position through the token stream. Each token's `raw.split('\n').length - 1` gives its line count. For headings that's 2 — the heading line plus the following blank line, both in the heading's `raw` field. For paragraphs in marked.js v9 it's 0. The trailing newline lives in the *space* token that comes after the paragraph.

With `rawLines=0`, the position tracker doesn't advance. `tokenEnd` equals `line`. The overlap check `c.aStart < tokenEnd` becomes `c.aStart < line` — which fails when a chunk starts exactly at the current line. Paragraph chunks always start exactly at the current line, because `line` never moved past them.

The fix:

```js
const endForCheck = Math.max(tokenEnd, line + 1);
const ci = chunks.findIndex(c =>
  c.op !== 'eq' && c[startKey] < endForCheck && c[endKey] > line);
```

`endForCheck` is only for the overlap check. `line` still advances by the actual `rawLines` — so tokens after the paragraph remain correctly positioned. The heading worked because `rawLines=2` gives it `tokenEnd = line + 2`, which finds chunks correctly. In the fixture, the only mod-chunk heading was "Limitations" — so that's the only element that ever got annotated.

The tests had passed when the word-level diff shipped. "Changed words are highlighted" — true. "Count greater than zero" — also true. One word, "Limitations," in one heading. The paragraphs with the actual interesting changes — "evaluates business rules against a set of facts" versus "runs your business rules so developers do not have to hard-code them" — had been silently skipped.

The cleanup branch was supposed to be routine.
