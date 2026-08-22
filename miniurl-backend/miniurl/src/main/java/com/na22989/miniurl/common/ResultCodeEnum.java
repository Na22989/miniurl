package com.na22989.miniurl.common;


import lombok.Getter;

@Getter
public enum ResultCodeEnum {

    // ─── 成功 ───
    SUCCESS(20000, "ok"),
    CREATED(20100, "创建成功"),

    // ─── 通用客户端错误 ───
    BAD_REQUEST(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未登录或 Token 已过期"),
    TOKEN_REVOKED(40101, "Token 已被注销，请重新登录"),
    REFRESH_TOKEN_INVALID(40102, "Refresh Token 无效或已过期，请重新登录"),
    FORBIDDEN(40300, "无权限访问"),
    NOT_FOUND(40400, "请求资源不存在"),

    // ─── 用户模块 41xxx ───
    USERNAME_EXISTS(41100, "用户名已存在"),
    USER_NOT_FOUND(41101, "用户不存在"),
    PASSWORD_ERROR(41102, "密码错误"),

    // ─── 短链模块 42xxx ───
    LINK_NOT_FOUND(42100, "短链不存在"),
    LINK_EXPIRED(42101, "短链已过期"),
    SHORT_CODE_CONFLICT(42102, "短码生成冲突，请重试"),

    // ─── 限流 43xxx ───
    RATE_LIMIT(43100, "请求过于频繁，请稍后再试"),

    // ─── 服务端错误 ───
    INTERNAL_ERROR(50000, "服务器内部错误"),
    OPERATION_FAILED(50001, "操作失败");

    private final int code;
    private final String message;

    ResultCodeEnum(int code, String message) {
        this.code = code;
        this.message = message;
    }

}