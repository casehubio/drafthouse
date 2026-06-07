package io.casehub.drafthouse;

import java.util.UUID;

/**
 * Immutable snapshot of an active debate session.
 *
 * channelName is stored explicitly — it cannot be reconstructed from debateSessionId alone.
 * revInstanceId and impInstanceId identify the two debate agents in Qhorus.
 */
public record DebateSession(
        UUID channelId,         // registry key; also UUID.fromString(debateSessionId)
        String debateSessionId, // channelId.toString() — the caller's stable handle
        String channelName,     // "drafthouse/debate/d-{uuid}" — needed by end_debate for deletion
        String revInstanceId,   // "drafthouse-rev-{debateSessionId}"
        String impInstanceId    // "drafthouse-imp-{debateSessionId}"
) {}
