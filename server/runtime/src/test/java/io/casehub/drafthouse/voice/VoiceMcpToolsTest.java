package io.casehub.drafthouse.voice;

import io.casehub.drafthouse.DraftHouseSessionRegistry;
import io.casehub.drafthouse.NoOpDraftHouseSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VoiceMcpToolsTest {

    @TempDir Path workDir;
    DraftHouseSessionRegistry registry;
    VoiceMcpTools tools;

    @BeforeEach
    void setUp() {
        registry = new DraftHouseSessionRegistry(new NoOpDraftHouseSessionStore());
        tools = new VoiceMcpTools();
        tools.registry = registry;
    }

    @Test
    void startVoiceCaptureWithoutSessionAutoCreates() {
        var result = tools.start_voice_capture(null, workDir.toString());
        assertTrue(result.contains("Voice capture started"));
        assertTrue(result.contains("auto-session"));
    }

    @Test
    void startVoiceCaptureWithActiveVoiceFacet() {
        var session = registry.create("s1");
        session.setWorkingDirectory(workDir);
        session.activateFacet(new VoiceFacet());
        var result = tools.start_voice_capture("s1", null);
        assertTrue(result.contains("Voice capture started"));
    }

    @Test
    void startVoiceCaptureWithoutFacetReturnsError() {
        var session = registry.create("s1");
        session.setWorkingDirectory(workDir);
        var result = tools.start_voice_capture("s1", null);
        assertTrue(result.startsWith("Failed:"));
        assertTrue(result.contains("voice facet"));
    }

    @Test
    void stopVoiceCaptureReturnsInstructions() {
        var result = tools.stop_voice_capture("s1");
        assertTrue(result.contains("POST /api/voice/upload"));
    }

    @Test
    void startVoiceCaptureUnknownSessionReturnsError() {
        var result = tools.start_voice_capture("nonexistent", null);
        assertTrue(result.startsWith("Failed:"));
    }
}
