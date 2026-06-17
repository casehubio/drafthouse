# Design: UI Batch + Document Sets

**Date:** 2026-06-17
**Issues:** #63 (keyboard shortcuts overlay), #65 (export debate summary), #59 (multi-document working sets)
**Branch:** `issue-58-diff-legend-and-ui-batch`

---

## #63 — Keyboard Shortcuts Help Overlay

### What

Press `?` to toggle a modal overlay listing all keyboard shortcuts. Press `?` again or `Escape` to dismiss.

### Design

The overlay is a plain HTML element in the workspace shell (`index.html`), not a Web Component panel — it is transient chrome, not a composable panel.

**Shortcuts listed:**

| Key | Action |
|-----|--------|
| `n` | Next diff |
| `p` | Previous diff |
| `Cmd/Ctrl+S` | Toggle scroll sync |
| `?` | Show/hide shortcuts |

**Behaviour:**
- Semi-transparent dark backdrop (`z-index: 999`), centered card (`z-index: 1000`) with Archive Room aesthetic
- The keyboard listener in `index.html` gains a `?` handler that toggles a `hidden` class on the overlay element
- Dismiss on `?`, `Escape`, or clicking the backdrop
- Overlay starts hidden
- All keyboard handlers (`?`, `n`, `p`, `Cmd+S`) suppress when focus is inside `INPUT`, `TEXTAREA`, `SELECT`, or a `[contenteditable]` element — the existing guard only checks `INPUT`, which is insufficient

**Files changed:**
- `index.html` — add overlay HTML element, `?` keyboard handler, fix input focus guard for all handlers
- `styles.css` — add overlay and backdrop styles

No backend changes.

---

## #65 — Export Debate Summary to Markdown

### What

A new MCP tool `export_debate_summary` writes the current debate summary to a markdown file on disk.

### Design

**MCP tool signature:**

```
export_debate_summary(debateSessionId: String, outputPath: String) → String
```

**Behaviour:**
- Projects current state via `projectionService.project(session.channelId(), debateProjection)` and renders via `debateProjection.render(result)` — the same render path as `get_debate_summary`, which handles the empty-state sentinel ("No debate activity yet.") before delegating to `SummaryRenderer`
- Appends active selection context if present (same logic as `get_debate_summary`)
- Appends working set section if documents exist (same logic as `get_debate_summary` — added as part of #59, which ships before this tool)
- Writes to `outputPath` via `Files.writeString()`
- Creates parent directories if missing (`Files.createDirectories`)
- Overwrites if file exists — MCP tools are non-interactive
- Byte count: computed from the rendered content via `content.getBytes(StandardCharsets.UTF_8).length` before writing
- Returns `{"status":"exported","path":"<absolute path>","bytes":<N>}` on success
- Returns `"error: ..."` on failure (consistent with existing tool error convention)

**Files changed:**
- `DebateMcpTools.java` — add `export_debate_summary` tool method

No REST endpoints. No UI changes. The MCP tool is the right surface — the browser doesn't have access to the projection service.

---

## #59 — Multi-Document Working Sets

### What

The debate session tracks a collection of related documents. The diff viewer stays two-pane — the session owns which pair is currently compared. LLM agents manage the set via MCP tools; the browser responds to SSE events.

### Domain Model

All in `server/api/` (pure Java, no framework deps).

**`DocumentSet`** — new class:

```java
public class DocumentSet {
    // Thread-safe ordered list
    private final CopyOnWriteArrayList<DocumentEntry> documents;
    private volatile ComparisonPair currentComparison;

    public record DocumentEntry(String path, String label) {
        public DocumentEntry {
            Objects.requireNonNull(path, "path");
            if (path.isBlank()) throw new IllegalArgumentException("path must be non-blank");
            Objects.requireNonNull(label, "label");
        }
    }
    public record ComparisonPair(String pathA, String pathB) {}

    public boolean add(String path, String label);   // returns false if path already exists
    public boolean remove(String path);              // returns false if not found
    public List<DocumentEntry> documents();           // defensive copy
    public Optional<DocumentEntry> primary();         // first document, or empty
    public void setComparison(String pathA, String pathB);
    public void clearComparison();
    public ComparisonPair currentComparison();

    public static DocumentSet copyOf(DocumentSet source);  // deep copy for restart_from_round
}
```

**Input validation:** `DocumentEntry` validates path (non-null, non-blank) and label (non-null) in its compact constructor — follows the `SelectionScope` pattern. With this validation, `primary().isPresent()` guarantees a usable path. `requireSpecPath()` simplifies to `primary().orElseThrow(...).path()` with no secondary null/blank check.

**Path uniqueness:** `add()` rejects duplicate paths — returns `false` if a document with the same path already exists. To change a label, remove and re-add.

**`DebateSession` changes:**
- Remove `specPath` field
- Add `DocumentSet documentSet` field (initialised in constructor)
- `specPath()` becomes a derived accessor: `documentSet.primary().map(DocumentEntry::path).orElse(null)` — preserves null semantics for callers
- `start_debate(specPath)` adds the spec as the first document with label `"spec"` internally — no MCP tool signature change
- `start_debate` does NOT set an initial comparison — the working set begins with one document and no active pair. `?a=&b=` query params drive the browser until `set_comparison` is called
- Constructor gains an optional `DocumentSet` parameter for `restart_from_round` to pass a deep copy

**`specPath` migration — affected files:**
- `AbstractDebateSubAgentHandler.requireSpecPath()` — null guard changes from `specPath == null || specPath.isBlank()` to handling empty `Optional` from `documentSet.primary()`
- `DeepAnalysisHandler.prepareTask()` — calls `requireSpecPath(session)`, no signature change
- `VerifyHandler.prepareTask()` — calls `requireSpecPath(session)`, no signature change
- `DebateEventResource.SessionInfo` — `s.specPath()` still works via derived accessor
- `DebateEventResource.activeSessions()` — no change needed, uses `s.specPath()`
- `DebateMcpTools.startDebate()` — changes from `new DebateSession(..., specPath)` to creating session then calling `session.documentSet().add(specPath, "spec")`
- `DebateMcpTools.restartFromRound()` — changes from `new DebateSession(..., original.specPath())` to `new DebateSession(..., DocumentSet.copyOf(original.documentSet()))`

**Test migration:**
- `DebateSessionTest.java` — 9 constructor call sites use the 4-arg `(channelId, sessionId, name, specPath)` form. The getter test at line 127 (`assertThat(session.specPath()).isEqualTo("my-spec.md")`) must verify the derived path via `documentSet.primary()`.
- `DebateMcpToolsTest.java` — `sessionFor()` helper (line 870) constructs `new DebateSession(..., "spec.md")`. All tests flow through this helper.
- `VerifyHandlerTest.java` — 3 constructor call sites (lines 76, 86, 96) with different specPath values. The `throws_on_null_specPath` test (line 84) constructs with `null` specPath and asserts `.hasMessageContaining("specPath")` — this assertion must update if the error message changes to reference `DocumentSet` or `primary`.

### MCP Tools

Four new tools on `DebateMcpTools`:

| Tool | Args | Returns |
|------|------|---------|
| `add_document` | `debateSessionId, path, label` | `{"status":"added","documentCount":<N>}` or error if path already in set |
| `remove_document` | `debateSessionId, path` | `{"status":"removed","documentCount":<N>}` or error if primary |
| `list_documents` | `debateSessionId` | `{"documents":[{path,label}], "currentComparison":{pathA,pathB}\|null}` |
| `set_comparison` | `debateSessionId, pathA, pathB` | `{"status":"set","pathA":"...","pathB":"..."}` or error if path not in set |

- `add_document` pushes `documents-changed` SSE metadata event
- `remove_document` pushes `documents-changed` SSE metadata event; if the removed path is one side of the current comparison, clears the comparison and pushes `comparison-changed` with `{"pathA":null,"pathB":null}`
- `set_comparison` pushes `comparison-changed` SSE metadata event
- Both paths in `set_comparison` must be in the document set — error otherwise

### REST + SSE

On `DebateEventResource`:

- `GET /api/debate/{id}/documents` — returns document list + current comparison (for browser initial load after session connect)
- `POST /api/debate/{id}/comparison` — browser-initiated comparison change. Accepts `{pathA, pathB}`, updates `DocumentSet.currentComparison()`, pushes `comparison-changed` SSE metadata event. Same pattern as existing `POST /api/debate/{id}/selection`. The MCP `set_comparison` tool and this REST endpoint share mutation logic on `DocumentSet`.
  - Session not found → 404 (`NotFoundException`)
  - Path not in document set → 400 with `{"error":"path not in document set: <path>"}`
  - `pathA == pathB` → allowed (the diff viewer shows no differences — correct and harmless; the LLM may have a reason)

**JSON shape for `list_documents` and `GET /api/debate/{id}/documents`** (canonical, shared by both surfaces):
```json
{"documents":[{"path":"/a.md","label":"spec"}], "currentComparison":{"pathA":"/a.md","pathB":"/b.md"}}
```
`currentComparison` is `null` when no comparison is set.

Two new SSE metadata event types via the existing `DebateEventBus`:

- `documents-changed` — payload: full document list array
- `comparison-changed` — payload: `{pathA, pathB}` (both null when cleared) — browser reloads diff panel

**Co-firing:** `remove_document` can push both events on a single call — `documents-changed` (always) then `comparison-changed` (only when the removed path was in the current comparison). These are independent events, not alternatives. The browser must handle each independently.

### Browser Changes

In `index.html` shell:

- Listen for `comparison-changed` on `DebateEventBus.onMeta()` subscriber
- When received with non-null paths, call `diffPanel.loadFile('a', pathA)` and `diffPanel.loadFile('b', pathB)`
- When received with null paths (comparison cleared), no auto-load — browser keeps current view
- When a debate session connects, fetch `GET /api/debate/{id}/documents`. If a current comparison exists, auto-load that pair (supersedes `?a=&b=` query params when a session is active). If no comparison is set, `?a=&b=` query params win.

Document list UI in topbar:

- Small badge showing document count (e.g., `3 docs`) — visible only when a debate session is connected and has >1 document
- Click opens a dropdown listing all documents with labels
- Each document has A/B assignment buttons — clicking loads that path into the chosen slot and calls `POST /api/debate/{id}/comparison` to sync back to the session
- Informational — the LLM agent is the primary driver of document management

### Summary Integration

`DebateMcpTools.getDebateSummary()` appends a "Working Set" section after the rendered state, same pattern as the existing "Active Selection" section:

```markdown
## Working Set
- **spec** — `/path/to/spec.md`
- **implementation** — `/path/to/impl.java`
- **test** — `/path/to/test.java`

**Comparing:** spec ↔ implementation
```

`SummaryRenderer` is unchanged — stays pure, takes `ReviewState`, no knowledge of documents.

### What Doesn't Change

- `SummaryRenderer` — pure fold-state renderer
- Selection scope — still A-side/B-side with text
- Diff panel public API — `loadFile(panel, path)` unchanged
- `?a=&b=` query params — still work for standalone use without a debate session
- Sub-agent handlers — `requireSpecPath()` changes source (derives from `DocumentSet.primary()`) but not behaviour; null guard adapts from string null/blank to empty `Optional`
- `DebateEventResource.SessionInfo` — uses `s.specPath()` derived accessor, no field change needed

---

## Implementation Order

1. **#63** — Keyboard shortcuts overlay (browser-only, no deps)
2. **#59** — Multi-document working sets (domain model + MCP tools + REST + SSE + browser UI)
3. **#65** — Export debate summary (ships after #59 so the working set section is available from day one)
