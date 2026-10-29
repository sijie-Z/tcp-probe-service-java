package com.example.tcpprobe.support;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link ValidHost} 的校验实现：null 与空白值交给 {@code @NotBlank} 单一负责，避免重复错误。 */
public class HostValidator implements ConstraintValidator<ValidHost, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.isBlank() || HostRules.isValid(value);
    }
}
