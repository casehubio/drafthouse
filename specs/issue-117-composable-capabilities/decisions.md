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

**Choice:** SPI interface in DraftHouse `server/api/` with default local whisper.cpp/FFM implementation in `server/runtime/`
**Alternatives:**
- casehub-blocks-api — category mismatch (STT is infrastructure, not a composed workflow), no `casehub-blocks-api` module exists, no second consumer
- casehub-neocortex — owns ONNX-based inference, but whisper.cpp/FFM is native code, not ONNX; muddies module focus
- Direct LangChain4j integration — simpler but locks to one provider
- Subprocess (shell out to whisper CLI) — simpler than FFM but slower startup, harder to stream
**Rationale:** DraftHouse is the only consumer of STT. The platform-api-scope protocol and blocks ARC42STORIES §1 ("Not infrastructure, not a framework, not a tool library") both argue against premature promotion. Ship the SPI locally; extract when a second consumer materializes. Local whisper.cpp via Java FFM/Panama gives offline capability, no API costs, Apple Silicon Metal acceleration.
**Trade-offs:** Extract-later tax when a second consumer appears. Routine for a well-tested SPI with one implementation.
**Sources:** #117 issue body (whisper.cpp + Java FFM), blocks ARC42STORIES §1, platform-api-scope protocol
**Exploration:** quick
**Status:** revised — moved from casehub-blocks-api to DraftHouse-local per R1-02. Blocks placement was a category mismatch (infrastructure vs composed workflow), referenced a nonexistent module (casehub-blocks-api), and had no second consumer. #118 precedent was circular (both unimplemented).

## D3: Pipeline stages

**Choice:** Two-stage pipeline: raw transcript → combined cleanup + metadata extraction (via structured output) → optional goal-specific refinement
**Alternatives:**
- Four stages (raw → fidelity → metadata → goal) — artificial separation; fidelity cleanup and metadata extraction operate on the same input and can be combined in a single structured-output call
- Single stage combining everything — loses the ability to skip goal refinement for untagged notes
**Rationale:** Cleanup (filler removal, punctuation, grammar) and metadata extraction (title, summary, tags) are both applied to the same transcript input and can be handled by a single LLM call with structured output schema. Goal-specific refinement is fundamentally different — it transforms the cleaned text based on an intent parameter — and is optional (untagged notes skip it). Two stages gives the right separation without artificial granularity.
**Trade-offs:** 1-2 LLM calls per note (down from 2-3). Structured output schema handles the multiple concerns cleanly.
**Sources:** Conversation — user established fidelity ladder concept and goal-specific refinement
**Exploration:** quick
**Status:** revised — collapsed from four stages to two per R1-04. Combined cleanup + metadata extraction into a single structured-output call.

## D4: Pipeline LLM calls via LangChain4j ChatModel

**Choice:** Pipeline LLM calls use LangChain4j ChatModel with structured output, not AgentProvider
**Alternatives:**
- AgentProvider — spawns Claude CLI subprocesses per invocation, concurrent-session semaphore, no model selection. The existing PlatformDebateAgentProvider and DocumentReviewer already reveal the mismatch: both collect all TextDelta events into a StringBuilder, ignoring streaming entirely
- MCP tool-driven by client LLM — more control but requires LLM client to orchestrate every note
**Rationale:** Pipeline stages are text-in/text-out transformations. LangChain4j ChatModel is purpose-built for this: structured output support, method-level model selection (Haiku-tier for cleanup, not the full agent model), no subprocess overhead. The platform already has `casehub-platform-agent-langchain4j` providing bidirectional ChatModel ↔ AgentProvider interop, so platform context tracking is preserved if needed. DraftHouse already uses LangChain4j for DocumentReviewer (even though it currently routes through AgentProvider).
**Trade-offs:** Different LLM abstraction than the debate/review agents. Justified — pipeline stages and agent invocations have fundamentally different requirements.
**Sources:** casehub-platform-agent-langchain4j (ChatModelAgentProvider + AgentProviderChatModel bridge), PlatformDebateAgentProvider.java
**Exploration:** quick
**Status:** revised — changed from AgentProvider to ChatModel per R1-03. AgentProvider is the wrong abstraction level for simple text transformations.

## D5: Vault location — configurable with DraftHouse default

**Choice:** Configurable vault path via `casehub.drafthouse.vault.path` (default `~/.drafthouse/vault/`); Obsidian can open as a secondary vault
**Alternatives:**
- Hardcoded DraftHouse-managed vault — no configuration surface but isolates from user's existing knowledge graph
- User's existing Obsidian vault — risk of polluting existing structure
**Rationale:** DraftHouse controls vault structure (notes/, raw/ directories, frontmatter format). A configuration property follows the established pattern (`casehub.drafthouse.storage.root` already exists for session storage). Users with an existing Obsidian vault can point DraftHouse there for graph connectivity; the default keeps it separate.
**Trade-offs:** One additional config property. Trivial.
**Sources:** Conversation — user confirmed DraftHouse-managed vault; DraftHouseConfig.java (existing config pattern)
**Exploration:** quick
**Status:** revised — added configurable path per R1-08. Follows existing `casehub.drafthouse.storage.root` config pattern.

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

## D9: Input modes — phased delivery starting with push-to-talk

**Choice:** Push-to-talk first; continuous with pause detection and record-then-review as subsequent enhancements
**Alternatives:**
- All three simultaneously — defers core pipeline validation behind the most complex input modes
- Push-to-talk only (permanently) — limits use cases unnecessarily
**Rationale:** Push-to-talk validates the entire voice-to-note pipeline (audio capture → STT → cleanup → vault storage) with the simplest possible input mode. Continuous mode requires VAD (silence threshold calibration, noise floor estimation, debounce). Record-then-review requires audio playback UI, seeking, and waveform visualization. Phasing delivery validates the core value proposition first, then adds complexity incrementally. All three modes remain in the vision — this is a sequencing decision, not a scope cut.
**Trade-offs:** Continuous and record-then-review delayed. Users with dictation needs must wait. Acceptable — push-to-talk covers the quick-capture use case that #117 emphasizes.
**Sources:** #117 issue body (listed all three modes), conversation
**Exploration:** quick
**Status:** revised — phased delivery per R1-07. Push-to-talk first validates the core pipeline with minimal complexity.

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

## D11: Audio transport — HTTP POST upload

**Choice:** Browser records audio using MediaRecorder API; completed recording uploaded via HTTP POST to `/api/voice/upload`; server receives audio, runs STT, writes transcript to working directory
**Alternatives:**
- WebSocket binary frames on existing /api/ws — requires @OnBinaryMessage handler, binary-to-session association protocol, backpressure management; significant rework of a text-only endpoint; unnecessary for push-to-talk where recording completes before processing
- Dedicated audio WebSocket endpoint (/api/audio) — clean separation but unnecessary for batch STT (push-to-talk); revisit when continuous mode adds streaming STT requirements
**Rationale:** Push-to-talk (D9) means the recording completes before processing begins — streaming STT is unnecessary. HTTP POST upload is the simplest server implementation: a standard JAX-RS endpoint receiving multipart form data. No WebSocket binary frame protocol needed. When continuous mode is implemented (requiring streaming STT), the transport decision can be revisited with actual streaming requirements.
**Trade-offs:** No real-time partial transcripts during recording. Acceptable for push-to-talk — the user presses stop, then sees the result.
**Sources:** DebateWebSocket.java (text-only @OnTextMessage, PushRequest.parse() protocol)
**Exploration:** quick
**Status:** revised — changed from WebSocket binary frames to HTTP POST upload per R1-05. Push-to-talk doesn't need streaming; HTTP upload is the simplest correct transport.

## D12: STT SPI location — DraftHouse-local

**Choice:** STT SPI interface in DraftHouse `server/api/`, default whisper.cpp/FFM implementation in `server/runtime/`. Extract to a shared module when a second consumer appears.
**Alternatives:**
- casehub-blocks — category mismatch (blocks §1: "not infrastructure"), no casehub-blocks-api module, no second consumer, #118 precedent is circular
- casehub-neocortex — ONNX-specific inference model; whisper.cpp/FFM is native, not ONNX
**Rationale:** DraftHouse is the sole consumer. The extraction-on-demand principle avoids premature promotion to a shared module. The SPI in `server/api/` is pure Java (no framework deps), making future extraction mechanical. The implementation in `server/runtime/` follows the established api/runtime split.
**Trade-offs:** Cross-repo coordination cost when extracting later. Routine — the SPI is well-bounded (audio bytes → text).
**Sources:** blocks ARC42STORIES §1, platform-api-scope protocol, DraftHouse api/runtime split pattern
**Exploration:** quick
**Status:** revised — moved from casehub-blocks to DraftHouse-local per R1-02. Aligned with D2 revision.

## D13: Persistence integration — filesystem vault as durable store

**Choice:** Voice notes persist as filesystem artifacts in the vault; the vault outlives sessions; pipeline recovery is file-based
**Alternatives:**
- Store notes in DraftHouseSessionStore — couples note lifecycle to session lifecycle; notes should outlive their capture session
- Event-sourced via Qhorus — heavyweight for file-based artifacts; Qhorus events are for agent communication, not document storage
**Rationale:** The vault (D5) is the durable store — notes/, raw/, and frontmatter files persist independently of DraftHouseSession lifecycle. SessionSnapshot stores facet metadata (active facets, vault path reference) but not note content. Pipeline stages write intermediate files to the session working directory — each stage's output file is the recovery checkpoint. In-progress recordings are ephemeral audio buffers; a crash loses the current recording but never corrupts the vault. On session restore, NotesFacet scans the working directory for incomplete pipeline artifacts and resumes from the last completed stage.
**Trade-offs:** No transactional guarantee between STT completion and vault write — a crash between these steps loses the transcript but not the audio (which can be re-processed). Acceptable for a local-only tool.
**Sources:** DraftHouseSessionStore.java (SessionSnapshot record), DraftHouseSession.java (workingDirectory), Facet.java (artifact-based communication)
**Exploration:** quick (surfaced by reviewer)
**Status:** captured — new decision added per R1-09 to make persistence integration explicit
