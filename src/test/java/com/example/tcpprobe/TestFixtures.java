package com.example.tcpprobe;

import com.example.tcpprobe.config.ProbeProperties;

/** 测试用配置构造器。 */
public final class TestFixtures {

    private TestFixtures() {
    }

    /** 默认配置：限流关闭、默认超时 3000ms、上限 10000ms、批量上限 200、活动上限 100。 */
    public static ProbeProperties props() {
        return props(3000, 10000, 200, 100, 3600, 1000, false, 5.0, 10);
    }

    public static ProbeProperties props(
            int defaultTimeoutMs,
            int maxTimeoutMs,
            int maxTargets,
            int maxActive,
            int ttlSeconds,
            int maxRetained,
            boolean rateLimitEnabled,
            double rps,
            int burst) {
        return new ProbeProperties(
                defaultTimeoutMs,
                maxTimeoutMs,
                100,
                new ProbeProperties.Task(maxTargets, maxActive, ttlSeconds, maxRetained),
                new ProbeProperties.RateLimit(rateLimitEnabled, rps, burst, 600.0),
                1_048_576L);
    }
}
