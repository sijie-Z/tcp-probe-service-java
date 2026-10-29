package com.example.tcpprobe.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenBucket} 单元测试：全部使用显式传入的虚拟时间，不依赖真实时钟。
 */
class TokenBucketTest {

    private static final double EPS = 1e-9;

    @Test
    void startsFullAndAllowsBurstUpToCapacity() {
        TokenBucket bucket = new TokenBucket(5.0, 1000.0, 0.0);

        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(0.0), "满桶应允许连续突发至容量");
        }
        assertFalse(bucket.tryAcquire(0.0), "超过容量后同一时刻必须拒绝");
    }

    @Test
    void drainsToEmptyAndRejects() {
        TokenBucket bucket = new TokenBucket(3.0, 1.0, 0.0);

        assertTrue(bucket.tryAcquire(0.0));
        assertTrue(bucket.tryAcquire(0.0));
        assertTrue(bucket.tryAcquire(0.0));
        assertFalse(bucket.tryAcquire(0.0), "令牌耗尽后拒绝");
        assertEquals(1.0, bucket.retryAfterSeconds(0.0), EPS, "空桶攒够 1 个令牌需 1/rps 秒");
    }

    @Test
    void refillsContinuouslyAtConfiguredRate() {
        TokenBucket bucket = new TokenBucket(10.0, 2.0, 0.0);
        for (int i = 0; i < 10; i++) {
            assertTrue(bucket.tryAcquire(0.0));
        }

        // 0.5 秒 × 2 令牌/秒 = 1 个令牌
        assertTrue(bucket.tryAcquire(0.5));
        assertFalse(bucket.tryAcquire(0.5), "刚补充的令牌已被消费");

        // 再过 0.5 秒，再补 1 个
        assertTrue(bucket.tryAcquire(1.0));
        assertFalse(bucket.tryAcquire(1.0));

        // 从 1.0 秒到 2.0 秒补 2 个
        assertTrue(bucket.tryAcquire(2.0));
        assertTrue(bucket.tryAcquire(2.0));
        assertFalse(bucket.tryAcquire(2.0));
    }

    @Test
    void capsAtCapacityAfterLongIdle() {
        TokenBucket bucket = new TokenBucket(2.0, 1.0, 0.0);
        assertTrue(bucket.tryAcquire(0.0));
        assertTrue(bucket.tryAcquire(0.0));

        // 空置 1000 秒本可补 1000 个令牌，但容量封顶为 2
        assertTrue(bucket.tryAcquire(1000.0));
        assertTrue(bucket.tryAcquire(1000.0));
        assertFalse(bucket.tryAcquire(1000.0), "补充不得超过容量");
    }

    @Test
    void retryAfterReflectsTokenDeficit() {
        TokenBucket bucket = new TokenBucket(5.0, 4.0, 0.0);
        for (int i = 0; i < 5; i++) {
            assertTrue(bucket.tryAcquire(0.0));
        }

        assertEquals(0.25, bucket.retryAfterSeconds(0.0), EPS, "空桶: (1 - 0) / 4");

        // 0.1 秒后补 0.4 个令牌，还差 0.6 个 → 0.6 / 4 = 0.15 秒
        assertEquals(0.15, bucket.retryAfterSeconds(0.1), EPS);

        // 0.5 秒后补 2 个令牌（≥1），已够 → 0，且确实可立即取用
        assertEquals(0.0, bucket.retryAfterSeconds(0.5), EPS);
        assertTrue(bucket.tryAcquire(0.5));
    }

    @Test
    void clockRollbackCountsAsZeroElapsed() {
        TokenBucket bucket = new TokenBucket(10.0, 1.0, 10.0);
        for (int i = 0; i < 10; i++) {
            assertTrue(bucket.tryAcquire(10.0));
        }

        // 时钟回拨到 5 秒：按零流逝处理，令牌数不倒退、不出现 NaN/负数
        assertEquals(1.0, bucket.retryAfterSeconds(5.0), EPS);

        // 若回拨把结算点错误地拨回 5，则 11 秒处会补 6 个令牌；正确实现只从 10 秒起补 1 个
        assertTrue(bucket.tryAcquire(11.0));
        assertFalse(bucket.tryAcquire(11.0), "同一段墙钟时间不能被重复计息");
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0.0, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(-1.0, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(Double.NaN, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucket(Double.POSITIVE_INFINITY, 1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1.0, -1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1.0, Double.NaN, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucket(1.0, Double.POSITIVE_INFINITY, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1.0, 1.0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenBucket(1.0, 1.0, Double.POSITIVE_INFINITY));
    }
}
