package com.example.tcpprobe.service;

import com.example.tcpprobe.TestFixtures;
import com.example.tcpprobe.config.ProbeProperties;
import com.example.tcpprobe.domain.TaskState;
import com.example.tcpprobe.model.CreateTaskRequest;
import com.example.tcpprobe.model.ProbeTarget;
import com.example.tcpprobe.model.TargetResult;
import com.example.tcpprobe.model.TargetStatus;
import com.example.tcpprobe.model.TaskStatus;
import com.example.tcpprobe.store.TaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskServiceTest {

    private TaskService service;

    private TaskService serviceWith(ProbeFn fn, ProbeProperties props) {
        service = new TaskService(props, new TaskStore(props), fn);
        return service;
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private static ProbeTarget target(String host, int port, Integer timeoutMs) {
        return new ProbeTarget(host, port, timeoutMs);
    }

    private static CreateTaskRequest request(ProbeTarget... targets) {
        return new CreateTaskRequest("test", List.of(targets));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitStatus(TaskState task, TaskStatus expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (task.getStatus() == expected) {
                return;
            }
            sleep(10);
        }
        assertThat(task.getStatus()).isEqualTo(expected);
    }

    /** 与真实引擎一致的可取消探测桩：被中断时抛出 CancellationException。 */
    private static ProbeFn cancellableSlowProbe() {
        return (host, port, timeout) -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CancellationException("probe cancelled");
            }
            return new TargetResult(host, port, TargetStatus.OPEN, null, 1.0, null);
        };
    }

    @Test
    void batchRunsAndPreservesSubmissionOrder() {
        ProbeFn fn = (host, port, timeout) -> {
            sleep(host.equals("slow") ? 50 : 10);
            return new TargetResult(host, port, TargetStatus.OPEN, null, 1.0, null);
        };
        TaskService taskService = serviceWith(fn, TestFixtures.props());

        TaskState task = taskService.submit(request(target("slow", 80, null), target("fast", 80, null)));

        assertThat(task.awaitCompletion(5000)).isTrue();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.snapshot().results()).extracting(TargetResult::host)
                .containsExactly("slow", "fast");
        assertThat(task.getCompleted()).isEqualTo(2);
    }

    @Test
    void cancelMarksUnfinishedTargetsCancelled() {
        TaskService taskService = serviceWith(cancellableSlowProbe(), TestFixtures.props());
        TaskState task = taskService.submit(request(target("a", 80, null), target("b", 80, null)));
        awaitStatus(task, TaskStatus.RUNNING, 2000);

        TaskState cancelled = taskService.cancel(task.getId());

        assertThat(cancelled.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(cancelled.getCompleted()).isEqualTo(2);
        assertThat(cancelled.snapshot().results()).extracting(TargetResult::status)
                .containsOnly(TargetStatus.CANCELLED);
    }

    @Test
    void cancelIsIdempotent() {
        TaskService taskService = serviceWith(cancellableSlowProbe(), TestFixtures.props());
        TaskState task = taskService.submit(request(target("a", 80, null)));
        awaitStatus(task, TaskStatus.RUNNING, 2000);

        TaskState first = taskService.cancel(task.getId());
        TaskState second = taskService.cancel(task.getId());

        assertThat(first.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(second.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void activeTaskCapacityBackpressure() {
        ProbeProperties props = TestFixtures.props(3000, 10000, 200, 1, 3600, 1000, false, 5.0, 10);
        TaskService taskService = serviceWith(cancellableSlowProbe(), props);
        taskService.submit(request(target("a", 80, null)));
        assertThatThrownBy(() -> taskService.submit(request(target("b", 80, null))))
                .isInstanceOf(TaskCapacityException.class);
    }

    @Test
    void rejectTooManyTargets() {
        ProbeProperties props = TestFixtures.props(3000, 10000, 2, 100, 3600, 1000, false, 5.0, 10);
        TaskService taskService = serviceWith(cancellableSlowProbe(), props);
        assertThatThrownBy(() -> taskService.submit(
                request(target("a", 80, null), target("b", 80, null), target("c", 80, null))))
                .isInstanceOf(ProbeRequestException.class)
                .hasMessageContaining("最多 2 个目标");
    }

    @Test
    void rejectTimeoutAboveMax() {
        ProbeProperties props = TestFixtures.props(500, 1000, 200, 100, 3600, 1000, false, 5.0, 10);
        TaskService taskService = serviceWith(cancellableSlowProbe(), props);
        assertThatThrownBy(() -> taskService.submit(request(target("a", 80, 5000))))
                .isInstanceOf(ProbeRequestException.class)
                .hasMessageContaining("timeout_ms 不能超过 1000");
    }

    @Test
    void shutdownCancelsRunningTasks() {
        TaskService taskService = serviceWith(cancellableSlowProbe(), TestFixtures.props());
        TaskState task = taskService.submit(request(target("a", 80, null)));
        awaitStatus(task, TaskStatus.RUNNING, 2000);

        taskService.shutdown();
        service = null;

        assertThat(task.getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(task.getFinishedAt()).isNotNull();
    }
}
