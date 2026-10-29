package com.example.tcpprobe;

import com.example.tcpprobe.model.TargetResult;
import com.example.tcpprobe.model.TargetStatus;
import com.example.tcpprobe.service.ProbeFn;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.CancellationException;

/**
 * 测试用可控探测桩：任何目标都挂起 30 秒（可被中断，模拟真实引擎的取消语义）。
 *
 * <p>用于确定性验证取消与背压，不依赖真实网络或防火墙行为。
 */
@TestConfiguration
public class SlowProbeTestConfig {

    @Bean
    @Primary
    public ProbeFn slowProbeFn() {
        return (host, port, timeoutMs) -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new CancellationException("probe cancelled");
            }
            return new TargetResult(host, port, TargetStatus.OPEN, null, 1.0, null);
        };
    }
}
