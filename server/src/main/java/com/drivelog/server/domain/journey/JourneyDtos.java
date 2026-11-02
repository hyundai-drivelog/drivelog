package com.drivelog.server.domain.journey;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class JourneyDtos {
    private JourneyDtos() {}

    public record LinkCodeResponse(String pollToken, String code, long expiresAtMillis, String webUrl) {}
    public record LinkPollResponse(boolean linked, boolean expired, String accessToken) {}
    public record LinkClaimRequest(@NotBlank @Pattern(regexp = "\\d{6}") String code) {}
    public record LinkClaimResponse(String accessToken) {}

    /** A place as the car received it from the navigation app; POI fields are stored for later route requests. */
    public record PlaceInput(
            @Size(max = 36) String id,
            @Size(max = 80) String poiId,
            @Size(max = 40) String poiSubId,
            @Size(max = 40) String poiProvider,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 200) String address,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

    public record GoalPutRequest(
            @NotBlank @Size(max = 60) String name,
            @NotNull @Size(min = 3, max = 3) List<@Valid PlaceInput> places) {}

    public record PlaceView(String id, int position, String name, String address, double latitude, double longitude,
                            String poiId, String poiSubId, String poiProvider,
                            String visitRecordId, Long visitedAtMillis) {}

    public record GoalView(String id, String name, String status, long createdAtMillis, Long achievedAtMillis,
                           Long archivedAtMillis, List<PlaceView> places) {}

    public record RecordUpsertRequest(
            @Size(max = 36) String goalId,
            @Size(max = 36) String placeId,
            @NotBlank @Size(max = 80) String placeName,
            @NotNull Long startedAtMillis,
            @NotNull Long endedAtMillis,
            @NotNull @PositiveOrZero Long elapsedSeconds,
            @PositiveOrZero Double distanceKm,
            @NotNull @Pattern(regexp = "GPS_ESTIMATED|VEHICLE|NONE") String distanceSource,
            @NotNull @Pattern(regexp = "COMPLETE|PARTIAL") String status,
            boolean simulated,
            @NotNull @Size(max = 20000) List<@Size(min = 3, max = 3) List<Double>> route) {}

    public record RecordView(String id, String goalId, String placeId, String placeName, long startedAtMillis,
                             long endedAtMillis, long elapsedSeconds, Double distanceKm, String distanceSource,
                             String status, boolean simulated, List<List<Double>> route, String title,
                             String cardJson, boolean hasPhoto) {}

    public record CardUpdateRequest(@Size(max = 40) String title, @Size(max = 2000) String cardJson) {}

    public record JourneyState(GoalView activeGoal, List<GoalView> archivedGoals, List<RecordView> records) {}
}
