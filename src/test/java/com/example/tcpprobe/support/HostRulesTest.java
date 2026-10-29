package com.example.tcpprobe.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostRulesTest {

    @Test
    void acceptsNormalHostsAndIpLiterals() {
        assertTrue(HostRules.isValid("example.com"));
        assertTrue(HostRules.isValid("my_service"));
        assertTrue(HostRules.isValid("localhost"));
        assertTrue(HostRules.isValid("10.0.0.1"));
        assertTrue(HostRules.isValid("::1"));
        assertTrue(HostRules.isValid("[::1]"));
    }

    @Test
    void rejectsNumericShorthandThatBypassesStrictIpv4() {
        // 这些串会被系统解析器当作 IP 简写（且各平台语义不一致）：
        // 严格 IPv4 解析失败必须拒绝，不能回退成主机名走 DNS
        assertFalse(HostRules.isValid("010.0.0.1"));
        assertFalse(HostRules.isValid("127.1"));
        assertFalse(HostRules.isValid("2130706433"));
        assertFalse(HostRules.isValid("0"));
        assertFalse(HostRules.isValid("999.999.999.999"));
    }

    @Test
    void rejectsMalformedHostnames() {
        assertFalse(HostRules.isValid(""));
        assertFalse(HostRules.isValid("   "));
        assertFalse(HostRules.isValid("bad host!"));
        assertFalse(HostRules.isValid("-leading"));
        assertFalse(HostRules.isValid("trailing-"));
    }
}
