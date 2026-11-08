---
layout: post
title: "The Note Is the Boundary"
date: 2026-08-23
entry_type: note
subtype: diary
projects: [casehub-drafthouse]
tags: [voice, notes, obsidian, speech, spi, pipeline]
series: issue-117-composable-capabilities
---

# The Note Is the Boundary

*Continues from [Facets, Not Modes](2026-08-21-mdp01-facets-not-modes.md)*

The facet architecture gave us composable building blocks. This session filled in two of them — VoiceFacet and NotesFacet — and the design turned on one question: where does capture end and creation begin?

I wanted voice notes. Speak into the mic, get a cleaned-up note stored somewhere useful. But "cleaned up" hides a design decision. How much cleaning? A raw transcript is full of filler — ums, ahs, repeated words, broken sentences. Removing those is faithful to intent. But improving grammar? Restructuring for readability? That starts changing what was said. And if the note isn't destined for a document — if it's an LLM prompt, or reference material — readability polish is the wrong optimisation entirely.

That second case matters more than I initially thought. A voice note that says "run the tests for the auth module and check if the session timeout regression is fixed" shouldn't get grammar-polished into an essay paragraph. It should stay as a clean instruction. So the pipeline needs to know what the note is for — but only at the refinement stage, not at capture. The fidelity cleanup is universal and goal-agnostic. The refinement is parameterised: document prose gets readability; prompts get precision and disambiguation. And you can tag the goal before recording if you already know, or after cleanup if you don't. Capture stays frictionless either way.

The resolution: the note is the boundary. Everything before it preserves the speaker's intent. Everything after it — drafting a document, composing a prompt, whatever — is authoring. The cleanup pipeline is a fidelity ladder: raw transcript, then filler removal plus metadata extraction in a single structured-output call, then optional goal-specific refinement. Each stage removes noise without adding meaning. The pipeline stops the moment the note is clean enough for its purpose.

This split the implementation cleanly. VoiceFacet handles capture — browser mic, audio upload, STT. NotesFacet handles everything from raw transcript onward — cleanup pipeline, metadata extraction, vault storage. They communicate through a CDI event (`TranscriptReady`), not direct references. NotesFacet can also receive input from non-voice sources — paste, type, import — because it doesn't care how the text arrived. The artifact declarations on both facets (`outputs: raw-transcript-*.md`, `inputs: raw-transcript-*.md`) are metadata for introspection — the actual trigger is the CDI event, not filesystem watching.

## Speech as a platform capability

The speech SPIs had an interesting journey. I wanted them in casehub-blocks from the start — speech-to-text and text-to-speech are domain-agnostic building blocks, exactly what blocks is for. The decision review disagreed. It argued blocks was a category mismatch (blocks ARC42STORIES says "not infrastructure"), there was no `casehub-blocks-api` module yet, and DraftHouse was the only consumer. All valid conservative reasoning. I overrode it. The extract-later tax is real — building the SPI in the right place from the start avoids it, and signals the architectural intent that any CaseHub app should be able to use speech.

Once STT was going into blocks, TTS followed naturally. If you're doing voice in, you'll want voice out. `TextToSpeechService` produces audio bytes and — critically — `PhonemeTiming` data: which phoneme was spoken at which millisecond offset. That's the feed for avatar lip-sync, which we filed as a future blocks capability. The phoneme timing is cheap to produce (sherpa-onnx gives it for free with VITS/Piper models) but opens a whole visual interaction layer.

Both SPIs share a single runtime: sherpa-onnx via Java FFM/Panama. One native binding handles Whisper models for STT and VITS/Piper for TTS, with Apple Silicon Metal acceleration. But the SPI abstraction is the real point — latency and quality vary enormously across models and services. The interface needs to make it trivial to swap a local Whisper model for Deepgram's API, or a VITS voice for ElevenLabs, without touching any consumer code. That's what lets you A/B test providers as you figure out what works.

`SpeechToTextService.transcribe()` takes a `Path`, not `byte[]`. A 30-minute recording is ~30MB — loading that into heap for every transcription is wasteful when sherpa-onnx reads files natively. A streaming variant (`transcribeStreaming`) can be added later without breaking the initial contract, when continuous capture mode needs real-time partial transcripts.

## Obsidian as the integration point

The vault is Obsidian-compatible — markdown files with YAML frontmatter in a DraftHouse-managed directory that Obsidian opens as a secondary vault. Raw transcripts in `raw/`, cleaned notes in `notes/`. The LLM extracts title, summary, and tags during cleanup; those go straight into frontmatter for Obsidian's search and graph features. The vault path is configurable — power users can point it at an existing Obsidian vault if they want graph connectivity with their other notes.

The vault outlives sessions. Notes persist independently of the DraftHouseSession that created them. A crash during pipeline execution loses the current recording but never corrupts existing notes — each pipeline stage writes its output file as a checkpoint, and NotesFacet resumes from the last completed stage on session restore.

## Where it stands

The speech SPI module is complete — seven types, tests covering validation and edge cases. VoiceFacet, NotesFacet, and TranscriptReady are in place on the drafthouse side. What's next is the runtime wiring: VoiceUploadResource (the JAX-RS endpoint that receives audio and invokes STT), the cleanup pipeline (LangChain4j ChatModel with structured output), and the vault writer. The plan review surfaced seven architectural issues to fix inline — async CDI events so the upload endpoint doesn't block on the full pipeline, correct WebSocket topic routing, audio format handling (MediaRecorder produces webm, not wav). Those are all addressable during implementation rather than design changes.
