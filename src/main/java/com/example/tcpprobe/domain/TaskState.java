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

    /**
     * 进入运行态；若任务已取消或已收敛终态则拒绝并返回 false。
     *
     * <p>与 {@link #finish} 使用同一把对象锁：取消线程先把任务收敛为 CANCELLED 后，
     * 迟到的协调线程调用本方法会得到 false，不会把终态翻回 RUNNING。
     */
    public synchronized boolean markRunning() {
        if (status.isTerminal() || cancelRequested) {
            return false;
        }
        status = TaskStatus.RUNNING;
        startedAt = Instant.now();
        return true;
    }

    public void requestCancel() {
        cancelRequested = true;
    }

    /**
     * 登记探测 Future；若任务已被取消，立即取消该 Future。
     *
     * <p>取消与登记之间没有先后假设：取消后才提交/登记的探测会被就地取消，
     * 不会出现"取消后仍启动新探测"的窗口。
     */
    public void registerProbeFuture(Future<?> future) {
        probeFutures.add(future);
        if (cancelRequested) {
            future.cancel(true);
        }
    }

    public void cancelProbeFutures() {
        synchronized (probeFutures) {
            for (Future<?> future : probeFutures) {
                future.cancel(true);
            }
        }
    }

    /**
     * 探测线程写入自己的槽位并递增完成计数。
     *
     * <p>用 CAS 保证「每个槽位只计一次」：取消收尾可能已经先用 cancelled 占位，
     * 此时探测线程的迟到结果不再覆盖（取消后未完成目标按 cancelled 处理），
     * 完成计数也不会重复累加。
     */
    public void completeSlot(int index, TargetResult result) {
        if (results.compareAndSet(index, null, result)) {
            completed.incrementAndGet();
        }
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
