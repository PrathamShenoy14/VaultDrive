package com.vaultdrive.common.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {
    private final com.vaultdrive.operations.Readiness readiness;
    public HealthController(com.vaultdrive.operations.Readiness readiness) { this.readiness = readiness; }

    @GetMapping("/ready")
    public org.springframework.http.ResponseEntity<HealthResponse> ready() {
        boolean ready = readiness.ready();
        return org.springframework.http.ResponseEntity.status(ready ? 200 : 503).header("Cache-Control", "no-store")
                .body(new HealthResponse(ready ? "UP" : "DOWN", "vaultdrive-api"));
    }

    @GetMapping
    public HealthResponse health() {
        return new HealthResponse(
                "UP",
                "vaultdrive-api"
        );
    }
}
