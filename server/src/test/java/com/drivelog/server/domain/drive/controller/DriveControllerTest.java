package com.drivelog.server.domain.drive.controller;

import com.drivelog.server.domain.drive.dto.DriveCreateRequest;
import com.drivelog.server.domain.drive.dto.DriveResponse;
import com.drivelog.server.domain.drive.entity.DriveSourceType;
import com.drivelog.server.domain.drive.service.DriveService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DriveController.class)
class DriveControllerTest {
    private static final String VALID = """
            {"startedAt":"2026-01-01T00:00:00Z","endedAt":"2026-01-01T00:15:00Z",
             "durationSeconds":900,"distanceKm":12.450,"averageEfficiency":null,
             "efficiencyUnit":null,"sourceType":"MOCK"}
            """;

    @Autowired private MockMvc mvc;
    @MockitoBean private DriveService service;

    private DriveResponse response() {
        return new DriveResponse(91L, OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-01-01T00:15:00Z"), 900L, new BigDecimal("12.450"),
                null, null, DriveSourceType.MOCK, Instant.parse("2026-01-01T00:16:00Z"));
    }

    @Test
    void createsDtoWithLocationAndNullableEfficiency() throws Exception {
        when(service.create(any(DriveCreateRequest.class))).thenReturn(response());
        mvc.perform(post("/api/v1/drives").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/drives/91"))
                .andExpect(jsonPath("$.id").value(91)).andExpect(jsonPath("$.sourceType").value("MOCK"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"distanceKm\":-1", "\"distanceKm\":0.0001", "\"durationSeconds\":899",
            "\"endedAt\":\"2025-12-31T23:59:00Z\"", "\"sourceType\":\"OTHER\"",
            "\"averageEfficiency\":0", "\"efficiencyUnit\":\"mile/gallon\"",
            "\"startedAt\":null", "\"durationSeconds\":null", "\"sourceType\":null"
    })
    void rejectsInvalidRecordAsProblemJson(String replacement) throws Exception {
        String field = replacement.substring(0, replacement.indexOf(':'));
        String invalid = VALID.replaceAll(java.util.regex.Pattern.quote(field) + ":(?:null|[0-9.]+|\"[^\"]*\")", replacement);
        mvc.perform(post("/api/v1/drives").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void listsAndReadsDtosAndReturnsNotFound() throws Exception {
        when(service.recent(20)).thenReturn(List.of(response()));
        when(service.get(91)).thenReturn(response());
        when(service.get(999)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Drive not found"));
        mvc.perform(get("/api/v1/drives")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(91));
        mvc.perform(get("/api/v1/drives/91")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(91));
        mvc.perform(get("/api/v1/drives/999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/drives?limit=101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/drives/0")).andExpect(status().isBadRequest());
    }
}
