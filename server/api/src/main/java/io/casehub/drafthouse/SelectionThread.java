package io.casehub.drafthouse;

import java.util.Objects;

public record SelectionThread(String threadId, SelectionScope anchor, ThreadStatus status) {
    public SelectionThread {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(status, "status");
    }

    public SelectionThread withStatus(ThreadStatus newStatus) {
        return new SelectionThread(threadId, anchor, newStatus);
    }
}
