package io.casehub.drafthouse.debate;

import io.casehub.drafthouse.SelectionScope;
import io.casehub.drafthouse.ThreadEntry;
import io.casehub.drafthouse.ThreadStatus;
import java.util.List;

public record ThreadView(
        String threadId,
        SelectionScope anchor,
        ThreadStatus status,
        List<ThreadEntry> entries,
        String createdBy) {}
