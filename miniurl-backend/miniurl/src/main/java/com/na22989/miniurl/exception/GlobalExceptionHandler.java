package com.na22989.miniurl.exception;


import com.na22989.miniurl.common.Result;
import com.na22989.miniurl.common.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<?> bizExceptionHandler(BizException e) {
        log.warn("BizException: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getMessage(), e.getCode());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> methodArgumentNotValidExceptionHandler(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return Result.fail(ResultCodeEnum.BAD_REQUEST, message);
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public Result<?> constraintViolationExceptionHandler(jakarta.validation.ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", message);
        return Result.fail(ResultCodeEnum.BAD_REQUEST, message);
    }

    @ExceptionHandler(RuntimeException.class)
    public Result<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return Result.fail(ResultCodeEnum.INTERNAL_ERROR, "系统错误");
    }

    @ExceptionHandler(Exception.class)
    public Result<?> exceptionHandler(Exception e) {
        log.error("Exception", e);
        return Result.fail(ResultCodeEnum.INTERNAL_ERROR, "系统错误");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public Result<?> handleMissingHeader(MissingRequestHeaderException e) {
        log.warn("Missing header: {}", e.getHeaderName());
        return Result.fail(ResultCodeEnum.BAD_REQUEST, "缺少必要的请求头");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<?> handleMissingParameter(MissingServletRequestParameterException e) {
        log.warn("Missing param: {}", e.getParameterName());
        return Result.fail(ResultCodeEnum.BAD_REQUEST, "缺少必要的请求参数");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("Malformed JSON request");
        return Result.fail(ResultCodeEnum.BAD_REQUEST, "请求体格式错误");
    }
}
