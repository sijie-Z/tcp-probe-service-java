package com.example.tcpprobe.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.xml.Jaxb2RootElementHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * MVC 消息转换器裁剪。
 *
 * <p>运行环境没有 JAXB 实现（未引入 jakarta.xml.bind），默认转换器列表里的
 * {@link Jaxb2RootElementHttpMessageConverter} 会在内容协商时触发
 * {@code NoClassDefFoundError}，让「无 Content-Type」的请求变成 500。
 * 这里直接移除该转换器：这类请求按 HTTP 语义应得到 415 统一错误信封。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(Jaxb2RootElementHttpMessageConverter.class::isInstance);
    }
}
