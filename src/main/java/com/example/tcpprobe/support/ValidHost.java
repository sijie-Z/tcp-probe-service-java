package com.example.tcpprobe.support;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 校验字段是合法的主机名或 IP 字面量（规则见 {@link HostRules}）。 */
@Documented
@Constraint(validatedBy = HostValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidHost {

    String message() default "host 不是合法的主机名或 IP 地址";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
