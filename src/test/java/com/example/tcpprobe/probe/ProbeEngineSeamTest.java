package com.example.tcpprobe.probe;

import com.example.tcpprobe.model.TargetResult;
import com.example.tcpprobe.model.TargetStatus;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 通过注入缝确定性地验证「连接超时」分支（不依赖黑洞地址/防火墙行为）。
 */
class ProbeEngineSeamTest {

    @Test
    void injectedConnectTimeoutMapsToTimeout() {
        ProbeEngine.HostResolver resolver =
                host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
        ProbeEngine.Connector connector = (address, timeoutNanos) -> {
            throw new SocketTimeoutException("simulated handshake timeout");
        };

        TargetResult result = ProbeEngine.probeWith("example.test", 80, 500, resolver, connector);

        assertEquals(TargetStatus.TIMEOUT, result.status());
        assertNotNull(result.error());
        assertEquals(true, result.error().startsWith("timeout:"), result.error());
    }
}
