package com.example.tcpprobe.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 单个目标的探测结果状态。
 *
 * <p>语义约定的关键点：
 * <ul>
 *   <li>{@code closed} 与 {@code timeout} 是两回事：前者说明主机可达、端口明确拒绝（RST），
 *       后者是超时预算内没有收到任何明确答复，无法判断端口是否开放；</li>
 *   <li>{@code unreachable} 是网络层给出的明确否定（无路由、主机不可达等），
 *       比 {@code timeout} 更强：请求根本没到达目标。</li>
 * </ul>
 */
public enum TargetStatus {

    /** TCP 三次握手成功，端口可连接。 */
    OPEN("open"),

    /** 对端明确拒绝（RST / ECONNREFUSED）：主机可达、端口未监听。 */
    CLOSED("closed"),

    /** 在超时预算内未完成握手，且没有任何地址给出明确答复。 */
    TIMEOUT("timeout"),

    /** 主机名解析失败（NXDOMAIN / 临时 DNS 故障）。 */
    DNS_ERROR("dns_error"),

    /** 网络层不可达（NoRouteToHost、连接被重置等）。 */
    UNREACHABLE("unreachable"),

    /** 入参非法（主机名或端口不合法），未发起任何连接。 */
    INVALID("invalid"),

    /** 探测过程中发生未预期异常（内部错误，不应当出现）。 */
    ERROR("error"),

    /** 任务被取消，该目标未完成探测。 */
    CANCELLED("cancelled");

    private final String wireName;

    TargetStatus(String wireName) {
        this.wireName = wireName;
    }

    /** JSON 序列化时输出的稳定值（小写下划线），与 README 的语义表一一对应。 */
    @JsonValue
    public String wireName() {
        return wireName;
    }
}
