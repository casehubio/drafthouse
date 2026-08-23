# Voice Notes Capture & Obsidian-Compatible Storage — Design Spec

> **Issue:** casehubio/drafthouse#117 (Composable capability architecture)
> **Date:** 2026-08-23
> **Status:** Design — pending implementation plan
> **Scope:** VoiceFacet, NotesFacet, speech SPIs, cleanup pipeline, Obsidian vault storage

---

## 1. Problem Statement

DraftHouse needs a way to capture voice notes, clean them up through a fidelity-preserving pipeline, and store them as Obsidian-compatible markdown notes. These notes serve as source material for downstream work — document drafting, LLM prompt authoring, or general reference. The capture boundary is distinct from the creation boundary: everything before the note preserves the speaker's intent; everything after it is authoring.

## 2. Architecture Overview

Two new facets plug into the existing `DraftHouseSession` container:

**VoiceFacet** — captures audio from the browser mic via MediaRecorder API, uploads completed recordings to `POST /api/voice/upload`, and invokes the STT SPI to produce a raw transcript file in the session working directory.

**NotesFacet** — watches for raw transcripts, runs the cleanup pipeline (LangChain4j ChatModel with structured output), and writes the resulting note to the Obsidian-compatible vault. Also handles non-voice note creation (paste, import, typed).

Communication between them follows the existing artifact contract: VoiceFacet declares `raw-transcript-*.md` as an output; NotesFacet declares it as an input. No direct coupling — they communicate through files in the shared working directory.

**Session integration:** Both facets activate within a `DraftHouseSession`. For quick capture, auto-session creation transparently handles the session lifecycle — the user just hits record. The vault (`~/.drafthouse/vault/` by default, configurable via `casehub.drafthouse.vault.path`) outlives sessions — notes persist independently.

### Component diagram

```
Browser                              Quarkus Server                        Filesystem
┌─────────────────┐                 ┌──────────────────────────┐          ┌─────────────────┐
│ <voice-capture>  │──POST /api/──→│ VoiceUploadResource       │          │ Session workdir  │
│  MediaRecorder   │  voice/upload  │   ↓                      │          │ ├── raw-transcript│
│  push-to-talk    │                │ SpeechToTextService (SPI) │──write──→│ │   -*.md         │
└─────────────────┘                │   (sherpa-onnx default)   │          │ └── ...           │
                                    └──────────────────────────┘          └────────┬──────────┘
                                                                                   │ artifact
┌─────────────────┐                 ┌──────────────────────────┐                   │ contract
│ <note-list>      │←──WebSocket───│ NotesFacet                │←──watches─────────┘
│ <note-detail>    │   note-created │   ↓                      │
│ <pipeline-status>│                │ ChatModel (structured)    │          ┌─────────────────┐
└─────────────────┘                │   cleanup + metadata      │──write──→│ Vault            │
                                    │   ↓ (optional)            │          │ ├── notes/*.md    │
                                    │ ChatModel (goal refine)   │          │ ├── raw/*.md      │
                                    └──────────────────────────┘          │ └── .obsidian/    │
                                                                          └─────────────────┘
```

## 3. Speech SPIs (casehub-blocks)

Speech capabilities are domain-agnostic composable building blocks — they live in casehub-blocks as a speech module, reusable across CaseHub apps.

### 3.1 SpeechToTextService

```java
public interface SpeechToTextService {
    TranscriptionResult transcribe(byte[] audioData, TranscriptionOptions options);
}

public record TranscriptionResult(String text, String language, double confidence) {}

public record TranscriptionOptions(
    String audioFormat,
    String languageHint,
    String modelSize
) {}
```

### 3.2 TextToSpeechService

```java
public interface TextToSpeechService {
    SynthesisResult synthesise(String text, SynthesisOptions options);
}

public record SynthesisResult(byte[] audioData, String audioFormat, List<PhonemeTiming> phonemes) {}

public record PhonemeTiming(String phoneme, long startMs, long endMs) {}

public record SynthesisOptions(
    String voice,
    String language,
    String audioFormat,
    boolean includePhonemes
) {}
```

### 3.3 Default implementation: sherpa-onnx

Sherpa-onnx serves as the unified runtime for both STT and TTS via Java FFM/Panama:
- **STT:** Whisper models — offline, no API costs, Apple Silicon Metal acceleration
- **TTS:** VITS/Piper models — local synthesis with phoneme timing output
- One native binding for both directions

Alternative implementations pluggable via CDI for external services (Deepgram, Google, ElevenLabs, Amazon Polly).

The SPI abstraction is critical: latency and quality vary significantly across models and services. Implementations must be swappable without touching consumer code.

### 3.4 Future: avatar lip-sync

TTS phoneme timing output feeds avatar lip-sync animation (casehubio/blocks#154). Not in scope for this design — filed as a future capability.

## 4. Voice Capture Pipeline

### 4.1 Browser capture

**Phase 1 (this design): Push-to-talk**
- MediaRecorder API captures audio from user's microphone
- User holds/toggles a button to record, releases to stop
- Completed recording uploaded via `POST /api/voice/upload` (multipart: audio file + session ID + optional goal tag)

**Phase 2 (future): Additional input modes**
- **Continuous with pause detection** — VAD segments audio at natural pauses. Requires silence threshold calibration, noise floor estimation, debounce.
- **Record-then-review** — long recording with audio playback, seeking, and waveform UI before submitting.

### 4.2 Audio transport

HTTP POST upload to `/api/voice/upload`. Push-to-talk means the recording completes before processing begins — streaming STT is unnecessary. When continuous mode is implemented (Phase 2), the transport decision can be revisited with actual streaming requirements.

### 4.3 Server processing

1. JAX-RS `VoiceUploadResource` receives multipart form data
2. Invokes `SpeechToTextService.transcribe()` via the blocks STT SPI
3. Writes raw transcript to session working directory as `raw-transcript-{timestamp}.md`
4. Emits `voice-transcript-ready` WebSocket event
5. NotesFacet picks up the raw transcript via artifact contract

## 5. Notes Pipeline

### 5.1 Two-stage pipeline

**Stage 1 — Cleanup + metadata extraction (always runs):**
- Single LLM call via LangChain4j ChatModel with structured output schema
- Fidelity cleanup: remove filler words (um, ah), repeated words/sentences, broken sentence fragments
- Metadata extraction: generate title, summary, tags from the content
- Model selection: Haiku-tier — mechanical text cleanup, not creative reasoning
- Input: raw transcript text
- Output: structured response containing cleaned text, title, summary, tags[], detected language

**Stage 2 — Goal-specific refinement (optional):**
- Runs only if the note has a goal tag (set at capture time or after stage 1)
- Parameterized by intent — different prompts for different goals:
  - `document` — readability, grammar, sentence structure (still the speaker's words, just clearer)
  - `prompt` — precision, disambiguation, instruction clarity
  - `reference` — no refinement (stage 1 output is sufficient)
- Model selection: can vary by goal — prompts may benefit from a stronger model

**Key constraint:** No stage changes what was said — only how clearly it reads. The note is the boundary between capture and creation.

### 5.2 Non-voice input

NotesFacet also accepts input from sources other than voice:
- `create_note` MCP tool — paste text, type directly, or import existing markdown
- Same pipeline applies: cleanup + metadata extraction, optional goal refinement
- Same vault storage format

## 6. Vault Storage

### 6.1 Directory structure

```
~/.drafthouse/vault/                    (configurable via casehub.drafthouse.vault.path)
├── notes/
│   └── 2026-08-23-standup-thoughts.md  ← cleaned note with frontmatter
├── raw/
│   └── 2026-08-23-standup-thoughts.md  ← verbatim STT output
└── .obsidian/                           ← Obsidian config (auto-created if absent)
```

Obsidian opens this directory as a secondary vault. `raw/` can be excluded from Obsidian search/graph via `.obsidian/app.json` excludes.

### 6.2 Note frontmatter (Obsidian-compatible YAML)

```yaml
---
title: Standup thoughts on auth module
date: 2026-08-23T09:15:00
source: voice
duration: 45s
session: dh-abc123
goal: prompt
tags: [auth, testing, regression]
summary: Check whether the session timeout regression is fixed in the auth module
raw: ../raw/2026-08-23-standup-thoughts.md
---
```

Fields: `date` (capture timestamp), `source` (voice | text | import), `duration` (recording length, voice only), `session` (DraftHouseSession ID), `goal` (document | prompt | reference, if tagged), `tags` (LLM-extracted), `title` (LLM-extracted), `summary` (LLM-extracted), `raw` (relative path to raw transcript).

### 6.3 Persistence model

- The vault is the durable store — notes outlive their capture session
- `SessionSnapshot` stores facet metadata (active facets, vault path reference) but not note content
- Pipeline recovery is file-based: each stage writes its output file as a checkpoint. On session restore, NotesFacet scans the working directory for incomplete pipeline artifacts and resumes from the last completed stage.
- In-progress recordings are ephemeral audio buffers — a crash loses the current recording but never corrupts the vault
- No transactional guarantee between STT completion and vault write — a crash between these steps loses the transcript but not the audio (which can be re-processed)

## 7. MCP Tools

### 7.1 VoiceFacet tools (active when voice facet is activated)

| Tool | Description |
|------|-------------|
| `start_recording` | Begin audio capture (mode: push-to-talk, continuous, record-review) |
| `stop_recording` | End capture, trigger upload and STT |
| `list_recordings` | Show recordings in current session |

### 7.2 NotesFacet tools (active when notes facet is activated)

| Tool | Description |
|------|-------------|
| `create_note` | Create a note from text (non-voice path) |
| `refine_note` | Apply goal-specific refinement to a cleaned note |
| `list_notes` | List notes in the vault with metadata |
| `search_notes` | Search vault by tags, title, content |
| `get_note` | Retrieve note content and metadata |

### 7.3 Session-level

- `activate_facet voice` activates VoiceFacet (NotesFacet auto-activated as dependency)
- Quick-capture auto-creates a lightweight session with both facets pre-activated

## 8. Browser UI Panels

All panels are LitElement with Shadow DOM, registered via `registerPanel()`, orchestrated through `onPagesEvent()`.

| Panel | Placement | Purpose |
|-------|-----------|---------|
| `<voice-capture>` | Topbar | Mic button, mode selector, recording status, waveform indicator |
| `<note-list>` | Main area | Vault browser — notes with metadata, search/filter, click to view |
| `<note-detail>` | Main area | Note content, frontmatter, raw transcript toggle, refine/re-tag |
| `<pipeline-status>` | Topbar | Pipeline progress for in-flight notes (transient) |

### WebSocket events (via existing /api/ws)

| Event | When |
|-------|------|
| `voice-recording-started` | Capture begins |
| `voice-recording-stopped` | Capture ends |
| `voice-transcript-ready` | STT complete, raw transcript available |
| `note-created` | Pipeline complete, note written to vault |
| `note-updated` | Refinement applied to existing note |

## 9. Phased Delivery

| Phase | Scope | Rationale |
|-------|-------|-----------|
| 1 | Push-to-talk capture, STT SPI + sherpa-onnx, two-stage pipeline, vault storage, NotesFacet MCP tools, basic UI panels | Validates full voice-to-note pipeline with simplest input mode |
| 2 | Continuous mode (VAD), record-then-review, audio playback UI, waveform visualisation | Adds complexity after core pipeline is proven |
| 3 | TTS SPI + sherpa-onnx, read-aloud capability | Speech output — independent of capture pipeline |

## 10. Dependencies

| Dependency | What | Where |
|------------|------|-------|
| casehub-blocks speech module | STT + TTS SPI interfaces | New submodule in casehubio/blocks |
| casehub-blocks-speech-sherpa | sherpa-onnx FFM implementation | New submodule in casehubio/blocks |
| sherpa-onnx native library | whisper + VITS/Piper models | Runtime dependency, Apple Silicon Metal |
| LangChain4j ChatModel | Pipeline structured output calls | Already available via casehub-platform-agent-langchain4j |
| DraftHouseSession + Facet | Session container, facet lifecycle | Already implemented on branch |

## References

- `server/api/src/main/java/io/casehub/drafthouse/Facet.java` — facet interface with artifact contract
- `server/api/src/main/java/io/casehub/drafthouse/ArtifactSpec.java` — artifact declaration
- `server/api/src/main/java/io/casehub/drafthouse/DraftHouseSession.java` — unified session container
- `server/runtime/src/main/java/io/casehub/drafthouse/SessionMcpTools.java` — session-level MCP tools (activate_facet stub)
- `server/runtime/src/main/java/io/casehub/drafthouse/DraftHouseSessionRegistry.java` — session lifecycle
- casehubio/drafthouse#117 — parent epic (composable capability architecture)
- casehubio/blocks#154 — future avatar lip-sync capability
- `casehub-platform-agent-langchain4j` — ChatModel ↔ AgentProvider bridge
- `PlatformDebateAgentProvider.java` — existing agent provider pattern
- `DebateWebSocket.java` — existing WebSocket /api/ws endpoint
