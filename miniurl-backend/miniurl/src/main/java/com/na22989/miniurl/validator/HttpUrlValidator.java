package com.na22989.miniurl.validator;

import com.na22989.miniurl.annotation.HttpUrl;
import com.na22989.miniurl.util.UrlSecurityUtil;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link HttpUrl} 注解校验器：委托 {@link UrlSecurityUtil} 做统一校验。
 */
public class HttpUrlValidator implements ConstraintValidator<HttpUrl, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        // 判空交给 @NotBlank 处理，使用更加灵活
        if (value == null || value.isEmpty()) {
            return true;
        }
        return UrlSecurityUtil.isSafeHttpUrl(value);
    }
}
