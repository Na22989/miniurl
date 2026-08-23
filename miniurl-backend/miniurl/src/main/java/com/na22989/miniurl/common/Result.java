package com.na22989.miniurl.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 通用返回类
 *
 * @param <T> 响应数据 data 的类型
 * @author wangtianjian
 */
@Data
public class Result<T> implements Serializable {

    private int code;

    private String message;

    private T data;

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public Result(ResultCodeEnum resultCodeEnum) {
        this(resultCodeEnum.getCode(), resultCodeEnum.getMessage(), null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCodeEnum.SUCCESS.getCode(), "success", data);
    }

    public static Result<?> fail(ResultCodeEnum resultCodeEnum) {
        return new Result<>(resultCodeEnum);
    }

    public static Result<?> fail(ResultCodeEnum resultCodeEnum, String message) {
        return new Result<>(resultCodeEnum.getCode(), message, null);
    }


    public static Result<?> fail(String message, int code) {
        return new Result<>(code, message, null);
    }


}
