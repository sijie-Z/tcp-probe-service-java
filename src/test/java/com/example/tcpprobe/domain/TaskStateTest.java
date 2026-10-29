package com.example.tcpprobe.domain;

import com.example.tcpprobe.model.ProbeTarget;
import com.example.tcpprobe.model.TargetResult;
import com.example.tcpprobe.model.TargetStatus;
import com.example.tcpprobe.model.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStateTest {

    private static TaskState singleTargetTask() {
        return new TaskState("t1", null, List.of(new ProbeTarget("127.0.0.1", 80, null)));
    }

    @Test
    void lateProbeResultDoesNotOverwriteCancelledPlaceholder() {
        TaskState task = singleTargetTask();
        task.fillMissing(TargetStatus.CANCELLED, "任务已取消");

        // 取消收尾已占位后，探测线程的迟到结果不得覆盖，完成计数也不能重复累加
        task.completeSlot(0, new TargetResult("127.0.0.1", 80, TargetStatus.OPEN, null, 1.0, null));

        assertThat(task.getCompleted()).isEqualTo(1);
        assertThat(task.snapshot().results().get(0).status()).isEqualTo(TargetStatus.CANCELLED);
    }

    @Test
    void fillMissingDoesNotOverwriteRealResult() {
        TaskState task = singleTargetTask();
        task.completeSlot(0, new TargetResult("127.0.0.1", 80, TargetStatus.OPEN, null, 1.0, null));

        task.fillMissing(TargetStatus.CANCELLED, "任务已取消");

        assertThat(task.getCompleted()).isEqualTo(1);
        assertThat(task.snapshot().results().get(0).status()).isEqualTo(TargetStatus.OPEN);
    }

    @Test
    void finishIsIdempotentAndFirstTerminalWins() {
        TaskState task = singleTargetTask();
        task.finish(TaskStatus.SUCCEEDED, null);
        task.finish(TaskStatus.CANCELLED, "late cancel");

        assertThat(task.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(task.awaitCompletion(10)).isTrue();
    }

    @Test
    void markRunningRefusesAfterCancelOrTerminal() {
        TaskState cancelled = singleTargetTask();
        cancelled.requestCancel();
        cancelled.fillMissing(TargetStatus.CANCELLED, "任务已取消");
        cancelled.finish(TaskStatus.CANCELLED, null);

        // 取消/终态后迟到的协调线程不得把状态翻回 RUNNING
        assertThat(cancelled.markRunning()).isFalse();
        assertThat(cancelled.getStatus()).isEqualTo(TaskStatus.CANCELLED);

        TaskState finished = singleTargetTask();
        finished.finish(TaskStatus.SUCCEEDED, null);
        assertThat(finished.markRunning()).isFalse();
        assertThat(finished.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
    }

    @Test
    void registerProbeFutureCancelsLateRegistrations() {
        TaskState task = singleTargetTask();
        task.requestCancel();

        var future = new java.util.concurrent.CompletableFuture<Void>();
        task.registerProbeFuture(future);

        // 取消后才登记/提交的探测必须就地取消，不能继续启动
        assertThat(future.isCancelled()).isTrue();
    }
}
