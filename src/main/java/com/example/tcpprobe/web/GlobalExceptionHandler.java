package com.example.tcpprobe.web;

import com.example.tcpprobe.model.ErrorResponse;
import com.example.tcpprobe.service.ProbeRequestException;
import com.example.tcpprobe.service.TaskAlreadyFinishedException;
import com.example.tcpprobe.service.TaskCapacityException;
import com.example.tcpprobe.service.TaskNotFoundException;
import com.fasterxml.jackson.databind.JsonMappingException;
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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
                .map(error -> new ErrorResponse.FieldError(
                        normalizeBodyField(error.getField()), error.getDefaultMessage()))
                .toList();
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "请求参数校验失败", request, details, new HttpHeaders());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> onConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> details = ex.getConstraintViolations().stream()
                .map(violation -> new ErrorResponse.FieldError(
                        normalizeQueryParam(violation.getPropertyPath().toString()),
                        violation.getMessage()))
                .toList();
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "请求参数校验失败", request, details, new HttpHeaders());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> onTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String expected = ex.getRequiredType() == null ? "未知" : ex.getRequiredType().getSimpleName();
        List<ErrorResponse.FieldError> details = List.of(new ErrorResponse.FieldError(
                normalizeQueryParam(ex.getName()), "参数类型不正确，期望 " + expected));
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "请求参数校验失败", request, details, new HttpHeaders());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> onHandlerMethodValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldError> details = ex.getParameterValidationResults().stream()
                .map(result -> new ErrorResponse.FieldError(
                        normalizeQueryParam(
                                result.getMethodParameter().getParameterName()),
                        result.getResolvableErrors().isEmpty()
                                ? "参数不合法"
                                : result.getResolvableErrors().get(0).getDefaultMessage()))
                .toList();
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "请求参数校验失败", request, details, new HttpHeaders());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> onUnreadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        String field = "";
        String message = "请求体解析失败";
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof JsonMappingException mappingException) {
            field = normalizeBodyField(mappingException.getPath().stream()
                    .map(reference -> reference.getFieldName() != null
                            ? reference.getFieldName()
                            : "[" + reference.getIndex() + "]")
                    .reduce((left, right) -> right.startsWith("[") ? left + right : left + "." + right)
                    .orElse(""));
            message = mappingException.getOriginalMessage();
        } else if (cause != null) {
            message = cause.getClass().getSimpleName();
        }
        List<ErrorResponse.FieldError> details = List.of(new ErrorResponse.FieldError(field, message));
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
                "请求体不是合法 JSON 或字段类型不符", request, details, new HttpHeaders());
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

    /**
     * 请求体字段路径规范化：{@code targets[0].port} → {@code targets.0.port}（与 Python 版一致）。
     */
    private static String normalizeBodyField(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.replaceAll("\\[(\\d+)]", ".$1");
    }

    /**
     * 查询参数名规范化：{@code list.limit} / {@code create.waitTimeoutMs} → {@code query.limit} /
     * {@code query.wait_timeout_ms}（与 Python 版一致的 query 前缀 + snake_case）。
     */
    private static String normalizeQueryParam(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "query.";
        }
        String name = raw.substring(raw.lastIndexOf('.') + 1);
        name = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
        return "query." + name;
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
