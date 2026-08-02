# casehub-drafthouse — Contributor Guide

> Internal architecture, extension points, and development details for platform contributors working on DraftHouse internals.

**GitHub:** [casehubio/drafthouse](https://github.com/casehubio/drafthouse)

---

## Internal Architecture

```
Quarkus Server (drafthouse-server-runner.jar)
  ├── GET /api/ping          ← health check
  ├── GET /api/file?path=    ← read any local file
  ├── WS  /api/ws            ← WebSocket push (debate events, session lifecycle, file changes — pages wire format)
  ├── GET /                  ← Quinoa serves bundled webui (TypeScript → app.js)
  ├── MCP tools (review)     ← start_review, update_selection, query_review, end_review, list_reviewers, get_reviewer_instructions
  ├── MCP tools (debate)     ← start_debate, raise_point, respond_to, flag_human, get_debate_summary, end_debate, report_context
  ├── MCP tools (documents)  ← add_document, remove_document, list_documents, set_comparison, export_debate_summary
  ├── MCP tools (workspace)  ← load_workspace (replay completed workspaces OR watch in-progress reviews via WorkspaceWatcher)
  ├── MCP tools (brainstorm) ← start_brainstorm, present_options, update_option, set_recommendation, mark_eliminated, mark_selected, end_brainstorm
  └── WS  /api/terminal     ← PTY-over-WebSocket (pty4j) — terminal sessions for brainstorming mode
```

---

## Module Details

### server/api/ — `casehub-drafthouse-api`

Pure Java domain model. Depends on `casehub-blocks` (context tracking, message meta, bounded projection) and `qhorus-api`. Key types:

- `debate/` package — `DebateSession`, `DebateSessionSnapshot`, `DebateSessionStore` SPI, `DocumentEntry`, `ComparisonPair`, `ResolvedReviewer`
- `EntryType` — RAISE, AGREE, COUNTER, DISPUTE, QUALIFY, FLAG_HUMAN, DECLINED, VERIFIED, DEFERRED, MEMO, SUB_TASK_*, RESTART_CONTEXT, ROUND_SNAPSHOT, COMMENT, HUMAN_OVERRIDE, REPRIORITISE
- `AgentType` — REV, IMP, SUPERVISOR, MODERATOR, SELECTOR, HUMAN
- `SnapshotSource` (sealed), `DocumentSnapshot`, `DocumentTimeline`
- `BrainstormSession`, `BrainstormOption`

### server/runtime/ — `casehub-drafthouse`

Quarkus 3.34.3 app. Key components:

- **Resources:** Ping, File, Ui, HumanActionResource, DebateEventResource, BrainstormResource
- **MCP tools:** DraftHouseMcpTools, DebateMcpTools, BrainstormMcpTools
- **Services:** BrainstormService, BrainstormSessionRegistry, DebateSessionRegistryImpl, ReviewSessionRegistryImpl
- **Channel backends:** ReviewerChannelBackend/Factory, DebateChannelBackend/Factory
- **Reviewer system:** DraftHouseReviewerRegistry, ReviewerDescriptorSeeder (4 personas), ReviewerResolver, SimplePromptRenderer, DocumentReviewer
- **Debate persistence:** `DebateSessionStore` SPI — `JpaDebateSessionStore` (activated by `casehub.drafthouse.persistence.enabled=true` via `@IfBuildProperty`), `NoOpDebateSessionStore` (`@DefaultBean` in-memory fallback)
- **WebSocket:** DebateWebSocket, WebSocketEventBus
- **Workspace replay:** WorkspaceParser, WorkspaceReplayAdapter, WorkspaceWatcher, ProgressLogParser
- **Platform agent:** PlatformDebateAgentProvider
- **Terminal:** TerminalEndpoint (PTY via pty4j)

### server/claude-agent/

Optional module — `ClaudeAgentSdkDebateAgentProvider` (AgentProvider-backed, displaces PlatformDebateAgentProvider).

### Frontend (server/runtime/src/main/webui/)

TypeScript webui built with Quinoa. casehub-pages workbench with Lit (LitElement) panels:

| Panel | Element | Role |
|-------|---------|------|
| Diff viewer | `<document-diff>` | Two-panel markdown diff with LCS line diff, word-level highlights, canvas minimap, scroll sync |
| Channel feed | `<channel-feed>` | Debate event conversation feed (pages-event subscriber) |
| Review tracker | `<review-tracker>` | Review point status checklist with HIL actions |
| Context gauge | `<context-gauge>` | Topbar context usage gauge (normal/warn/error states) |
| Doc picker | `<doc-picker>` | Topbar document badge dropdown for A/B slot assignment |
| Timeline | `<document-timeline>` | Document version timeline strip above diff panel |
| Workspace status | `<workspace-status>` | Topbar live workspace watching progress |
| Brainstorm options | `<brainstorm-options>` | Interactive option cards with status, actions, convergence summary |
| Brainstorm picker | `<brainstorm-picker>` | Topbar session switcher dropdown |
| Terminal | `<pages-component-terminal>` | xterm.js terminal (from @casehubio/pages-component-terminal, brainstorm mode only) |

---

## Key SPIs and Extension Points

| SPI | Purpose |
|-----|---------|
| `ReviewSessionRegistry` | Storing and retrieving active review sessions |
| `DebateSessionStore` | Pluggable debate session persistence — `save`, `load`, `remove`, `loadAll` |
| `AgentRegistry` (Eidos) | Multi-LLM reviewer registry — `DraftHouseReviewerRegistry` implements this |

### Reviewer Personas (seeded at startup)

| Agent ID | Slot | Focus |
|----------|------|-------|
| `drafthouse-structural-reviewer` | `document-reviewer` | Structural integrity |
| `drafthouse-content-reviewer` | `document-reviewer` | Accuracy, evidence |
| `drafthouse-readability-reviewer` | `document-reviewer` | Clarity, prose |
| `drafthouse-completeness-reviewer` | `document-reviewer` | Coverage, edge cases |

---

## Depended On By

Nothing in the casehubio ecosystem — application tier only.

---

## Current State

- Two-module Maven project (`server/api/` + `server/runtime/`) — restructured in drafthouse#21
- `casehub-qhorus 0.2-SNAPSHOT` dependency wired
- `casehub-eidos-api` wired for multi-LLM reviewer registry
- `quarkus-langchain4j-anthropic 1.9.1` added for Phase 2 LLM reviewer integration
- Brainstorming MCP tools, debate persistence, context tracking, and export tools operational
- No deployed production instances

---

## Recent Features

- **Quinoa integration (drafthouse#74)** — casehub-pages workbench via Quinoa frontend build. DebateEventBus migrated to `pages-event` CustomEvent.
- **WebSocket real-time updates (drafthouse#88)** — replaced SSE with WebSocket push. Reconnection with exponential backoff (max 60s).
- **Section highlighting (drafthouse#90)** — clicking review points scrolls to and highlights corresponding document sections. CSS `::part()` styling.
- **Replay adapter (drafthouse#95)** — replays design-review workspaces from CLAUDE.md snapshots into debate channels.
- **Document timeline (drafthouse#98)** — version navigation UI across review rounds.
- **Brainstorming MCP tools** — session model with 7 MCP tools and terminal endpoint.
- **Multi-LLM reviewer registry** — 4 reviewer personas via Eidos AgentRegistry.
- **Debate session persistence** — pluggable `DebateSessionStore` SPI with JPA and in-memory backends.
- **Context meter UI** — `report_context` MCP tool with `<context-gauge>` web component.
- **Export debate summary** — `export_debate_summary` MCP tool writes debate projection to markdown.

---

## Design Documents

- **Research spec:** `docs/superpowers/specs/2026-05-26-document-review-tool-research.md`
- **Feature backlog:** `docs/FEATURES.md`
- **Architecture record:** `ARC42STORIES.MD` (Arc42Stories v0.1, CaseHub Application tier profile)
- [CLAUDE.md](https://raw.githubusercontent.com/casehubio/drafthouse/main/CLAUDE.md) — stack, module coordinates, key design decisions
