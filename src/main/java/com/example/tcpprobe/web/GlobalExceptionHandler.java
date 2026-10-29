package com.example.tcpprobe.web;

import com.example.tcpprobe.model.ErrorResponse;
import com.example.tcpprobe.service.ProbeRequestException;
import com.example.tcpprobe.service.TaskAlreadyFinishedException;
import com.example.tcpprobe.service.TaskCapacityException;
import com.example.tcpprobe.service.TaskNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * 统一错误信封：所有 4xx/5xx 响应都转换为
 * {@code {"error": {"code", "message", "request_id", "details"}}}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ErrorResponse> onTaskNotFound(
            TaskNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或已过期", request);
    }

    @ExceptionHandler(TaskAlreadyFinishedException.class)
    public ResponseEntity<ErrorResponse> onTaskFinished(
            TaskAlreadyFinishedException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "TASK_ALREADY_FINISHED", "任务已结束，无法取消", request);
    }

    @ExceptionHandler(TaskCapacityException.class)
    public ResponseEntity<ErrorResponse> onTaskCapacity(
            TaskCapacityException ex, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "2");
        return build(HttpStatus.SERVICE_UNAVAILABLE, "TASK_CAPACITY_EXCEEDED",
                ex.getMessage(), request, null, headers);
    }

    @ExceptionHandler(ProbeRequestException.class)
    public ResponseEntity<ErrorResponse> onProbeRequest(
            ProbeRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> onValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "请求参数校验失败", request, details, new HttpHeaders());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> onConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> details = ex.getConstraintViolations().stream()
                .map(violation -> new ErrorResponse.FieldError(
                        violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "请求参数校验失败", request, details, new HttpHeaders());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "请求体不是合法 JSON 或字段类型不符", request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> onNoHandler(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not Found", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> onMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (ex.getSupportedHttpMethods() != null) {
            headers.setAllow(ex.getSupportedHttpMethods());
        }
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                ex.getMessage(), request, null, headers);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> onMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> onUnhandled(Exception ex, HttpServletRequest request) {
        log.error("unhandled_error rid={}", requestId(request), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误", request);
    }

    private static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestContextFilter.ATTR_REQUEST_ID);
        return value == null ? "" : value.toString();
    }

    private static ResponseEntity<ErrorResponse> build(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        return build(status, code, message, request, null, new HttpHeaders());
    }

    private static ResponseEntity<ErrorResponse> build(
            HttpStatus status, String code, String message, HttpServletRequest request,
            List<ErrorResponse.FieldError> details, HttpHeaders headers) {
        String requestId = requestId(request);
        HttpHeaders merged = new HttpHeaders();
        merged.putAll(headers);
        merged.set("X-Request-ID", requestId);
        ErrorResponse body = details == null
                ? ErrorResponse.of(code, message, requestId)
                : ErrorResponse.of(code, message, requestId, details);
        return new ResponseEntity<>(body, merged, status);
    }
}
