package com.example.tcpprobe.model;

import java.time.Instant;
import java.util.List;

/**
 * 一次批量探测任务（API 响应）。
 *
 * @param results 与提交顺序一致的探测结果；未完成的槽位为 null
 */
public record TaskResponse(
        String id,
        String name,
        TaskStatus status,
        int total,
        int completed,
        List<TargetResult> results,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        String error) {
}
