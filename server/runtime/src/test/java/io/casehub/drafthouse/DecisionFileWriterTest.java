package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionFileWriterTest {

    @Test
    void append_createsDirectoryAndFile(@TempDir Path tempDir) throws IOException {
        DecisionFileWriter writer = new DecisionFileWriter();
        writer.append(tempDir.toString(), 1, "Comments", "R1-05", "Human comment");

        Path file = tempDir.resolve("decisions/human-round-1.md");
        assertThat(file).exists();
        String content = Files.readString(file);
        assertThat(content).contains("# Human Decisions — Round 1");
        assertThat(content).contains("## Comments");
        assertThat(content).contains("### R1-05");
        assertThat(content).contains("Human comment");
    }

    @Test
    void append_multipleEntries_sameSection(@TempDir Path tempDir) throws IOException {
        DecisionFileWriter writer = new DecisionFileWriter();
        writer.append(tempDir.toString(), 1, "Comments", "R1-05", "First comment");
        writer.append(tempDir.toString(), 1, "Comments", "R1-07", "Second comment");

        String content = Files.readString(tempDir.resolve("decisions/human-round-1.md"));
        assertThat(content).contains("### R1-05");
        assertThat(content).contains("### R1-07");
        long sectionCount = content.lines().filter(l -> l.equals("## Comments")).count();
        assertThat(sectionCount).isEqualTo(1);
    }

    @Test
    void append_differentSections(@TempDir Path tempDir) throws IOException {
        DecisionFileWriter writer = new DecisionFileWriter();
        writer.append(tempDir.toString(), 2, "Comments", "R1-05", "A comment");
        writer.append(tempDir.toString(), 2, "Overrides", "R1-03", "Override reason");

        String content = Files.readString(tempDir.resolve("decisions/human-round-2.md"));
        assertThat(content).contains("## Comments");
        assertThat(content).contains("## Overrides");
    }

    @Test
    void append_differentRounds_separateFiles(@TempDir Path tempDir) throws IOException {
        DecisionFileWriter writer = new DecisionFileWriter();
        writer.append(tempDir.toString(), 1, "Comments", "R1-05", "Round 1");
        writer.append(tempDir.toString(), 2, "Comments", "R2-01", "Round 2");

        assertThat(tempDir.resolve("decisions/human-round-1.md")).exists();
        assertThat(tempDir.resolve("decisions/human-round-2.md")).exists();
    }

    @Test
    void append_nullWorkspacePath_silentlySkips() {
        DecisionFileWriter writer = new DecisionFileWriter();
        writer.append(null, 1, "Comments", "R1-05", "content");
    }
}
