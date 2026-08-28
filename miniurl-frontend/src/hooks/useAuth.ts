import { useAuthStore } from '../stores/authStore';

export function useAuth() {
  const accessToken = useAuthStore((s) => s.accessToken);
  const userInfo = useAuthStore((s) => s.userInfo);
  const login = useAuthStore((s) => s.login);
  const register = useAuthStore((s) => s.register);
  const logout = useAuthStore((s) => s.logout);

  return {
    isAuthenticated: !!accessToken,
    userInfo,
    login,
    register,
    logout,
  };
}
