package com.drivelog.server.domain.drive.service;

import com.drivelog.server.domain.drive.dto.DriveCreateRequest;
import com.drivelog.server.domain.drive.dto.DriveResponse;
import com.drivelog.server.domain.drive.entity.Drive;
import com.drivelog.server.domain.drive.repository.DriveRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class DriveService {
    private final DriveRepository repository;

    public DriveService(DriveRepository repository) { this.repository = repository; }

    @Transactional
    public DriveResponse create(DriveCreateRequest request) {
        return DriveResponse.from(repository.save(new Drive(request.startedAt(), request.endedAt(),
                request.durationSeconds(), request.distanceKm(), request.averageEfficiency(),
                request.efficiencyUnit(), request.sourceType())));
    }

    public List<DriveResponse> recent(int limit) {
        return repository.findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "startedAt", "id")))
                .map(DriveResponse::from).getContent();
    }

    public DriveResponse get(long id) {
        return repository.findById(id).map(DriveResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Drive not found"));
    }
}
