package com.example.tcpprobe.service;

import com.example.tcpprobe.model.TargetResult;

/** 探测函数抽象（生产用 {@code ProbeEngine::probe}，测试可注入假实现）。 */
@FunctionalInterface
public interface ProbeFn {

    TargetResult probe(String host, int port, int timeoutMs);
}
