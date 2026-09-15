package io.casehub.drafthouse.voice;

import io.casehub.drafthouse.DraftHouseSessionRegistry;
import io.casehub.drafthouse.NoOpDraftHouseSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NotesMcpToolsTest {

    @TempDir Path workDir;
    @TempDir Path vaultDir;
    DraftHouseSessionRegistry registry;
    NotesMcpTools tools;

    @BeforeEach
    void setUp() throws Exception {
        registry = new DraftHouseSessionRegistry(new NoOpDraftHouseSessionStore());
        var session = registry.create("test-session");
        session.setWorkingDirectory(workDir);
        session.activateFacet(new NotesFacet());
        session.metadata().put("vault.path", vaultDir.toString());

        var notesDir = vaultDir.resolve("notes");
        Files.createDirectories(notesDir);
        Files.writeString(notesDir.resolve("2026-08-23-test-note.md"),
                "---\ntitle: Test Note\ntags: [test]\n---\n\nHello world");

        tools = new NotesMcpTools();
        tools.registry = registry;
    }

    @Test
    void listNotesShowsExistingNotes() {
        var result = tools.list_notes("test-session");
        assertTrue(result.contains("2026-08-23-test-note.md"));
    }

    @Test
    void getNoteReturnsContent() {
        var result = tools.get_note("test-session", "2026-08-23-test-note.md");
        assertTrue(result.contains("Hello world"));
        assertTrue(result.contains("title: Test Note"));
    }

    @Test
    void getNoteNotFoundReturnsError() {
        var result = tools.get_note("test-session", "nonexistent.md");
        assertTrue(result.contains("not found"));
    }

    @Test
    void listNotesEmptyVault() throws Exception {
        Files.delete(vaultDir.resolve("notes").resolve("2026-08-23-test-note.md"));
        var result = tools.list_notes("test-session");
        assertTrue(result.contains("No notes"));
    }

    @Test
    void listNotesWithoutNotesFacetReturnsError() {
        var session = registry.create("no-notes");
        session.setWorkingDirectory(workDir);
        var result = tools.list_notes("no-notes");
        assertTrue(result.startsWith("Failed:"));
    }
}
