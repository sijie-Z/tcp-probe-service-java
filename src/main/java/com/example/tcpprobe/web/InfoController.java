package com.example.tcpprobe.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 服务信息与存活探针。 */
@RestController
public class InfoController {

    private static final Instant STARTED_AT = Instant.now();

    @GetMapping("/")
    public ServiceInfo index() {
        return new ServiceInfo("tcp-probe-service", "1.0.0", "/docs", List.of(
                "POST /api/v1/tasks",
                "GET /api/v1/tasks",
                "GET /api/v1/tasks/{task_id}",
                "DELETE /api/v1/tasks/{task_id}",
                "GET /healthz"));
    }

    @GetMapping("/healthz")
    public Health health() {
        double uptimeSeconds = Duration.between(STARTED_AT, Instant.now()).toMillis() / 1000.0;
        return new Health("ok", uptimeSeconds);
    }

    public record ServiceInfo(String service, String version, String docs, List<String> endpoints) {
    }

    public record Health(String status, double uptimeSeconds) {
    }
}
