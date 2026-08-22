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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    @PostMapping("/login")
    public Result<LoginUserVO> login(@Valid @RequestBody LoginRequest request) {
        LoginUserVO loginUserVO = userService.login(request);
        return Result.success(loginUserVO);
    }


    @PostMapping("/register")
    public Result<UserVO> register(@Valid @RequestBody RegisterRequest request) {
        UserVO userVO = userService.register(request);
        return Result.success(userVO);
    }

    @GetMapping("/me")
    public Result<UserVO> me(@RequestAttribute("userId") Long userId) {
        UserVO userVO = userService.getCurrentUserInfo(userId);
        return Result.success(userVO);
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestAttribute("userId") Long userId) {
        userService.logout(userId);
        return Result.success(null);
    }

    @PostMapping("/refresh")
    public Result<RefreshTokenVO> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        RefreshTokenVO refreshTokenVO = userService.refreshToken(request);
        return Result.success(refreshTokenVO);
    }
}
