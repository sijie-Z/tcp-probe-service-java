package com.example.tcpprobe.probe;

import com.example.tcpprobe.model.TargetResult;
import com.example.tcpprobe.model.TargetStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProbeEngine} 的行为测试：状态语义、多地址收敛、异常分类与取消传播。
 */
class ProbeEngineTest {

    @Test
    void openPortReturnsOpenWithHandshakeLatencyAndResolvedIp() throws IOException {
        try (ServerSocket server = new ServerSocket(0)) {
            TargetResult result = ProbeEngine.probe("127.0.0.1", server.getLocalPort(), 3000);

            assertEquals(TargetStatus.OPEN, result.status());
            assertEquals("127.0.0.1", result.resolvedIp());
            assertNotNull(result.latencyMs(), "open 必须给出握手耗时");
            assertTrue(result.latencyMs() > 0, "握手耗时应为正数，实际 " + result.latencyMs());
            assertNull(result.error());
            assertEquals("127.0.0.1", result.host());
            assertEquals(server.getLocalPort(), result.port());
        }
    }

    @Test
    void closedPortReturnsClosedWithRefusedAddress() throws IOException {
        int port;
        try (ServerSocket server = new ServerSocket(0)) {
            port = server.getLocalPort();
        }
        // 本机安全软件可能把 RST 延迟约 2 秒，预算 5000ms 必须覆盖该窗口
        TargetResult result = ProbeEngine.probe("127.0.0.1", port, 5000);

        assertEquals(TargetStatus.CLOSED, result.status());
        assertEquals("127.0.0.1", result.resolvedIp());
        assertEquals("connection_refused: 127.0.0.1:" + port, result.error());
        assertNull(result.latencyMs(), "closed 不给出握手耗时");
    }

    @Test
    void invalidInputsReturnInvalidWithoutNetworkIo() {
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            assertEquals(TargetStatus.INVALID, ProbeEngine.probe("bad host!", 80, 1000).status());
            assertEquals(TargetStatus.INVALID, ProbeEngine.probe("127.0.0.1", 0, 1000).status());
            assertEquals(TargetStatus.INVALID,
                    ProbeEngine.probe("127.0.0.1", 65536, 1000).status());
            assertEquals(TargetStatus.INVALID, ProbeEngine.probe("", 80, 1000).status());
            assertEquals(TargetStatus.INVALID, ProbeEngine.probe(null, 80, 1000).status());
            assertEquals(TargetStatus.INVALID, ProbeEngine.probe("127.0.0.1", 80, 0).status());
        });
    }

    @Test
    void unresolvableHostReturnsDnsError() {
        // RFC 2606 保留域 .invalid，保证不可解析
        String host = UUID.randomUUID() + ".invalid";
        TargetResult result = ProbeEngine.probe(host, 80, 5000);

        assertEquals(TargetStatus.DNS_ERROR, result.status());
        assertNull(result.resolvedIp());
        assertNotNull(result.error());
        assertTrue(result.error().startsWith("dns_error"), result.error());
    }

    @Test
    void blackholeAddressTimesOut() {
        // 192.0.2.0/24 为 RFC 5737 TEST-NET-1，无真实主机，理论上只会超时
        TargetResult result = ProbeEngine.probe("192.0.2.1", 80, 500);

        Assumptions.assumeTrue(result.status() != TargetStatus.UNREACHABLE,
                "本机对 TEST-NET-1 快速返回 NoRouteToHost，无法构造稳定超时场景，跳过");
        assertEquals(TargetStatus.TIMEOUT, result.status());
        assertNull(result.latencyMs());
        assertNotNull(result.error());
    }

    @Test
    void ipv6LoopbackReturnsOpenWithCompressedIp() throws IOException {
        ServerSocket server;
        try {
            server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getByName("::1"), 0));
        } catch (IOException | RuntimeException e) {
            Assumptions.assumeTrue(false, "本机不支持 IPv6 回环，跳过: " + e);
            return;
        }
        try (server) {
            TargetResult result = ProbeEngine.probe("[::1]", server.getLocalPort(), 3000);

            assertEquals(TargetStatus.OPEN, result.status());
            assertEquals("::1", result.resolvedIp());
            assertNull(result.error());
        }
    }

    @Test
    void refusedAddressDoesNotAbortRemainingAddresses() throws IOException {
        InetAddress first = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        InetAddress second = InetAddress.getByAddress(new byte[] {127, 0, 0, 2});
        ProbeEngine.HostResolver resolver = host -> new InetAddress[] {first, second};
        AtomicInteger calls = new AtomicInteger();
        ProbeEngine.Connector connector = (address, timeoutNanos) -> {
            if (calls.incrementAndGet() == 1) {
                throw new ConnectException("connection refused");
            }
        };

        TargetResult result =
                ProbeEngine.probeWith("multi.example", 8080, 2000, resolver, connector);

        assertEquals(TargetStatus.OPEN, result.status());
        assertEquals(2, calls.get(), "第一个地址被拒后必须继续尝试第二个地址");
        assertEquals(second.getHostAddress(), result.resolvedIp());
        assertNull(result.error());
    }

    @Test
    void classifyMapsExceptionsToExpectedStatuses() {
        assertEquals(TargetStatus.CLOSED, ProbeEngine.classify(new ConnectException("refused")));
        assertEquals(TargetStatus.TIMEOUT,
                ProbeEngine.classify(new SocketTimeoutException("timed out")));
        assertEquals(TargetStatus.DNS_ERROR,
                ProbeEngine.classify(new UnknownHostException("nope")));
        assertEquals(TargetStatus.UNREACHABLE,
                ProbeEngine.classify(new NoRouteToHostException("no route")));
        assertEquals(TargetStatus.UNREACHABLE,
                ProbeEngine.classify(new SocketException("connection reset")));
        assertEquals(TargetStatus.ERROR, ProbeEngine.classify(new RuntimeException("boom")));
    }

    @Test
    void interruptDuringConnectPropagatesAsCancellation() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProbeEngine.HostResolver resolver =
                host -> new InetAddress[] {InetAddress.getLoopbackAddress()};
        ProbeEngine.Connector blockingConnector = (address, timeoutNanos) -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("probe cancelled");
            }
        };
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                ProbeEngine.probeWith("cancel.example", 80, 5000, resolver, blockingConnector);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });

        assertTrue(entered.await(5, TimeUnit.SECONDS), "connector 未被调用");
        worker.interrupt();
        worker.join(5000);

        assertFalse(worker.isAlive(), "探测线程未被取消");
        assertInstanceOf(CancellationException.class, thrown.get(),
                "中断必须传播为 CancellationException，不得归类为 unreachable/error");
    }
}
