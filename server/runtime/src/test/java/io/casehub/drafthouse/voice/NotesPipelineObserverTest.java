package io.casehub.drafthouse.voice;

import io.casehub.drafthouse.DraftHouseSessionRegistry;
import io.casehub.drafthouse.NoOpDraftHouseSessionStore;
import io.casehub.drafthouse.WebSocketEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotesPipelineObserverTest {

    @TempDir Path workDir;
    @TempDir Path vaultDir;

    DraftHouseSessionRegistry registry;
    NotesPipeline pipeline;
    VaultWriter vaultWriter;
    WebSocketEventBus eventBus;
    NotesPipelineObserver observer;

    @BeforeEach
    void setUp() {
        registry = new DraftHouseSessionRegistry(new NoOpDraftHouseSessionStore());
        pipeline = new NotesPipeline((dev.langchain4j.model.chat.ChatModel) null);
        vaultWriter = new VaultWriter();
        eventBus = mock(WebSocketEventBus.class);

        var session = registry.create("test-session");
        session.setWorkingDirectory(workDir);
        session.metadata().put("vault.path", vaultDir.toString());

        observer = new NotesPipelineObserver();
        observer.pipeline = pipeline;
        observer.vaultWriter = vaultWriter;
        observer.registry = registry;
        observer.eventBus = eventBus;
    }

    @Test
    void onTranscriptProcessesAndWritesNote() throws Exception {
        var rawPath = workDir.resolve("raw-transcript-test.md");
        Files.writeString(rawPath, "um hello uh world");

        observer.onTranscript(new TranscriptReady("test-session", rawPath, null));

        assertTrue(Files.exists(vaultDir.resolve("notes")));
        long noteCount;
        try (var stream = Files.list(vaultDir.resolve("notes"))) {
            noteCount = stream.filter(p -> p.toString().endsWith(".md")).count();
        }
        assertEquals(1, noteCount);
        verify(eventBus).pushVoiceEvent(eq("test-session"), eq("note-created"), any());
    }

    @Test
    void onTranscriptMovesRawToVault() throws Exception {
        var rawPath = workDir.resolve("raw-transcript-test.md");
        Files.writeString(rawPath, "raw text");

        observer.onTranscript(new TranscriptReady("test-session", rawPath, null));

        assertFalse(Files.exists(rawPath));
        assertTrue(Files.exists(vaultDir.resolve("raw").resolve("raw-transcript-test.md")));
    }

    @Test
    void onTranscriptUnknownSessionNoOps() throws Exception {
        var rawPath = workDir.resolve("raw-transcript-test.md");
        Files.writeString(rawPath, "text");

        observer.onTranscript(new TranscriptReady("unknown-session", rawPath, null));

        verifyNoInteractions(eventBus);
    }

    @Test
    void onTranscriptPassesGoalTag() throws Exception {
        var rawPath = workDir.resolve("raw-transcript-test.md");
        Files.writeString(rawPath, "some text");

        observer.onTranscript(new TranscriptReady("test-session", rawPath, "prompt"));

        verify(eventBus).pushVoiceEvent(eq("test-session"), eq("note-created"), any());
    }

    @Test
    void pipelineErrorPushesErrorEvent() throws Exception {
        var badPipeline = mock(NotesPipeline.class);
        when(badPipeline.process(any(), any())).thenThrow(new RuntimeException("LLM timeout"));
        observer.pipeline = badPipeline;

        var rawPath = workDir.resolve("raw-transcript-test.md");
        Files.writeString(rawPath, "text");

        observer.onTranscript(new TranscriptReady("test-session", rawPath, null));

        verify(eventBus).pushVoiceEvent(eq("test-session"), eq("pipeline-error"), any());
    }
}
