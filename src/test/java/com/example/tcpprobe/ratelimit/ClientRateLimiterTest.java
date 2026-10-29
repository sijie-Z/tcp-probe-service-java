package com.example.tcpprobe.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClientRateLimiter} 单元测试：用可变数组注入假时钟，全部断言均为确定性（无真实等待）。
 */
class ClientRateLimiterTest {

    private static final double EPS = 1e-9;

    /** 可变假时钟（秒）。JUnit 默认为每个测试方法创建新实例，互不影响。 */
    private final double[] clock = {0.0};

    private DoubleSupplier clock() {
        return () -> clock[0];
    }

    private void advance(double seconds) {
        clock[0] += seconds;
    }

    private static void assertAllowed(ClientRateLimiter.CheckResult result) {
        assertTrue(result.allowed(), "应放行");
        assertEquals(0.0, result.retryAfterSeconds(), 0.0, "放行时 retry-after 必须为 0");
    }

    private static void assertDenied(ClientRateLimiter.CheckResult result, double expectedRetryAfter) {
        assertFalse(result.allowed(), "应拒绝");
        assertEquals(expectedRetryAfter, result.retryAfterSeconds(), EPS);
    }

    @Test
    void allowsBurstThenRejects() {
        ClientRateLimiter limiter = new ClientRateLimiter(4.0, 3, 60.0, clock());

        for (int i = 0; i < 3; i++) {
            assertAllowed(limiter.check("client-a"));
        }
        // 第 4 次拒绝，1/rps = 0.25 秒后才有 1 个令牌
        assertDenied(limiter.check("client-a"), 0.25);
    }

    @Test
    void recoversOneTokenPerInterval() {
        ClientRateLimiter limiter = new ClientRateLimiter(2.0, 2, 60.0, clock());
        assertAllowed(limiter.check("k"));
        assertAllowed(limiter.check("k"));
        assertFalse(limiter.check("k").allowed());

        advance(0.5); // 1/rps = 0.5 秒补 1 个
        assertAllowed(limiter.check("k"));
        assertFalse(limiter.check("k").allowed(), "只补了 1 个令牌");

        advance(0.5);
        assertAllowed(limiter.check("k"));
        assertFalse(limiter.check("k").allowed());
    }

    @Test
    void retryAfterCountsDown() {
        ClientRateLimiter limiter = new ClientRateLimiter(2.0, 1, 60.0, clock());
        assertAllowed(limiter.check("k"));

        assertDenied(limiter.check("k"), 0.5);
        advance(0.25); // 已补 0.5 个令牌，还差 0.5 个 → 0.25 秒
        assertDenied(limiter.check("k"), 0.25);
        advance(0.25);
        assertAllowed(limiter.check("k"));
    }

    @Test
    void keysAreIsolated() {
        ClientRateLimiter limiter = new ClientRateLimiter(2.0, 1, 60.0, clock());

        assertAllowed(limiter.check("a"));
        assertFalse(limiter.check("a").allowed());

        // b 的桶与 a 完全独立：新 key 满桶，不受 a 被限流影响
        assertAllowed(limiter.check("b"));
        assertFalse(limiter.check("b").allowed());

        advance(0.5);
        assertAllowed(limiter.check("a"));
        assertAllowed(limiter.check("b"));
    }

    @Test
    void idleKeyIsSweptWhileActiveKeySurvives() {
        // 小阈值构造器：interval=1 让每次都满足触发条件，清 sweeps 的冷却由 1 秒间隔控制
        ClientRateLimiter limiter = new ClientRateLimiter(
                0.001, 2, 30.0, clock(), 1, Integer.MAX_VALUE, 1_000_000);

        assertAllowed(limiter.check("idle"));   // t=0，触发首次清扫（无过期桶）
        assertAllowed(limiter.check("idle"));
        assertAllowed(limiter.check("active"));
        assertAllowed(limiter.check("active"));
        assertEquals(2, limiter.trackedKeys());

        advance(100.0); // idle 空闲 100 秒 > ttl 30；active 将在本次 check 中刷新

        // active 的桶 t=0 已耗尽，100 秒 × 0.001 只补了 0.1 个令牌 → 拒绝；
        // 同一次 check 触发清扫：回收 idle，保留刚刷新的 active
        assertFalse(limiter.check("active").allowed());
        assertEquals(1, limiter.trackedKeys(), "空闲超 TTL 的 idle 应被回收");

        // idle 已被回收：重建为满桶 → 放行；active 状态被保留：仍然只剩 0.1 个令牌 → 拒绝
        assertAllowed(limiter.check("idle"));
        assertFalse(limiter.check("active").allowed(), "活跃 key 的桶不应被清扫影响");
        assertEquals(2, limiter.trackedKeys());
    }

    @Test
    void sweepCooldownPreventsRepeatedFullScan() {
        // ttl=2 秒，interval=1 → 每来一个 check 都“想清扫”，但两次清扫至少间隔 1 秒
        ClientRateLimiter limiter = new ClientRateLimiter(
                4.0, 2, 2.0, clock(), 1, Integer.MAX_VALUE, 1_000_000);

        assertAllowed(limiter.check("stale"));      // t=0 建立 stale，并触发第 1 次清扫
        assertEquals(1, limiter.sweepCount());

        advance(1.2);
        assertAllowed(limiter.check("other"));      // t=1.2 触发第 2 次清扫；stale 未过期(1.2 ≤ 2)
        assertEquals(2, limiter.sweepCount());
        assertEquals(2, limiter.trackedKeys());

        advance(0.9); // t=2.1：stale 已过期(2.1 > 2)，但距上次清扫仅 0.9 秒 → 冷却拦截
        assertAllowed(limiter.check("other"));
        assertEquals(2, limiter.sweepCount(), "冷却窗口内不得再次清扫");
        assertEquals(2, limiter.trackedKeys(), "冷却窗口内过期的 stale 仍被保留，证明未发生扫描");

        advance(0.2); // t=2.3：距上次清扫 1.1 秒 ≥ 1 秒 → 允许清扫
        assertAllowed(limiter.check("other"));
        assertEquals(3, limiter.sweepCount(), "冷却结束后应恢复清扫");
        assertEquals(1, limiter.trackedKeys(), "expired 的 stale 被回收，活跃的 other 保留");
    }

    @Test
    void hardCapEvictsLeastRecentlySeenKey() {
        // 自动清扫永不触发（interval/threshold 极大），用 sweep() 精确验证硬上限的淘汰顺序
        ClientRateLimiter limiter = new ClientRateLimiter(
                0.001, 1, 1_000_000.0, clock(), 1_000_000, Integer.MAX_VALUE, 3);

        assertAllowed(limiter.check("a")); // t=0
        advance(1.0);
        assertAllowed(limiter.check("b")); // t=1
        advance(1.0);
        assertAllowed(limiter.check("c")); // t=2
        advance(1.0);
        assertAllowed(limiter.check("d")); // t=3
        assertEquals(4, limiter.trackedKeys(), "硬上限在清扫时才收敛，允许瞬时超额");

        assertEquals(1, limiter.sweep(), "手动清扫：TTL 内无过期，超额 1 个，淘汰最旧的 a");
        assertEquals(3, limiter.trackedKeys());

        // a 被淘汰：重建为满桶 → 放行；b、c 保留旧状态（0.001 rps 下远未补满）→ 拒绝
        assertAllowed(limiter.check("a"));
        assertFalse(limiter.check("b").allowed(), "b 应保留耗尽状态（未被误淘汰）");
        assertFalse(limiter.check("c").allowed(), "c 应保留耗尽状态（未被误淘汰）");
    }

    @Test
    void constructorRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new ClientRateLimiter(0.0, 1, 60.0, clock()));
        assertThrows(IllegalArgumentException.class, () -> new ClientRateLimiter(-1.0, 1, 60.0, clock()));
        assertThrows(IllegalArgumentException.class, () -> new ClientRateLimiter(Double.NaN, 1, 60.0, clock()));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientRateLimiter(Double.POSITIVE_INFINITY, 1, 60.0, clock()));

        assertThrows(IllegalArgumentException.class, () -> new ClientRateLimiter(1.0, 0, 60.0, clock()));
        assertThrows(IllegalArgumentException.class, () -> new ClientRateLimiter(1.0, -5, 60.0, clock()));

        assertThrows(IllegalArgumentException.class, () -> new ClientRateLimiter(1.0, 1, 0.0, clock()));
        assertThrows(IllegalArgumentException.class, () -> new ClientRateLimiter(1.0, 1, -1.0, clock()));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientRateLimiter(1.0, 1, Double.NaN, clock()));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientRateLimiter(1.0, 1, Double.POSITIVE_INFINITY, clock()));

        // package-private 构造器的附加参数校验
        assertThrows(IllegalArgumentException.class,
                () -> new ClientRateLimiter(1.0, 1, 60.0, clock(), 0, 4096, 100_000));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientRateLimiter(1.0, 1, 60.0, clock(), 1024, 0, 100_000));
        assertThrows(IllegalArgumentException.class,
                () -> new ClientRateLimiter(1.0, 1, 60.0, clock(), 1024, 4096, 0));
    }

    @Test
    void concurrentChecksNeverExceedBurst() throws InterruptedException {
        // 冻结时钟：不会补充任何令牌，burst=100 应恰好放行 100 次（验证单临界区的原子性）
        ClientRateLimiter limiter = new ClientRateLimiter(1.0, 100, 60.0, clock());
        int threads = 8;
        int checksPerThread = 20;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                executor.execute(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < checksPerThread; i++) {
                            if (limiter.check("shared").allowed()) {
                                allowed.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS), "并发 check 应在 10 秒内完成");
        }

        assertEquals(100, allowed.get(), "并发下放行次数必须恰好等于桶容量");
    }
}
