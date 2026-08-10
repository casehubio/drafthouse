# S/XS Cleanup Batch — Design Spec
**Date:** 2026-05-22  
**Branch:** issue-13-sx-cleanup  
**Issues:** #4, #5, #8, #10, #12 (umbrella: #13)

---

## Scope

Five independent, low-complexity cleanup items addressed in a single branch:
- #4 — pageerror listener + path guard in helpers.js
- #5 — syncPanelDOM re-parse, loadFile label redundancy, missing await
- #8 — nav test direction assertion, global-setup deduplication
- #10 — diff-summary test specificity, tokenize unit tests
- #12 — word-diff test specificity, tokenize edge case tests

---

## #4 — Playwright Test Hardening

### Problem
`launchApp` in `helpers.js` has two gaps:
1. No `pageerror` listener — JS errors in the renderer are silently swallowed during tests.
2. No guard against `undefined` file arguments — if `TEST_FILE_A`/`TEST_FILE_B` env vars are unset, `launchApp` silently launches without content, producing confusing downstream failures.

### Design

**pageerror:** `launchApp` attaches `window.on('pageerror', err => jsErrors.push(err.message))` before returning. The return type changes from `{ app, window }` to `{ app, window, jsErrors }`. All call sites are updated to destructure `jsErrors`. Every spec's `afterAll` asserts `expect(jsErrors).toHaveLength(0)`.

The `nav.spec.js` minimap test has its own local listener that predates this — remove it and use the shared `jsErrors` array instead.

**path guard:** If any argument to `launchApp` is JavaScript `undefined` (not null, not a string), throw immediately:
```
Error: launchApp received undefined for fileA — TEST_FILE_A env var not set. Run tests via Playwright with global-setup.
```
The existing `if (fileA)` null/empty guards remain for the legitimate "launch without files" path (e.g., programmatic tests that set up state via evaluate).

### API change
`launchApp` return type: `{ app, window }` → `{ app, window, jsErrors: string[] }`  
All `beforeAll` destructuring blocks across all specs must add `jsErrors`.

---

## #5 — index.html Code Quality

### Problems
1. **Label re-parse:** The label `input` event handler calls `syncPanelDOM(p)`, which re-runs `marked.parse(s.content)` just because the user typed a label character. This is unnecessary CPU work on every keystroke.
2. **loadFile DOM redundancy:** `loadFile` sets `$('path-${panel}').textContent` and `.classList.add('loaded')` directly (lines 187–188), then calls `renderMarkdown` → `syncPanelDOM` which sets the same elements again from `panels[panel].path`. The direct-DOM lines are redundant.
3. **Missing await:** The `onInitConfig` callback is not `async` and calls `loadFile` without `await`, unlike `onInitFiles` which correctly awaits both calls. Also the `drop` event handler calls `loadFile` without `await`.

### Design

1. **Label handler:** Remove the `syncPanelDOM(p)` call from the label `input` handler. The handler only needs to set `panels[p].label = $('label-${p}').value`. The DOM input already reflects the typed value.
2. **loadFile:** Delete the two redundant direct-DOM lines (187–188). `panels[panel].path = path` is set before `renderMarkdown` is called, so `syncPanelDOM` will correctly render path text and loaded class.
3. **onInitConfig:** Make the callback `async` and `await` both `loadFile` calls, matching the pattern already used in `onInitFiles`. The drop handler: add `await` and make the arrow function `async`.

---

## #8 — Nav Test Hardening + global-setup Deduplication

### Problems
1. `p key decrements counter` asserts `after !== before` but does not verify the counter went down. A buggy `prevDiff` that increments instead of decrementing would pass.
2. `ELECTRON_BIN` and `APP_PATH` are copy-pasted between `helpers.js` and `global-setup.js`. One source of truth, two definitions.

### Design

1. **Direction assertion:** After pressing `p`, parse the counter's first number and assert `afterNum < beforeNum`:
   ```js
   const beforeNum = parseInt(before.split(' / ')[0]);
   const afterNum  = parseInt(after.split(' / ')[0]);
   expect(afterNum).toBeLessThan(beforeNum);
   ```
2. **Deduplication:** Move `ELECTRON_BIN` and `APP_PATH` from `global-setup.js` to `helpers.js`, export them. `global-setup.js` imports them from `helpers.js`. The `_electron` import stays in both files — each uses it independently.

---

## #10 — Diff-Summary Test Specificity + Tokenize Unit Tests

### Problems
1. The `summary shows breakdown` regex `/[~−+]\d/` matches any symbol followed by a single digit — too loose. It passes for `~0` or any garbage string containing one of those chars.
2. No unit-level tests for `tokenize` — the core splitting function has no isolated coverage.

### Design

1. **Regex tightening:** Assert the full string structure. `text` must match the pattern of one or more summary parts with no extraneous content:
   ```js
   expect(text).toMatch(/^(~\d+)?( ?−\d+)?( ?\+\d+)?$/);
   expect(text.length).toBeGreaterThan(0); // at least one part present
   ```
   The `sum()` helper in the swap test already correctly uses `.match(/\d+/g)` which handles multi-digit numbers — no change needed there.

2. **Tokenize unit tests:** Added to `diff-summary.spec.js` as a new nested `describe('tokenize unit')` block, using `window.evaluate`:
   - Basic word + space: `'hello world'` → `[{text:'hello',word:true,start:0,end:5},{text:' ',word:false},{text:'world',word:true,start:6,end:11}]`
   - Non-word tokens have no `start`/`end` properties
   - Leading/trailing whitespace produces non-word tokens

---

## #12 — Word-Diff Test Specificity + Tokenize Edge Cases

### Problems
1. Word-diff tests use `toBeGreaterThan(0)` — confirm presence but don't verify correctness. A bug that marks every word would pass.
2. `tokenize` has no edge case coverage: empty string, whitespace-only, punctuation-attached-to-word.

### Design

1. **Fixture-based word assertions:** Use known fixture content to verify specific words:
   - "evaluates" appears in panel A's fixture but not B — it must be inside a `<mark.diff-word-a>` element.
   - "rule" appears in both A and B unchanged — it must NOT be inside any `<mark>` element in a diff block.
   These assertions make the test sensitive to regressions in the LCS word diff, not just presence of any highlighting.

2. **Tokenize edge cases:** Added to `word-diff.spec.js` as `describe('tokenize edge cases')`:
   - `tokenize('')` → `[]`
   - `tokenize('  ')` → `[{text:'  ',word:false}]`
   - `tokenize('hello,')` → `[{text:'hello,',word:true,start:0,end:6}]` (punctuation attached to word is part of the word token, since `\S+` is the match pattern)

---

## Protocol Coherence

- `playwright-jvm-warmup.md`: None of these changes touch the warmup block in `global-setup.js`. The `ELECTRON_BIN`/`APP_PATH` deduplication moves constants only. ✅
- No Flyway migrations, no platform-level architecture changes.
