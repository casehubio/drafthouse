package io.casehub.drafthouse;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class BrainstormResourceTest {

    @Inject BrainstormService service;
    @Inject BrainstormSessionRegistry registry;

    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = service.startSession();
        service.presentOptions(sessionId, List.of(
                new BrainstormService.OptionInput("A", "Option A", "Desc A", "Trade A"),
                new BrainstormService.OptionInput("B", "Option B", "Desc B", "Trade B")));
    }

    @AfterEach
    void tearDown() {
        if (sessionId != null) registry.remove(sessionId);
    }

    @Test
    void patchOption_eliminate_returns200() {
        given()
            .contentType("application/json")
            .body("{\"status\":\"ELIMINATED\"}")
        .when()
            .patch("/api/brainstorm/" + sessionId + "/options/A")
        .then()
            .statusCode(200);

        assertThat(registry.find(sessionId).get().findOption("A").get().status())
                .isEqualTo(BrainstormOption.Status.ELIMINATED);
    }

    @Test
    void patchOption_recommend_returns200() {
        given()
            .contentType("application/json")
            .body("{\"status\":\"RECOMMENDED\"}")
        .when()
            .patch("/api/brainstorm/" + sessionId + "/options/A")
        .then()
            .statusCode(200);

        assertThat(registry.find(sessionId).get().findOption("A").get().status())
                .isEqualTo(BrainstormOption.Status.RECOMMENDED);
    }

    @Test
    void patchOption_select_convergesSession() {
        given()
            .contentType("application/json")
            .body("{\"status\":\"SELECTED\"}")
        .when()
            .patch("/api/brainstorm/" + sessionId + "/options/A")
        .then()
            .statusCode(200);

        assertThat(registry.find(sessionId).get().state())
                .isEqualTo(BrainstormSession.State.CONVERGED);
        sessionId = null;
    }

    @Test
    void patchOption_invalidStatus_returns400() {
        given()
            .contentType("application/json")
            .body("{\"status\":\"EXPLORED\"}")
        .when()
            .patch("/api/brainstorm/" + sessionId + "/options/A")
        .then()
            .statusCode(400);
    }

    @Test
    void patchOption_unknownSession_returns404() {
        given()
            .contentType("application/json")
            .body("{\"status\":\"ELIMINATED\"}")
        .when()
            .patch("/api/brainstorm/nonexistent/options/A")
        .then()
            .statusCode(404);
    }

    @Test
    void patchOption_unknownOption_returns404() {
        given()
            .contentType("application/json")
            .body("{\"status\":\"ELIMINATED\"}")
        .when()
            .patch("/api/brainstorm/" + sessionId + "/options/UNKNOWN")
        .then()
            .statusCode(404);
    }

    @Test
    void patchOption_invalidTransition_returns409() {
        service.markEliminated(sessionId, "A");

        given()
            .contentType("application/json")
            .body("{\"status\":\"RECOMMENDED\"}")
        .when()
            .patch("/api/brainstorm/" + sessionId + "/options/A")
        .then()
            .statusCode(409);
    }

    @Test
    void getSessions_returnsActiveSessions() {
        given()
        .when()
            .get("/api/brainstorm/sessions")
        .then()
            .statusCode(200)
            .body("size()", org.hamcrest.Matchers.greaterThanOrEqualTo(1));
    }
}
