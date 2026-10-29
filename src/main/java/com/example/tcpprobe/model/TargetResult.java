package com.example.tcpprobe.model;

/**
 * 单个目标的探测结果。
 *
 * @param resolvedIp 实际尝试/成功连接的 IP（主机名解析后），未知时为 null
 * @param latencyMs  TCP 握手耗时（毫秒），仅 open 时给出；不含 DNS 阶段
 * @param error      失败原因摘要（机器可读前缀 + 细节），成功时为 null
 */
public record TargetResult(
        String host,
        int port,
        TargetStatus status,
        String resolvedIp,
        Double latencyMs,
        String error) {
}
