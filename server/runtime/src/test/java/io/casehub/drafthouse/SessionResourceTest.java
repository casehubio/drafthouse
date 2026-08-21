package io.casehub.drafthouse;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class SessionResourceTest {

    @Test
    void createAndListSessions() {
        String id = "session-rest-test-" + System.nanoTime();

        given()
            .contentType(ContentType.JSON)
            .body("{\"id\": \"" + id + "\"}")
            .when().post("/api/sessions")
            .then().statusCode(200)
            .body("id", equalTo(id));

        given()
            .when().get("/api/sessions")
            .then().statusCode(200)
            .body("id", hasItem(id));

        given()
            .when().delete("/api/sessions/" + id)
            .then().statusCode(204);
    }

    @Test
    void deleteNonExistentReturns404() {
        given()
            .when().delete("/api/sessions/nonexistent-" + System.nanoTime())
            .then().statusCode(404);
    }

    @Test
    void createWithoutIdGeneratesOne() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when().post("/api/sessions")
            .then().statusCode(200)
            .body("id", notNullValue())
            .extract().path("id");

        given().when().delete("/api/sessions/" + id).then().statusCode(204);
    }
}
