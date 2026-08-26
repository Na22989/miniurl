# MiniURL — 轻量级私有短链服务

MiniURL 是一个自托管的轻量级 URL 短链服务：注册账号、创建短链、302 重定向、访问统计一应俱全。定位是**私有部署**——数据自持，不依赖第三方短链平台，也适合作为 SpringBoot 全栈学习项目。

## 特性

- **短链生成**：Snowflake 主键 + Base62 编码，短码最长 11 位，无碰撞
- **多级缓存**：Caffeine 本地缓存（L1）+ Redis 缓存（L2）+ 布隆过滤器防穿透，热点短链命中率 > 99%
- **点击计数**：Redis 原子 `GETDEL` 取走计数，每 30s 批量落库，失败自动 `INCREMENT` 补偿，不丢数据
- **访问统计**：PV / UV / 小时趋势 / 独立访客数，越权统一错误码不暴露短链存在性
- **安全加固**：BCrypt 密码、JWT 双 Token（access 30min + refresh 7 天）、Nginx `limit_req` 限速、可信代理白名单防 X-Forwarded-For 伪造
- **全链路可观测**：每个请求贯穿 TraceId，异步任务同步搬运；Prometheus 指标 + Grafana 看板
- **一键部署**：`docker compose up -d --build` 拉起 MySQL + Redis + App + Nginx + Prometheus + Grafana 六容器

## 技术栈

| 层 | 选型 |
|----|------|
| 语言 / 框架 | Java 17 · SpringBoot 3.4.4 |
| ORM | MyBatis-Plus 3.5.17 |
| 数据库 | MySQL 8.0 · Redis 7.x（AOF 持久化） |
| 鉴权 | JWT HS256（双 Token）· BCrypt |
| 缓存 | Caffeine（本地 5min）· Redis（远端）· Guava BloomFilter |
| 限流 | Nginx `limit_req` + 应用令牌桶（全局限流）+ 滑动窗口（用户限流） |
| 监控 | Micrometer · Prometheus · Grafana |
| 前端 | 极简页面（`miniurl-frontend/`，Swagger UI 亦可在线调试） |
| CI | GitHub Actions（单测 + Spotless 格式检查 + 镜像部署冒烟） |

## 架构

```
客户端 ──► Nginx(:80, limit_req 10r/s + burst 20)
              │  proxy_set_header X-Real-IP / X-Forwarded-For
              ▼
         miniurl-app(:9191)
              ├─ TraceIdFilter（MDC 全链路）→ 限流 → 登录鉴权 → 业务
              ├─ L1 Caffeine  → L2 Redis → DB（Cache-Aside + Bloom 防穿透）
              └─ 点击计数：Redis 计数 → 30s 批量落库（GETDEL 原子取走）
                    │
                    ▼
              MySQL（短链表 + 访问日志表）
```

- Nginx 是**对外唯一入口**，短链重定向（302）与业务接口统一走 `:80`，真实客户端 IP 透传后端
- 后端只信任白名单内的代理 IP，非可信源伪造 `X-Real-IP` / `X-Forwarded-For` 一律忽略（strict-mode 下直接拒绝）

## 快速开始

### 本地开发（直接跑 JVM）

前置：本机已有 MySQL 8.0（建库 `miniurl_db` 并执行 `sql/create_table.sql`）和 Redis 7.x，且 `application.yml` 的账号密码匹配。

```bash
cd miniurl-backend/miniurl
./mvnw clean compile -DskipTests   # 编译
./mvnw test                        # 全量测试（148/148）
./mvnw spring-boot:run             # 启动，应用端口 http://localhost:9191
```

### Docker 部署（推荐，六容器一键拉起）

```bash
docker compose up -d --build       # 构建并后台启动全部服务
docker compose ps                  # 全部 Up + healthy
```

| 服务 | 端口 | 说明 |
|------|------|------|
| nginx | :80 | 对外入口（业务接口 + 短链重定向） |
| miniurl-app | :9191 | 应用（可直接访问） |
| mysql | :3306 | 数据持久化到具名卷 |
| redis | :6379 | AOF 持久化 |
| prometheus | :9090 | 指标抓取 |
| grafana | :3000 | 看板（admin/admin） |

#### 短链对外地址（base-url）

短链拼出的完整地址取决于部署事实，用环境变量覆盖：

- 本机访问：默认 `http://localhost`（无需配置）
- 局域网 / 手机访问：`.env` 里写 `MINIURL_LAN_IP=192.168.x.x`，短链即为 `http://192.168.x.x/s/xxx`
- nginx 反代后：用 `MINIURL_BASE_URL` 指向你的域名或服务器 IP

```bash
# VM 或服务器上
echo "MINIURL_LAN_IP=192.168.100.3" >> .env
docker compose up -d
```

> ⚠️ 公网部署务必：换掉 `application.yml` 的 `jwt.secret`、收紧 `miniurl.security.trusted-proxies` 白名单（`172.16.0.0/12` 会信任所有内网主机）、开启 `MINIURL_SECURITY_STRICT_MODE=true`。

## 接口一览

短链重定向免登录；业务接口需请求头 `Authorization: Bearer <accessToken>`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/user/register` | 注册 |
| POST | `/api/user/login` | 登录（返回 access + refresh） |
| POST | `/api/user/refresh` | 刷新 access token |
| GET | `/api/user/me` | 当前用户信息 |
| POST | `/api/user/logout` | 登出（token 进黑名单） |
| POST | `/api/link/create` | 创建短链（可选过期时间） |
| POST | `/api/link/list` | 我的短链分页列表 |
| GET | `/api/link/detail/{linkId}` | 短链详情 |
| DELETE | `/api/link/delete` | 删除短链（同步清缓存） |
| GET | `/api/link/status/{linkId}` | 访问统计（PV / UV / 趋势） |
| GET | `/s/{shortCode}` | **302 重定向**到目标地址 |
| GET | `/actuator/health` | 健康检查（Docker healthcheck 用） |
| GET | `/actuator/prometheus` | Prometheus 指标 |

> 全部接口可在 `/swagger-ui.html` 在线调试（SpringDoc）。统一响应体 `Result<T>`，5 位错误码见 `ERROR_CODE.md`。

## 关键设计

| 设计点 | 方案 |
|--------|------|
| 短码 | 迷你 Snowflake 生成 64 位 id，Base62 编码为短码（最长 11 位），应用层生成、无 DB 自增往返 |
| 读路径 | Bloom 过滤 → L1 Caffeine（5min）→ L2 Redis（TTL 取 1h 与过期时间较小值）→ DB，逐级回填 |
| 点击计数 | 重定向事件异步计数到 Redis → 每 30s `SCAN` + `GETDEL` 原子取走批量落库 → 失败 `INCREMENT` 写回补偿 |
| 越权防护 | 非本人访问他人短链统一返回 `LINK_NOT_FOUND`，不暴露存在性 |
| 重定向安全 | 落库前 `@HttpUrl` 校验 + 跳转前二次协议校验（XSS 兜底），302 不缓存浏览器 |
| 可观测 | TraceId 贯穿请求 / 异步 / 定时任务；埋点指标入 Prometheus，`logCacheStats` 每 5 分钟输出缓存命中率 |

> 详细架构决策见 `TECH_DESIGN.md`，设计规格见 `docs/superpowers/specs/`，缓存方案见 `docs/WEEK2_PLAN.md`。

## 目录结构

```
miniurl-backend/miniurl/     Maven 工程（SpringBoot 应用）
├── src/main/java/com/na22989/miniurl/
│   ├── common/               Result / ResultCodeEnum / PageResult
│   ├── config/               Security / WebMvc / MyBatisPlus / Redis / Bloom / Caffeine
│   ├── controller/           User / Link / Redirect（302）
│   ├── interceptor/          Login / 限流拦截器
│   ├── model/                entity / dto / vo
│   ├── service/              业务层（impl 实现）
│   ├── task/                 SyncClickCounts2DBTask（点击计数落库）
│   └── util/                 JwtUtil / ShortLinkUtil / NetUtil（可信代理 IP 解析）
├── src/test/java/            148 个单元测试
├── sql/create_table.sql      建表脚本（compose 首次启动自动执行）
├── Dockerfile                多阶段构建（缓存 /root/.m2，非 root 运行）
└── docker-compose.yml        六服务编排（ci 覆盖见 docker-compose.ci.yml）
miniurl-frontend/             前端页面
nginx/nginx.conf              Nginx 反代 + 限速
smoke_status.sh               容器化冒烟脚本
.github/workflows/ci.yml      CI（单测 + Spotless + 镜像部署冒烟）
```

## 测试与 CI

- `./mvnw test` — 148/148 通过（服务层单测 + 安全用例 + 定时任务）
- `./mvnw spotless:check` — 格式门禁（removeUnusedImports / 行尾空白 / 文件结尾换行）
- CI（GitHub Actions）：job1 托管 runner 跑 Spotless + 单测；job2 self-hosted runner 构建镜像、起三件套、跑 `smoke_status.sh` 冒烟

## 文档索引

| 文档 | 用途 |
|------|------|
| `CLAUDE.md` | 项目规范、构建命令、禁止修改清单（新会话先读） |
| `docs/codex-handoff6.md` | 当前进度 + 遗留项（新会话必读） |
| `TECH_DESIGN.md` / `PRD.md` | 原始技术决策 / 产品需求 |
| `ERROR_CODE.md` | 5 位错误码速查 |
| `docs/DEV_CHECKLIST.md` | 写代码后自检 |
| `docs/linux-progress.md` | Linux 学习进度（场景题） |
