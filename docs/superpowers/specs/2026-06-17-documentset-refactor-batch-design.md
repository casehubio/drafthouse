# DocumentSet Refactor Batch — Design Spec

**Issue:** #67
**Scale:** S | **Complexity:** Low

Three code-quality fixes from code review on #59.

## Item 1 — DocumentSet thread safety

**Problem:** `DocumentSet.add()` iterates `CopyOnWriteArrayList` to check for duplicates, then adds — not atomic. Two concurrent adds of the same path can both pass the check. `remove()` has a similar read-modify-write race: it removes from the list then conditionally clears `currentComparison`, but another thread could interleave. See GE-20260610-98066a.

**Fix:** Replace `CopyOnWriteArrayList` + `volatile ComparisonPair` with a plain `ArrayList` + `ComparisonPair` guarded by `synchronized(this)` on all instance methods. Document sets are small (2-5 entries); a synchronized ArrayList is simpler and correct.

Synchronized instance methods: `add`, `remove`, `setComparison`, `clearComparison`, `documents`, `primary`, `currentComparison`.

`copyOf` is a static method — it synchronizes on `source` (the argument), not `this`. This acquires the source's monitor to get a consistent snapshot of both the document list and comparison pair.

**Caller-level atomicity:** Internal synchronization makes each DocumentSet operation atomic, but compound caller sequences are not atomic. For example, `DebateMcpTools.removeDocument()` reads `currentComparison()`, calls `remove()`, reads `currentComparison()` again, then conditionally pushes an event — another thread could `setComparison()` between the `remove()` and the second `currentComparison()` read, causing a spurious `pushComparisonChanged(null)`. This is acceptable because MCP tool calls serialize per-session in practice. The spec does not attempt to provide caller-level atomicity.

## Item 2 — Triplicated JSON serialization

**Problem:** Document-list JSON is built in three places:
1. `DebateEventResource.pushDocumentsChanged()` (line 66) — manual `escapeJson()` with `\"`-wrapped values
2. `DebateEventResource.getDocuments()` (line 243) — same manual pattern, includes `currentComparison`
3. `DebateMcpTools.buildDocumentsJson()` (line 660) — uses `jsonString()` helper (handles null, wraps quotes)

The core escaping logic is identical across all three sites (escapes `\ " \n \r \t`). The difference is only null handling and quote wrapping. This is a duplication problem, not a correctness bug.

**Fix:** Create `DocumentSetJson` — a package-private utility class in `server/runtime/` (where all three call sites live). `DocumentSet` is in `server/api/`, the pure-Java domain module whose contract is "ports and types only; no framework deps" (ARC42STORIES §5). JSON serialization is a transport concern and does not belong in the domain model.

`DocumentSetJson` contains:
- `static String documentsToJson(List<DocumentSet.DocumentEntry> docs)` — serializes the document list as a JSON array
- `static String comparisonToJson(DocumentSet.ComparisonPair cp)` — serializes the comparison pair as a JSON object, or the string `null`
- `static String documentsAndComparisonToJson(DocumentSet documentSet)` — full response shape (`{"documents":[...],"currentComparison":...}`)
- `private static String escapeAndQuote(String s)` — the consolidated escape+quote helper (null → `null`, non-null → `"escaped"`)

Call site replacements:
- `DebateEventResource.pushDocumentsChanged()` → `"{\"type\":\"documents-changed\",\"documents\":" + DocumentSetJson.documentsToJson(documentSet.documents()) + "}"`
- `DebateEventResource.getDocuments()` → `DocumentSetJson.documentsAndComparisonToJson(session.documentSet())`
- `DebateMcpTools.listDocuments()` → `DocumentSetJson.documentsAndComparisonToJson(session.documentSet())` (replaces call to deleted `buildDocumentsJson()`)
- `DebateMcpTools.buildDocumentsJson()` — deleted entirely

`DebateEventResource.escapeJson()` remains for non-document JSON (selection events, context snapshots). `DebateMcpTools.jsonString()` remains for MCP tool response strings.

## Item 3 — Private `_panels` access from index.html

**Problem:** `index.html:251-252` accesses `diffPanel._panels.a.path` — reaching into the Web Component's private state, breaking the `@casehub/ui` Component encapsulation contract.

**Fix:** Add `currentPath(slot)` to `drafthouse-diff.js`:
```js
currentPath(slot) {
  return this._panels[slot]?.path || null;
}
```

Replace in `index.html` (preserving the ternary context):
```js
// Before
const currentA = slot === 'a' ? doc.path : (diffPanel._panels?.a?.path || '');
const currentB = slot === 'b' ? doc.path : (diffPanel._panels?.b?.path || '');

// After
const currentA = slot === 'a' ? doc.path : (diffPanel.currentPath('a') || '');
const currentB = slot === 'b' ? doc.path : (diffPanel.currentPath('b') || '');
```

## Files changed

| File | Change |
|------|--------|
| `server/api/.../DocumentSet.java` | `synchronized` on all instance methods; `copyOf` synchronizes on `source`; replace `CopyOnWriteArrayList` with `ArrayList`, drop `volatile` |
| `server/runtime/.../DocumentSetJson.java` | **New** — package-private utility; document list + comparison JSON serialization |
| `server/runtime/.../DebateEventResource.java` | `pushDocumentsChanged()` and `getDocuments()` delegate to `DocumentSetJson` |
| `server/runtime/.../DebateMcpTools.java` | Delete `buildDocumentsJson()`; `listDocuments()` calls `DocumentSetJson` |
| `panels/drafthouse-diff.js` | Add `currentPath(slot)` public accessor |
| `index.html` | Replace `diffPanel._panels?.a?.path` with `diffPanel.currentPath('a')` |

## Testing

**Thread safety** — extend existing `DocumentSetTest` with a concurrent-add test: `ExecutorService` with N threads all calling `add()` with the same path simultaneously, assert final `documents().size() == 1`. Also test concurrent add + remove interleaving to verify no `ConcurrentModificationException` or lost updates.

**JSON serialization** — new `DocumentSetJsonTest` in `server/runtime/`: verify JSON output for a document set with documents and comparison, without comparison, with paths containing characters that need escaping (`"`, `\n`, `\`), and with null paths in `ComparisonPair`. This logic was previously inline in JAX-RS methods and untested.

**Existing coverage** — E2E tests cover document switching and the browser dropdown. No regression expected from the accessor change.
