package com.example.tcpprobe.service;

/** 业务校验失败（如目标数超限、timeout 超上限、wait 参数越界）。 */
public class ProbeRequestException extends RuntimeException {

    private final String code;

    public ProbeRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
