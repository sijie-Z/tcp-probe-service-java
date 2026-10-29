package com.example.tcpprobe.domain;

import com.example.tcpprobe.model.ProbeTarget;
import com.example.tcpprobe.model.TargetResult;
import com.example.tcpprobe.model.TargetStatus;
import com.example.tcpprobe.model.TaskResponse;
import com.example.tcpprobe.model.TaskStatus;
import com.example.tcpprobe.model.TaskSummary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 探测任务的内部状态载体（可变、线程安全）。
 *
 * <p>状态流转：{@code PENDING → RUNNING → SUCCEEDED | FAILED | CANCELLED}，由协调线程驱动；
 * 探测线程只写各自的结果槽位（与提交顺序一致的固定下标）与完成计数，避免共享可变状态竞争。
 * {@link #snapshot()} 返回不可变视图，可直接作为 API 响应。
 */
public final class TaskState {

    private final String id;
    private final String name;
    private final int total;
    private final List<ProbeTarget> targets;
    private final Instant createdAt = Instant.now();
    private final AtomicReferenceArray<TargetResult> results;
    private final AtomicInteger completed = new AtomicInteger();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private final List<Future<?>> probeFutures = Collections.synchronizedList(new ArrayList<>());

    private volatile TaskStatus status = TaskStatus.PENDING;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String error;
    private volatile boolean cancelRequested;

    public TaskState(String id, String name, List<ProbeTarget> targets) {
        this.id = id;
        this.name = name;
        this.total = targets.size();
        this.targets = List.copyOf(targets);
        this.results = new AtomicReferenceArray<>(total);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getTotal() {
        return total;
    }

    public List<ProbeTarget> getTargets() {
        return targets;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getError() {
        return error;
    }

    public int getCompleted() {
        return completed.get();
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void markRunning() {
        status = TaskStatus.RUNNING;
        startedAt = Instant.now();
    }

    public void requestCancel() {
        cancelRequested = true;
    }

    public void registerProbeFuture(Future<?> future) {
        probeFutures.add(future);
    }

    public void cancelProbeFutures() {
        synchronized (probeFutures) {
            for (Future<?> future : probeFutures) {
                future.cancel(true);
            }
        }
    }

    /** 探测线程写入自己的槽位并递增完成计数（每个槽位只写一次）。 */
    public void completeSlot(int index, TargetResult result) {
        results.set(index, result);
        completed.incrementAndGet();
    }

    /** 把未完成的槽位补记为指定状态（CAS 语义：已被探测线程写入的槽位保留原结果）。 */
    public void fillMissing(TargetStatus missingStatus, String reason) {
        for (int index = 0; index < total; index++) {
            if (results.get(index) == null) {
                ProbeTarget target = targets.get(index);
                TargetResult placeholder = new TargetResult(
                        target.host(), target.port(), missingStatus, null, null, reason);
                if (results.compareAndSet(index, null, placeholder)) {
                    completed.incrementAndGet();
                }
            }
        }
    }

    /** 收敛终态；重复调用幂等（先到者生效）。 */
    public synchronized void finish(TaskStatus terminalStatus, String terminalError) {
        if (status.isTerminal()) {
            return;
        }
        if (terminalError != null) {
            error = terminalError;
        }
        finishedAt = Instant.now();
        status = terminalStatus;
        completion.complete(null);
    }

    /** 等待终态，最多 timeoutMs 毫秒；超时返回 false。 */
    public boolean awaitCompletion(long timeoutMs) {
        try {
            completion.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException ex) {
            return false;
        }
    }

    public TaskResponse snapshot() {
        List<TargetResult> list = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            list.add(results.get(index));
        }
        return new TaskResponse(
                id, name, status, total, completed.get(), list, createdAt, startedAt, finishedAt, error);
    }

    public TaskSummary summary() {
        return new TaskSummary(id, name, status, total, completed.get(), createdAt, finishedAt);
    }
}
