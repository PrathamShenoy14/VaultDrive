package com.vaultdrive.job;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface BackgroundJobRepository extends JpaRepository<BackgroundJob, UUID> {
}
