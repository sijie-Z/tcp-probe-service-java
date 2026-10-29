package com.example.tcpprobe.store;

import com.example.tcpprobe.config.ProbeProperties;
import com.example.tcpprobe.domain.TaskState;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * 进程内任务存储。
 *
 * <p>单实例部署下的最简实现：任务只保存在内存中，进程重启即丢失（README「已知限制」）。
 * 两条治理规则把内存限制在可控范围：
 * <ul>
 *   <li>TTL：终态任务超过 ttl 秒后，在访问/写入时惰性清理；</li>
 *   <li>保留上限：任务数超过 max-retained 时，按完成时间淘汰最旧的终态任务，运行中的永不淘汰。</li>
 * </ul>
 * 并发：ConcurrentHashMap + TaskState 自身线程安全，没有全局锁。
 */
@Component
public class TaskStore {

    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final ProbeProperties properties;
    private final DoubleSupplier clockSeconds;

    @org.springframework.beans.factory.annotation.Autowired
    public TaskStore(ProbeProperties properties) {
        this(properties, () -> System.currentTimeMillis() / 1000.0);
    }

    TaskStore(ProbeProperties properties, DoubleSupplier clockSeconds) {
        this.properties = properties;
        this.clockSeconds = clockSeconds;
    }

    public synchronized void add(TaskState task) {
        sweepExpired();
        tasks.put(task.getId(), task);
        trimToCapacity();
    }

    /**
     * 原子准入：在同一个临界区内完成「活动计数检查 + 注册」，避免
     * check-then-act 竞态让并发提交突破 max-active 背压。
     *
     * @return false 表示活动任务已达上限，未注册该任务
     */
    public synchronized boolean tryAddActive(TaskState task, int maxActive) {
        sweepExpired();
        if (countActive() >= maxActive) {
            return false;
        }
        tasks.put(task.getId(), task);
        trimToCapacity();
        return true;
    }

    /** 提交阶段失败时回滚注册（如执行器已关停）。 */
    public void remove(String taskId) {
        tasks.remove(taskId);
    }

    public TaskState find(String taskId) {
        TaskState task = tasks.get(taskId);
        if (task != null && isExpired(task)) {
            tasks.remove(taskId, task);
            return null;
        }
        return task;
    }

    public List<TaskState> recent(int limit) {
        sweepExpired();
        return tasks.values().stream()
                .sorted(Comparator.comparing(TaskState::getCreatedAt).reversed())
                .limit(limit)
                .toList();
    }

    public int countActive() {
        int active = 0;
        for (TaskState task : tasks.values()) {
            if (!task.getStatus().isTerminal()) {
                active++;
            }
        }
        return active;
    }

    public int size() {
        return tasks.size();
    }

    private boolean isExpired(TaskState task) {
        if (!task.getStatus().isTerminal()) {
            return false;
        }
        double finishedAt = task.getFinishedAt() == null
                ? clockSeconds.getAsDouble()
                : task.getFinishedAt().toEpochMilli() / 1000.0;
        return clockSeconds.getAsDouble() - finishedAt > properties.task().ttlSeconds();
    }

    private void sweepExpired() {
        tasks.entrySet().removeIf(entry -> isExpired(entry.getValue()));
    }

    private void trimToCapacity() {
        int overflow = tasks.size() - properties.task().maxRetained();
        if (overflow <= 0) {
            return;
        }
        tasks.values().stream()
                .filter(task -> task.getStatus().isTerminal())
                .sorted(Comparator.comparing(task -> task.getFinishedAt() == null
                        ? java.time.Instant.EPOCH
                        : task.getFinishedAt()))
                .limit(overflow)
                .map(TaskState::getId)
                .toList()
                .forEach(tasks::remove);
    }
}
