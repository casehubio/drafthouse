package io.casehub.drafthouse;

import io.casehub.drafthouse.debate.AgentType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class HumanActionResourceTest {

    private static final Pattern DEBATE_ID_PATTERN =
            Pattern.compile("\"debateSessionId\":\"([^\"]+)\"");
    private static final Pattern POINT_ID_PATTERN =
            Pattern.compile("\"pointId\":\"([^\"]+)\"");

    @Inject DebateMcpTools tools;
    @Inject DebateSessionRegistry registry;

    private String activeDebateSessionId;

    @BeforeEach
    void setUp() {
        activeDebateSessionId = null;
    }

    @AfterEach
    void tearDown() {
        if (activeDebateSessionId != null) {
            tools.endDebate(activeDebateSessionId, false);
        }
    }

    private String startDebateAndRaisePoint() {
        String startResult = tools.startDebate("test-spec.md", null);
        activeDebateSessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        String raiseResult = tools.raisePoint(activeDebateSessionId, "REV", 1,
                "Test issue", "HIGH", "ISOLATED", null);
        return extractGroup(POINT_ID_PATTERN, raiseResult);
    }

    @Test
    void comment_addsEntryToChannel() {
        String pointId = startDebateAndRaisePoint();

        given()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"pointId\":\"" + pointId + "\",\"content\":\"Human comment\"}")
                .when()
                .post("/api/debate/" + activeDebateSessionId + "/human/comment")
                .then()
                .statusCode(200);

        DebateSession session = registry.find(UUID.fromString(activeDebateSessionId)).orElseThrow();
        assertThat(session.participants()).containsKey(AgentType.HUMAN);
    }

    @Test
    void comment_invalidSession_returns404() {
        given()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"pointId\":\"fake\",\"content\":\"comment\"}")
                .when()
                .post("/api/debate/" + UUID.randomUUID() + "/human/comment")
                .then()
                .statusCode(404);
    }

    @Test
    void comment_blankContent_returns400() {
        String pointId = startDebateAndRaisePoint();

        given()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"pointId\":\"" + pointId + "\",\"content\":\"  \"}")
                .when()
                .post("/api/debate/" + activeDebateSessionId + "/human/comment")
                .then()
                .statusCode(400);
    }

    @Test
    void raise_createsNewPoint() {
        String startResult = tools.startDebate("test-spec.md", null);
        activeDebateSessionId = extractGroup(DEBATE_ID_PATTERN, startResult);

        String body = given()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"content\":\"Human-raised point\",\"priority\":\"P1\"}")
                .when()
                .post("/api/debate/" + activeDebateSessionId + "/human/raise")
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(body).contains("pointId");
    }

    @Test
    void raise_invalidPriority_returns400() {
        String startResult = tools.startDebate("test-spec.md", null);
        activeDebateSessionId = extractGroup(DEBATE_ID_PATTERN, startResult);

        given()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"content\":\"point\",\"priority\":\"CRITICAL\"}")
                .when()
                .post("/api/debate/" + activeDebateSessionId + "/human/raise")
                .then()
                .statusCode(400);
    }

    @Test
    void override_setsHumanOverrideStatus() {
        String pointId = startDebateAndRaisePoint();

        given()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"pointId\":\"" + pointId + "\",\"reason\":\"Override reason\"}")
                .when()
                .post("/api/debate/" + activeDebateSessionId + "/human/override")
                .then()
                .statusCode(200);
    }

    @Test
    void override_alreadyResolved_returns409() {
        String pointId = startDebateAndRaisePoint();
        tools.respondTo(activeDebateSessionId, "IMP", 1, pointId, "agree", "Agreed");

        given()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"pointId\":\"" + pointId + "\",\"reason\":\"Override reason\"}")
                .when()
                .post("/api/debate/" + activeDebateSessionId + "/human/override")
                .then()
                .statusCode(409);
    }

    @Test
    void prioritise_changesPriority() {
        String pointId = startDebateAndRaisePoint();

        given()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"pointId\":\"" + pointId + "\",\"newPriority\":\"P3\"}")
                .when()
                .post("/api/debate/" + activeDebateSessionId + "/human/prioritise")
                .then()
                .statusCode(200);
    }

    @Test
    void batch_acceptsMultiplePoints() {
        String startResult = tools.startDebate("test-spec.md", null);
        activeDebateSessionId = extractGroup(DEBATE_ID_PATTERN, startResult);

        String r1 = tools.raisePoint(activeDebateSessionId, "REV", 1,
                "Issue 1", "LOW", "ISOLATED", null);
        String r2 = tools.raisePoint(activeDebateSessionId, "REV", 1,
                "Issue 2", "LOW", "ISOLATED", null);
        String pid1 = extractGroup(POINT_ID_PATTERN, r1);
        String pid2 = extractGroup(POINT_ID_PATTERN, r2);

        given()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"pointIds\":[\"" + pid1 + "\",\"" + pid2 + "\"],\"verdict\":\"VERIFIED\"}")
                .when()
                .post("/api/debate/" + activeDebateSessionId + "/human/batch")
                .then()
                .statusCode(200);
    }

    @Test
    void batch_emptyPointIds_returns400() {
        String startResult = tools.startDebate("test-spec.md", null);
        activeDebateSessionId = extractGroup(DEBATE_ID_PATTERN, startResult);

        given()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"pointIds\":[],\"verdict\":\"VERIFIED\"}")
                .when()
                .post("/api/debate/" + activeDebateSessionId + "/human/batch")
                .then()
                .statusCode(400);
    }

    @Test
    void batch_invalidVerdict_returns400() {
        String startResult = tools.startDebate("test-spec.md", null);
        activeDebateSessionId = extractGroup(DEBATE_ID_PATTERN, startResult);

        given()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"pointIds\":[\"fake\"],\"verdict\":\"APPROVED\"}")
                .when()
                .post("/api/debate/" + activeDebateSessionId + "/human/batch")
                .then()
                .statusCode(400);
    }

    private static String extractGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        assertThat(m.find()).as("Pattern %s not found in: %s", pattern, input).isTrue();
        return m.group(1);
    }
}
