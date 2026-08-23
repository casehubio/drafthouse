package io.casehub.drafthouse.voice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VaultWriterTest {

    @TempDir Path vaultDir;

    @Test
    void writesNoteWithValidYamlFrontmatter() throws Exception {
        var writer = new VaultWriter();
        var result = new CleanupResult(
                "This is the cleaned text.", "Test Note Title",
                "A test summary", List.of("test", "voice"), "en");
        var rawPath = vaultDir.resolve("raw-transcript-2026-08-23T091500.md");
        Files.writeString(rawPath, "um, this is the uh cleaned text");

        var notePath = writer.writeNote(result, rawPath, vaultDir, "voice", 45, "s1", "prompt");

        assertTrue(Files.exists(notePath));
        var content = Files.readString(notePath);
        assertTrue(content.startsWith("---\n"));

        var parts = content.split("---\n", 3);
        assertEquals(3, parts.length);
        @SuppressWarnings("unchecked")
        Map<String, Object> fm = new Yaml().load(parts[1]);
        assertEquals("Test Note Title", fm.get("title"));
        assertEquals("voice", fm.get("source"));
        assertEquals(List.of("test", "voice"), fm.get("tags"));
        assertEquals("45s", fm.get("duration"));
        assertEquals("s1", fm.get("session"));
        assertEquals("prompt", fm.get("goal"));
        assertEquals("A test summary", fm.get("summary"));

        assertTrue(parts[2].trim().contains("This is the cleaned text."));
    }

    @Test
    void movesRawToVaultRawDir() throws Exception {
        var writer = new VaultWriter();
        var result = new CleanupResult("cleaned", "Title", "sum", List.of(), "en");
        var rawSrc = vaultDir.resolve("raw-transcript-test.md");
        Files.writeString(rawSrc, "raw text");

        writer.writeNote(result, rawSrc, vaultDir, "voice", 10, "s1", null);

        assertFalse(Files.exists(rawSrc), "Original raw file should be moved");
        assertTrue(Files.exists(vaultDir.resolve("raw").resolve(rawSrc.getFileName())));
    }

    @Test
    void createsVaultSubdirsIfAbsent() throws Exception {
        var freshVault = vaultDir.resolve("new-vault");
        var writer = new VaultWriter();
        var result = new CleanupResult("text", "Title", "sum", List.of(), "en");
        var rawPath = vaultDir.resolve("raw-test.md");
        Files.writeString(rawPath, "raw");

        writer.writeNote(result, rawPath, freshVault, "voice", 5, "s1", null);

        assertTrue(Files.isDirectory(freshVault.resolve("notes")));
        assertTrue(Files.isDirectory(freshVault.resolve("raw")));
    }

    @Test
    void omitsOptionalFieldsWhenNull() throws Exception {
        var writer = new VaultWriter();
        var result = new CleanupResult("text", "Title", null, List.of(), "en");
        var rawPath = vaultDir.resolve("raw-test.md");
        Files.writeString(rawPath, "raw");

        var notePath = writer.writeNote(result, rawPath, vaultDir, "text", 0, "s1", null);

        var content = Files.readString(notePath);
        var parts = content.split("---\n", 3);
        @SuppressWarnings("unchecked")
        Map<String, Object> fm = new Yaml().load(parts[1]);
        assertNull(fm.get("goal"));
        assertNull(fm.get("duration"));
        assertNull(fm.get("summary"));
    }

    @Test
    void slugifiesTitleForFilename() throws Exception {
        var writer = new VaultWriter();
        var result = new CleanupResult("text", "Auth Module — Session Timeout!", null, List.of(), "en");
        var rawPath = vaultDir.resolve("raw-test.md");
        Files.writeString(rawPath, "raw");

        var notePath = writer.writeNote(result, rawPath, vaultDir, "voice", 0, "s1", null);

        var filename = notePath.getFileName().toString();
        assertFalse(filename.contains(" "));
        assertFalse(filename.contains("!"));
        assertTrue(filename.contains("auth-module"));
    }
}
