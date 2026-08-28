/** 用户信息，与后端 model/vo/user/UserVO 对齐 */
export interface UserVO {
  id: number;
  username: string;
  nickname: string | null;
  createTime: string;
}

/** 登录返回，与后端 model/vo/user/LoginUserVO 对齐 */
export interface LoginUserVO {
  accessToken: string;
  refreshToken: string;
  userInfo: UserVO;
}
