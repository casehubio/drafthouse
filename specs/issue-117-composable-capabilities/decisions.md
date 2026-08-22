# Design Decisions — Voice Notes Capture & Obsidian Storage

## D1: VoiceFacet architecture — split vs monolithic

**Choice:** Split into VoiceFacet + NotesFacet
**Alternatives:**
- Monolithic VoiceFacet — simpler lifecycle but violates single-responsibility, can't use vault without voice capture
- Three-way split (Voice + Pipeline + Vault) — over-engineered, pipeline and vault are tightly coupled
**Rationale:** Capture and note management are distinct concerns with different input sources. VoiceFacet produces raw transcripts as artifacts; NotesFacet consumes them, runs cleanup pipeline, and manages the vault. NotesFacet can receive input from non-voice sources (paste, import, typed) without redesign.
**Trade-offs:** Two facets to activate for full voice-to-note flow — mitigated by auto-session activating both by default.
**Sources:** Facet.java (artifact-based cross-capability integration), #117 issue body (composable capabilities via artifacts)
**Exploration:** quick
**Status:** captured

## D2: STT runtime — SPI with local default

**Choice:** SPI interface in casehub-blocks-api with default local whisper.cpp/FFM implementation
**Alternatives:**
- Direct LangChain4j integration — simpler but locks to one provider
- Subprocess (shell out to whisper CLI) — simpler than FFM but slower startup, harder to stream
**Rationale:** Follows existing casehub-blocks SPI pattern. Local whisper.cpp via Java FFM/Panama gives offline capability, no API costs, Apple Silicon Metal acceleration. Alternative SPI plugins for external services (Google, Deepgram, etc.) when needed.
**Trade-offs:** FFM integration is more complex than subprocess. Worth it for streaming partial results and avoiding process overhead.
**Sources:** #117 issue body (whisper.cpp + Java FFM), #118 issue (casehub-blocks SPI pattern for image generation)
**Exploration:** quick
**Status:** captured

## D3: Pipeline stages

**Choice:** Four-stage pipeline: raw transcript → fidelity cleanup → metadata extraction → optional goal-specific refinement
**Alternatives:**
- Two-stage (raw → cleaned only) — simpler but loses metadata enrichment
- Single cleanup stage combining fidelity + metadata — conflates two distinct operations
**Rationale:** Fidelity cleanup is universal and goal-agnostic (remove filler, repeats). Metadata extraction (title, summary, tags) enriches every note. Goal-specific refinement is optional and parameterized by intent (document prose gets readability; prompts get precision). Stages are progressive and non-destructive — each removes noise or adds structure without changing meaning.
**Trade-offs:** More LLM calls per note (2-3 via AgentProvider). Acceptable — notes are infrequent compared to chat, and each call is small.
**Sources:** Conversation — user established fidelity ladder concept and goal-specific refinement
**Exploration:** quick
**Status:** captured

## D4: Pipeline LLM calls via AgentProvider

**Choice:** All pipeline LLM calls go through casehub-platform AgentProvider
**Alternatives:**
- Direct LangChain4j calls — simpler wiring but bypasses platform orchestration
- MCP tool-driven by client LLM — more control but requires LLM client to orchestrate every note
**Rationale:** AgentProvider is the platform's LLM abstraction, already used for debate agents. Keeps all LLM usage consistent with platform context tracking and provider configuration.
**Trade-offs:** Dependency on platform agent infrastructure for what are relatively simple LLM calls.
**Sources:** PlatformDebateAgentProvider.java, casehub-platform-agent-api
**Exploration:** quick
**Status:** captured

## D5: Vault location — DraftHouse-managed

**Choice:** DraftHouse-managed vault (e.g. ~/.drafthouse/vault/) that Obsidian can open as a secondary vault
**Alternatives:**
- User's existing Obsidian vault — risk of polluting existing structure
- Configurable — more flexible but more configuration surface
**Rationale:** DraftHouse controls vault structure (notes/, raw/ directories, frontmatter format). Obsidian opens it as a secondary vault — full compatibility without interfering with existing vaults.
**Trade-offs:** User must add DraftHouse vault as a secondary vault in Obsidian manually. One-time setup.
**Sources:** Conversation — user confirmed DraftHouse-managed vault
**Exploration:** quick
**Status:** captured

## D6: Raw transcript storage — separate directory

**Choice:** Raw transcripts in raw/ subdirectory within the vault, cleaned notes in notes/
**Alternatives:**
- Inline in note — raw appended as collapsed section, makes notes long
- Outside vault — raw in session working directory only, harder to find later
**Rationale:** Clean separation. Obsidian sees both directories but raw/ can be excluded from search/graph. Raw is always recoverable and cross-referenced from the cleaned note.
**Trade-offs:** Two files per voice capture. Acceptable for auditability.
**Sources:** Conversation — user selected separate raw directory
**Exploration:** quick
**Status:** captured

## D7: Session scope — facet model with auto-session

**Choice:** VoiceFacet always runs within a DraftHouseSession; quick-capture auto-creates a lightweight session transparently
**Alternatives:**
- Session-only — requires explicit session creation, adds friction to quick capture
- Independent capture path — bypasses facet model, inconsistent architecture
**Rationale:** Keeps the facet model consistent internally. Quick capture auto-creates a minimal session so the user just hits record. Full sessions give richer context (working directory, documents).
**Trade-offs:** Auto-session creation adds a small abstraction layer. Justified by architectural consistency.
**Sources:** Facet.java, DraftHouseSession.java, DraftHouseSessionRegistry.java
**Exploration:** quick
**Status:** captured

## D8: Note goal tagging — optional at capture

**Choice:** Goal can be set before recording or after fidelity cleanup; default is untagged
**Alternatives:**
- Always after cleanup — simpler pipeline but can't skip straight to refinement
- Always at capture — adds friction to every capture
**Rationale:** Frictionless by default (just record). Pre-tagging available when intent is known upfront — pipeline can flow directly to goal-specific refinement. Untagged notes stop after fidelity cleanup + metadata extraction.
**Trade-offs:** Pipeline needs to handle both tagged and untagged paths. Minor complexity.
**Sources:** Conversation — user selected "either"
**Exploration:** quick
**Status:** captured

## D9: Input modes — all three

**Choice:** Push-to-talk, continuous with pause detection, and record-then-review
**Alternatives:**
- Push-to-talk only — simplest but limits use cases
- Push-to-talk + continuous — missing long-form review workflow
**Rationale:** Full range from quick capture (push-to-talk) to hands-free dictation (continuous) to extended sessions (record-then-review). Each mode serves a different capture context.
**Trade-offs:** More UI work and audio processing (silence detection for continuous mode). All three are needed for a complete voice capture tool.
**Sources:** #117 issue body (listed all three modes)
**Exploration:** quick
**Status:** captured

## D10: Note metadata — basics plus LLM-extracted


**Choice:** Frontmatter includes: date, source (mic/session), duration, goal (if tagged), plus LLM-extracted title, summary, and tags
**Alternatives:**
- Rich metadata — adds pipeline stage markers, linked notes, refinement level. Over-specified for initial version.
- Minimal (date + title only) — loses useful Obsidian features like tag-based search
**Rationale:** Enough metadata for Obsidian discoverability (tags, summary) and provenance (date, source, duration) without overloading. LLM extraction handles the creative parts (title, summary, tags).
**Trade-offs:** LLM-extracted metadata may need manual correction in Obsidian. Acceptable.
**Sources:** Conversation — user selected basics + extracted
**Exploration:** quick
**Status:** captured

## D11: Audio transport — WebSocket binary frames

**Choice:** Browser sends audio frames over existing WebSocket connection (/api/ws) as binary messages; server decodes and feeds to STT
**Alternatives:**
- Dedicated audio WebSocket endpoint (/api/audio) — clean separation but second connection per session, more wiring
- HTTP upload (POST /api/voice/upload) — simplest server impl but no streaming STT, poor UX for continuous mode
**Rationale:** Reuses existing WebSocket infrastructure. Low latency for streaming STT (partial transcripts during recording). WebSocket spec natively distinguishes binary frames from text frames — existing JSON events are text, audio is binary, no protocol ambiguity.
**Trade-offs:** Audio traffic shares connection with UI events. Acceptable — audio frames are small and UI events are infrequent.
**Sources:** DebateWebSocket.java (existing /api/ws endpoint), WebSocketEventBus.java
**Exploration:** quick
**Status:** captured

## D12: STT SPI location — casehub-blocks

**Choice:** STT SPI interface in casehub-blocks-api, default whisper.cpp/FFM implementation in casehub-blocks-stt-whisper submodule
**Alternatives:**
- DraftHouse-local (server/api/) — faster to ship but extract-later tax, inconsistent with #117/#118 direction
**Rationale:** STT is domain-agnostic — converts audio to text. Belongs at the blocks tier alongside image generation SPI. Reusable across CaseHub apps. Follows established blocks SPI pattern.
**Trade-offs:** Cross-repo coordination with blocks release cycle. Worth it for architectural consistency.
**Sources:** #117 issue body (casehub-blocks-stt), #118 issue (casehub-blocks-image-* pattern)
**Exploration:** quick
**Status:** captured
