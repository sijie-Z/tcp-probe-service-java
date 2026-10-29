package com.example.tcpprobe.model;

import java.time.Instant;

/** 任务列表项（不含结果明细，控制列表响应体积）。 */
public record TaskSummary(
        String id,
        String name,
        TaskStatus status,
        int total,
        int completed,
        Instant createdAt,
        Instant finishedAt) {
}
