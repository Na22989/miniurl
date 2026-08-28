import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import * as authApi from '../api/auth';
import type { UserVO } from '../types/user';
import type { LoginRequest, RegisterRequest } from '../api/auth';

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  userInfo: UserVO | null;
  login: (req: LoginRequest) => Promise<void>;
  register: (req: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
  refreshAccess: () => Promise<string>;
  clearAuth: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      refreshToken: null,
      userInfo: null,

      async login(req) {
        const { accessToken, refreshToken, userInfo } = await authApi.login(req);
        set({ accessToken, refreshToken, userInfo });
      },

      async register(req) {
        await authApi.register(req);
      },

      async logout() {
        try {
          await authApi.logout();
        } finally {
          get().clearAuth();
        }
      },

      async refreshAccess() {
        const currentRefreshToken = get().refreshToken;
        if (!currentRefreshToken) {
          throw new Error('缺少 refreshToken');
        }
        // authApi.refresh 走同一个 client 实例，但 /user/refresh 已在 WebMvcConfig 排除拦截，不会递归触发 401 刷新
        const { accessToken } = await authApi.refresh(currentRefreshToken);
        set({ accessToken });
        return accessToken;
      },

      clearAuth() {
        set({ accessToken: null, refreshToken: null, userInfo: null });
      },
    }),
    {
      name: 'miniurl-auth',
      partialize: (state) => ({
        accessToken: state.accessToken,
        refreshToken: state.refreshToken,
        userInfo: state.userInfo,
      }),
    },
  ),
);
