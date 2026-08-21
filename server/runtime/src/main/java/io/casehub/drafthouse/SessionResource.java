package io.casehub.drafthouse;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/sessions")
@Produces(MediaType.APPLICATION_JSON)
public class SessionResource {

    @Inject DraftHouseSessionRegistry registry;

    @GET
    public List<Map<String, Object>> list() {
        return registry.activeSessions().stream()
            .map(s -> Map.<String, Object>of(
                "id", s.id(),
                "created", s.created().toString(),
                "facets", s.activeFacets().keySet().stream().toList()
            ))
            .toList();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> create(Map<String, String> body) {
        String id = body != null && body.containsKey("id")
            ? body.get("id")
            : UUID.randomUUID().toString();
        DraftHouseSession session = registry.create(id);
        return Map.of("id", session.id(), "created", session.created().toString());
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        if (registry.find(id).isEmpty()) {
            return Response.status(404).build();
        }
        registry.remove(id);
        return Response.noContent().build();
    }
}
