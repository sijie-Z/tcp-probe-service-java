package com.example.tcpprobe.support;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 严格整数反序列化：只接受 JSON 整数。
 *
 * <p>Jackson 默认的 lax 模式会把 {@code true} 反序列化成 1、把数字字符串当整数，
 * 与 API 文档的 int 语义不符；该注解在请求边界上直接拒绝，与 Python 版 StrictInt 行为一致。
 */
@Documented
@JacksonAnnotationsInside
@JsonDeserialize(using = StrictIntegerDeserializer.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrictInt {
}
