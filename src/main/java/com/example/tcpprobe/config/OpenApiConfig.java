package com.example.tcpprobe.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Swagger UI（/docs）与 /v3/api-docs 的元信息。 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI tcpProbeOpenApi() {
        return new OpenAPI().info(new Info()
                .title("简易 TCP 连接探测服务")
                .version("1.0.0")
                .description("HTTP 接口接收批量 TCP 连通性探测任务，Java 21 虚拟线程 + 纯 JDK 自研探测"
                        + "（未使用任何第三方探测组件），按来源 IP 令牌桶限流。完整说明见 README。"));
    }
}
