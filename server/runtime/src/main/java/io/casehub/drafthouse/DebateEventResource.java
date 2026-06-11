package io.casehub.drafthouse;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.casehub.drafthouse.debate.DebateStreamEntry;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

@ApplicationScoped
@Path("/api/debate")
public class DebateEventResource {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(DebateEventResource.class.getName());

    @Inject DebateSessionRegistry registry;
    @Inject MessageService messageService;
    @Inject ObjectMapper mapper;

    record SessionInfo(String debateSessionId, String channelName, String specPath) {}

    @GET
    @Path("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<SessionInfo> activeSessions() {
        return registry.activeSessions().stream()
                .map(s -> new SessionInfo(s.debateSessionId(), s.channelName(), s.specPath()))
                .toList();
    }

    @GET
    @Path("/{debateSessionId}/events")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @io.smallrye.common.annotation.Blocking
    public Multi<String> events(@PathParam("debateSessionId") String debateSessionId) {
        UUID channelId;
        try {
            channelId = UUID.fromString(debateSessionId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Invalid session id: " + debateSessionId);
        }

        DebateSession session = registry.find(channelId).orElse(null);
        if (session == null) {
            throw new NotFoundException("No active debate session: " + debateSessionId);
        }

        AtomicLong lastSentId = new AtomicLong(0L);

        Multi<String> catchUp = Multi.createFrom().uni(
                Uni.createFrom().item(() -> {
                    List<Message> messages = messageService.pollAfter(channelId, 0L, 500);
                    return serializeMessages(messages, lastSentId);
                })
        ).filter(Objects::nonNull);

        Multi<String> live = Multi.createFrom().ticks().every(Duration.ofMillis(500))
                .onItem().transformToUniAndConcatenate(tick ->
                        Uni.createFrom().item(() -> {
                            List<Message> messages = messageService.pollAfter(
                                    channelId, lastSentId.get(), 50);
                            if (messages.isEmpty()) return "{\"type\":\"heartbeat\"}";
                            return serializeMessages(messages, lastSentId);
                        })
                        .onFailure().invoke(e -> LOG.warning(
                                "SSE tick failed for " + debateSessionId + ": " + e.getMessage()))
                        .onFailure().recoverWithItem("{\"type\":\"heartbeat\"}")
                );

        return Multi.createBy().concatenating().streams(catchUp, live);
    }

    private String serializeMessages(List<Message> messages, AtomicLong lastSentId) {
        List<DebateStreamEntry> entries = messages.stream()
                .map(DebateStreamEntry::from)
                .filter(Objects::nonNull)
                .toList();

        if (!messages.isEmpty()) {
            long maxId = messages.stream()
                    .mapToLong(m -> m.id)
                    .max()
                    .orElse(lastSentId.get());
            lastSentId.set(maxId);
        }

        if (entries.isEmpty()) return null;

        try {
            return mapper.writeValueAsString(entries);
        } catch (Exception e) {
            LOG.warning("Failed to serialize debate events: " + e.getMessage());
            return null;
        }
    }
}
