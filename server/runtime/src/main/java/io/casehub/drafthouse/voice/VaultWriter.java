package io.casehub.drafthouse.voice;

import jakarta.enterprise.context.ApplicationScoped;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;

@ApplicationScoped
public class VaultWriter {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ISO_INSTANT;

    public Path writeNote(CleanupResult result, Path rawTranscript, Path vaultPath,
                          String source, int durationSecs, String sessionId,
                          String goalTag) throws IOException {
        var notesDir = vaultPath.resolve("notes");
        var rawDir = vaultPath.resolve("raw");
        Files.createDirectories(notesDir);
        Files.createDirectories(rawDir);

        var now = Instant.now();
        var slug = slugify(result.title());
        var fileName = DATE_FMT.format(now) + "-" + slug + ".md";
        var notePath = notesDir.resolve(fileName);
        var rawDest = rawDir.resolve(rawTranscript.getFileName());

        var fm = new LinkedHashMap<String, Object>();
        fm.put("title", result.title());
        fm.put("date", ISO_FMT.format(now));
        fm.put("source", source);
        if (durationSecs > 0) {
            fm.put("duration", durationSecs + "s");
        }
        fm.put("session", sessionId);
        if (goalTag != null && !goalTag.isBlank()) {
            fm.put("goal", goalTag);
        }
        if (!result.tags().isEmpty()) {
            fm.put("tags", result.tags());
        }
        if (result.summary() != null && !result.summary().isBlank()) {
            fm.put("summary", result.summary());
        }
        fm.put("raw", "../raw/" + rawTranscript.getFileName());

        var opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        var yaml = new Yaml(opts);
        var frontmatter = yaml.dump(fm);

        var content = "---\n" + frontmatter + "---\n\n" + result.cleanedText() + "\n";
        Files.writeString(notePath, content);

        Files.move(rawTranscript, rawDest);

        return notePath;
    }

    private String slugify(String title) {
        return title.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }
}
