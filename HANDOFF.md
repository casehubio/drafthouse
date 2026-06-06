# Handover — 2026-06-06

**Branch:** `main` (clean)

## Last Session

Closed #34 (removed `@QuarkusTest` from `DebateRoundTripTest`) and #28 (configurable session storage path via `casehub.drafthouse.storage.root` — `@WithDefault("${user.home}/.drafthouse/reviews")` on `Path root()` in `DraftHouseConfig.Storage`). Restructured `DraftHouseConfig` to nested `Reviewer` + `Storage` sub-interfaces. Also closed stale epics #1 and #20 on GitHub and stamped `EPIC-CLOSED.md` on five previously unclosed branches. Filed #35 (UUID channel slug starts-with-digit flaky test) and #37 (CritiqueResourceTest missing API key at startup).

Garden: GE-20260606-bc1b15 (`${sys:user.home}` fallback trap), GE-20260606-1c20a7 (`@WithDefault`+`Path` technique), GE-20260606-668cee (nested `@ConfigMapping` mock NPE). Protocol: PP-20260606-f15545 (DraftHouseConfig two-level mock rule).

## Immediate Next Step

```
/work
```

Pick up #35 (XS, quick fix — prefix UUID slug with letter) or #31 (M/Med, main chapter work — ChannelProjection SPI migration, unblocked).

## What's Left

- #35 — UUID channel slug starts with digit → Qhorus validation fails intermittently · XS · Low
- #37 — CritiqueResourceTest fails at Quarkus startup (missing API key in test profile) · XS · Low
- #36 — ReviewSessionService unit test (deferred to #27 when service gains live callers) · S · Low

## What's Next

| # | Description | Scale | Complexity | Notes |
|---|-------------|-------|------------|-------|
| #31 | Migrate to ChannelProjection SPI — replaces local file-parser | M | Med | Unblocked: qhorus#230 ✅ |
| #27 | Qhorus DebateChannel — type + AGREE/QUALIFY sub-classification | M | Med | C5 chapter |
| #33 | Orphaned reviewer instance on start_review partial failure | S | Med | Blocked: needs InstanceService.deregister() from platform |

## References

| Context | Where |
|---|---|
| Architecture record | `ARC42STORIES.MD` (§9.4 for layer entries) |
| Latest blog | `wksp/blog/2026-06-06-mdp07-the-config-default-that-collapsed.md` |
| Key GEs | GE-20260606-bc1b15 (`${sys:}` trap), GE-20260606-1c20a7 (`@WithDefault`+`Path`), GE-20260606-668cee (nested mock NPE) |
| Protocol | `docs/protocols/drafthouse-config-mock-two-level.md` (PP-20260606-f15545) |
