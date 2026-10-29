package com.example.tcpprobe.support;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link ValidHost} 的校验实现：null 交给 {@code @NotBlank} 处理，这里只校验非空值。 */
public class HostValidator implements ConstraintValidator<ValidHost, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || HostRules.isValid(value);
    }
}
