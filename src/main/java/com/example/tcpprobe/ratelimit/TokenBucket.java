package com.example.tcpprobe.ratelimit;

/**
 * 单 key 的连续补充型令牌桶（continuously-refilling token bucket）。
 *
 * <p>模型：桶内令牌随时间连续补充，{@code tokens += elapsedSeconds * refillRatePerSecond}，
 * 上限为构造时给定的 {@code capacity}；每次放行消耗 1 个令牌。与固定窗口计数相比，
 * 连续补充模型不会在窗口边界产生双倍突发，且可以给出精确的 retry-after 等待时长。
 *
 * <p>时间：所有方法由调用方显式传入当前时间（秒），类本身不读取系统时钟——
 * 既便于测试注入假时钟，也保证同一请求链路内多个操作共享同一时间基准。
 *
 * <p>时钟回拨：若传入时间早于上次结算时刻，按“零流逝”处理：不补充令牌、不改变内部状态；
 * 待调用方时钟追平上次结算时刻后，再按真实流逝继续补充。这样同一段墙钟时间不会被重复计息，
 * 令牌数也不会因回拨而倒退。
 *
 * <p>并发：所有状态读写都发生在 {@code synchronized} 实例方法内，单桶层面线程安全。
 * 实际使用中通常由 {@link ClientRateLimiter} 在其自身的临界区内串行访问本类。
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillRatePerSecond;

    /** 当前可用令牌数，恒在 {@code [0, capacity]} 区间内。 */
    private double tokens;

    /** 上次结算（补充）令牌的时刻，单位秒。 */
    private double lastRefillSeconds;

    /**
     * 创建一个满桶。
     *
     * @param capacity            桶容量（即允许的最大突发数），必须是有限正数
     * @param refillRatePerSecond 每秒补充的令牌数，必须是有限正数
     * @param nowSeconds          创建时刻（秒），必须是有限数
     * @throws IllegalArgumentException 任一参数非法时抛出
     */
    public TokenBucket(double capacity, double refillRatePerSecond, double nowSeconds) {
        if (!Double.isFinite(capacity) || capacity <= 0) {
            throw new IllegalArgumentException("capacity 必须是有限正数: " + capacity);
        }
        if (!Double.isFinite(refillRatePerSecond) || refillRatePerSecond <= 0) {
            throw new IllegalArgumentException("refillRatePerSecond 必须是有限正数: " + refillRatePerSecond);
        }
        if (!Double.isFinite(nowSeconds)) {
            throw new IllegalArgumentException("nowSeconds 必须是有限数: " + nowSeconds);
        }
        this.capacity = capacity;
        this.refillRatePerSecond = refillRatePerSecond;
        this.tokens = capacity;
        this.lastRefillSeconds = nowSeconds;
    }

    /**
     * 先按 {@code nowSeconds} 结算补充令牌，再尝试扣减 1 个令牌。
     *
     * @param nowSeconds 当前时刻（秒）
     * @return {@code true} 表示取到令牌（放行）；不足 1 个令牌时返回 {@code false} 且不扣减
     */
    public synchronized boolean tryAcquire(double nowSeconds) {
        refill(nowSeconds);
        if (tokens < 1.0) {
            return false;
        }
        tokens -= 1.0;
        return true;
    }

    /**
     * 先按 {@code nowSeconds} 结算补充令牌，再计算凑够 1 个令牌还需等待的秒数。
     *
     * @param nowSeconds 当前时刻（秒）
     * @return 已有至少 1 个令牌时返回 {@code 0}；否则返回 {@code (1 - tokens) / refillRatePerSecond}
     */
    public synchronized double retryAfterSeconds(double nowSeconds) {
        refill(nowSeconds);
        if (tokens >= 1.0) {
            return 0.0;
        }
        return (1.0 - tokens) / refillRatePerSecond;
    }

    /**
     * 把 {@code lastRefillSeconds} 到 {@code nowSeconds} 之间流逝的时间折算成令牌。
     *
     * <p>走 {@code !(elapsed > 0)} 而不是 {@code elapsed <= 0}，让 NaN 也走“零流逝”分支，
     * 防御非法时钟把桶污染成 NaN。时钟回拨时不推进 {@code lastRefillSeconds}，
     * 避免时钟恢复后把同一段墙钟时间重复计息。
     */
    private void refill(double nowSeconds) {
        double elapsed = nowSeconds - lastRefillSeconds;
        if (!(elapsed > 0)) {
            return;
        }
        tokens = Math.min(capacity, tokens + elapsed * refillRatePerSecond);
        lastRefillSeconds = nowSeconds;
    }
}
