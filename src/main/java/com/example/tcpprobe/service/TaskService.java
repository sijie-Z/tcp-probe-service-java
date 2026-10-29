package com.example.tcpprobe.service;

import com.example.tcpprobe.config.ProbeProperties;
import com.example.tcpprobe.domain.TaskState;
import com.example.tcpprobe.model.CreateTaskRequest;
import com.example.tcpprobe.model.ProbeTarget;
import com.example.tcpprobe.model.TargetResult;
import com.example.tcpprobe.model.TargetStatus;
import com.example.tcpprobe.model.TaskStatus;
import com.example.tcpprobe.model.TaskSummary;
import com.example.tcpprobe.store.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 探测任务编排。
 *
 * <p>设计要点：
 * <ul>
 *   <li>提交即返回：任务创建后立即返回（202），协调协程在虚拟线程上执行，调用方可轮询或 wait=true 长轮询；</li>
 *   <li>全局并发上限：所有任务共享一个信号量，限制同时在途的 TCP 连接数，防止文件描述符耗尽；</li>
 *   <li>结果有序：结果列表与提交顺序一一对应，未完成的槽位为 null，完成后原地填充；</li>
 *   <li>取消语义：取消会中断协调线程与在途探测，未完成的目标以 cancelled 补齐，已出的结果保留；</li>
 *   <li>背压：活动任务数达到上限时直接拒绝（503），而不是让队列无限增长。</li>
 * </ul>
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final ProbeProperties properties;
    private final TaskStore store;
    private final ProbeFn probeFn;
    private final Semaphore permits;
    private final ExecutorService probeExecutor;
    private final ExecutorService coordinatorExecutor;
    private final Map<String, Future<?>> coordinators = new ConcurrentHashMap<>();

    public TaskService(ProbeProperties properties, TaskStore store, ProbeFn probeFn) {
        this.properties = properties;
        this.store = store;
        this.probeFn = probeFn;
        this.permits = new Semaphore(properties.maxConcurrency());
        // Java 21 虚拟线程：探测是纯 IO 等待，虚拟线程的阻塞成本远低于平台线程
        this.probeExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("probe-", 0).factory());
        this.coordinatorExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("task-", 0).factory());
    }

    public TaskState submit(CreateTaskRequest request) {
        List<ProbeTarget> targets = request.targets();
        if (targets.size() > properties.task().maxTargets()) {
            throw new ProbeRequestException("TARGETS_TOO_MANY",
                    "单任务最多 " + properties.task().maxTargets() + " 个目标，收到 " + targets.size() + " 个");
        }
        for (ProbeTarget target : targets) {
            if (target.timeoutMs() != null && target.timeoutMs() > properties.maxTimeoutMs()) {
                throw new ProbeRequestException("TIMEOUT_TOO_LARGE",
                        "timeout_ms 不能超过 " + properties.maxTimeoutMs()
                                + "（host=" + target.host() + ":" + target.port() + "）");
            }
        }
        TaskState task = new TaskState(UUID.randomUUID().toString().replace("-", ""),
                request.name(), targets);
        // 原子准入：检查与注册在同一临界区，并发提交不会突破 max-active 背压
        if (!store.tryAddActive(task, properties.task().maxActive())) {
            throw new TaskCapacityException(properties.task().maxActive());
        }
        try {
            Future<?> coordinator = coordinatorExecutor.submit(() -> run(task, targets));
            coordinators.put(task.getId(), coordinator);
            if (coordinator.isDone()) {
                // 极快任务可能在 put 之前就完成并已自清理，避免残留条目
                coordinators.remove(task.getId(), coordinator);
            }
        } catch (RuntimeException ex) {
            store.remove(task.getId());
            throw ex;
        }
        return task;
    }

    private void run(TaskState task, List<ProbeTarget> targets) {
        if (!task.markRunning()) {
            // 已取消或已收敛终态（如提交后未及调度即被取消）：不再启动任何探测
            task.fillMissing(TargetStatus.CANCELLED, "任务已取消");
            task.finish(TaskStatus.CANCELLED, null);
            return;
        }
        try {
            List<Future<?>> futures = new ArrayList<>(targets.size());
            for (int index = 0; index < targets.size(); index++) {
                if (task.isCancelRequested() || Thread.currentThread().isInterrupted()) {
                    break; // 取消后不再提交新的探测；未提交的槽位由收尾补 cancelled
                }
                final int slot = index;
                final ProbeTarget target = targets.get(index);
                Future<?> future = probeExecutor.submit(() -> probeSlot(task, slot, target));
                task.registerProbeFuture(future);
                futures.add(future);
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (CancellationException ignored) {
                    // 取消：槽位留空，由收尾统一补 cancelled
                } catch (ExecutionException ex) {
                    log.warn("probe_slot_failed task_id={}", task.getId(), ex.getCause());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            boolean cancelled = task.isCancelRequested() || Thread.currentThread().isInterrupted();
            if (cancelled) {
                task.fillMissing(TargetStatus.CANCELLED, "任务已取消");
                task.finish(TaskStatus.CANCELLED, null);
            } else {
                task.finish(TaskStatus.SUCCEEDED, null);
            }
        } catch (RuntimeException ex) {
            log.error("task_failed task_id={}", task.getId(), ex);
            task.fillMissing(TargetStatus.ERROR, "任务内部错误");
            task.finish(TaskStatus.FAILED, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            coordinators.remove(task.getId());
        }
    }

    private void probeSlot(TaskState task, int slot, ProbeTarget target) {
        int timeoutMs = target.timeoutMs() != null
                ? target.timeoutMs()
                : properties.defaultTimeoutMs();
        try {
            permits.acquire();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return; // 槽位留空，取消时补 cancelled
        }
        try {
            TargetResult result = probeFn.probe(target.host(), target.port(), timeoutMs);
            task.completeSlot(slot, result);
        } catch (CancellationException ignored) {
            // 取消：槽位留空，由收尾统一补 cancelled
        } catch (RuntimeException ex) {
            task.completeSlot(slot, new TargetResult(target.host(), target.port(),
                    TargetStatus.ERROR, null, null,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage()));
        } finally {
            permits.release();
        }
    }

    public TaskState get(String taskId) {
        TaskState task = store.find(taskId);
        if (task == null) {
            throw new TaskNotFoundException(taskId);
        }
        return task;
    }

    public List<TaskSummary> recent(int limit) {
        return store.recent(limit).stream().map(TaskState::summary).toList();
    }

    public TaskState cancel(String taskId) {
        TaskState task = get(taskId);
        synchronized (task) {
            if (task.getStatus() == TaskStatus.CANCELLED) {
                return task; // 幂等：重复取消已取消的任务直接返回当前快照
            }
            if (task.isTerminal()) {
                throw new TaskAlreadyFinishedException(taskId);
            }
            task.requestCancel();
        }
        task.cancelProbeFutures();
        Future<?> coordinator = coordinators.remove(taskId);
        if (coordinator != null) {
            coordinator.cancel(true);
        }
        if (task.getStatus() == TaskStatus.PENDING) {
            // 协调线程尚未获得调度（协程体从未执行），直接兜底收敛
            task.fillMissing(TargetStatus.CANCELLED, "任务已取消");
            task.finish(TaskStatus.CANCELLED, null);
        } else {
            task.awaitCompletion(2000);
            if (!task.isTerminal()) {
                task.fillMissing(TargetStatus.CANCELLED, "任务已取消");
                task.finish(TaskStatus.CANCELLED, null);
            }
        }
        return task;
    }

    @PreDestroy
    public void shutdown() {
        List<TaskState> running = new ArrayList<>();
        for (String taskId : List.copyOf(coordinators.keySet())) {
            TaskState task = store.find(taskId);
            if (task == null) {
                continue;
            }
            synchronized (task) {
                if (!task.isTerminal()) {
                    task.requestCancel();
                    running.add(task);
                }
            }
            task.cancelProbeFutures();
            Future<?> coordinator = coordinators.remove(taskId);
            if (coordinator != null) {
                coordinator.cancel(true);
            }
        }
        // 统一截止时间（5 秒）内等待收敛，避免逐任务串行等待拖长优雅关停
        long deadline = System.currentTimeMillis() + 5000;
        for (TaskState task : running) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0 || !task.awaitCompletion(remaining)) {
                task.fillMissing(TargetStatus.CANCELLED, "服务关停");
                task.finish(TaskStatus.CANCELLED, null);
            }
        }
        probeExecutor.shutdownNow();
        coordinatorExecutor.shutdownNow();
        try {
            probeExecutor.awaitTermination(3, TimeUnit.SECONDS);
            coordinatorExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
