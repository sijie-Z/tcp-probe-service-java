package com.example.tcpprobe.support;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * 主机名校验（Bean Validation 与探测引擎共用的单一事实来源）。
 *
 * <p>规则：接受 IP 字面量（IPv4/IPv6，IPv6 兼容 {@code [::1]} 方括号写法）；
 * 接受主机名（RFC 1123 兼容，且允许下划线——Docker Compose 服务名等内网命名常见）。
 */
public final class HostRules {

    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)(?!-)[A-Za-z0-9_-]{1,63}(?<!-)(?:\\.(?!-)[A-Za-z0-9_-]{1,63}(?<!-))*\\.?$");

    private static final Pattern IPV4_SHAPE =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    /** 纯数字/点分串：必须按严格 IPv4 解析成功，失败即整体的非法输入。 */
    private static final Pattern NUMERIC_ONLY = Pattern.compile("^[0-9.]+$");

    private HostRules() {
    }

    /** 去掉 IPv6 字面量的方括号写法：{@code [::1]} → {@code ::1}。 */
    public static String stripBrackets(String candidate) {
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            return candidate.substring(1, candidate.length() - 1);
        }
        return candidate;
    }

    /**
     * 识别 IP 字面量并返回去括号后的地址串；非 IP 字面量返回 null。
     *
     * <p>IPv4 手工解析（避免 {@code InetAddress.getByName} 对非法点分串发起 DNS 查询）；
     * IPv6 含冒号、不可能走 DNS 解析，交给 {@code InetAddress} 校验。
     */
    public static String ipLiteralOrNull(String candidate) {
        String literal = stripBrackets(candidate);

        var matcher = IPV4_SHAPE.matcher(literal);
        if (matcher.matches()) {
            for (int index = 1; index <= 4; index++) {
                String part = matcher.group(index);
                if (part.length() > 1 && part.charAt(0) == '0') {
                    return null; // 拒绝前导零，避免 010 之类的八进制歧义
                }
                if (Integer.parseInt(part) > 255) {
                    return null;
                }
            }
            return literal;
        }

        if (literal.indexOf(':') >= 0) {
            try {
                InetAddress address = InetAddress.getByName(literal);
                return address instanceof Inet6Address ? literal : null;
            } catch (UnknownHostException ex) {
                return null;
            }
        }
        return null;
    }

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        String candidate = value.strip();
        if (candidate.isEmpty()) {
            return false;
        }
        if (ipLiteralOrNull(candidate) != null) {
            return true;
        }
        if (NUMERIC_ONLY.matcher(candidate).matches()) {
            // 010.0.0.1 / 127.1 / 2130706433 这类串会被系统解析器当作 IP 简写处理，
            // 且各平台（Windows / glibc 八进制）语义不一致——严格 IPv4 解析失败即拒绝，
            // 不再回退为主机名（也是未来目标白名单的关键防绕过点）。
            return false;
        }
        return HOSTNAME.matcher(candidate).matches();
    }
}
