package com.na22989.miniurl.controller;

import com.na22989.miniurl.common.Result;
import com.na22989.miniurl.model.dto.user.LoginRequest;
import com.na22989.miniurl.model.dto.user.RefreshTokenRequest;
import com.na22989.miniurl.model.dto.user.RegisterRequest;
import com.na22989.miniurl.model.vo.user.LoginUserVO;
import com.na22989.miniurl.model.vo.user.RefreshTokenVO;
import com.na22989.miniurl.model.vo.user.UserVO;
import com.na22989.miniurl.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    /**
     * 用户登录
     *
     * @param request 登录请求（用户名、密码）
     * @return access/refresh token 与用户信息
     * @throws BizException 用户不存在 USER_NOT_FOUND；密码错误 PASSWORD_ERROR
     */
    @PostMapping("/login")
    public Result<LoginUserVO> login(@Valid @RequestBody LoginRequest request) {
        LoginUserVO loginUserVO = userService.login(request);
        return Result.success(loginUserVO);
    }


    /**
     * 用户注册
     *
     * @param request 注册请求（用户名、密码、昵称）
     * @return 注册成功的用户信息
     * @throws BizException 用户名已存在：USERNAME_EXISTS
     */
    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        UserVO userVO = userService.register(request);
        return Result.success(userVO);
    }

    /**
     * 查询当前登录用户信息
     *
     * @param userId 用户 ID（拦截器注入）
     * @return 当前用户信息
     * @throws BizException 用户不存在：USER_NOT_FOUND
     */
    @GetMapping("/me")
    public Result<UserVO> me(@RequestAttribute("userId") Long userId) {
        UserVO userVO = userService.getCurrentUserInfo(userId);
        return Result.success(userVO);
    }

    /**
     * 注销：将 userId 加入黑名单，使已签发 token 失效
     *
     * @param userId 用户 ID（拦截器注入）
     * @return 统一结果
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestAttribute("userId") Long userId) {
        userService.logout(userId);
        return Result.success(null);
    }

    /**
     * 用 refresh token 换取新 access token
     *
     * @param request 刷新请求（含 refresh token）
     * @return 新 access token
     * @throws BizException refresh token 无效 REFRESH_TOKEN_INVALID；已被拉黑 TOKEN_REVOKED
     */
    @PostMapping("/refresh")
    public Result<RefreshTokenVO> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        RefreshTokenVO refreshTokenVO = userService.refreshToken(request);
        return Result.success(refreshTokenVO);
    }
}
