package com.example.tcpprobe.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** 探测任务的生命周期状态。 */
public enum TaskStatus {

    /** 已接收，尚未开始执行（通常在毫秒级内流转到 running）。 */
    PENDING("pending"),

    /** 执行中，结果列表按提交顺序逐步填充，未完成的槽位为 null。 */
    RUNNING("running"),

    /** 所有目标都已有结果（无论单个目标是 open 还是 timeout）。 */
    SUCCEEDED("succeeded"),

    /** 编排层未预期异常导致任务失败（防御性分支，正常不应出现）。 */
    FAILED("failed"),

    /** 被调用方取消；未完成的目标以 cancelled 状态补齐。 */
    CANCELLED("cancelled");

    private final String wireName;

    TaskStatus(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
