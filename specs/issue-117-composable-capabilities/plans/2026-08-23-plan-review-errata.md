# Plan Review Errata — Voice Notes Implementation

Light review raised 23 issues (7 HIGH, 8 MEDIUM, 8 LOW). These corrections must be applied during execution.

## HIGH — must fix during implementation

1. **Async CDI event (R1-02):** `transcriptReadyEvent.fire()` blocks the HTTP thread. Use `fireAsync()` with `@ObservesAsync` on `NotesPipelineObserver`. Upload response returns after STT, not after full pipeline.

2. **LangChain4j dependency (R1-03):** `dev.langchain4j` not in drafthouse's POM. Add `quarkus-langchain4j-core` and a CDI producer for `ChatLanguageModel`, or route through `AgentProvider`. D4 decision says ChatModel — add the dependency explicitly in Task 4.

3. **WebSocketEventBus API (R1-04):** `TopicRegistry` uses `listen(id, List.of(...))` and `connections(topic)` returning `Set<String>`, not `subscribe(conn, topic)`. Fix `watchVoice`/`pushVoiceEvent` to match `watchBrainstorm`/`pushBrainstormEvent` patterns exactly.

4. **DebateWebSocket voice routing (R1-05):** Must add `voice:` prefix handling to `DebateWebSocket.handleSubscribe()`, `handleUnsubscribe()`, `handleListen()`, `handleUnlisten()`.

5. **Audio format (R1-06):** MediaRecorder produces webm/Opus, server expects WAV. Either transcode server-side or configure MediaRecorder to produce WAV. Save with correct extension.

6. **MCP exception catch-all (R1-07):** All `@Tool` methods must wrap in try/catch returning `"Failed: ..."` strings per platform protocol.

7. **onPagesEvent signature (R1-08):** Requires 3 args: `onPagesEvent(target, topic, handler)`. Add `document` or `this` as first arg in all panel usages.

## MEDIUM — address during implementation

8. **Auto-session idle timeout (R1-10):** Add `@Scheduled` cleanup or last-activity tracking. File follow-up issue if deferred.

9. **Pipeline stage 2 stub (R1-11):** Don't use `Closes #121` until stage 2 is implemented. Use `Refs #121`.

10. **Package consistency (R1-14):** Put runtime voice classes in `io.casehub.drafthouse.voice` subpackage.

11. **YAML injection (R1-13):** Use SnakeYAML for frontmatter serialization, not string concatenation.

12. **Hardcoded facet switch (R1-15):** Consider CDI-based facet registry. Acceptable for now with 2 facets.

13. **Commit claim (R1-16):** Change `Closes` to `Refs` for incomplete features.

14. **Panel stubs (R1-17):** Flesh out note-detail and pipeline-status during execution.

## LOW — nice to fix

15. **Audio file cleanup (R1-19):** Delete audio after successful transcription.
16. **Duration always zero (R1-20):** Compute from MediaRecorder or audio file.
17. **Misleading MCP tool names (R1-21):** `start_recording` prepares session, doesn't record.
18. **list_recordings placeholder (R1-22):** Actually enumerate files.
19. **Filename collision (R1-23):** Add timestamp suffix to vault filenames.
