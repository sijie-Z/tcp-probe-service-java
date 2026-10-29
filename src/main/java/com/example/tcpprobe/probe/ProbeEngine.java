package com.example.tcpprobe.probe;

import com.example.tcpprobe.model.TargetResult;
import com.example.tcpprobe.model.TargetStatus;
import com.example.tcpprobe.support.HostRules;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * TCP 连通性探测引擎：对单个 {@code host:port} 执行一次带总预算的 TCP 握手探测。
 *
 * <h2>预算模型</h2>
 * <p>总预算 {@code timeoutMs} 从 {@link #probe} 进入时以 {@link System#nanoTime()} 起算，
 * 覆盖 DNS 解析与全部地址尝试两个阶段。每次发起新解析/新连接前都按截止时刻重算剩余预算，
 * 剩余预算 {@code <= 0} 时不再发起新尝试，按已收集的结论收敛。因此除下文 DNS 已知限制外，
 * 方法阻塞时长不会超出预算。
 *
 * <h2>状态判定（优先级 open &gt; closed &gt; timeout &gt; unreachable）</h2>
 * <ul>
 *   <li>{@code open}：握手成功。{@code latencyMs} 为一次连接建立的总耗时（含
 *       SocketChannel/Selector 本地准备与握手；nanoTime 差值，毫秒保留 3 位小数），
 *       不含 DNS 耗时；{@code resolvedIp} 为命中地址。</li>
 *   <li>{@code closed}：某地址被明确拒绝（{@link ConnectException} / RST）。此时不立即
 *       返回，而是记为 best-so-far 继续尝试其余地址（多 A/AAAA 记录时后续地址仍可能
 *       open）；最终无 open 才返回 closed，{@code resolvedIp} 为被拒地址，
 *       {@code error} 形如 {@code connection_refused: ip:port}。</li>
 *   <li>{@code timeout}：预算内没有任何明确答复，且存在超时的连接尝试
 *       （{@link SocketTimeoutException}）；DNS 阶段超时也归入本状态，
 *       {@code error} 统一以 {@code timeout:} 开头（与 Python 版前缀对齐）。</li>
 *   <li>{@code dns_error}：{@link UnknownHostException} 或解析结果为空，立即返回。</li>
 *   <li>{@code unreachable}：{@link NoRouteToHostException}、连接重置等其他
 *       {@link IOException}。</li>
 *   <li>{@code invalid}：host/port 非法（{@code timeoutMs <= 0} 视为调用方编程错误，
 *       同样判为 invalid），不发起任何网络 I/O；主机校验复用 {@link HostRules}，
 *       端口限定 1-65535。</li>
 *   <li>{@code error}：未预期 {@link RuntimeException} 的兜底。</li>
 *   <li>{@code cancelled}：本引擎不返回该状态；线程被中断时以
 *       {@link CancellationException} 向上传播，由上层任务框架归类。</li>
 * </ul>
 *
 * <h2>取消语义</h2>
 * <p>探测可被线程中断取消：阻塞等待连接完成期间若线程被中断，JDK 抛
 * {@link ClosedByInterruptException}（其他平台上 select 提前返回后由本引擎检查中断
 * 标志补抛），引擎捕获后统一抛出 {@code CancellationException("probe cancelled")}，
 * 绝不归类为 timeout/unreachable/error；等待 DNS 解析的 {@link Future#get} 被中断时
 * 同样处理。抛出前恢复线程的中断标志。
 *
 * <h2>DNS 已知限制</h2>
 * <p>主机名解析放在独立的虚拟线程 executor 中执行，并用
 * {@code Future.get(剩余预算)} 限时等待，保证探测本身按时返回；但
 * {@code Future.cancel(true)} 只能中断线程，若底层解析器阻塞在无法响应中断的本地调用
 * 中，该后台虚拟线程会继续存活至操作系统级解析超时，无法被强制回收。这是
 * {@link InetAddress} 同步解析 API 的固有限制。
 */
public final class ProbeEngine {

    /** 每个目标最多尝试的地址数（多 A/AAAA 记录只取前 8 个，避免长尾地址拖死预算）。 */
    private static final int MAX_ADDRESSES = 8;

    /** 默认解析器：{@link InetAddress#getAllByName}。 */
    private static final HostResolver DEFAULT_RESOLVER = InetAddress::getAllByName;

    /** 默认连接器：非阻塞 SocketChannel + Selector 手写带超时握手。 */
    private static final Connector DEFAULT_CONNECTOR = ProbeEngine::defaultConnect;

    /**
     * DNS 解析专用虚拟线程执行器：线程随任务创建、随任务结束，空闲时无驻留线程；
     * 进程内共享，无需关闭。
     */
    private static final ExecutorService RESOLVER_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private ProbeEngine() {
    }

    /**
     * 探测 {@code host:port} 的 TCP 连通性，总预算 {@code timeoutMs} 毫秒。
     *
     * <p>预算、状态优先级、多地址 best-so-far 与取消的完整语义见类注释。
     *
     * @param host      主机名或 IP 字面量；IPv6 允许 {@code [::1]} 方括号写法
     * @param port      目标端口，合法范围 1-65535
     * @param timeoutMs 总预算毫秒数，必须为正；覆盖 DNS 解析与全部地址尝试
     * @return 探测结果，各字段的取值约定见 {@link TargetResult} 与类注释
     * @throws CancellationException 当前线程在探测期间被中断
     */
    public static TargetResult probe(String host, int port, int timeoutMs) {
        return probeWith(host, port, timeoutMs, DEFAULT_RESOLVER, DEFAULT_CONNECTOR);
    }

    /**
     * 可注入解析器/连接器的探测入口（测试缝）；公共入口 {@link #probe} 用默认实现委托到
     * 这里。
     *
     * @param hostText  原始主机串，允许首尾空白；为 null 时按非法主机处理
     * @param port      目标端口
     * @param timeoutMs 总预算毫秒数
     * @param resolver  主机名解析器；IP 字面量不会调用它
     * @param connector 单地址连接器，收到的 timeoutNanos 为本次尝试的剩余预算
     * @return 探测结果；取消仍以 CancellationException 抛出
     */
    static TargetResult probeWith(
            String hostText, int port, int timeoutMs, HostResolver resolver, Connector connector) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        String host = hostText == null ? "" : hostText.strip();

        if (!HostRules.isValid(hostText)) {
            return failure(host, port, TargetStatus.INVALID, "invalid_host: " + host);
        }
        if (port < 1 || port > 65535) {
            return failure(host, port, TargetStatus.INVALID, "invalid_port: " + port);
        }
        if (timeoutMs <= 0) {
            return failure(host, port, TargetStatus.INVALID, "invalid_timeout: " + timeoutMs);
        }

        try {
            Resolution resolution = resolve(host, port, deadlineNanos, resolver);
            if (resolution.terminal() != null) {
                return resolution.terminal();
            }
            return probeAddresses(host, port, resolution, deadlineNanos, connector);
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            return failure(host, port, TargetStatus.ERROR,
                    "internal_error: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 解析阶段：IP 字面量直接转 {@link InetAddress}（不发 DNS）；主机名在虚拟线程中解析，
     * 等待时长受剩余预算约束。失败时返回携带终止性结果的 {@link Resolution}。
     */
    private static Resolution resolve(
            String host, int port, long deadlineNanos, HostResolver resolver) {
        String literal = HostRules.ipLiteralOrNull(host);
        if (literal != null) {
            try {
                return Resolution.ok(new InetAddress[] {InetAddress.getByName(literal)}, literal);
            } catch (UnknownHostException e) {
                return Resolution.terminal(
                        failure(host, port, TargetStatus.DNS_ERROR, "dns_error: " + host));
            }
        }

        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return Resolution.terminal(failure(host, port, TargetStatus.TIMEOUT,
                    "timeout: budget exhausted before DNS resolution"));
        }
        Future<InetAddress[]> future = RESOLVER_EXECUTOR.submit(() -> resolver.resolve(host));
        try {
            InetAddress[] addresses = future.get(remainingNanos, TimeUnit.NANOSECONDS);
            if (addresses == null || addresses.length == 0) {
                return Resolution.terminal(failure(host, port, TargetStatus.DNS_ERROR,
                        "dns_error: no_addresses: " + host));
            }
            return Resolution.ok(addresses, null);
        } catch (TimeoutException e) {
            future.cancel(true);
            return Resolution.terminal(
                    failure(host, port, TargetStatus.TIMEOUT,
                            "timeout: dns resolution exceeded budget: " + host));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("probe cancelled");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException) {
                return Resolution.terminal(
                        failure(host, port, TargetStatus.DNS_ERROR, "dns_error: " + host));
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            return Resolution.terminal(failure(host, port, TargetStatus.ERROR,
                    "internal_error: " + cause.getClass().getSimpleName()));
        }
    }

    /**
     * 连接阶段：按解析顺序逐个地址尝试（最多 {@value #MAX_ADDRESSES} 个），每次尝试前
     * 重算剩余预算。任一地址 open 立即返回；否则按 closed &gt; timeout &gt; unreachable
     * 的优先级用已收集的结论收敛。
     */
    private static TargetResult probeAddresses(
            String host,
            int port,
            Resolution resolution,
            long deadlineNanos,
            Connector connector) {
        InetAddress[] addresses = resolution.addresses();
        String literal = resolution.literal();
        int count = Math.min(addresses.length, MAX_ADDRESSES);

        String closedIp = null;
        String closedError = null;
        String timeoutIp = null;
        String unreachableIp = null;
        String unreachableError = null;
        String lastTriedIp = null;
        int attempted = 0;

        for (int index = 0; index < count; index++) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            InetAddress address = addresses[index];
            if (address == null) {
                continue;
            }
            String ip = literal != null ? literal : address.getHostAddress();
            lastTriedIp = ip;
            attempted++;
            long handshakeStartNanos = System.nanoTime();
            try {
                connector.connect(new InetSocketAddress(address, port), remainingNanos);
                return new TargetResult(host, port, TargetStatus.OPEN, ip,
                        roundToMillis(System.nanoTime() - handshakeStartNanos), null);
            } catch (IOException e) {
                TargetStatus status = classify(e);
                if (status == TargetStatus.CANCELLED) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("probe cancelled");
                }
                if (status == TargetStatus.CLOSED) {
                    if (closedIp == null) {
                        closedIp = ip;
                        closedError = "connection_refused: " + ip + ":" + port;
                    }
                } else if (status == TargetStatus.TIMEOUT) {
                    timeoutIp = ip;
                } else if (unreachableIp == null) {
                    unreachableIp = ip;
                    unreachableError = "unreachable: " + ip + ":" + port;
                }
            }
        }

        if (closedIp != null) {
            return new TargetResult(host, port, TargetStatus.CLOSED, closedIp, null, closedError);
        }
        if (timeoutIp != null) {
            return new TargetResult(host, port, TargetStatus.TIMEOUT, timeoutIp, null,
                    "timeout: " + timeoutIp + ":" + port);
        }
        if (unreachableIp != null) {
            return new TargetResult(host, port, TargetStatus.UNREACHABLE, unreachableIp, null,
                    unreachableError);
        }
        return new TargetResult(host, port, TargetStatus.TIMEOUT, lastTriedIp, null,
                "timeout: no definitive answer within budget");
    }

    /**
     * 默认连接器：非阻塞 {@link SocketChannel} + {@link Selector} 实现带超时的 TCP 握手。
     *
     * <p>channel 由 try-with-resources 保证关闭；握手期间线程被中断时原样抛出
     * {@link ClosedByInterruptException}（Windows 上 select 直接抛出，其他平台 select
     * 提前返回后由本方法检查中断标志补抛），由上层转换为取消语义。
     */
    private static void defaultConnect(InetSocketAddress address, long timeoutNanos)
            throws IOException {
        long deadlineNanos = System.nanoTime() + timeoutNanos;
        try (SocketChannel channel = SocketChannel.open();
             Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            if (channel.connect(address)) {
                return;
            }
            channel.register(selector, SelectionKey.OP_CONNECT);
            while (true) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new SocketTimeoutException("connect timed out: " + address);
                }
                int selected = selector.select(toSelectMillis(remainingNanos));
                if (Thread.currentThread().isInterrupted()) {
                    throw new ClosedByInterruptException();
                }
                if (selected == 0) {
                    continue;
                }
                var iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (key.isValid() && key.isConnectable() && channel.finishConnect()) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * 把连接阶段抛出的异常映射为状态（纯函数，便于单测）。
     *
     * <p>分支顺序保证子类型优先：{@link ClosedByInterruptException} 代表线程被中断而非
     * 网络故障，映射为 {@link TargetStatus#CANCELLED}，引擎据此抛出
     * {@link CancellationException} 而不是归类为 unreachable/error。
     */
    static TargetStatus classify(Throwable error) {
        if (error instanceof ClosedByInterruptException) {
            return TargetStatus.CANCELLED;
        }
        if (error instanceof SocketTimeoutException) {
            return TargetStatus.TIMEOUT;
        }
        if (error instanceof ConnectException) {
            return TargetStatus.CLOSED;
        }
        if (error instanceof UnknownHostException) {
            return TargetStatus.DNS_ERROR;
        }
        if (error instanceof NoRouteToHostException) {
            return TargetStatus.UNREACHABLE;
        }
        if (error instanceof SocketException) {
            return TargetStatus.UNREACHABLE;
        }
        if (error instanceof IOException) {
            return TargetStatus.UNREACHABLE;
        }
        return TargetStatus.ERROR;
    }

    /** 把 nanoTime 差值换算为毫秒并保留 3 位小数（四舍五入）。 */
    private static double roundToMillis(long elapsedNanos) {
        return Math.round(elapsedNanos / 1_000.0) / 1_000.0;
    }

    /** 把剩余纳秒向上取整为毫秒，至少 1ms，避免 select(0) 永久阻塞。 */
    private static long toSelectMillis(long remainingNanos) {
        return Math.max(1L, (remainingNanos + 999_999L) / 1_000_000L);
    }

    private static TargetResult failure(String host, int port, TargetStatus status, String error) {
        return new TargetResult(host, port, status, null, null, error);
    }

    /** 主机名解析缝：生产实现为 {@link InetAddress#getAllByName}，测试可注入伪造结果。 */
    @FunctionalInterface
    interface HostResolver {

        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** 单地址连接缝：生产实现为 {@link #defaultConnect}，测试可注入脚本化行为。 */
    @FunctionalInterface
    interface Connector {

        /**
         * 在 {@code timeoutNanos} 内完成到 {@code address} 的 TCP 握手。
         *
         * @param address      目标地址（已解析，不含主机名）
         * @param timeoutNanos 本次尝试可用的剩余预算（纳秒）
         * @throws IOException 拒绝（{@link ConnectException}）、超时
         *                     （{@link SocketTimeoutException}）或网络层错误
         */
        void connect(InetSocketAddress address, long timeoutNanos) throws IOException;
    }

    /** 解析阶段结果：成功时携带地址列表与展示用 IP 字面量，失败时携带终止性结果。 */
    private record Resolution(InetAddress[] addresses, String literal, TargetResult terminal) {

        static Resolution ok(InetAddress[] addresses, String literal) {
            return new Resolution(addresses, literal, null);
        }

        static Resolution terminal(TargetResult terminal) {
            return new Resolution(null, null, terminal);
        }
    }
}
