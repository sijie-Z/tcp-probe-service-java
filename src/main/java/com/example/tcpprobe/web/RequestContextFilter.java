package com.example.tcpprobe.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 为每个 HTTP 请求生成/透传 X-Request-ID，并输出一条结构化访问日志。
 *
 * <p>request id 同时写入 request attribute（供异常处理器使用）、MDC（供日志格式使用）与响应头，
 * 保证响应体、响应头、日志三处可关联。调用方传入的值必须匹配字符集与长度约束才透传，
 * 否则生成 32 位十六进制 UUID。
 */
// Bean 名称必须避开 Spring Boot 自动配置的 requestContextFilter（OrderedRequestContextFilter），
// 否则 BeanDefinitionOverrideException 导致上下文启动失败
@Component("tcpprobeRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {

    public static final String ATTR_REQUEST_ID = "tcpprobe.requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader("X-Request-ID");
        String requestId = incoming != null && VALID_REQUEST_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString().replace("-", "");

        request.setAttribute(ATTR_REQUEST_ID, requestId);
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);

        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            double durationMs = Math.round((System.nanoTime() - started) / 100_000.0) / 10.0;
            log.info("access rid={} method={} path={} status={} duration_ms={} client_ip={}",
                    requestId, request.getMethod(), request.getRequestURI(),
                    response.getStatus(), durationMs, request.getRemoteAddr());
            MDC.remove("requestId");
        }
    }
}
