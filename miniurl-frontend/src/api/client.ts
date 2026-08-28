import axios, { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from 'axios';
import { message } from 'antd';
import { useAuthStore } from '../stores/authStore';
import { parseSafeJson } from '../utils/safeJson';

const client: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: {
    'Content-Type': 'application/json',
  },
  // 覆盖默认 JSON.parse：Link.id 等 Snowflake 长整型超过 Number.MAX_SAFE_INTEGER，
  // 原生解析会静默丢精度，必须在这里转成 string 保留
  transformResponse: [
    (data: string) => {
      if (typeof data !== 'string' || data.length === 0) return data;
      try {
        return parseSafeJson(data);
      } catch {
        return data;
      }
    },
  ],
});

// 单飞队列：并发 401 时只触发一次刷新，其余请求排队等待新 token
let isRefreshing = false;
let failedQueue: Array<{
  resolve: (token: string) => void;
  reject: (err: unknown) => void;
}> = [];

function processQueue(error: unknown, token: string | null) {
  failedQueue.forEach((p) => (error ? p.reject(error) : p.resolve(token as string)));
  failedQueue = [];
}

// 请求拦截器：注入 accessToken
client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const { accessToken } = useAuthStore.getState();
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

// 响应拦截器：剥壳 Result<T> + 401 静默刷新重放
client.interceptors.response.use(
  (response) => {
    const { code, message: msg, data } = response.data;
    // 20000=SUCCESS，20100=CREATED，其余视为业务错误（BizException 经 200 返回）
    if (code === 20000 || code === 20100) {
      return data;
    }
    const bizError = new Error(msg || '请求失败') as Error & { code?: number };
    bizError.code = code;
    return Promise.reject(bizError);
  },
  async (error: AxiosError<{ code: number; message: string }>) => {
    const originalRequest = error.config as (InternalAxiosRequestConfig & { _retry?: boolean }) | undefined;

    // HTTP 401：LoginInterceptor 拦截（accessToken 缺失/失效/被拉黑）
    if (error.response?.status === 401 && originalRequest && !originalRequest._retry) {
      const authStore = useAuthStore.getState();

      if (!authStore.refreshToken) {
        authStore.clearAuth();
        message.warning('登录已过期，请重新登录');
        window.location.href = '/login';
        return Promise.reject(error);
      }

      if (isRefreshing) {
        return new Promise<string>((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        }).then((token) => {
          originalRequest.headers.Authorization = `Bearer ${token}`;
          return client.request(originalRequest);
        });
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        const newAccessToken = await authStore.refreshAccess();
        processQueue(null, newAccessToken);
        originalRequest.headers.Authorization = `Bearer ${newAccessToken}`;
        return client.request(originalRequest);
      } catch (refreshError) {
        // refreshToken 也失效（40102）或被拉黑（40101）：无法恢复，清空并跳登录
        processQueue(refreshError, null);
        authStore.clearAuth();
        message.error('登录已过期，请重新登录');
        window.location.href = '/login';
        return Promise.reject(refreshError);
      } finally {
        isRefreshing = false;
      }
    }

    const errorMessage = error.response?.data?.message || error.message || '网络异常，请稍后再试';
    message.error(errorMessage);
    return Promise.reject(error);
  },
);

export default client;
