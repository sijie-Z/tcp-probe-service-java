package com.example.tcpprobe.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 层集成测试：真实本地 TCP 监听器 + 完整 Spring 上下文。
 *
 * <p>closed 依赖系统对未监听端口回 RST；本机安全软件会把 RST 延迟约 2 秒，
 * 因此目标显式给 5000ms 预算、默认预算设为 3000ms。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "probe.rate-limit.enabled=false",
                "probe.default-timeout-ms=3000",
                "probe.max-timeout-ms=5000",
                "probe.task.max-targets=5",
                "probe.task.max-active=100"
        })
@Import(ApiIntegrationTest.BoomConfig.class)
class ApiIntegrationTest {

    @TestConfiguration
    static class BoomConfig {
        @Bean
        BoomController boomController() {
            return new BoomController();
        }
    }

    @RestController
    static class BoomController {
        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("boom");
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private ResponseEntity<String> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(url(path), new HttpEntity<>(json, headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws IOException {
        return objectMapper.readTree(response.getBody());
    }

    private static ServerSocket listen() throws IOException {
        return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static Map<String, Object> target(String host, int port, Integer timeoutMs) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("host", host);
        target.put("port", port);
        if (timeoutMs != null) {
            target.put("timeout_ms", timeoutMs);
        }
        return target;
    }

    private String payload(Map<String, Object>... targets) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "demo");
        body.put("targets", List.of(targets));
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void healthzAndServiceInfo() throws IOException {
        ResponseEntity<String> health = restTemplate.getForEntity(url("/healthz"), String.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(json(health).get("status").asText()).isEqualTo("ok");

        ResponseEntity<String> info = restTemplate.getForEntity(url("/"), String.class);
        assertThat(json(info).get("service").asText()).isEqualTo("tcp-probe-service");
    }

    @Test
    void requestIdEchoedAndSanitized() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-ID", "abc-123_ok:1");
        ResponseEntity<String> response =
                restTemplate.exchange(url("/healthz"), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getHeaders().getFirst("X-Request-ID")).isEqualTo("abc-123_ok:1");

        HttpHeaders bad = new HttpHeaders();
        bad.set("X-Request-ID", "bad id with spaces");
        ResponseEntity<String> sanitized =
                restTemplate.exchange(url("/healthz"), HttpMethod.GET, new HttpEntity<>(bad), String.class);
        assertThat(sanitized.getHeaders().getFirst("X-Request-ID")).hasSize(32);
    }

    @Test
    void waitModeBatchReturnsOrderedResults() throws Exception {
        try (ServerSocket listener = listen()) {
            String json = payload(
                    target("127.0.0.1", listener.getLocalPort(), null),
                    target("127.0.0.1", closedPort(), 5000),
                    target("no-such-host-a1b2c3.invalid", 80, 5000));
            ResponseEntity<String> response =
                    post("/api/v1/tasks?wait=true&wait_timeout_ms=8000", json);
            JsonNode body = json(response);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(body.get("status").asText()).isEqualTo("succeeded");
            assertThat(body.get("completed").asInt()).isEqualTo(3);
            JsonNode results = body.get("results");
            assertThat(results.get(0).get("status").asText()).isEqualTo("open");
            assertThat(results.get(1).get("status").asText()).isEqualTo("closed");
            assertThat(results.get(2).get("status").asText()).isEqualTo("dns_error");
            assertThat(results.get(0).get("resolved_ip").asText()).isEqualTo("127.0.0.1");
            assertThat(results.get(0).get("latency_ms").asDouble()).isPositive();
        }
    }

    @Test
    void asyncModePollUntilSucceeded() throws Exception {
        try (ServerSocket listener = listen()) {
            ResponseEntity<String> response =
                    post("/api/v1/tasks", payload(target("127.0.0.1", listener.getLocalPort(), null)));
            assertThat(response.getStatusCode().value()).isEqualTo(202);
            String taskId = json(response).get("id").asText();

            long deadline = System.currentTimeMillis() + 5000;
            String status = "";
            while (System.currentTimeMillis() < deadline) {
                JsonNode task = json(restTemplate.getForEntity(
                        url("/api/v1/tasks/" + taskId), String.class));
                status = task.get("status").asText();
                if (List.of("succeeded", "failed", "cancelled").contains(status)) {
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(status).isEqualTo("succeeded");
        }
    }

    @Test
    void validationEnvelopeForBadHost() throws IOException {
        ResponseEntity<String> response =
                post("/api/v1/tasks", payload(target("bad host!", 80, null)));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        JsonNode body = json(response);
        assertThat(body.get("error").get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body.get("error").get("details").toString()).contains("host");
    }

    @Test
    void validationRejectsEmptyTargetsUnknownFieldsAndBoolPort() throws IOException {
        assertThat(post("/api/v1/tasks", "{\"targets\": []}").getStatusCode().value()).isEqualTo(422);
        assertThat(post("/api/v1/tasks",
                "{\"targets\":[{\"host\":\"a.com\",\"port\":80}],\"extra\":1}")
                .getStatusCode().value()).isEqualTo(422);
        assertThat(post("/api/v1/tasks",
                "{\"targets\":[{\"host\":\"127.0.0.1\",\"port\":true}]}")
                .getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void tooManyTargetsAndTimeoutTooLarge() throws Exception {
        try (ServerSocket listener = listen()) {
            List<Map<String, Object>> targets = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                targets.add(target("127.0.0.1", listener.getLocalPort(), null));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("targets", targets);
            ResponseEntity<String> tooMany =
                    post("/api/v1/tasks", objectMapper.writeValueAsString(body));
            assertThat(tooMany.getStatusCode().value()).isEqualTo(422);
            assertThat(json(tooMany).get("error").get("code").asText()).isEqualTo("TARGETS_TOO_MANY");

            ResponseEntity<String> tooLarge =
                    post("/api/v1/tasks", payload(target("127.0.0.1", 80, 20000)));
            assertThat(tooLarge.getStatusCode().value()).isEqualTo(422);
            assertThat(json(tooLarge).get("error").get("code").asText()).isEqualTo("TIMEOUT_TOO_LARGE");
        }
    }

    @Test
    void unknownTaskAndDeleteIdempotent() throws Exception {
        ResponseEntity<String> missing =
                restTemplate.getForEntity(url("/api/v1/tasks/nope"), String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(json(missing).get("error").get("code").asText()).isEqualTo("TASK_NOT_FOUND");

        ResponseEntity<String> created = post("/api/v1/tasks",
                payload(target("192.0.2.1", 80, 5000)));
        String taskId = json(created).get("id").asText();

        ResponseEntity<String> first = restTemplate.exchange(
                url("/api/v1/tasks/" + taskId), HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(json(first).get("status").asText()).isEqualTo("cancelled");

        ResponseEntity<String> second = restTemplate.exchange(
                url("/api/v1/tasks/" + taskId), HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(json(second).get("status").asText()).isEqualTo("cancelled");
    }

    @Test
    void listTasksReturnsSummaries() throws Exception {
        try (ServerSocket listener = listen()) {
            post("/api/v1/tasks?wait=true&wait_timeout_ms=8000",
                    payload(target("127.0.0.1", listener.getLocalPort(), null)));
            ResponseEntity<String> response =
                    restTemplate.getForEntity(url("/api/v1/tasks?limit=10"), String.class);
            JsonNode items = json(response);
            assertThat(items.isArray()).isTrue();
            assertThat(items.get(0).has("results")).isFalse();
            assertThat(items.get(0).get("total").asInt()).isEqualTo(1);
        }
    }

    @Test
    void unhandledErrorReturnsEnvelopeWithRequestId() throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-ID", "trace-500");
        ResponseEntity<String> response = restTemplate.exchange(
                url("/boom"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        JsonNode body = json(response);
        assertThat(body.get("error").get("code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.get("error").get("request_id").asText()).isEqualTo("trace-500");
        assertThat(response.getHeaders().getFirst("X-Request-ID")).isEqualTo("trace-500");
    }

    @Test
    void unknownPathReturnsEnvelope404() throws IOException {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/nope"), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(json(response).get("error").get("code").asText()).isEqualTo("NOT_FOUND");
    }
}
