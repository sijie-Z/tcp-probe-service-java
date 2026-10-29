package com.example.tcpprobe.web;

import com.example.tcpprobe.SlowProbeTestConfig;
import com.example.tcpprobe.model.TaskSummary;
import com.example.tcpprobe.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 背压与取消的确定性集成测试。
 *
 * <p>用 {@link SlowProbeTestConfig} 注入挂起 30 秒的探测桩，不依赖真实网络/防火墙行为：
 * 验证活动任务准入的原子性（并发提交不得突破 max-active）与取消幂等。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "probe.rate-limit.enabled=false",
                "probe.default-timeout-ms=5000",
                "probe.max-timeout-ms=5000",
                "probe.task.max-active=1"
        })
@Import(SlowProbeTestConfig.class)
class CapacityApiTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TaskService taskService;

    @AfterEach
    void drainActiveTasks() {
        // 同一测试上下文共享 TaskStore：清掉本用例留下的挂起任务，保证用例相互独立
        for (TaskSummary summary : taskService.recent(100)) {
            if (!summary.status().isTerminal()) {
                try {
                    taskService.cancel(summary.id());
                } catch (RuntimeException ignored) {
                    // 已终态或并发收敛，忽略
                }
            }
        }
    }

    private String payload() throws IOException {
        return objectMapper.writeValueAsString(Map.of(
                "targets", List.of(Map.of(
                        "host", "slow.example", "port", 80, "timeout_ms", 5000))));
    }

    private ResponseEntity<String> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(
                "http://127.0.0.1:" + port + path, new HttpEntity<>(json, headers), String.class);
    }

    @Test
    void secondActiveTaskReturns503WithRetryAfter() throws IOException {
        String json = payload();
        assertThat(post("/api/v1/tasks", json).getStatusCode().value()).isEqualTo(202);

        ResponseEntity<String> busy = post("/api/v1/tasks", json);
        assertThat(busy.getStatusCode().value()).isEqualTo(503);
        assertThat(busy.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        assertThat(objectMapper.readTree(busy.getBody())
                .get("error").get("code").asText()).isEqualTo("TASK_CAPACITY_EXCEEDED");
    }

    @Test
    void concurrentSubmitsRespectActiveCapacity() throws Exception {
        String json = payload();
        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < attempts; index++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return post("/api/v1/tasks", json).getStatusCode().value();
                }));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get(10, TimeUnit.SECONDS));
            }

            // 原子准入：并发提交只能有 1 个成功，其余全部 503
            assertThat(codes.stream().filter(code -> code == 202).count()).isEqualTo(1);
            assertThat(codes.stream().filter(code -> code == 503).count())
                    .isEqualTo(attempts - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deleteIsIdempotentForCancelledTask() throws IOException {
        ResponseEntity<String> created = post("/api/v1/tasks", payload());
        assertThat(created.getStatusCode().value()).isEqualTo(202);
        String taskId = objectMapper.readTree(created.getBody()).get("id").asText();

        ResponseEntity<String> first = restTemplate.exchange(
                "http://127.0.0.1:" + port + "/api/v1/tasks/" + taskId,
                HttpMethod.DELETE, HttpEntity.EMPTY, String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(objectMapper.readTree(first.getBody()).get("status").asText())
                .isEqualTo("cancelled");

        ResponseEntity<String> second = restTemplate.exchange(
                "http://127.0.0.1:" + port + "/api/v1/tasks/" + taskId,
                HttpMethod.DELETE, HttpEntity.EMPTY, String.class);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(objectMapper.readTree(second.getBody()).get("status").asText())
                .isEqualTo("cancelled");
    }
}
