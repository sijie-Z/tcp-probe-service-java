package com.example.tcpprobe.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.example.tcpprobe.support.StrictInt;
import com.example.tcpprobe.support.ValidHost;

/**
 * 单个探测目标（请求体中的一项）。
 */
public record ProbeTarget(
        @NotBlank(message = "host 不能为空")
        @Size(max = 253, message = "host 长度不能超过 253")
        @ValidHost
        String host,

        @NotNull(message = "port 不能为空")
        @StrictInt
        @Min(value = 1, message = "port 必须在 1-65535 之间")
        @Max(value = 65535, message = "port 必须在 1-65535 之间")
        Integer port,

        @StrictInt
        @Min(value = 1, message = "timeout_ms 必须为正整数")
        Integer timeoutMs) {
}
