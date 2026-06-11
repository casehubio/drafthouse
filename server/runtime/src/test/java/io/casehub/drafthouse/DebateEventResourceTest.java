package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

@QuarkusTest
class DebateEventResourceTest {

    private static final Pattern DEBATE_ID_PATTERN =
            Pattern.compile("\"debateSessionId\":\"([^\"]+)\"");

    @Inject DebateMcpTools tools;

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

    @Test
    void invalidSessionId_returns404() {
        RestAssured.given()
                .accept("text/event-stream")
                .when()
                .get("/api/debate/00000000-0000-0000-0000-000000000000/events")
                .then()
                .statusCode(404);
    }

    @Test
    void catchUp_deliversHistoricalEvents() throws Exception {
        String startResult = tools.startDebate("test-spec.md");
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        tools.raisePoint(sessionId, "REV", 1,
                "The API is ambiguous.", "P1", "ISOLATED", "§3.2");

        String raiseResult2 = tools.raisePoint(sessionId, "REV", 1,
                "Missing validation.", "P2", "SYSTEMIC", null);
        String pointId2 = extractPointId(raiseResult2);
        tools.respondTo(sessionId, "IMP", 2, pointId2, "agree", "Will add.");

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> received =
                new java.util.concurrent.atomic.AtomicReference<>();

        String url = "http://localhost:8081/api/debate/" + sessionId + "/events";

        Thread sseThread = Thread.ofVirtual().start(() -> {
            try {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.connect();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream()))) {
                    String line;
                    StringBuilder accumulated = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        accumulated.append(line);
                        if (accumulated.toString().contains("\"entryType\":\"RAISE\"")) {
                            received.set(accumulated.toString());
                            latch.countDown();
                            return;
                        }
                    }
                }
            } catch (java.io.IOException e) {
                // expected when connection closes
            }
        });

        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("SSE catch-up events were not received within 5 seconds")
                .isTrue();

        String body = received.get();
        assertThat(body).contains("\"entryType\":\"RAISE\"");
        assertThat(body).contains("\"agentRole\":\"REV\"");
        assertThat(body).contains("The API is ambiguous.");

        sseThread.interrupt();
    }

    @Test
    void activeSessions_returnsCurrentDebates() {
        String startResult = tools.startDebate("test-spec.md");
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        String body = RestAssured.given()
                .accept(MediaType.APPLICATION_JSON)
                .when()
                .get("/api/debate/sessions")
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(body).contains(sessionId);
        assertThat(body).contains("test-spec.md");
    }

    @Test
    void activeSessions_emptyWhenNoDebates() {
        String body = RestAssured.given()
                .accept(MediaType.APPLICATION_JSON)
                .when()
                .get("/api/debate/sessions")
                .then()
                .statusCode(200)
                .extract().body().asString();

        assertThat(body).isEqualTo("[]");
    }

    private static String extractGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : "";
    }

    private String extractPointId(String raiseResult) {
        Matcher m = Pattern.compile("\"pointId\":\"([^\"]+)\"").matcher(raiseResult);
        return m.find() ? m.group(1) : "";
    }
}
