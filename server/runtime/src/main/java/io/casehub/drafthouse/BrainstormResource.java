package io.casehub.drafthouse;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/api/brainstorm")
public class BrainstormResource {

    private static final Set<String> BROWSER_STATUSES =
            Set.of("ELIMINATED", "RECOMMENDED", "SELECTED");

    record StatusRequest(String status) {}
    record SessionInfo(String sessionId, String state, int optionCount) {}

    @Inject BrainstormService service;
    @Inject BrainstormSessionRegistry registry;
    @Inject WebSocketEventBus eventBus;

    @PATCH
    @Path("/{sessionId}/options/{optionId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response patchOption(
            @PathParam("sessionId") String sessionId,
            @PathParam("optionId") String optionId,
            StatusRequest request) {

        if (request == null || request.status() == null
                || !BROWSER_STATUSES.contains(request.status())) {
            return Response.status(400)
                    .entity(Map.of("error", "status must be one of: " + BROWSER_STATUSES))
                    .build();
        }

        BrainstormSession session;
        try {
            session = service.resolve(sessionId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage());
        }

        BrainstormOption option = session.findOption(optionId).orElse(null);
        if (option == null) {
            throw new NotFoundException("Unknown option: " + optionId);
        }

        String optionTitle = option.title();

        try {
            switch (request.status()) {
                case "ELIMINATED" -> service.markEliminated(sessionId, optionId);
                case "RECOMMENDED" -> service.setRecommendation(sessionId, optionId);
                case "SELECTED" -> service.markSelected(sessionId, optionId);
            }
        } catch (IllegalStateException e) {
            return Response.status(409)
                    .entity(Map.of("error", e.getMessage())).build();
        }

        eventBus.broadcast("brainstorm-user-action", Map.of(
                "sessionId", sessionId,
                "optionId", optionId,
                "optionTitle", optionTitle,
                "action", request.status()));

        return Response.ok(Map.of("status", "ok")).build();
    }

    @GET
    @Path("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    public List<SessionInfo> sessions() {
        return registry.activeSessions().stream()
                .map(s -> new SessionInfo(s.sessionId(), s.state().name(), s.options().size()))
                .toList();
    }
}
