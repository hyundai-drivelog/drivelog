package com.drivelog.server.domain.journey;

import com.drivelog.server.domain.journey.JourneyDtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Goals, visits and records. Everything except link endpoints is scoped by the caller's account. */
@Service
public class JourneyService {
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient db;
    private final ObjectMapper json;
    private final Path uploadDir;
    private final String webUrl;
    private final StampCollection stampCollection;

    public JourneyService(JdbcClient db, ObjectMapper json,
                          @Value("${drivelog.upload-dir}") String uploadDir,
                          @Value("${drivelog.web-url:}") String webUrl) {
        this.db = db;
        this.json = json;
        this.stampCollection = new StampCollection(json);
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
        this.webUrl = webUrl;
    }

    private static ResponseStatusException status(HttpStatus status, String reason) {
        return new ResponseStatusException(status, reason);
    }

    private static OffsetDateTime time(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    private static OffsetDateTime time(long millis) { return time(Instant.ofEpochMilli(millis)); }

    private static Long millis(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant().toEpochMilli();
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // --- Account linking -------------------------------------------------------------------------

    public String accountFor(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw status(HttpStatus.UNAUTHORIZED, "token required");
        return db.sql("SELECT account_id FROM drivelog.device_tokens WHERE token_hash = ?")
                .param(hash(authorization.substring(7).trim())).query(String.class).optional()
                .orElseThrow(() -> status(HttpStatus.UNAUTHORIZED, "unknown token"));
    }

    @Transactional
    public LinkCodeResponse createLinkCode() {
        Instant now = Instant.now();
        db.sql("DELETE FROM drivelog.link_codes WHERE expires_at < ?").param(time(now.minus(Duration.ofHours(1)))).update();
        String code = null;
        for (int attempt = 0; attempt < 20 && code == null; attempt++) {
            String candidate = "%06d".formatted(RANDOM.nextInt(1_000_000));
            boolean inUse = db.sql("SELECT COUNT(*) FROM drivelog.link_codes WHERE code = ? AND expires_at > ? AND claimed = FALSE")
                    .params(candidate, time(now)).query(Long.class).single() > 0;
            if (!inUse) code = candidate;
        }
        if (code == null) throw status(HttpStatus.SERVICE_UNAVAILABLE, "no code available");
        String poll = newToken();
        Instant expires = now.plus(CODE_LIFETIME);
        db.sql("INSERT INTO drivelog.link_codes (poll_hash, code, expires_at, claimed) VALUES (?, ?, ?, FALSE)")
                .params(hash(poll), code, time(expires)).update();
        return new LinkCodeResponse(poll, code, expires.toEpochMilli(), webUrl);
    }

    @Transactional
    public LinkPollResponse pollLink(String pollToken) {
        String key = hash(pollToken);
        var row = db.sql("SELECT claimed, car_token, expires_at FROM drivelog.link_codes WHERE poll_hash = ?").param(key)
                .query((rs, n) -> new Object[]{rs.getBoolean(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class)})
                .optional().orElseThrow(() -> status(HttpStatus.NOT_FOUND, "unknown code"));
        String carToken = (String) row[1];
        if ((boolean) row[0] && carToken != null) {
            // The token leaves the database the moment the car has received it.
            db.sql("UPDATE drivelog.link_codes SET car_token = NULL WHERE poll_hash = ?").param(key).update();
            return new LinkPollResponse(true, false, carToken);
        }
        return new LinkPollResponse(false, ((OffsetDateTime) row[2]).toInstant().isBefore(Instant.now()), null);
    }

    /** A phone that already has an account brings its goals with it; otherwise a new account starts. */
    @Transactional
    public LinkClaimResponse claimLink(String code, String authorization) {
        Instant now = Instant.now();
        String pollHash = db.sql("SELECT poll_hash FROM drivelog.link_codes WHERE code = ? AND claimed = FALSE AND expires_at > ?")
                .params(code, time(now)).query(String.class).optional()
                .orElseThrow(() -> status(HttpStatus.NOT_FOUND, "invalid or expired code"));
        String account;
        if (authorization != null) account = accountFor(authorization);
        else {
            account = UUID.randomUUID().toString();
            db.sql("INSERT INTO drivelog.accounts (id, created_at) VALUES (?, ?)").params(account, time(now)).update();
        }
        String phoneToken = newToken();
        String carToken = newToken();
        db.sql("INSERT INTO drivelog.device_tokens (token_hash, account_id, device, created_at) VALUES (?, ?, 'PHONE', ?)")
                .params(hash(phoneToken), account, time(now)).update();
        db.sql("INSERT INTO drivelog.device_tokens (token_hash, account_id, device, created_at) VALUES (?, ?, 'CAR', ?)")
                .params(hash(carToken), account, time(now)).update();
        int claimed = db.sql("UPDATE drivelog.link_codes SET claimed = TRUE, car_token = ? WHERE poll_hash = ? AND claimed = FALSE")
                .params(carToken, pollHash).update();
        if (claimed != 1) throw status(HttpStatus.CONFLICT, "code already used");
        return new LinkClaimResponse(phoneToken);
    }

    // --- Goals -----------------------------------------------------------------------------------

    private List<PlaceView> places(String goalId) {
        return db.sql("SELECT * FROM drivelog.places WHERE goal_id = ? ORDER BY position").param(goalId)
                .query((rs, n) -> new PlaceView(rs.getString("id"), rs.getInt("position"), rs.getString("name"),
                        rs.getString("address"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                        rs.getString("poi_id"), rs.getString("poi_sub_id"), rs.getString("poi_provider"),
                        rs.getString("visit_record_id"), millis(rs, "visited_at"))).list();
    }

    private GoalView goalRow(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        return new GoalView(id, rs.getString("name"), rs.getString("status"), millis(rs, "created_at"),
                millis(rs, "achieved_at"), millis(rs, "archived_at"), places(id));
    }

    private GoalView requireGoal(String account, String goalId) {
        return db.sql("SELECT * FROM drivelog.goals WHERE id = ? AND account_id = ?").params(goalId, account)
                .query((rs, n) -> goalRow(rs)).optional()
                .orElseThrow(() -> status(HttpStatus.NOT_FOUND, "goal not found"));
    }

    /** Idempotent by the car-generated id: an offline car may send the same goal again after reconnecting. */
    @Transactional
    public GoalView putGoal(String account, String goalId, GoalPutRequest request) {
        String owner = db.sql("SELECT account_id FROM drivelog.goals WHERE id = ?").param(goalId).query(String.class).optional().orElse(null);
        if (owner != null && !owner.equals(account)) throw status(HttpStatus.CONFLICT, "goal id in use");
        if (owner != null) return requireGoal(account, goalId);
        boolean hasActive = db.sql("SELECT COUNT(*) FROM drivelog.goals WHERE account_id = ? AND status <> 'ARCHIVED'")
                .param(account).query(Long.class).single() > 0;
        if (hasActive) throw status(HttpStatus.CONFLICT, "진행 중인 목표가 이미 있습니다.");
        db.sql("INSERT INTO drivelog.goals (id, account_id, name, status, created_at) VALUES (?, ?, ?, 'ACTIVE', ?)")
                .params(goalId, account, request.name().trim(), time(Instant.now())).update();
        int position = 0;
        for (PlaceInput place : request.places()) insertPlace(goalId, position++, place);
        return requireGoal(account, goalId);
    }

    private void insertPlace(String goalId, int position, PlaceInput place) {
        db.sql("INSERT INTO drivelog.places (id, goal_id, position, name, address, latitude, longitude, poi_id, poi_sub_id, poi_provider) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(place.id() == null ? UUID.randomUUID().toString() : place.id(), goalId, position, place.name().trim(),
                        place.address() == null ? "" : place.address().trim(), place.latitude(), place.longitude(),
                        place.poiId(), place.poiSubId(), place.poiProvider()).update();
    }

    @Transactional
    public GoalView renameGoal(String account, String goalId, String name) {
        requireGoal(account, goalId);
        db.sql("UPDATE drivelog.goals SET name = ? WHERE id = ?").params(name.trim(), goalId).update();
        return requireGoal(account, goalId);
    }

    /** Places can only change before their visit is confirmed. */
    @Transactional
    public GoalView replacePlace(String account, String goalId, String placeId, PlaceInput place) {
        requireGoal(account, goalId);
        int changed = db.sql("""
                UPDATE drivelog.places SET name = ?, address = ?, latitude = ?, longitude = ?, poi_id = ?, poi_sub_id = ?, poi_provider = ?
                WHERE id = ? AND goal_id = ? AND visit_record_id IS NULL""")
                .params(place.name().trim(), place.address() == null ? "" : place.address().trim(),
                        place.latitude(), place.longitude(), place.poiId(), place.poiSubId(), place.poiProvider(), placeId, goalId).update();
        if (changed != 1) throw status(HttpStatus.CONFLICT, "이미 방문한 장소는 변경할 수 없습니다.");
        return requireGoal(account, goalId);
    }

    /** Removes the goal, its records and their stored photos. Previously exported images are unaffected. */
    @Transactional
    public void deleteGoal(String account, String goalId) {
        requireGoal(account, goalId);
        db.sql("SELECT photo_file FROM drivelog.drive_records WHERE goal_id = ? AND photo_file IS NOT NULL")
                .param(goalId).query(String.class).list().forEach(this::removeFile);
        db.sql("DELETE FROM drivelog.goals WHERE id = ?").param(goalId).update();
    }

    @Transactional
    public GoalView archiveGoal(String account, String goalId) {
        GoalView goal = requireGoal(account, goalId);
        if (!"ACHIEVED".equals(goal.status())) throw status(HttpStatus.CONFLICT, "달성한 목표만 보관할 수 있습니다.");
        db.sql("UPDATE drivelog.goals SET status = 'ARCHIVED', archived_at = ? WHERE id = ?")
                .params(time(Instant.now()), goalId).update();
        return requireGoal(account, goalId);
    }

    // --- Records ---------------------------------------------------------------------------------

    private RecordView recordRow(ResultSet rs) throws SQLException {
        List<List<Double>> route;
        try {
            route = json.readValue(rs.getString("route_json"), new TypeReference<>() {});
        } catch (JsonProcessingException corrupt) {
            route = new ArrayList<>();
        }
        double distance = rs.getDouble("distance_km");
        Double nullableDistance = rs.wasNull() ? null : distance;
        return new RecordView(rs.getString("id"), rs.getString("goal_id"), rs.getString("place_id"),
                rs.getString("place_name"), millis(rs, "started_at"), millis(rs, "ended_at"),
                rs.getLong("elapsed_seconds"), nullableDistance, rs.getString("distance_source"),
                rs.getString("status"), rs.getBoolean("simulated"), route, rs.getString("title"),
                rs.getString("card_json"), rs.getString("photo_file") != null);
    }

    private RecordView requireRecord(String account, String recordId) {
        return db.sql("SELECT * FROM drivelog.drive_records WHERE id = ? AND account_id = ?").params(recordId, account)
                .query((rs, n) -> recordRow(rs)).optional()
                .orElseThrow(() -> status(HttpStatus.NOT_FOUND, "record not found"));
    }

    public List<StampCollection.Visit> stamps(String account) {
        // Do not use the journey response's 200-record window: old visits must stay visible.
        var records = db.sql("SELECT * FROM drivelog.drive_records WHERE account_id = ? ORDER BY started_at, id")
                .param(account).query((rs, n) -> recordRow(rs)).list();
        return stampCollection.collect(records);
    }

    /** Idempotent by the client-generated id, so sync retries can never store a drive twice. */
    @Transactional
    public RecordView upsertRecord(String account, String recordId, RecordUpsertRequest request) {
        String owner = db.sql("SELECT account_id FROM drivelog.drive_records WHERE id = ?").param(recordId)
                .query(String.class).optional().orElse(null);
        if (owner != null && !owner.equals(account)) throw status(HttpStatus.CONFLICT, "record id in use");
        if (owner != null) return requireRecord(account, recordId);
        if (request.endedAtMillis() < request.startedAtMillis()) throw status(HttpStatus.BAD_REQUEST, "end before start");
        if (request.goalId() != null) requireGoal(account, request.goalId());
        String route;
        try {
            route = json.writeValueAsString(request.route());
        } catch (JsonProcessingException invalid) {
            throw status(HttpStatus.BAD_REQUEST, "invalid route");
        }
        db.sql("""
                INSERT INTO drivelog.drive_records (id, account_id, goal_id, place_id, place_name, started_at, ended_at,
                    elapsed_seconds, distance_km, distance_source, status, simulated, route_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(recordId, account, request.goalId(), request.placeId(), request.placeName(),
                        time(request.startedAtMillis()), time(request.endedAtMillis()), request.elapsedSeconds(),
                        request.distanceKm(), request.distanceSource(), request.status(), request.simulated(),
                        route, time(Instant.now())).update();
        return requireRecord(account, recordId);
    }

    /** Progress counts places, not confirmations: a second confirmation of a place changes nothing. */
    @Transactional
    public GoalView confirmVisit(String account, String recordId) {
        RecordView record = requireRecord(account, recordId);
        if (record.goalId() == null || record.placeId() == null) throw status(HttpStatus.CONFLICT, "record has no target place");
        db.sql("""
                UPDATE drivelog.places SET visit_record_id = ?, visited_at = ?
                WHERE id = ? AND goal_id = ? AND visit_record_id IS NULL""")
                .params(recordId, time(Instant.now()), record.placeId(), record.goalId()).update();
        refreshAchievement(record.goalId());
        return requireGoal(account, record.goalId());
    }

    @Transactional
    public GoalView cancelVisit(String account, String recordId) {
        RecordView record = requireRecord(account, recordId);
        if (record.goalId() == null) throw status(HttpStatus.CONFLICT, "record has no goal");
        db.sql("UPDATE drivelog.places SET visit_record_id = NULL, visited_at = NULL WHERE goal_id = ? AND visit_record_id = ?")
                .params(record.goalId(), recordId).update();
        refreshAchievement(record.goalId());
        return requireGoal(account, record.goalId());
    }

    private void refreshAchievement(String goalId) {
        long open = db.sql("SELECT COUNT(*) FROM drivelog.places WHERE goal_id = ? AND visit_record_id IS NULL")
                .param(goalId).query(Long.class).single();
        if (open == 0) {
            db.sql("UPDATE drivelog.goals SET status = 'ACHIEVED', achieved_at = COALESCE(achieved_at, ?) WHERE id = ? AND status = 'ACTIVE'")
                    .params(time(Instant.now()), goalId).update();
        } else {
            db.sql("UPDATE drivelog.goals SET status = 'ACTIVE', achieved_at = NULL WHERE id = ? AND status = 'ACHIEVED'")
                    .param(goalId).update();
        }
    }

    @Transactional
    public RecordView updateCard(String account, String recordId, CardUpdateRequest request) {
        requireRecord(account, recordId);
        db.sql("UPDATE drivelog.drive_records SET title = ?, card_json = ? WHERE id = ?")
                .params(request.title(), request.cardJson(), recordId).update();
        return requireRecord(account, recordId);
    }

    @Transactional
    public void deleteRecord(String account, String recordId) {
        RecordView record = requireRecord(account, recordId);
        deletePhoto(account, recordId);
        db.sql("UPDATE drivelog.places SET visit_record_id = NULL, visited_at = NULL WHERE visit_record_id = ?").param(recordId).update();
        db.sql("DELETE FROM drivelog.drive_records WHERE id = ?").param(recordId).update();
        if (record.goalId() != null) refreshAchievement(record.goalId());
    }

    // --- Photos ----------------------------------------------------------------------------------

    private static String extensionOf(byte[] data) {
        if (data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) return "jpg";
        if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return "png";
        throw status(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "JPEG 또는 PNG만 업로드할 수 있습니다.");
    }

    @Transactional
    public void storePhoto(String account, String recordId, byte[] data) {
        requireRecord(account, recordId);
        String file = UUID.randomUUID() + "." + extensionOf(data);
        try {
            Files.createDirectories(uploadDir);
            Files.write(uploadDir.resolve(file), data);
        } catch (IOException failure) {
            throw status(HttpStatus.INTERNAL_SERVER_ERROR, "photo could not be stored");
        }
        deletePhoto(account, recordId);
        db.sql("UPDATE drivelog.drive_records SET photo_file = ? WHERE id = ?").params(file, recordId).update();
    }

    public record StoredPhoto(byte[] data, String contentType) {}

    public StoredPhoto loadPhoto(String account, String recordId) {
        requireRecord(account, recordId);
        String file = db.sql("SELECT photo_file FROM drivelog.drive_records WHERE id = ?").param(recordId)
                .query((rs, n) -> rs.getString(1)).optional().orElse(null);
        if (file == null) throw status(HttpStatus.NOT_FOUND, "no photo");
        try {
            return new StoredPhoto(Files.readAllBytes(uploadDir.resolve(file)), file.endsWith("png") ? "image/png" : "image/jpeg");
        } catch (IOException missing) {
            throw status(HttpStatus.NOT_FOUND, "no photo");
        }
    }

    @Transactional
    public void deletePhoto(String account, String recordId) {
        requireRecord(account, recordId);
        db.sql("SELECT photo_file FROM drivelog.drive_records WHERE id = ?").param(recordId)
                .query((rs, n) -> rs.getString(1)).optional().ifPresent(this::removeFile);
        db.sql("UPDATE drivelog.drive_records SET photo_file = NULL WHERE id = ?").param(recordId).update();
    }

    private void removeFile(String file) {
        if (file == null) return;
        try {
            Files.deleteIfExists(uploadDir.resolve(file));
        } catch (IOException ignored) {
            // The row is authoritative; an orphaned private file is not exposed by any endpoint.
        }
    }

    // --- Snapshot --------------------------------------------------------------------------------

    public JourneyState state(String account) {
        return snapshot(account, false);
    }

    /** Vehicle sync replaces its local snapshot, so a limited mobile feed would lose older drives. */
    public JourneyState vehicleState(String account) {
        return snapshot(account, true);
    }

    private JourneyState snapshot(String account, boolean complete) {
        List<GoalView> goals = db.sql("SELECT * FROM drivelog.goals WHERE account_id = ? ORDER BY created_at DESC")
                .param(account).query((rs, n) -> goalRow(rs)).list();
        GoalView active = goals.stream().filter(goal -> !"ARCHIVED".equals(goal.status())).findFirst().orElse(null);
        List<GoalView> archived = goals.stream().filter(goal -> "ARCHIVED".equals(goal.status())).toList();
        List<RecordView> records = db.sql("SELECT * FROM drivelog.drive_records WHERE account_id = ? ORDER BY started_at DESC, id DESC" + (complete ? "" : " LIMIT 200"))
                .param(account).query((rs, n) -> recordRow(rs)).list();
        return new JourneyState(active, archived, records);
    }
}
