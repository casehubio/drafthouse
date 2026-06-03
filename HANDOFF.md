# Handover — 2026-06-03

**Branch:** `main` (clean)

## Last Session

Designed and implemented review manifest Layer 2 (#29): structured agent-to-agent
spec review loop. `SummaryProjector implements ChannelProjection<ReviewState>` (qhorus#230),
incremental fold (qhorus#231), `LangChain4jDebateAgentProvider @DefaultBean`,
`ClaudeAgentSdkDebateAgentProvider @Alternative @Priority(1)` stub,
`ReviewSessionService` (JGit). 95 tests passing. Delivered to upstream as 3 squashed
commits (111 → 3). Two universal protocols captured; platform#55 filed.

## Immediate Next Step

```
/work
```

## Cross-Module

**We're blocking:**
- `casehubio/qhorus` — qhorus#232 (`project_channel` MCP tool + `ProjectionRenderer<S>` SPI)
  depends on qhorus#230 (shipped). `SummaryRenderer` stays local until #232 ships. · S · Low

**Blocked by:**
- `casehubio/platform` — platform#55 (`casehub-platform-agent` module) required before
  `ClaudeAgentSdkDebateAgentProvider` stub can be implemented · M · Med

## What's Left

- #24 — DraftHouseMcpTools: `start_review`, `update_selection`, `end_review`
  (Layer 3 entry point for review sessions) · M · Low
- #25 — ReviewSessionLifecycleIT: assess whether to close or extend with H2 variant · XS · Low
- casehubio/parent#145 — PLATFORM.md Cross-Repo Dependency Map + APPLICATIONS.md
  (server/api now depends on casehub-qhorus-api; DraftHouse status → Active) · S · Low

## What's Next

| # | Description | Scale | Complexity | Notes |
|---|-------------|-------|------------|-------|
| #24 | DraftHouseMcpTools: start_review, update_selection, end_review | M | Low | Layer 3 entry point; ReviewSessionRegistry live |
| #27 | Qhorus DebateChannel — DebateChannel type, AGREE/QUALIFY sub-classification | M | Med | Gates Layer 3 Qhorus integration |
| #28 | Session storage path configurability | S | Low | Hardcoded `~/.drafthouse/reviews/` |
| #32 | Debate minor quality improvements (formatter sorting, Clock in renderer, etc.) | S | Low | Batched from code review |
| platform#55 | casehub-platform-agent: Claude Agent SDK Quarkus wrapper | M | Med | Unblocks ClaudeAgentSdkDebateAgentProvider real impl |
| qhorus#232 | ProjectionRenderer SPI + project_channel MCP tool | S | Low | Then SummaryRenderer can implement it |

## References

| Context | Where |
|---|---|
| Layer 1 spec | `docs/superpowers/specs/2026-06-01-review-manifest-design.md` |
| Layer 2 spec | `docs/superpowers/specs/2026-06-02-review-manifest-layer2-impl-design.md` |
| Epic | casehubio/drafthouse#20 — Phase 2 critique backend (parent) |
| Latest blog | `blog/2026-06-02-mdp06-two-agents-and-a-fold.md` |
| Key GEs | GE-20260602-093fea (@Blocking on CDI methods), GE-20260602-f8c7db (claude-agent-sdk not on Central) |
| Platform issues | casehubio/platform#55 (casehub-platform-agent), casehubio/parent#145 (doc sync) |
