package com.example.tcpprobe.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 探测服务配置，统一使用 {@code probe.*} 前缀。
 *
 * <p>所有配置项都支持环境变量覆盖（见 application.yml 中的占位符），
 * 默认值保证 {@code mvn spring-boot:run} 或 {@code docker compose up} 开箱即用。
 * 非法取值在启动时立即失败（Bean Validation + 跨字段断言），避免带病运行。
 */
@Validated
@ConfigurationProperties(prefix = "probe")
public record ProbeProperties(
        @Min(1) int defaultTimeoutMs,
        @Min(1) int maxTimeoutMs,
        @Min(1) int maxConcurrency,
        @Valid Task task,
        @Valid RateLimit rateLimit) {

    @AssertTrue(message = "probe.default-timeout-ms 不能大于 probe.max-timeout-ms")
    public boolean isTimeoutRangeValid() {
        return defaultTimeoutMs <= maxTimeoutMs;
    }

    @AssertTrue(message = "probe.rate-limit.rps 必须是有限的非负数（不允许 nan/inf）")
    public boolean isRateRpsFinite() {
        return rateLimit == null
                || (Double.isFinite(rateLimit.rps()) && rateLimit.rps() >= 0);
    }

    @AssertTrue(message = "限流开启时 probe.rate-limit.rps 必须大于 0 且不小于 1e-6")
    public boolean isRateEnabledSane() {
        return rateLimit != null && (!rateLimit.enabled() || rateLimit.rps() >= 1e-6);
    }

    @AssertTrue(message = "probe.rate-limit.idle-ttl-seconds 必须是有限正数")
    public boolean isIdleTtlFinite() {
        return rateLimit == null
                || (Double.isFinite(rateLimit.idleTtlSeconds()) && rateLimit.idleTtlSeconds() > 0);
    }

    public record Task(
            @Min(1) int maxTargets,
            @Min(1) int maxActive,
            @Min(1) int ttlSeconds,
            @Min(1) int maxRetained) {
    }

    public record RateLimit(
            boolean enabled,
            @PositiveOrZero double rps,
            @Min(1) int burst,
            @Positive double idleTtlSeconds) {
    }
}
