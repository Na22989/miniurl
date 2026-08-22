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
     */
    UserVO register(RegisterRequest request);

    /**
     * 用户登录
     */
    LoginUserVO login(LoginRequest request);

    UserVO getCurrentUserInfo(Long userId);

    void logout(Long userId);

    RefreshTokenVO refreshToken(RefreshTokenRequest request);
}
