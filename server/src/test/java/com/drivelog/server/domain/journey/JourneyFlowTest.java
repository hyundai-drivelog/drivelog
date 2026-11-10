package com.drivelog.server.domain.journey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The whole phone-and-car contract against the real schema on in-memory H2. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:journey;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;INIT=CREATE SCHEMA IF NOT EXISTS drivelog",
        "drivelog.upload-dir=build/test-uploads"})
class JourneyFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    String phone;
    String car;
    JsonNode goal;
    String goalId;

    private JsonNode body(MockHttpServletRequestBuilder request, int expected) throws Exception {
        String content = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return content.isEmpty() ? null : json.readTree(content);
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request, String content) {
        return request.contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private int addressSuffix = 0;

    /** Claims are throttled per address, so unrelated tests must not share one. */
    private String nextAddress() { return "10.0." + (System.identityHashCode(this) % 200) + "." + (++addressSuffix); }

    private MockHttpServletRequestBuilder claim(String code, String address) {
        return withJson(post("/api/v1/link/claim"), "{\"code\":\"" + code + "\"}").with(request -> { request.setRemoteAddr(address); return request; });
    }

    private String[] link() throws Exception {
        JsonNode code = body(post("/api/v1/link/codes"), 200);
        JsonNode pending = body(get("/api/v1/link/codes/" + code.get("pollToken").asText()), 200);
        assertThat(pending.get("linked").asBoolean()).isFalse();
        String phoneToken = body(claim(code.get("code").asText(), nextAddress()), 200).get("accessToken").asText();
        String carToken = body(get("/api/v1/link/codes/" + code.get("pollToken").asText()), 200).get("accessToken").asText();
        // The car's token can only be collected once.
        assertThat(body(get("/api/v1/link/codes/" + code.get("pollToken").asText()), 200).get("accessToken").isNull()).isTrue();
        return new String[]{phoneToken, carToken};
    }

    private static final String GOAL = """
            {"name":"올해 가보고 싶은 부산 바다","places":[
            {"name":"광안리 해수욕장","address":"부산 수영구","latitude":35.15,"longitude":129.11,"poiId":"poi-1","poiSubId":"0","poiProvider":"navi"},
            {"name":"송정 해수욕장","address":"부산 해운대구","latitude":35.17,"longitude":129.19},
            {"name":"다대포 해수욕장","address":"부산 사하구","latitude":35.04,"longitude":128.96}]}""";

    private String record(String id, int place) {
        return """
            {"goalId":"%s","placeId":"%s","placeName":"장소","startedAtMillis":1000,"endedAtMillis":61000,"elapsedSeconds":60,
             "distanceKm":12.5,"distanceSource":"GPS_ESTIMATED","status":"COMPLETE","simulated":true,
             "route":[[35.1,129.1,1000],[35.2,129.2,2000]]}""".formatted(goal.get("id").asText(), goal.get("places").get(place).get("id").asText());
    }

    private void putRecord(String id, int place) throws Exception {
        body(withJson(as(car, put("/api/v1/records/" + id)), record(id, place)), 200);
    }

    @BeforeEach
    void linkAndCreateGoal() throws Exception {
        String[] tokens = link();
        phone = tokens[0];
        car = tokens[1];
        goalId = java.util.UUID.randomUUID().toString();
        goal = body(withJson(as(car, put("/api/v1/goals/" + goalId)), GOAL), 200);
    }

    @Test
    void mobileWebIsServedAtTheAddressShownOnTheCar() throws Exception {
        mvc.perform(get("/m/")).andExpect(status().isOk());
        mvc.perform(get("/m")).andExpect(status().is3xxRedirection());
    }

    @Test
    void stampsFollowRealVisitsAcrossDevicesAndDeletion() throws Exception {
        body(get("/api/v1/stamps"), 401);
        String payload = """
            {"placeName":"경복궁","startedAtMillis":1000,"endedAtMillis":61000,"elapsedSeconds":60,
             "distanceKm":1.5,"distanceSource":"GPS_ESTIMATED","status":"COMPLETE","simulated":false,
             "route":[[37.56,126.97,1000],[37.5786111,126.9772222,61000]]}
            """;
        String first = java.util.UUID.randomUUID().toString();
        body(withJson(as(car, put("/api/v1/records/" + first)), payload), 200);
        // Retrying an upload must not duplicate a stamp.
        body(withJson(as(car, put("/api/v1/records/" + first)), payload), 200);
        JsonNode visits = body(as(phone, get("/api/v1/stamps")), 200);
        assertThat(visits.size()).isEqualTo(1);
        assertThat(visits.get(0).path("place").path("id").asText()).isEqualTo("gyeongbokgung");
        assertThat(visits.get(0).path("recordId").asText()).isEqualTo(first);
        assertThat(body(as(link()[0], get("/api/v1/stamps")), 200).size()).isZero();
        // Null distance and simulation must never earn a stamp.
        for (String invalid : new String[]{payload.replace("1.5", "null"), payload.replace("false", "true")}) {
            body(withJson(as(car, put("/api/v1/records/" + java.util.UUID.randomUUID())), invalid), 200);
        }
        body(as(car, delete("/api/v1/records/" + first)), 204);
        assertThat(body(as(phone, get("/api/v1/stamps")), 200).size()).isZero();
    }

    @Test
    void goalCreatedOnPhoneAppearsOnCar() throws Exception {
        JsonNode state = body(as(car, get("/api/v1/journey")), 200);
        assertThat(state.get("activeGoal").get("name").asText()).isEqualTo("올해 가보고 싶은 부산 바다");
        assertThat(state.get("activeGoal").get("places")).hasSize(3);
    }

    @Test
    void offlineCarCanResendItsGoalSafelyAndPoiIsKept() throws Exception {
        JsonNode again = body(withJson(as(car, put("/api/v1/goals/" + goalId)), GOAL), 200);
        assertThat(again.get("id").asText()).isEqualTo(goalId);
        assertThat(again.get("places").get(0).get("poiId").asText()).isEqualTo("poi-1");
    }

    @Test
    void onlyOneActiveGoal() throws Exception {
        body(withJson(as(car, put("/api/v1/goals/" + java.util.UUID.randomUUID())), GOAL), 409);
    }

    @Test
    void goalRequiresExactlyThreePlaces() throws Exception {
        body(withJson(as(car, put("/api/v1/goals/" + java.util.UUID.randomUUID())), "{\"name\":\"x\",\"places\":[]}"), 400);
    }

    @Test
    void retriedRecordIsStoredOnce() throws Exception {
        String id = "retry-" + System.nanoTime();
        putRecord(id, 0);
        putRecord(id, 0);
        assertThat(body(as(phone, get("/api/v1/journey")), 200).get("records")).hasSize(1);
    }

    @Test
    void repeatedVisitsCountOnceAndCancelRestores() throws Exception {
        putRecord("visit-a", 0);
        putRecord("visit-b", 0);
        body(as(car, put("/api/v1/records/visit-a/visit")), 200);
        JsonNode updated = body(as(car, put("/api/v1/records/visit-b/visit")), 200);
        assertThat(updated.get("places").get(0).get("visitRecordId").asText()).isEqualTo("visit-a");
        JsonNode cancelled = body(as(phone, delete("/api/v1/records/visit-a/visit")), 200);
        assertThat(cancelled.get("places").get(0).get("visitRecordId").isNull()).isTrue();
    }

    @Test
    void threeVisitsAchieveThenArchiveFreesASlot() throws Exception {
        for (int i = 0; i < 3; i++) {
            putRecord("achieve-" + i, i);
            body(as(car, put("/api/v1/records/achieve-" + i + "/visit")), 200);
        }
        JsonNode state = body(as(phone, get("/api/v1/journey")), 200);
        assertThat(state.get("activeGoal").get("status").asText()).isEqualTo("ACHIEVED");
        body(as(phone, post("/api/v1/goals/" + goal.get("id").asText() + "/archive")), 200);
        JsonNode archived = body(as(phone, get("/api/v1/journey")), 200);
        assertThat(archived.get("activeGoal").isNull()).isTrue();
        assertThat(archived.get("archivedGoals")).hasSize(1);
        body(withJson(as(car, put("/api/v1/goals/" + java.util.UUID.randomUUID())), GOAL), 200);
    }

    @Test
    void visitedPlaceCannotBeChanged() throws Exception {
        putRecord("locked", 0);
        body(as(car, put("/api/v1/records/locked/visit")), 200);
        String placeId = goal.get("places").get(0).get("id").asText();
        body(withJson(as(phone, put("/api/v1/goals/" + goal.get("id").asText() + "/places/" + placeId)),
                "{\"name\":\"다른 곳\",\"address\":\"\",\"latitude\":35,\"longitude\":129}"), 409);
    }

    @Test
    void anotherAccountSeesNothing() throws Exception {
        putRecord("private-record", 0);
        String stranger = link()[0];
        body(as(stranger, get("/api/v1/records/private-record/photo")), 404);
        body(as(stranger, delete("/api/v1/records/private-record")), 404);
        body(as(stranger, delete("/api/v1/goals/" + goal.get("id").asText())), 404);
        assertThat(body(as(stranger, get("/api/v1/journey")), 200).get("records")).isEmpty();
        // Knowing a record id does not let another account overwrite it.
        body(withJson(as(stranger, put("/api/v1/records/private-record")), record("private-record", 0)), 409);
    }

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        body(get("/api/v1/journey"), 401);
        body(as("not-a-token", get("/api/v1/journey")), 401);
    }

    @Test
    void codeWorksOnlyOnce() throws Exception {
        JsonNode code = body(post("/api/v1/link/codes"), 200);
        String address = nextAddress();
        body(claim(code.get("code").asText(), address), 200);
        body(claim(code.get("code").asText(), address), 404);
    }

    @Test
    void guessingCodesIsThrottled() throws Exception {
        String address = "10.99.0.1";
        for (int attempt = 0; attempt < 10; attempt++) mvc.perform(claim("000000", address));
        body(claim("000000", address), 429);
    }

    @Test
    void photoRoundTripRejectsNonImagesAndDeleteCascades() throws Exception {
        putRecord("photo-record", 0);
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
        mvc.perform(as(phone, put("/api/v1/records/photo-record/photo")).contentType(MediaType.IMAGE_JPEG).content(new byte[]{1, 2, 3, 4, 5}))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(as(phone, put("/api/v1/records/photo-record/photo")).contentType(MediaType.IMAGE_JPEG).content(jpeg)).andExpect(status().isNoContent());
        byte[] stored = mvc.perform(as(phone, get("/api/v1/records/photo-record/photo"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(stored).isEqualTo(jpeg);
        body(as(phone, delete("/api/v1/goals/" + goal.get("id").asText())), 204);
        assertThat(body(as(phone, get("/api/v1/journey")), 200).get("records")).isEmpty();
    }
}
