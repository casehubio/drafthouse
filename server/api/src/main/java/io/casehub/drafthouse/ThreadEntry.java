package io.casehub.drafthouse;

import java.time.Instant;
import java.util.Objects;

public record ThreadEntry(String threadId, String sender, String content,
                           String agentRole, Instant timestamp) {
    public ThreadEntry {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(content, "content");
    }
}
