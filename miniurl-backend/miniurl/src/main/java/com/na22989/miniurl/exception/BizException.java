package com.na22989.miniurl.exception;

import com.na22989.miniurl.common.ResultCodeEnum;
import lombok.Getter;

/**
 * 自定义异常类
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(ResultCodeEnum resultCodeEnum, String message) {
        this(resultCodeEnum.getCode(), message);
    }

    public BizException(ResultCodeEnum resultCodeEnum) {
        this(resultCodeEnum.getCode(), resultCodeEnum.getMessage());
    }

}
