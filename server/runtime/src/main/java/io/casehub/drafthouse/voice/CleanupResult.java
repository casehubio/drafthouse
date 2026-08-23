package io.casehub.drafthouse.voice;

import java.util.List;

public record CleanupResult(
        String cleanedText,
        String title,
        String summary,
        List<String> tags,
        String language
) {
    public CleanupResult {
        if (cleanedText == null) throw new NullPointerException("cleanedText");
        if (title == null) throw new NullPointerException("title");
        tags = tags != null ? List.copyOf(tags) : List.of();
    }
}
