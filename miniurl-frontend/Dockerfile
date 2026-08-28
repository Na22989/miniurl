# 构建阶段：node 环境打包出静态资源
FROM node:22-alpine AS build
WORKDIR /app

# 先复制依赖清单，利用 Docker 层缓存：依赖不变时不重复 npm ci
COPY package.json package-lock.json ./
RUN npm ci

# 复制源码并构建（.dockerignore 已排除 node_modules/dist）
COPY . .
RUN npm run build

# 运行阶段：nginx 只服务静态文件，不代理业务
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
