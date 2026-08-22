package com.na22989.miniurl.annotation;


import com.na22989.miniurl.validator.HttpUrlValidator;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = {HttpUrlValidator.class})
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpUrl {

    String message() default "链接必须是合法的 http/https URL";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
