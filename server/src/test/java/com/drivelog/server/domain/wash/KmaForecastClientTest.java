package com.drivelog.server.domain.wash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KmaForecastClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KmaForecastClient client = new KmaForecastClient(json, "", HttpClient.newHttpClient());

    @Test void parsesShortForecastCategoriesWithoutInventingMissingValues() throws Exception {
        var items = json.readTree("""
            [{"fcstDate":"20261109","fcstTime":"1200","category":"POP","fcstValue":"80"},
             {"fcstDate":"20261109","fcstTime":"1200","category":"PTY","fcstValue":"1"},
             {"fcstDate":"20261109","fcstTime":"1200","category":"SKY","fcstValue":"4"},
             {"fcstDate":"20261109","fcstTime":"1200","category":"PCP","fcstValue":"1mm 미만"},
             {"fcstDate":"20261109","fcstTime":"1500","category":"PTY","fcstValue":"0"}]
            """);
        var slots = KmaForecastClient.parseShortItems(items);
        assertThat(slots).hasSize(2);
        assertThat(slots.getFirst().pop()).isEqualTo(80);
        assertThat(slots.getFirst().precipitation()).isTrue();
        assertThat(slots.get(1).pop()).isNull();
    }

    @Test void parsesMidTermMorningAfternoonAndUsesMaximumProbability() throws Exception {
        var item = json.readTree("""
            {"regId":"11B00000","rnSt4Am":30,"rnSt4Pm":70,
             "wf4Am":"구름많음","wf4Pm":"흐리고 비","rnSt5Am":null,"rnSt5Pm":20,"wf5Pm":"맑음"}
            """);
        var days = KmaForecastClient.parseMidItem(item, LocalDate.of(2026, 11, 9));
        assertThat(days).hasSize(2);
        assertThat(days.getFirst().date()).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(days.getFirst().maxPop()).isEqualTo(70);
        assertThat(days.getFirst().weather()).isEqualTo(WashForecast.WeatherType.RAINY);
        assertThat(days.get(1).amPop()).isNull();
    }

    @Test void distinguishesNoDataAuthRateLimitAndMalformedResponses() {
        assertThat(client.body("{\"response\":{\"header\":{\"resultCode\":\"03\"}}}")).isNull();
        assertThatThrownBy(() -> client.body("{\"response\":{\"header\":{\"resultCode\":\"30\"}}}"))
                .isInstanceOf(KmaException.class).extracting("kind").isEqualTo(KmaException.Kind.AUTH);
        assertThatThrownBy(() -> client.body("<resultCode>22</resultCode>"))
                .isInstanceOf(KmaException.class).extracting("kind").isEqualTo(KmaException.Kind.RATE_LIMIT);
        assertThatThrownBy(() -> client.body("not json"))
                .isInstanceOf(KmaException.class).extracting("kind").isEqualTo(KmaException.Kind.INVALID_DATA);
    }

    @Test void shortAndMidHttpRequestsUseOneEncodedKeyAndOfficialParameters() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> shortQuery = new AtomicReference<>();
        AtomicReference<String> midQuery = new AtomicReference<>();
        server.createContext(KmaForecastClient.SHORT_PATH, exchange -> {
            shortQuery.set(exchange.getRequestURI().getRawQuery());
            reply(exchange, """
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":2,"items":{"item":[
                {"fcstDate":"20261109","fcstTime":"1200","category":"POP","fcstValue":"70"},
                {"fcstDate":"20261109","fcstTime":"1200","category":"PTY","fcstValue":"1"}]}}}}
                """);
        });
        server.createContext(KmaForecastClient.MID_PATH, exchange -> {
            midQuery.set(exchange.getRequestURI().getRawQuery());
            reply(exchange, """
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":1,"items":{"item":[
                {"regId":"11B00000","rnSt4Am":20,"rnSt4Pm":60,"wf4Am":"맑음","wf4Pm":"비"}]}}}}
                """);
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var liveClient = new KmaForecastClient(json, "a+b/c", HttpClient.newHttpClient(), base);
            assertThat(liveClient.shortTerm(WashRegion.SEOUL, LocalDateTime.of(2026, 11, 9, 8, 0)))
                    .hasSize(1);
            assertThat(liveClient.midTerm(WashRegion.SEOUL, LocalDateTime.of(2026, 11, 9, 6, 0)))
                    .hasSize(1);
            assertThat(shortQuery.get()).contains("serviceKey=a%2Bb%2Fc", "base_date=20261109", "base_time=0800", "nx=60", "ny=127");
            assertThat(midQuery.get()).contains("serviceKey=a%2Bb%2Fc", "regId=11B00000", "tmFc=202611090600");
            new KmaForecastClient(json, "a%2Bb%2Fc", HttpClient.newHttpClient(), base)
                    .shortTerm(WashRegion.SEOUL, LocalDateTime.of(2026, 11, 9, 8, 0));
            assertThat(shortQuery.get()).contains("serviceKey=a%2Bb%2Fc").doesNotContain("%252B");
        } finally {
            server.stop(0);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
