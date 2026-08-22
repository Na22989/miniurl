package com.na22989.miniurl.model.vo.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUserVO {

    private String accessToken;

    private String refreshToken;

    private UserVO userInfo;
}
