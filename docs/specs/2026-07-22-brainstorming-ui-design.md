# Brainstorming UI — Interactive Option Cards

**Issue:** #53 (part of epic #84)
**Date:** 2026-07-22
**Status:** Draft

## Context

DraftHouse is a collaborative document authoring tool. The diff viewer, debate
panel, and review tracker make document improvement with Claude visible and
interactive in a browser. Brainstorming is one workflow within this — the
design-review workflow is already built.

Slices 1-2 (#53) delivered the brainstorming backend: `BrainstormSession` and
`BrainstormOption` domain model, `BrainstormMcpTools` (7 MCP tools),
`WebSocketEventBus` brainstorm event support, `TerminalEndpoint` (PTY-over-WebSocket),
and `buildBrainstormLayout()` in index.ts. The brainstorm mode currently shows
only a terminal panel — no visual rendering of the brainstorming process.

## Goal

Add a `<brainstorm-options>` panel that makes the brainstorming process visible
and interactive. Users see options as cards with status indicators, can
eliminate/select/recommend options by clicking, and watch convergence happen in
real time — while Claude Code drives the process from the terminal.

## Scope

**In scope:**
- `<brainstorm-options>` Lit panel with interactive option cards
- `BrainstormService` CDI bean (extracted from `BrainstormMcpTools`)
- REST endpoints for browser-initiated option actions
- Brainstorm session list endpoint + session switcher in topbar
- Layout wiring to show options panel alongside terminal
- Convergence visualization (status counts, visual card states)

**Deferred:**
- Free-text browser input (second conversation channel — needs its own design)
- First-principles deep-dive rendering (structured challenge mode — issue TBD)
- Unified multi-session navigation (tabs, sidebar — workbench-level, all session types)
- Pre-loaded brainstorming templates
- Guided flows beyond brainstorming (spec, plan, scaffold)

**Issue #53 scope:** This spec addresses items 1 and 3 from #53 (interactive option
cards, convergence visualisation). Item 2 (first-principles challenge mode) is
deferred to a follow-up issue. #53 remains open after this slice.

## Future Direction

DraftHouse's broader purpose is collaborative document authoring with Claude.
Brainstorming is one guided workflow; design review is another. The same panel
architecture could support business plan creation, compliance document drafting,
CaseHub application scaffolding, or any goal-directed document improvement.

One concrete target: making it easier for newcomers to build CaseHub applications.
A user opens a browser, describes what they want to build, and the visual experience
guides them through brainstorming to a validated design spec — without needing CLI
fluency. The platform knowledge (tiers, blocks-ui, qhorus, conventions) lives in
the brainstorming skill and platform docs, not the panel.

The panel we build now is generic — it renders options and handles state transitions.
Nothing in its architecture precludes other guided workflows.

## Design

### Domain Model

The existing state model covers everything the UI needs:

- **Option statuses:** LIVE, RECOMMENDED, EXPLORED, ELIMINATED, SELECTED
- **Session states:** ACTIVE, CONVERGED, ABANDONED

**State transition guards on `BrainstormOption`:** Replace the plain `setStatus()`
setter with a guarded transition method that enforces valid transitions:

| From | Allowed transitions |
|------|-------------------|
| LIVE | RECOMMENDED, EXPLORED, ELIMINATED, SELECTED |
| EXPLORED | RECOMMENDED, ELIMINATED, SELECTED, EXPLORED *(no-op)* |
| RECOMMENDED | EXPLORED *(recommendation cleared)*, ELIMINATED, SELECTED, RECOMMENDED *(no-op)* |
| ELIMINATED | *(terminal — no transitions)* |
| SELECTED | *(terminal — no transitions)* |

**Self-transitions** (EXPLORED → EXPLORED, RECOMMENDED → RECOMMENDED) are
idempotent no-ops — they do not throw. This is standard state machine behaviour
and preserves the `update_option` MCP tool's existing pattern of setting
EXPLORED on every call (iterative enrichment across multiple passes).

**SELECTED from any non-terminal state:** Selection is the convergence decision.
`BrainstormSession.markSelected()` currently works on any option regardless of
status. The transition table preserves this — requiring RECOMMENDED before
SELECTED would force a workflow that doesn't exist today.

Invalid transitions throw `IllegalStateException`. This prevents contradictory
state (e.g. ELIMINATED → RECOMMENDED) regardless of whether the mutation comes
from MCP tools or browser REST endpoints.

**Single recommendation:** `BrainstormSession` gains a `setRecommendation(optionId)`
method that clears any existing RECOMMENDED option before setting the new one.
Only one option can be RECOMMENDED at a time. When a recommendation is cleared,
the previously-recommended option reverts to EXPLORED — it has been considered
and evaluated, just not chosen. This uses the RECOMMENDED → EXPLORED transition
in the table above.

**Known limitation:** `BrainstormSessionRegistry` is in-memory (`ConcurrentHashMap`).
Server restart loses all brainstorm state. The debate system has `JpaDebateSessionStore`
for persistence; brainstorming has no equivalent. Acceptable for this UI-focused
iteration — persistence is a separate concern if longer-lived sessions are needed.

### BrainstormService Extraction

Extract a `BrainstormService` CDI bean that owns all mutation + event push logic.
Both `BrainstormMcpTools` and the new REST endpoints delegate to this service.

**Methods:**

| Method | Action |
|--------|--------|
| `startSession()` | Creates session, registers, broadcasts creation event, returns ID |
| `presentOptions(sessionId, options)` | Validates, adds options to session, pushes event |
| `updateOption(sessionId, optionId, description, tradeoffs)` | Updates option fields, pushes event |
| `setRecommendation(sessionId, optionId)` | Clears any existing RECOMMENDED option, sets this option to RECOMMENDED, pushes event |
| `markEliminated(sessionId, optionId)` | Sets option status to ELIMINATED (guarded — throws on terminal statuses), pushes event |
| `markSelected(sessionId, optionId)` | Sets option status to SELECTED, converges session (guarded — throws on non-ACTIVE sessions), pushes converged event |
| `endSession(sessionId)` | Abandons session, pushes event |

**Injected dependencies:** `BrainstormSessionRegistry`, `WebSocketEventBus`, `ObjectMapper`.

**Thread safety:** All `BrainstormService` methods that mutate session state
synchronize on the session object (`synchronized (session) { ... }`). With dual
mutation sources (MCP tool threads and JAX-RS request threads), the session's
mutable state (`ArrayList<BrainstormOption>`, `State`, `Instant lastActivity`)
requires synchronization. The session object is the natural lock scope — it's
small, short-lived, and contention is negligible for 2-4 options. Read-only
operations (e.g. catch-up in `sendBrainstormCatchUp`) also synchronize to get
a consistent snapshot.

`BrainstormMcpTools` becomes thin `@Tool` wrappers that parse JSON args and delegate.
Error handling pattern: service methods throw on invalid state; MCP tools catch and
return `"error: ..."` strings per protocol; REST endpoints catch and return HTTP status codes.

### REST Endpoints

New `BrainstormResource` JAX-RS class for browser-initiated actions:

| Endpoint | Delegates to |
|----------|-------------|
| `PATCH /api/brainstorm/{sessionId}/options/{optionId}` | `BrainstormService` — routes by status in body |

Request body: `{"status": "ELIMINATED"}`, `{"status": "RECOMMENDED"}`, or
`{"status": "SELECTED"}`. Only these three statuses are accepted from the
browser — EXPLORED is MCP-only (content enrichment via `update_option`) and
LIVE is initial state (no reverting). Other values return 400.

Single endpoint, consistent with the noun-based REST style used by debate
endpoints (`POST /api/debate/{id}/selection`, `POST /api/debate/{id}/comparison`).

**Response codes:**
- 200 with JSON status on success
- 404 if session or option not found
- 409 Conflict if the state transition is invalid (e.g. ELIMINATED → RECOMMENDED)

**Error handling:** `BrainstormResource` catches `IllegalStateException` from
service methods and returns 409. `IllegalArgumentException` returns 400.
`NotFoundException` wraps missing session/option lookups.

**Terminal notification:** After a successful browser mutation, the REST endpoint
pushes a `brainstorm-user-action` event via `WebSocketEventBus.broadcast()`.

Event payload:
```json
{
  "sessionId": "bs-...",
  "optionId": "A",
  "optionTitle": "Event sourcing approach",
  "action": "ELIMINATED"
}
```

The `terminal-inject` bridge in `index.ts` (already wired for brainstorm mode)
listens for `brainstorm-user-action` events and constructs a terminal message:
`"[User eliminated 'Event sourcing approach' via browser]"`. This ensures Claude
Code's conversation context reflects browser-initiated state changes. The MCP
tool response on the next Claude Code call will also reflect the updated state,
providing belt-and-suspenders synchronization.

Only status changes — `startSession`, `presentOptions`, `updateOption`, and
`endSession` are LLM-driven operations that don't belong on browser buttons.

### `<brainstorm-options>` Panel

Lit panel following the same patterns as `<channel-feed>` and `<review-tracker>`:

**Event subscription:**
- `brainstorm-options` — full option list with statuses, pushed on every state change
- `brainstorm-converged` — session converged with selected option ID
- `brainstorm-session-created` — broadcast when a new session starts (panel auto-connects)

**Rendering:**
- Each option rendered as a card: title, description, tradeoffs, status badge
- Action buttons per card: Eliminate, Recommend, Select
- Each button does a `fetch()` PATCH to `/api/brainstorm/{sessionId}/options/{optionId}`
  with the appropriate `{"status": "..."}` body
- Convergence summary at top: "3 options, 1 eliminated, 1 recommended"

**Visual status treatment:**
- LIVE: default card appearance
- RECOMMENDED: highlighted border/accent
- ELIMINATED: dimmed, struck-through title, actions disabled
- SELECTED: prominent "chosen" treatment, other cards dimmed
- EXPLORED: subtle indicator (visited but no decision)

**Session lifecycle handling:**
- **Session ended** (`brainstorm-ended` event): Show "Session ended" banner,
  disable all action buttons, keep the last option state visible (frozen view).
- **Session removed** (REST returns 404): Show "Session no longer available"
  message, clear options display.
- **Reconnection** (`reconnected` event): Re-subscribe to the current session's
  brainstorm topic via WebSocket. The server sends catch-up state on
  re-subscribe (same pattern as debate panels).

**Conventions:** Shadow DOM, `static styles = css\`...\``, `_cleanups[]` array
for teardown in `disconnectedCallback()`, `@property`/`@state` decorators,
`configure(props)` for pages-runtime initialization.

### Session Switcher

A user running multiple brainstorming sessions (e.g., 20 design discussions across
different features) should not need 20 browser windows. Minimal version for this
iteration: a topbar dropdown that lists active brainstorm sessions.

**REST endpoint:** `GET /api/brainstorm/sessions` — returns list of active sessions
with ID, creation time, option count, and state. Delegates to
`BrainstormSessionRegistry.activeSessions()` (existing method).

**Session discovery on WebSocket connect:** `DebateWebSocket.onOpen()` sends a
`brainstorm-sessions` event listing active brainstorm sessions alongside the
existing `sessions` (debate) event. This enables auto-connect when a single
brainstorm session is running.

**UI:** A `<brainstorm-picker>` topbar element (same pattern as `<doc-picker>`).
Dropdown shows active sessions. Clicking one calls `connectBrainstormSession()`
(see Layout Wiring) to subscribe to that session's events. The terminal is
independent — it's the operator's Claude Code session, not tied to a specific
brainstorm.

**Picker event subscriptions** (keep the session list current):
- `brainstorm-session-created` — add the new session to the dropdown
- `brainstorm-ended` — remove the ended session from the dropdown
- `brainstorm-sessions` — replace the full list (on WebSocket reconnect/initial connect)

Existing infrastructure: `showSessionPicker()` in index.ts and
`GET /api/debate/sessions` handle the debate equivalent. Same shape.

The bigger problem — unified navigation across all session types (debates,
brainstorms, future workflows), with tabs or a sidebar — is a separate issue.

### Layout Wiring

Update `buildBrainstormLayout()` in `index.ts`:

```typescript
split("horizontal", [
  hostPanel("terminal", { wsUrl: terminalWsUrl }),
  hostPanel("brainstorm-options", {}),
], { ratio: [50, 50] })
```

**Panel registration and imports** (prerequisite for `hostPanel()` to render):

```typescript
import "./panels/brainstorm-options.js";
registerPanel("brainstorm-options", "brainstorm-options");
```

`<brainstorm-picker>` is a standalone custom element (like `<doc-picker>`) — it
needs a side-effect import (`import "./panels/brainstorm-picker.js";`) but not
a `registerPanel()` call since it's placed directly in the topbar HTML, not
hosted via the pages layout engine.

Add `<brainstorm-picker>` to the brainstorm topbar alongside the "Brainstorm" label.

**WebSocket subscription wiring:** Add `connectBrainstormSession(sessionId)` in
`index.ts`, mirroring the debate pattern in `connectDebateSession()`:

1. Unsubscribe from any previous brainstorm session topic
2. Subscribe to `brainstorm:<sessionId>` via `wsSource.subscribe()` — this
   triggers `DebateWebSocket.handleSubscribe()` which calls
   `eventBus.watchBrainstorm()` and sends catch-up state
3. Call `configure({ sessionId })` on the `<brainstorm-options>` panel

**Session lifecycle events in `index.ts`:** Listen for `pages-event` topics:
- `brainstorm-sessions` (from `onOpen`) — auto-connect if single session
- `brainstorm-session-created` (broadcast) — auto-connect to new session
- `brainstorm-user-action` — inject notification into terminal via the
  existing `terminal-inject` bridge

Layout is trivially reconfigurable via the pages DSL — ratio, orientation, and
panel arrangement are one-line changes.

## Component Summary

| Component | Module | Type |
|-----------|--------|------|
| `BrainstormService` | server/runtime | CDI bean (new) |
| `BrainstormResource` | server/runtime | JAX-RS resource (new) |
| `BrainstormMcpTools` | server/runtime | Refactor to delegate to service |
| `<brainstorm-options>` | webui/panels | Lit panel (new) |
| `<brainstorm-picker>` | webui/panels | Lit topbar element (new) |
| `buildBrainstormLayout()` | webui/index.ts | Layout update |

## Testing

- **BrainstormOption:** Unit tests for state transition guards — valid transitions succeed, invalid transitions throw `IllegalStateException`
- **BrainstormSession:** Unit tests for single-recommendation enforcement — setting a new recommendation clears the previous one
- **BrainstormService:** Unit tests for all mutation methods, error cases, event push verification, terminal notification dispatch
- **BrainstormResource:** Unit tests for REST endpoints — success (200), not found (404), invalid transition (409), invalid status (400)
- **BrainstormMcpTools:** Existing tests updated to verify delegation (behaviour unchanged)
- **`<brainstorm-options>`:** Playwright E2E — render options, click actions, verify status updates, session ended state, reconnection catch-up
- **`<brainstorm-picker>`:** Unit test — session list rendering, session switch triggers reconfigure
