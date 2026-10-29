package com.example.tcpprobe.config;

import com.example.tcpprobe.probe.ProbeEngine;
import com.example.tcpprobe.ratelimit.ClientRateLimiter;
import com.example.tcpprobe.service.ProbeFn;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.DoubleSupplier;

/** 核心组件装配：探测函数与按客户端限流器。 */
@Configuration
public class ServiceBeans {

    @Bean
    public ProbeFn probeFn() {
        return ProbeEngine::probe;
    }

    @Bean
    public ClientRateLimiter clientRateLimiter(ProbeProperties properties) {
        ProbeProperties.RateLimit rateLimit = properties.rateLimit();
        // 关闭限流时也构造一个良性实例（过滤器会跳过）；rps 不能为 0 是构造约束
        double rps = rateLimit.enabled() ? rateLimit.rps() : Math.max(rateLimit.rps(), 1.0);
        DoubleSupplier monotonicSeconds = () -> System.nanoTime() / 1_000_000_000.0;
        return new ClientRateLimiter(
                rps, rateLimit.burst(), rateLimit.idleTtlSeconds(), monotonicSeconds);
    }
}
