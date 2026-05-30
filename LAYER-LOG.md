# LAYER-LOG — DraftHouse

Architecture record of what was built at each integration layer. Each entry is complete
when the layer closes.

**Migration note:** This file will migrate to `ARC42STORIES.MD §9.4` Layer Entries when
that document is bootstrapped. See `../parent/docs/arc42stories-spec.md` and
`../parent/docs/arc42stories-casehub-profile.md`.

---

## Layer 0 — Scaffold and Infrastructure

**Started:** 2026-05-26
**Completed:** 🔲

### Summary
Migrated from `mdproctor/md-compare` to `casehubio/drafthouse`. Removed Electron
shell (browser-only UI). Renamed all artifacts to `io.casehub.drafthouse`. Integrated
with CaseHub parent BOM, CI, dashboards, and website.

### Accountability gaps closed
| Gap | What breaks without it | Closed by |
|-----|----------------------|-----------|
| No CaseHub identity | Can't use foundation modules (Qhorus, LangChain4j) | Parent POM + BOM registration |
| Electron dependency | Requires npm/Sparge for dev and test | Quarkus-only architecture |

### Key wiring
- `ui.dir` JVM property tells UiResource where to find `index.html` and `styles.css`
- URL query params `?a=<path>&b=<path>` replace Electron IPC for initial file loading
- Relative API URLs replace `http://127.0.0.1:${port}` — same-origin serving

### Architectural decisions
Dropped Electron in favour of browser-based UI served by Quarkus. This eliminates
npm, Sparge dependency, and the process manager. Trade-off: no native file dialog —
replaced with `prompt()` for now, but the MCP tool surface will be the primary way
to load documents.

### Pattern introduced
Browser-served Quarkus UI with URL query param initialization.

### Pattern anchor
`UiResource.java` — `serveFile()` method serves static assets from `ui.dir`.

### Gotchas
🔲

### Pattern to replicate
1. Serve HTML/CSS from Quarkus via a catch-all resource with configurable root dir
2. Use relative API URLs in the frontend (no port configuration needed)
3. Pass initial state via URL query params instead of IPC

### Navigation
`git log --grep="#15" --oneline`
