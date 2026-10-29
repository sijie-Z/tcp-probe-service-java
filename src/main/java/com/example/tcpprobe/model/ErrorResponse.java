package com.example.tcpprobe.model;

import java.util.List;

/** 统一错误响应体：所有 4xx/5xx 响应都采用该结构（307 尾斜杠重定向除外）。 */
public record ErrorResponse(ErrorBody error) {

    public record ErrorBody(String code, String message, String requestId, List<FieldError> details) {
    }

    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(String code, String message, String requestId) {
        return new ErrorResponse(new ErrorBody(code, message, requestId, null));
    }

    public static ErrorResponse of(
            String code, String message, String requestId, List<FieldError> details) {
        return new ErrorResponse(new ErrorBody(code, message, requestId, details));
    }
}
