package com.example.tcpprobe.metrics;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内计数器指标（Prometheus 文本格式，零依赖手写实现）。
 *
 * <p>只统计运行期计数（counter），不维护直方图；重启即归零，单实例部署下够用。
 * 并发：LongAdder + ConcurrentHashMap，多线程自增无锁竞争。
 */
@Component
public class MetricsRegistry {

    private static final Map<String, String> HELP = Map.of(
            "tcp_probe_tasks_submitted_total", "已接收的探测任务数",
            "tcp_probe_tasks_rejected_total", "被拒绝的提交数（按原因）",
            "tcp_probe_tasks_finished_total", "进入终态的任务数（按状态）",
            "tcp_probe_targets_total", "目标探测结果数（按状态）",
            "tcp_probe_rate_limited_total", "被限流拒绝的请求数",
            "tcp_probe_body_rejected_total", "因请求体超限被拒绝的请求数");

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

    /** 自增一个计数器；labelPairs 为 {key, value} 交替的可变参数。 */
    public void increment(String name, String... labelPairs) {
        counters.computeIfAbsent(key(name, labelPairs), ignored -> new LongAdder()).increment();
    }

    /** 读取当前计数值（测试与运维用）。 */
    public long value(String name, String... labelPairs) {
        LongAdder adder = counters.get(key(name, labelPairs));
        return adder == null ? 0L : adder.sum();
    }

    /** 按 Prometheus 文本格式（0.0.4）渲染，family 按名称排序，输出稳定。 */
    public String render() {
        StringBuilder output = new StringBuilder(1024);
        String currentFamily = null;
        for (Map.Entry<String, LongAdder> entry : new TreeMap<>(counters).entrySet()) {
            String seriesKey = entry.getKey();
            String family = familyOf(seriesKey);
            if (!family.equals(currentFamily)) {
                currentFamily = family;
                output.append("# HELP ").append(family).append(' ')
                        .append(HELP.getOrDefault(family, "")).append('\n');
                output.append("# TYPE ").append(family).append(" counter\n");
            }
            output.append(seriesKey).append(' ').append(entry.getValue().sum()).append('\n');
        }
        return output.toString();
    }

    private static String familyOf(String seriesKey) {
        int brace = seriesKey.indexOf('{');
        return brace < 0 ? seriesKey : seriesKey.substring(0, brace);
    }

    private static String key(String name, String... labelPairs) {
        if (labelPairs.length < 2) {
            return name;
        }
        StringBuilder key = new StringBuilder(name).append('{');
        for (int index = 0; index + 1 < labelPairs.length; index += 2) {
            if (index > 0) {
                key.append(',');
            }
            key.append(labelPairs[index]).append("=\"").append(labelPairs[index + 1]).append('"');
        }
        return key.append('}').toString();
    }
}
