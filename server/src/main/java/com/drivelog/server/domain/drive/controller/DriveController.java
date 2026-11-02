package com.drivelog.server.domain.drive.controller;

import com.drivelog.server.domain.drive.dto.DriveCreateRequest;
import com.drivelog.server.domain.drive.dto.DriveResponse;
import com.drivelog.server.domain.drive.service.DriveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Drive", description = "Local demo only; no per-user isolation or source attestation")
@RestController
@RequestMapping("/api/v1/drives")
public class DriveController {
    private final DriveService service;

    public DriveController(DriveService service) { this.service = service; }

    @Operation(summary = "Create a drive record")
    @PostMapping
    public ResponseEntity<DriveResponse> create(@Valid @RequestBody DriveCreateRequest request) {
        DriveResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/drives/" + response.id())).body(response);
    }

    @Operation(summary = "List recent drives, newest start first")
    @GetMapping
    public List<DriveResponse> recent(@RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit) {
        return service.recent(limit);
    }

    @GetMapping("/{id}")
    public DriveResponse get(@PathVariable("id") @Positive long id) { return service.get(id); }
}
