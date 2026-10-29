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

    public void add(TaskState task) {
        sweepExpired();
        tasks.put(task.getId(), task);
        trimToCapacity();
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
