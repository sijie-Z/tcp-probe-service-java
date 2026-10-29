package com.example.tcpprobe.ratelimit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * 按客户端 key 限流的令牌桶集合（每 key 一个桶 + 惰性清扫 + 硬上限兜底）。
 *
 * <p>每个 key 对应一个 {@link TokenBucket}：容量等于 {@code burst}、每秒补充 {@code rps} 个令牌。
 * 新 key 以满桶创建，因此同一 key 最多允许连续突发 {@code burst} 次，之后按 {@code rps} 匀速恢复；
 * 不同 key 的桶彼此独立。
 *
 * <h2>惰性清扫（lazy sweep）</h2>
 * 不启动后台线程：每次 {@link #check(String)} 递增计数器，在“累计 check 次数达到
 * {@code sweepCheckInterval}（默认 1024）”或“桶数超过 {@code sweepKeyThreshold}（默认 4096）”
 * 时触发清扫，但任意两次清扫之间至少间隔 1 秒。冷却窗口是关键：若没有它，桶数一旦长期高于阈值，
 * 每个请求都会做一次 O(n) 全表扫描，攻击者用海量不同 key 即可把限流器本身变成 CPU 放大器。
 * 清扫删除满足 {@code now - lastSeen > idleTtlSeconds}（严格大于）的桶；每次 check 都会刷新
 * 对应 key 的 lastSeen，被拒绝（不计费）的请求同样算活跃。
 *
 * <h2>硬上限</h2>
 * 桶数超过 {@code maxKeys}（默认 100_000）时，同一次清扫内按 lastSeen 升序淘汰最旧的部分，
 * 直到回落到上限。两次清扫之间最多再增长约“1 秒的请求量”，因此内存占用有界。
 *
 * <h2>并发</h2>
 * {@link #check(String)} 会被 Tomcat 的多个工作线程并发调用，这里用单个 {@code synchronized}
 * 方法把所有状态读写收进同一个临界区。临界区内的主体是 HashMap 查找、lastSeen 刷新与两次数值运算，
 * 均为 O(1) 且不含任何 I/O；清扫的 O(n) 扫描被 1 秒冷却窗口限制为每秒至多一次。
 * 对本题单机部署的请求规模而言，这是“正确性优先、实现简单”的合理取舍；若将来吞吐成为瓶颈，
 * 可再演进为分段锁（StripedLock）或按 key 哈希的并发结构。
 *
 * <h2>时间</h2>
 * 时间统一由构造时注入的 {@link DoubleSupplier} 提供，单位秒。时钟回拨由 {@link TokenBucket}
 * 按零流逝兜底；lastSeen 刷新在回拨时可能短暂“变年轻”，只影响清扫时机，不影响限流判定本身。
 */
public final class ClientRateLimiter {

    /** 两次惰性清扫之间的最小间隔（秒）。 */
    private static final double MIN_SWEEP_INTERVAL_SECONDS = 1.0;

    /** 默认每累计多少次 check 触发一次清扫。 */
    private static final int DEFAULT_SWEEP_CHECK_INTERVAL = 1024;

    /** 默认桶数超过多少时触发清扫。 */
    private static final int DEFAULT_SWEEP_KEY_THRESHOLD = 4096;

    /** 默认允许跟踪的最大 key 数（硬上限）。 */
    private static final int DEFAULT_MAX_KEYS = 100_000;

    private final double rps;
    private final int burst;
    private final double idleTtlSeconds;
    private final DoubleSupplier clockSeconds;
    private final int sweepCheckInterval;
    private final int sweepKeyThreshold;
    private final int maxKeys;

    private final Map<String, ClientBucket> buckets = new HashMap<>();
    private long checksSinceSweep;
    private double lastSweepAtSeconds = Double.NEGATIVE_INFINITY;
    private int sweepCount;

    /**
     * 使用默认清扫参数（1024 次 check / 4096 个 key / 硬上限 100_000）创建限流器。
     *
     * @param rps              每个 key 每秒补充的令牌数，必须是有限正数
     * @param burst            每个 key 的桶容量（允许的最大突发数），必须 ≥ 1
     * @param idleTtlSeconds   空闲桶的存活时间（秒），必须是有限正数
     * @param clockSeconds     时钟，返回当前时刻（秒）
     * @throws IllegalArgumentException 任一参数非法时抛出
     * @throws NullPointerException     {@code clockSeconds} 为 null 时抛出
     */
    public ClientRateLimiter(double rps, int burst, double idleTtlSeconds, DoubleSupplier clockSeconds) {
        this(rps, burst, idleTtlSeconds, clockSeconds,
                DEFAULT_SWEEP_CHECK_INTERVAL, DEFAULT_SWEEP_KEY_THRESHOLD, DEFAULT_MAX_KEYS);
    }

    /**
     * 可调清扫参数的构造器（package-private，供测试注入小阈值以验证冷却窗口与硬上限）。
     */
    ClientRateLimiter(double rps, int burst, double idleTtlSeconds, DoubleSupplier clockSeconds,
                      int sweepCheckInterval, int sweepKeyThreshold, int maxKeys) {
        if (!Double.isFinite(rps) || rps <= 0) {
            throw new IllegalArgumentException("rps 必须是有限正数: " + rps);
        }
        if (burst < 1) {
            throw new IllegalArgumentException("burst 必须大于等于 1: " + burst);
        }
        if (!Double.isFinite(idleTtlSeconds) || idleTtlSeconds <= 0) {
            throw new IllegalArgumentException("idleTtlSeconds 必须是有限正数: " + idleTtlSeconds);
        }
        this.clockSeconds = Objects.requireNonNull(clockSeconds, "clockSeconds 不能为空");
        if (sweepCheckInterval < 1) {
            throw new IllegalArgumentException("sweepCheckInterval 必须大于等于 1: " + sweepCheckInterval);
        }
        if (sweepKeyThreshold < 1) {
            throw new IllegalArgumentException("sweepKeyThreshold 必须大于等于 1: " + sweepKeyThreshold);
        }
        if (maxKeys < 1) {
            throw new IllegalArgumentException("maxKeys 必须大于等于 1: " + maxKeys);
        }
        this.rps = rps;
        this.burst = burst;
        this.idleTtlSeconds = idleTtlSeconds;
        this.sweepCheckInterval = sweepCheckInterval;
        this.sweepKeyThreshold = sweepKeyThreshold;
        this.maxKeys = maxKeys;
    }

    /**
     * 对指定 key 做一次限流判定：取到令牌则放行，否则返回建议的 retry-after。
     *
     * <p>同一临界区内依次完成：取/建桶、刷新 lastSeen、扣减令牌、按需触发惰性清扫。
     * 新 key 以满桶创建，因此首次请求必然放行（burst ≥ 1）。
     *
     * @param key 客户端标识，不允许为 null
     * @return 判定结果；放行时 {@code retryAfterSeconds} 为 0
     * @throws NullPointerException {@code key} 为 null 时抛出
     */
    public synchronized CheckResult check(String key) {
        Objects.requireNonNull(key, "key 不能为空");
        double now = clockSeconds.getAsDouble();

        ClientBucket clientBucket = buckets.get(key);
        if (clientBucket == null) {
            clientBucket = new ClientBucket(new TokenBucket(burst, rps, now), now);
            buckets.put(key, clientBucket);
        } else {
            clientBucket.lastSeenSeconds = now;
        }

        boolean allowed = clientBucket.bucket.tryAcquire(now);
        double retryAfterSeconds = allowed ? 0.0 : clientBucket.bucket.retryAfterSeconds(now);

        checksSinceSweep++;
        if (isSweepDue(now)) {
            sweepInternal(now);
        }
        return new CheckResult(allowed, retryAfterSeconds);
    }

    /** 当前跟踪的 key 数。 */
    public synchronized int trackedKeys() {
        return buckets.size();
    }

    /**
     * 手动触发一次清扫（不受冷却窗口限制），并重置冷却计时与 check 计数。
     *
     * <p>与惰性清扫执行同一套逻辑：先删除空闲超过 TTL 的桶，再按 lastSeen 升序淘汰超出硬上限的部分。
     *
     * @return 本次回收（含淘汰）的桶数
     */
    public synchronized int sweep() {
        return sweepInternal(clockSeconds.getAsDouble());
    }

    /**
     * 惰性清扫是否到期：check 计数或桶数达到触发条件，且距上次清扫已满 1 秒。
     *
     * <p>冷却条件用 {@code >=}：恰好满 1 秒即允许，语义上是“两次清扫至少间隔 1 秒”。
     * 首次清扫前 {@code lastSweepAtSeconds} 为负无穷，条件恒成立。
     */
    private boolean isSweepDue(double now) {
        boolean triggered = checksSinceSweep >= sweepCheckInterval
                || buckets.size() > sweepKeyThreshold;
        return triggered && now - lastSweepAtSeconds >= MIN_SWEEP_INTERVAL_SECONDS;
    }

    /**
     * 执行清扫：TTL 回收 + 硬上限淘汰，并重置计数与冷却计时。
     *
     * <p>调用方必须持有本对象的锁（惰性路径与 {@link #sweep()} 均通过 synchronized 方法进入）。
     * 硬上限淘汰只在超限时创建一次按 lastSeen 升序的排序视图，最多每秒一次，成本可控。
     */
    private int sweepInternal(double now) {
        int removed = 0;

        var iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            ClientBucket candidate = iterator.next().getValue();
            if (now - candidate.lastSeenSeconds > idleTtlSeconds) {
                iterator.remove();
                removed++;
            }
        }

        int excess = buckets.size() - maxKeys;
        if (excess > 0) {
            var oldestFirst = new ArrayList<>(buckets.entrySet());
            oldestFirst.sort(Comparator.comparingDouble(entry -> entry.getValue().lastSeenSeconds));
            for (int index = 0; index < excess; index++) {
                buckets.remove(oldestFirst.get(index).getKey());
                removed++;
            }
        }

        checksSinceSweep = 0;
        lastSweepAtSeconds = now;
        sweepCount++;
        return removed;
    }

    /**
     * 已完成清扫的次数（package-private，仅供同包测试观测冷却行为，不属于公开 API）。
     */
    synchronized int sweepCount() {
        return sweepCount;
    }

    /**
     * 单次限流判定结果。
     *
     * @param allowed           是否放行（放行时已扣减 1 个令牌）
     * @param retryAfterSeconds 被拒绝时还需等待多少秒才能取到 1 个令牌；放行时为 0
     */
    public record CheckResult(boolean allowed, double retryAfterSeconds) {
    }

    /** key 的存活元数据：令牌桶本体 + 最近一次被访问（check）的时刻。 */
    private static final class ClientBucket {

        private final TokenBucket bucket;
        private double lastSeenSeconds;

        private ClientBucket(TokenBucket bucket, double lastSeenSeconds) {
            this.bucket = bucket;
            this.lastSeenSeconds = lastSeenSeconds;
        }
    }
}
