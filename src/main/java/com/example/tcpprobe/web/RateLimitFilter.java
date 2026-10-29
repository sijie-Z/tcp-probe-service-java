package com.example.tcpprobe.web;

import com.example.tcpprobe.config.ProbeProperties;
import com.example.tcpprobe.model.ErrorResponse;
import com.example.tcpprobe.ratelimit.ClientRateLimiter;
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
 * 按来源 IP 的令牌桶限流，只作用于 /api/*；超限返回 429 与 Retry-After。
 *
 * <p>客户端 IP 取 TCP 对端地址（{@code getRemoteAddr}）。不信任 X-Forwarded-For：
 * 该头可被客户端伪造；若部署在反向代理之后，限流会退化为按代理 IP 计算，
 * 应由代理层做真实来源限流（见 README 安全考量）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final ClientRateLimiter limiter;
    private final ProbeProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            ClientRateLimiter limiter, ProbeProperties properties, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.rateLimit().enabled()
                || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ClientRateLimiter.CheckResult decision = limiter.check(request.getRemoteAddr());
        if (!decision.allowed()) {
            // 上限 3600 秒：防止极小 rps 配置把取整推到溢出
            long retrySeconds = Math.max(1L,
                    Math.min(3600L, (long) Math.ceil(decision.retryAfterSeconds())));
            String requestId = String.valueOf(request.getAttribute(RequestContextFilter.ATTR_REQUEST_ID));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Retry-After", Long.toString(retrySeconds));
            objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(
                    "RATE_LIMITED", "请求过于频繁，请在 " + retrySeconds + " 秒后重试", requestId));
            return;
        }
        chain.doFilter(request, response);
    }
}
