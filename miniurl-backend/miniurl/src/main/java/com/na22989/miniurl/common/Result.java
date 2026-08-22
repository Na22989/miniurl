package com.na22989.miniurl.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 通用返回类
 *
 * @param <T>
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

    /**
     * 失败
     *
     */
    public static Result<?> fail(ResultCodeEnum resultCodeEnum) {
        return new Result<>(resultCodeEnum);
    }

    /**
     * 失败
     *
     */
    public static Result<?> fail(ResultCodeEnum resultCodeEnum, String message) {
        return new Result<>(resultCodeEnum.getCode(), message, null);
    }


    public static Result<?> fail(String message, int code) {
        return new Result<>(code, message, null);
    }


}
