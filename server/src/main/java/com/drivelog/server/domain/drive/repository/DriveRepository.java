package com.drivelog.server.domain.drive.repository;

import com.drivelog.server.domain.drive.entity.Drive;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriveRepository extends JpaRepository<Drive, Long> {}
