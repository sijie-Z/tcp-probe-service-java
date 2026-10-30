package com.example.tcpprobe.web;

import com.example.tcpprobe.config.ProbeProperties;
import com.example.tcpprobe.model.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 请求体大小限制：Content-Length 超过上限直接 413 统一信封，避免超大 JSON 占用内存。
 *
 * <p>只依据 Content-Length 头（常规 JSON 客户端都会发送）；无 Content-Length 的分块请求
 * 由 Tomcat 连接层兜底。顺序：RequestContext 之后、RateLimit 之前——超限请求不消耗限流令牌。
 */
@Component("tcpprobeBodySizeLimitFilter")
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class BodySizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ObjectMapper objectMapper;

    public BodySizeLimitFilter(ProbeProperties properties, ObjectMapper objectMapper) {
        this.maxBytes = properties.maxBodyBytes();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (length > maxBytes) {
            String requestId = String.valueOf(
                    request.getAttribute(RequestContextFilter.ATTR_REQUEST_ID));
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(
                    "PAYLOAD_TOO_LARGE",
                    "请求体超过大小上限（" + maxBytes + " 字节）",
                    requestId));
            return;
        }
        chain.doFilter(request, response);
    }
}
