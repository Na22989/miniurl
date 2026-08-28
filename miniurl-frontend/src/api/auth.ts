import client from './client';
import type { LoginUserVO, UserVO } from '../types/user';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  nickname?: string;
}

export function login(req: LoginRequest): Promise<LoginUserVO> {
  return client.post('/user/login', req);
}

export function register(req: RegisterRequest): Promise<UserVO> {
  return client.post('/user/register', req);
}

export function refresh(refreshToken: string): Promise<{ accessToken: string }> {
  return client.post('/user/refresh', { refreshToken });
}

export function me(): Promise<UserVO> {
  return client.get('/user/me');
}

export function logout(): Promise<null> {
  return client.post('/user/logout');
}
