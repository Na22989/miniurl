package com.na22989.miniurl.service.impl;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.na22989.miniurl.common.ResultCodeEnum;
import com.na22989.miniurl.exception.BizException;
import com.na22989.miniurl.mapper.UserMapper;
import com.na22989.miniurl.model.dto.user.LoginRequest;
import com.na22989.miniurl.model.dto.user.RefreshTokenRequest;
import com.na22989.miniurl.model.dto.user.RegisterRequest;
import com.na22989.miniurl.model.entity.User;
import com.na22989.miniurl.model.vo.user.LoginUserVO;
import com.na22989.miniurl.model.vo.user.RefreshTokenVO;
import com.na22989.miniurl.model.vo.user.UserVO;
import com.na22989.miniurl.service.UserService;
import com.na22989.miniurl.util.JwtUtil;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.na22989.miniurl.common.RedisKeyConstant.BLACKLIST_KEY_PREFIX;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserService {

    private final JwtUtil jwtUtil;

    private final PasswordEncoder passwordEncoder;

    private static final String DEFAULT_PREFIX = "user_";

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${jwt.refresh-expiration}")
    private Long refreshExpiration;



    @Override
    public UserVO register(RegisterRequest request) {
         String username = request.getUsername();
         String password = request.getPassword();
         String nickname = request.getNickname();

        // 1. 判断用户名是否已存在
        long count = this.count(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        if (count > 0) {
            throw new BizException(ResultCodeEnum.USERNAME_EXISTS);
        }

        // 2. 创建用户
        User newUser = new User()
                .setUsername(username)
                .setPassword(passwordEncoder.encode(password))
                .setNickname(
                    Optional.ofNullable(nickname)
                    .map(String::trim)
                    .filter(StringUtils::isNotBlank)
                    .orElse(generateDefaultNickname()))
                .setCreateTime(LocalDateTime.now());

        this.save(newUser);

        // 3. 返回用户信息（不包含密码）
        return UserVO.builder()
                .id(newUser.getId())
                .username(newUser.getUsername())
                .nickname(newUser.getNickname())
                .createTime(newUser.getCreateTime())
                .build();

    }

    @Override
    public LoginUserVO login(LoginRequest request) {
         String username = request.getUsername();
         String password = request.getPassword();

         // 1. 查询用户
        User user = this.getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
        );
        if (user == null) {
            throw new BizException(ResultCodeEnum.USER_NOT_FOUND);
        }

        // 2. 校验密码
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BizException(ResultCodeEnum.PASSWORD_ERROR);
        }

        // 3. 生成refreshToken 和 accessToken
        String refreshToken = jwtUtil.generateRefreshToken(user.getId());
        String accessToken = jwtUtil.generateAccessToken(user.getId());

        // 4. 返回
        UserVO userVO = UserVO.builder().
                id(user.getId())
                .nickname(user.getNickname())
                .username(user.getUsername())
                .createTime(user.getCreateTime())
                .build();

        return LoginUserVO.builder()
                .refreshToken(refreshToken)
                .accessToken(accessToken)
                .userInfo(userVO)
                .build();
    }

    @Override
    public UserVO getCurrentUserInfo(Long userId) {
        User user = this.getById(userId);

        if (user == null) {
            throw new BizException(ResultCodeEnum.USER_NOT_FOUND);
        }

        return UserVO.builder()
                .username(user.getUsername())
                .nickname(user.getNickname())
                .id(user.getId())
                .createTime(user.getCreateTime())
                .build();
    }

    @Override
    public void logout(Long userId) {
        stringRedisTemplate.opsForValue().set(BLACKLIST_KEY_PREFIX + userId, String.valueOf(System.currentTimeMillis()),
                refreshExpiration, TimeUnit.MILLISECONDS);
    }

    @Override
    public RefreshTokenVO refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();

        // 1. 判断refreshToken是否有效
        if (!jwtUtil.validateToken(refreshToken, "refresh")) {
            throw new BizException(ResultCodeEnum.REFRESH_TOKEN_INVALID);
        }

        // 2. 从 refresh token 解出 userId（不信任外部传入，防止越权换 token）
        Long userId = jwtUtil.extractUserId(refreshToken);

        // 3. 判断refreshToken是否在黑名单中
        String blackListTimeStr = stringRedisTemplate.opsForValue().get(BLACKLIST_KEY_PREFIX + userId);
        if (blackListTimeStr != null) {
            long blackListTime = Long.parseLong(blackListTimeStr);
            if (blackListTime > jwtUtil.extractIssuedAt(refreshToken).getTime()) {
                throw new BizException(ResultCodeEnum.TOKEN_REVOKED);
            }
        }

        // 4. 生成新的 accessToken
        String newAccessToken = jwtUtil.generateAccessToken(userId);

        return RefreshTokenVO.builder()
                .accessToken(newAccessToken)
                .build();
    }

    public static String generateDefaultNickname() {
        String randomSuffix = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8);
        return DEFAULT_PREFIX + randomSuffix;
    }
}
