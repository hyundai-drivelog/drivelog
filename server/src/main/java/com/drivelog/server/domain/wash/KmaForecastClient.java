package com.drivelog.server.domain.wash;

import com.drivelog.server.domain.wash.WashForecast.MidDay;
import com.drivelog.server.domain.wash.WashForecast.ShortSlot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Transport and parsing stay inside the Wash domain; no existing HTTP client is changed. */
@Component
class KmaForecastClient {
    static final String SHORT_PATH = "/1360000/VilageFcstInfoService_2.0/getVilageFcst";
    static final String MID_PATH = "/1360000/MidFcstInfoService/getMidLandFcst";
    private static final DateTimeFormatter ISSUE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter ISSUE_TIME = DateTimeFormatter.ofPattern("HHmm");
    private static final DateTimeFormatter MID_ISSUE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final int PAGE_SIZE = 2000;

    private final ObjectMapper json;
    private final HttpClient http;
    private final String key;
    private final URI baseUri;

    @Autowired
    KmaForecastClient(ObjectMapper json, @Value("${KMA_SERVICE_KEY:}") String key) {
        this(json, key, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    KmaForecastClient(ObjectMapper json, String key, HttpClient http) {
        this(json, key, http, URI.create("https://apis.data.go.kr"));
    }

    KmaForecastClient(ObjectMapper json, String key, HttpClient http, URI baseUri) {
        this.json = json;
        this.key = key == null ? "" : key.trim();
        this.http = http;
        this.baseUri = baseUri;
    }

    boolean configured() { return !key.isBlank(); }

    List<ShortSlot> shortTerm(WashRegion region, LocalDateTime issue) {
        List<ShortSlot> result = new ArrayList<>();
        for (int page = 1; page <= 4; page++) {
            String body = request(SHORT_PATH, Map.of(
                    "pageNo", Integer.toString(page), "numOfRows", Integer.toString(PAGE_SIZE),
                    "dataType", "JSON", "base_date", issue.format(ISSUE_DATE),
                    "base_time", issue.format(ISSUE_TIME), "nx", Integer.toString(region.nx),
                    "ny", Integer.toString(region.ny)));
            JsonNode responseBody = body(body);
            if (responseBody == null) {
                if (page == 1) return List.of();
                throw new KmaException(KmaException.Kind.INVALID_DATA, "short forecast page missing");
            }
            result.addAll(parseShortItems(responseBody.path("items").path("item")));
            int total = responseBody.path("totalCount").asInt(0);
            if (page * PAGE_SIZE >= total) return result.stream()
                    .sorted(Comparator.comparing(ShortSlot::time)).toList();
        }
        throw new KmaException(KmaException.Kind.INVALID_DATA, "short forecast exceeds page limit");
    }

    List<MidDay> midTerm(WashRegion region, LocalDateTime issue) {
        String body = request(MID_PATH, Map.of(
                "pageNo", "1", "numOfRows", "10", "dataType", "JSON",
                "regId", region.midTermRegionCode, "tmFc", issue.format(MID_ISSUE)));
        JsonNode responseBody = body(body);
        if (responseBody == null) return List.of();
        JsonNode item = responseBody.path("items").path("item");
        if (item.isArray()) item = item.isEmpty() ? null : item.get(0);
        if (item == null || !item.isObject()) return List.of();
        return parseMidItem(item, issue.toLocalDate());
    }

    private String request(String path, Map<String, String> parameters) {
        if (!configured()) throw new KmaException(KmaException.Kind.MISSING_KEY, "service key unavailable");
        StringBuilder query = new StringBuilder("serviceKey=").append(encodedKey());
        parameters.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                query.append('&').append(entry.getKey()).append('=').append(encode(entry.getValue())));
        URI uri;
        try {
            uri = URI.create(baseUri + path + "?" + query);
        } catch (IllegalArgumentException ex) {
            throw new KmaException(KmaException.Kind.INVALID_DATA, "KMA request parameters are invalid");
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(6))
                .header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                KmaException.Kind kind = response.statusCode() == 401 || response.statusCode() == 403
                        ? KmaException.Kind.AUTH : response.statusCode() == 429
                        ? KmaException.Kind.RATE_LIMIT : KmaException.Kind.HTTP;
                throw new KmaException(kind, "KMA HTTP " + response.statusCode());
            }
            if (response.body().length() > 2_000_000) throw new KmaException(KmaException.Kind.INVALID_DATA, "KMA response too large");
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new KmaException(KmaException.Kind.HTTP, "KMA request interrupted");
        } catch (IOException ex) {
            throw new KmaException(KmaException.Kind.HTTP, "KMA request failed");
        }
    }

    private String encodedKey() {
        // Portal users may copy either the decoded key or its already percent-encoded form.
        // Preserve an encoded form verbatim, otherwise encode the decoded form exactly once.
        return key.matches("(?s).*%[0-9A-Fa-f]{2}.*") ? key.replace("+", "%2B") : encode(key);
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    JsonNode body(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            String code = root.path("response").path("header").path("resultCode").asText("");
            if (code.equals("03")) return null; // Valid request, unpublished or empty issue.
            if (!code.equals("00")) throw codeFailure(code);
            JsonNode body = root.path("response").path("body");
            if (!body.isObject()) throw new KmaException(KmaException.Kind.INVALID_DATA, "KMA body missing");
            return body;
        } catch (KmaException ex) {
            throw ex;
        } catch (Exception ex) {
            String code = xmlCode(raw);
            if (code != null) throw codeFailure(code);
            throw new KmaException(KmaException.Kind.INVALID_DATA, "KMA response is not valid JSON");
        }
    }

    private static String xmlCode(String raw) {
        var match = java.util.regex.Pattern.compile("<(?:resultCode|returnReasonCode)>([^<]{1,8})</(?:resultCode|returnReasonCode)>").matcher(raw);
        return match.find() ? match.group(1) : null;
    }

    private static KmaException codeFailure(String code) {
        KmaException.Kind kind = switch (code) {
            case "20", "30", "31" -> KmaException.Kind.AUTH;
            case "22", "23" -> KmaException.Kind.RATE_LIMIT;
            case "", "00" -> KmaException.Kind.INVALID_DATA;
            default -> KmaException.Kind.HTTP;
        };
        return new KmaException(kind, "KMA resultCode " + code);
    }

    static List<ShortSlot> parseShortItems(JsonNode items) {
        if (!items.isArray()) return List.of();
        Map<LocalDateTime, Map<String, String>> slots = new HashMap<>();
        for (JsonNode item : items) {
            try {
                LocalDateTime time = LocalDateTime.parse(item.path("fcstDate").asText()
                        + item.path("fcstTime").asText(), MID_ISSUE);
                String category = item.path("category").asText();
                if (List.of("POP", "PTY", "SKY", "PCP").contains(category)) {
                    slots.computeIfAbsent(time, ignored -> new HashMap<>())
                            .put(category, item.path("fcstValue").asText());
                }
            } catch (DateTimeParseException ignored) {
                // A malformed slot cannot be treated as a zero-percent forecast.
            }
        }
        return slots.entrySet().stream().map(entry -> {
            Map<String, String> values = entry.getValue();
            return new ShortSlot(entry.getKey(), number(values.get("POP"), 100),
                    number(values.get("PTY"), 7), number(values.get("SKY"), 4), values.get("PCP"));
        }).sorted(Comparator.comparing(ShortSlot::time)).toList();
    }

    static List<MidDay> parseMidItem(JsonNode item, LocalDate issueDate) {
        List<MidDay> days = new ArrayList<>();
        for (int offset = 4; offset <= 10; offset++) {
            Integer am = number(item.path(offset <= 7 ? "rnSt" + offset + "Am" : "rnSt" + offset).asText(null), 100);
            Integer pm = offset <= 7 ? number(item.path("rnSt" + offset + "Pm").asText(null), 100) : null;
            String amWeather = text(item, offset <= 7 ? "wf" + offset + "Am" : "wf" + offset);
            String pmWeather = offset <= 7 ? text(item, "wf" + offset + "Pm") : null;
            if (am != null || pm != null || amWeather != null || pmWeather != null)
                days.add(new MidDay(issueDate.plusDays(offset), am, pm, amWeather, pmWeather));
        }
        return days;
    }

    private static String text(JsonNode node, String name) {
        String value = node.path(name).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static Integer number(String value, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 && parsed <= maximum ? parsed : null;
        } catch (NumberFormatException ex) { return null; }
    }
}

final class KmaException extends RuntimeException {
    enum Kind { MISSING_KEY, AUTH, RATE_LIMIT, HTTP, INVALID_DATA }
    final Kind kind;
    KmaException(Kind kind, String message) { super(message); this.kind = kind; }
}
