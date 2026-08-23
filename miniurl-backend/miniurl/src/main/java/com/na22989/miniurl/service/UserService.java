package com.na22989.miniurl.service;



import com.baomidou.mybatisplus.spring.service.IService;
import com.na22989.miniurl.model.dto.user.LoginRequest;
import com.na22989.miniurl.model.dto.user.RefreshTokenRequest;
import com.na22989.miniurl.model.dto.user.RegisterRequest;
import com.na22989.miniurl.model.entity.User;
import com.na22989.miniurl.model.vo.user.LoginUserVO;
import com.na22989.miniurl.model.vo.user.RefreshTokenVO;
import com.na22989.miniurl.model.vo.user.UserVO;

public interface UserService extends IService<User> {

    /**
     * 用户注册
     *
     * @param request 注册请求（用户名、密码、昵称）
     * @return 注册成功的用户信息（不含密码）
     * @throws BizException 用户名已存在：USERNAME_EXISTS
     */
    UserVO register(RegisterRequest request);

    /**
     * 用户登录，签发 access / refresh 双 token
     *
     * @param request 登录请求（用户名、密码）
     * @return access/refresh token 与用户信息
     * @throws BizException 用户不存在 USER_NOT_FOUND；密码错误 PASSWORD_ERROR
     */
    LoginUserVO login(LoginRequest request);

    /**
     * 查询当前登录用户信息（不含密码）
     *
     * @param userId 用户 ID
     * @return 用户信息
     * @throws BizException 用户不存在：USER_NOT_FOUND
     */
    UserVO getCurrentUserInfo(Long userId);

    /**
     * 注销：将 userId 加入黑名单，使已签发 token 失效
     *
     * @param userId 用户 ID
     */
    void logout(Long userId);

    /**
     * 用 refresh token 换取新 access token
     *
     * @param request 刷新请求（含 refresh token）
     * @return 新 access token
     * @throws BizException refresh token 无效 REFRESH_TOKEN_INVALID；已被拉黑 TOKEN_REVOKED
     */
    RefreshTokenVO refreshToken(RefreshTokenRequest request);
}
