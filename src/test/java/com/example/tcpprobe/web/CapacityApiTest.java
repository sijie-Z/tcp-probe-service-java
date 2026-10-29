package com.example.tcpprobe.web;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 背压集成测试：活动任务达上限返回 503 + Retry-After。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "probe.rate-limit.enabled=false",
                "probe.default-timeout-ms=5000",
                "probe.max-timeout-ms=5000",
                "probe.task.max-active=1"
        })
class CapacityApiTest {

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
    void secondActiveTaskReturns503WithRetryAfter() throws IOException {
        String payload = objectMapper.writeValueAsString(Map.of(
                "targets", List.of(Map.of(
                        "host", "192.0.2.1", "port", 80, "timeout_ms", 5000))));

        assertThat(post("/api/v1/tasks", payload).getStatusCode().value()).isEqualTo(202);

        ResponseEntity<String> busy = post("/api/v1/tasks", payload);
        assertThat(busy.getStatusCode().value()).isEqualTo(503);
        assertThat(busy.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(objectMapper.readTree(busy.getBody())
                .get("error").get("code").asText()).isEqualTo("TASK_CAPACITY_EXCEEDED");
    }
}
