# casehub-drafthouse — Consumer Guide

> MCP-driven document review tool — any LLM client can open documents, compare versions, create reviewer agents, and conduct grounded conversations about specific document regions.

**GitHub:** [casehubio/drafthouse](https://github.com/casehubio/drafthouse)
**Tier:** CaseHub Application

---

## Purpose

DraftHouse is an MCP-driven document review tool. Any LLM (Claude Code, Claudony, or any MCP client) can open a document, show before/after versions, create reviewer LLM agents, and have grounded conversations about specific document regions. Evolved from md-compare; promoted to the CaseHub application tier to leverage Qhorus channels and LangChain4j for provider-agnostic LLM calls.

---

## Module Structure

| Module | Artifact ID | Purpose |
|--------|-------------|---------|
| `server/api/` | `casehub-drafthouse-api` | Pure Java domain model — `ReviewSession`, `ReviewResult`, `DocumentSide`, `BrainstormSession`, `DebateSessionStore` SPI, `DebateSessionSnapshot`, `ResolvedReviewer` |
| `server/runtime/` | `casehub-drafthouse` | Quarkus app — MCP endpoints, Qhorus integration, LLM reviewer wiring, brainstorming tools, debate persistence, context tracking, terminal endpoint |

---

## Key Abstractions

| Concept | Role |
|---------|------|
| `ReviewSession` | A document review context: document sides (before/after), reviewer agents, grounded conversation |
| `DocumentSide` | One version of a document (before or after) within a review session |
| `ReviewResult` | Structured feedback from a reviewer agent |
| `BrainstormSession` | Brainstorming context: options with states (EXPLORED, RECOMMENDED, ELIMINATED, SELECTED), lifecycle (ACTIVE / CONVERGED / ABANDONED) |
| `DebateSessionSnapshot` | Serializable debate state: channel, documents, comparison, participants, agent ID |
| `ResolvedReviewer` | Resolved reviewer identity: `agentId`, `name`, `instructions` — produced by `ReviewerResolver` via Eidos `AgentRegistry` |

---

## MCP Tool Surface

**Brainstorming:** `start_brainstorm`, `present_options`, `update_option`, `set_recommendation`, `mark_eliminated`, `mark_selected`, `end_brainstorm`

**Debate:** `report_context`, `export_debate_summary`

**Replay:** `casehubio-drafthouse:replay-design-review`

**Planned (Phase 2):** `start_review`, `push_revision`, `get_cursor_context`, `get_diff`, `end_review`

---

## Channel Usage Pattern

DraftHouse uses a single APPEND channel per review session with QUERY/RESPONSE. This is idiomatic for a non-normative consumer — it does not apply the 3-channel NormativeChannelLayout (work/observe/oversight), which is Claudony's concern. QUERY/COMMAND from `casehub-qhorus`'s 9-type speech-act taxonomy are used for grounded review conversations.

---

## Dependencies

| Repo | Module | Nature |
|------|--------|--------|
| `casehub-qhorus-api` | `app` | `ChannelService`, `MessageService`, `ChannelGateway`, `DataService`, `InstanceService` — channel mesh SPIs |
| `casehub-qhorus` (runtime) | `runtime` | Channel mesh runtime — commitment lifecycle, typed messages |
| `casehub-eidos-api` | `runtime` | `AgentRegistry`, `AgentDescriptor`, `AgentQuery` — Eidos identity model for multi-LLM reviewer registry |
| `quarkus-langchain4j-anthropic 1.9.1` | `runtime` | LLM calls via `@AiService` for reviewer agents (Phase 2) |

---

## What This Repo Does NOT Do

- Provide general-purpose document storage (no document database — review state is in-memory or JPA when enabled)
- Implement consensus or voting across reviewers (each reviewer is independent)
- Know anything about git, PRs, or source control (`casehub-devtown` owns that domain)
- Implement audit trail, case orchestration, or agent identity — those are foundation concerns
