package com.example.tcpprobe.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** 限流集成测试：突发耗尽后 429 + Retry-After，/healthz 不受限。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "probe.rate-limit.enabled=true",
                "probe.rate-limit.rps=0.01",
                "probe.rate-limit.burst=2",
                "probe.default-timeout-ms=3000",
                "probe.max-timeout-ms=5000"
        })
class RateLimitApiTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    private ResponseEntity<String> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(
                "http://127.0.0.1:" + port + path, new HttpEntity<>(json, headers), String.class);
    }

    @Test
    void burstExhaustedThen429AndHealthzExempt() throws IOException {
        String payload = objectMapper.writeValueAsString(
                java.util.Map.of("targets", java.util.List.of(
                        java.util.Map.of("host", "127.0.0.1", "port", 1))));

        assertThat(post("/api/v1/tasks", payload).getStatusCode().value()).isEqualTo(202);
        assertThat(post("/api/v1/tasks", payload).getStatusCode().value()).isEqualTo(202);

        ResponseEntity<String> limited = post("/api/v1/tasks", payload);
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        JsonNode body = objectMapper.readTree(limited.getBody());
        assertThat(body.get("error").get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(limited.getHeaders().getFirst("Retry-After")).isNotBlank();
        assertThat(limited.getHeaders().getFirst("X-Request-ID"))
                .isEqualTo(body.get("error").get("request_id").asText());

        for (int index = 0; index < 5; index++) {
            assertThat(restTemplate.getForEntity(
                    "http://127.0.0.1:" + port + "/healthz", String.class)
                    .getStatusCode().value()).isEqualTo(200);
        }
    }
}
