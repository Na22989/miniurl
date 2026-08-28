import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 本地开发：/api 转发到后端，避免 CORS
      '/api': {
        target: 'http://localhost:9191',
        changeOrigin: true,
      },
    },
  },
});
