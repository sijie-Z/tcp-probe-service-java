package com.example.tcpprobe.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 创建探测任务（批量）。 */
public record CreateTaskRequest(
        @Size(max = 100, message = "name 长度不能超过 100")
        String name,

        @NotEmpty(message = "targets 不能为空")
        List<@Valid ProbeTarget> targets) {
}
