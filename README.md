# MiniURL — 轻量级私有短链服务

MiniURL 是一个自托管的轻量级 URL 短链服务：注册账号、创建短链、302 重定向、访问统计一应俱全。定位是**私有部署**——数据自持，不依赖第三方短链平台。

## 特性

- **短链生成**：Snowflake 主键 + Base62 编码，短码最长 11 位，应用层生成无 DB 往返
- **多级缓存**：Caffeine 本地缓存（L1）+ Redis 缓存（L2）+ 布隆过滤器防穿透，压测下缓存命中 100%、DB 零回源
- **点击计数**：Redis 原子 `GETDEL` 取走计数，每 30s 批量落库，失败自动 `INCREMENT` 补偿，不丢数据
- **访问统计**：PV / UV / 小时趋势 / 独立访客数，越权统一错误码不暴露短链存在性
- **安全加固**：BCrypt 密码、JWT 双 Token（access 30min + refresh 7 天）、Nginx `limit_req` 限速、可信代理白名单防 X-Forwarded-For 伪造
- **全链路可观测**：每个请求贯穿 TraceId，异步任务同步搬运；Prometheus 指标 + Grafana 看板
- **一键部署**：`docker compose up -d --build` 拉起 MySQL + Redis + App + 前端 + Nginx + Prometheus + Grafana 七容器

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
| 前端 | React 18 + TypeScript + Vite + Ant Design + Zustand + ECharts |
| CI | GitHub Actions（单测 + Spotless 格式检查 + 镜像部署冒烟） |

## 架构

```
客户端 ──► Nginx(:80, limit_req 10r/s + burst 20)
              │  /api/ /s/ → 后端 ; /  → 前端静态资源
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

### 1. 配置环境变量（必做）

口令与 JWT 密钥不写进代码，统一由环境变量注入：

```bash
cd miniurl-backend/miniurl
cp .env.example .env
```

然后编辑 `.env`，**至少填 `JWT_SECRET`**（HS256 要求 ≥ 32 字节）：

```bash
openssl rand -base64 48
# PowerShell: [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Max 256 }))
```

> `.env` 已被 `.gitignore` 忽略，不会入库。`docker compose` 会自动读取与
> `docker-compose.yml` 同目录的 `.env`。
>
> **Docker 栈未设置 `JWT_SECRET` 会直接启动失败**——这是刻意设计，
> 避免生产环境静默沿用 `application.yml` 里那个已随仓库公开的开发默认值。

### 2. 本地开发（直接跑 JVM）

前置：本机已有 MySQL 8.0（建库 `miniurl_db` 并执行 `sql/create_table.sql`）和 Redis 7.x。

`application.yml` 的数据库/缓存口令有 `root` 默认值，本地不配 `.env` 也能跑；
只有 `JWT_SECRET` 建议显式设置。

```bash
cd miniurl-backend/miniurl
./mvnw clean compile -DskipTests   # 编译
./mvnw test                        # 全量测试（158/158）
./mvnw spring-boot:run             # 启动，应用端口 http://localhost:9191
```

### 3. Docker 部署（推荐，七容器一键拉起）

```bash
docker compose up -d --build       # 构建并后台启动全部服务
docker compose ps                  # 全部 Up + healthy
```

| 服务 | 端口 | 说明 |
|------|------|------|
| nginx | :80 | 对外入口（业务接口 + 短链重定向 + 前端页面） |
| miniurl-app | :9191 | 应用（可直接访问） |
| mysql | :3306 | 数据持久化到具名卷 |
| redis | :6379 | AOF 持久化 |
| prometheus | :9090 | 指标抓取 |
| grafana | :3000 | 看板（口令见 `.env` 的 `GRAFANA_PASSWORD`） |
| frontend | — | 静态资源，不经 nginx 不对外暴露端口 |

#### 短链对外地址（base-url）

短链拼出的完整地址取决于**部署事实**（谁去点这条短链），不是请求上下文，
所以不能用 `request.getServerName()` 推，必须显式配置。

`docker-compose.yml` 里把容器内的 `MINIURL_BASE_URL` 拼成 `http://${MINIURL_LAN_IP:-localhost}`
（Spring 宽松绑定 → `miniurl.base-url`），因此**只需在 `.env` 里设 `MINIURL_LAN_IP`**：

```bash
echo "MINIURL_LAN_IP=192.168.100.3" >> .env    # 局域网 / 手机访问
docker compose up -d                           # 短链变为 http://192.168.100.3/s/xxx
```

不设置则回退 `http://localhost`（本机访问）。
> 注意：compose 文件里这一行是写死的 `MINIURL_BASE_URL: http://${MINIURL_LAN_IP:-localhost}`，
> 所以直接在 `.env` 里写 `MINIURL_BASE_URL=https://你的域名` **不生效**——会被 compose 覆盖。
> 走域名/ HTTPS 需要改 compose 那一行，或直接用 JVM 部署时设该环境变量。

> ⚠️ 公网部署务必：收紧 `miniurl.security.trusted-proxies` 白名单
> （默认的 `172.16.0.0/12` 会信任所有内网主机）、开启
> `MINIURL_SECURITY_STRICT_MODE=true`、并把 `.env` 里所有口令换成强口令。

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

> 全部接口可在 `/swagger-ui.html` 在线调试（SpringDoc）。统一响应体 `Result<T>`，
> 错误码定义见 `common/ResultCodeEnum.java`。

## 关键设计

| 设计点 | 方案 |
|--------|------|
| 短码 | 迷你 Snowflake 生成 64 位 id，Base62 编码为短码（最长 11 位），应用层生成、无 DB 自增往返 |
| 读路径 | Bloom 过滤 → L1 Caffeine（5min）→ L2 Redis（TTL 取 1h 与过期时间较小值）→ DB，逐级回填 |
| 缓存三防 | 穿透 → 布隆过滤器；击穿 → singleflight 合并回源（自旋 + 超时降级 DB）；雪崩 → 随机 TTL |
| 点击计数 | 重定向事件异步计数到 Redis → 每 30s `SCAN` + `GETDEL` 原子取走批量落库 → 失败 `INCREMENT` 写回补偿 |
| 越权防护 | 非本人访问他人短链统一返回 `LINK_NOT_FOUND`，不暴露存在性 |
| 重定向安全 | 落库前 `@HttpUrl` 校验 + 跳转前二次协议校验（XSS 兜底），302 不缓存浏览器 |
| 限流 | Lua 脚本保证原子性：用户维度 ZSET 滑动窗口 + 全局令牌桶，双维度独立开关 |
| 可观测 | TraceId 贯穿请求 / 异步 / 定时任务；埋点指标入 Prometheus，`logCacheStats` 每 5 分钟输出缓存命中率 |

### 压测结论

用 JMeter 对读路径做了 R1–R5 系列实验，两个有代表性的结果：

- **L1 + L2 混合读**（15000 码集 > L1 容量 10000，刻意构造的工作集大于本地缓存的场景）：
  L1 命中 59.8% / L2 命中 40.2% / **DB 回源 0**
- **L2 的边际代价仅 7.5%**（纯 L1 2,329 QPS → 混合 2,155 QPS），远低于预估的 35%
  ——说明瓶颈不在缓存读路径

## 已知局限

这些是我在开发和压测中发现的问题，刻意保留在 README 里。知道系统边界在哪，
比假装它没有边界更有意义。每条都写清了触发条件与修复方向。

### 1. 布隆过滤器是进程内内存，多实例部署会产生假阴性

`BloomFilterConfig` 用的是 Guava `BloomFilter`，数据存在 **JVM 堆内**。

- **单实例**：没问题——启动时 `@PostConstruct` 全量加载短码，新建短码同步 `put`
- **多实例**：实例 A 新建的短码，实例 B 的布隆里没有 → 用户访问时 B 判定"一定不存在"
  → **合法短链返回 404**

注意这**不是**"误判率 1%"那个假阳性问题——**假阴性是功能性缺陷，不是概率问题**。

**修复方向**：换成 Redis 布隆（或 RedisBloom 模块），让所有实例共享同一份位图。
**现状**：`bloomReady` volatile 开关支持优雅降级（加载未完成时跳过布隆直接查缓存/DB），
但多实例假阴性未处理——项目定位是单机自托管，多实例不在当前范围内。

### 2. 短码冲突未做重试

`ResultCodeEnum.SHORT_CODE_CONFLICT(42102)` 定义了，但**全仓没有任何地方抛出它**。

`createLink()` 里 `this.save(link)` 直接落库，没有捕获 `DuplicateKeyException`。
`short_code` 上有唯一索引（`uk_short_code`），一旦编码撞码，`save` 抛出的异常会冒泡成 500，
而不是按设计返回 42102 让调用方重试。

**当前概率**：Snowflake ID 本身唯一，撞码只可能来自 Base62 编码截断（短码最长 11 位，
要到 62^11 才截断，实际远未触及）。**现在不会触发，但设计意图和实现不一致。**

**修复方向**：`save` 包一层 `catch (DuplicateKeyException)` → 重新生成 → 重试 N 次。

### 3. Snowflake 机器位写死为 0

`ShortLinkUtil` 的 10 位机器 ID **当前固定为 0**，靠 `synchronized` 保证单机唯一。

**多实例部署时所有实例共用同一个 workerId → 同一毫秒内会生成相同 ID。**
（`ShortLinkUtil` 的类注释里已写明这一点。）

**修复方向**：从环境变量 / 注册中心分配 workerId。

### 4. 读路径吞吐被异步线程池回压限制在 ~2.1k QPS

这是**刻意的取舍，不是 bug**，但值得说明。

点击计数走 `Spring Event + @Async`，线程池拒绝策略选了 **`CallerRunsPolicy`**——
计数不能丢，宁可把任务回压到 Tomcat 工作线程让请求变慢，也不能静默丢弃造成统计错误。

**代价**：JMeter 实测读路径吞吐 2.15k QPS。瓶颈不在缓存层（见上文 7.5% 的边际代价），
而在这个回压。回压本身是自限的——压力越大回压越强，形成天然背压保护。

**修复方向**：计数改为 Redis 原子自增 + 定时批量落库，彻底移出请求链路。

### 5. `nextId()` 全方法加锁

`public synchronized long nextId()` —— 单机 ID 生成受锁限制。

**实测**：微基准下单机 3.6M ID/s（序列号位 4096/4096 打满），**远高于实际写入需求**，
所以没有优化。

**修复方向**：`ThreadLocal` 分段或改用无锁序列。

### 6. 本地缓存 L1 在多实例间不会互相失效

读路径是 L1（Caffeine）→ L2（Redis）→ DB 的 Cache-Aside 多级缓存，
`shortCodeLocalCache` 是 **JVM 进程内**的 Caffeine 实例（`expireAfterWrite` 5 分钟）。

`deleteLink()` 里做了两件事：

```java
stringRedisTemplate.delete(SHORT_CODE_PREFIX + link.getShortCode()); // L2：Redis，所有实例共享
shortCodeLocalCache.invalidate(link.getShortCode());                 // L1：仅当前 JVM
```

第一行删的是 Redis，**删了就是删了**；第二行 `invalidate` 只作用于**当前进程**。

- **单实例**：没问题——L1、L2 同时失效，删除立即生效
- **多实例**：实例 A 删除 → A 的 L1 已失效，但实例 B 的 L1 里**仍持有该短链的缓存值**
  → B 上的请求在 L1 就命中了，**根本不会去查那条已经被删掉的 Redis 键**
  → **用户最长 5 分钟（L1 的 TTL）内仍能通过 B 访问一条已删除的短链**

触发前提是 B 在此之前服务过这个短码（L2 命中会回填 L1），所以不是纯理论边界——
实例数一多、访问一分散就会碰到。

**修复方向**：L1 失效改为广播——删除后往 Redis Pub/Sub（或消息队列）发一条失效通知，
各实例收到后各自 `invalidate`。也可以直接把 L1 的 TTL 压到秒级：L1 换来的收益本就只是
省一次 Redis RTT，为它承担 5 分钟的不一致窗口并不划算。

**验证状态**：本条**由代码路径推导，尚未做多实例实测**。触发链条依赖的每一步
（L1 命中即返回、`invalidate` 的进程内语义）都能在上面引用的代码里直接读到，
但没有跑过双实例复现。

## 目录结构

```
miniurl/
├── miniurl-backend/miniurl/       Maven 工程（SpringBoot 应用）
│   ├── src/main/java/com/na22989/miniurl/
│   │   ├── annotation/  validator/    @HttpUrl 注解 + 校验器
│   │   ├── common/                    Result / ResultCodeEnum / PageResult
│   │   ├── config/                    Security / WebMvc / MyBatisPlus / Redis / Bloom / Caffeine / Async
│   │   ├── controller/                User / Link / Redirect（302）
│   │   ├── event/                     点击计数事件 + 监听器（@Async）
│   │   ├── exception/                 BizException / GlobalExceptionHandler
│   │   ├── filter/                    TraceIdFilter（MDC 全链路）
│   │   ├── interceptor/               LoginInterceptor / 限流拦截器
│   │   ├── mapper/                    UserMapper / LinkMapper / LinkAccessLogMapper
│   │   ├── model/                     entity / dto / vo
│   │   ├── monitor/                   自定义埋点指标
│   │   ├── service/                   业务层（impl 实现）
│   │   ├── task/                      SyncClickCounts2DBTask（点击计数落库）
│   │   └── util/                      Jwt / ShortLink / NetUtil（可信代理 IP 解析）/ UrlSecurity / TraceId
│   ├── src/test/java/                 158 个单元测试
│   ├── sql/create_table.sql           建表脚本（compose 首次启动自动执行）
│   ├── nginx/nginx.conf               反向代理 + limit_req 限速（挂载进 nginx 容器）
│   ├── postman/collections/           Postman 回归集合（TC-01 ~ TC-23）
│   ├── .env.example                   环境变量模板（复制为 .env 后填值）
│   ├── Dockerfile                     多阶段构建（缓存 /root/.m2，非 root 运行）
│   ├── docker-compose.yml             七服务编排
│   └── docker-compose.ci.yml          CI 覆盖（容器名/端口/卷全隔离）
├── miniurl-frontend/              前端（React 18 + TS + Vite + AntD）
│   ├── src/  Dockerfile  nginx.conf
├── JmeterTest/                    JMeter 压测场景（*.jmx + 造数脚本）
├── smoke_status.sh                容器化冒烟脚本（CI job2 调用）
├── test_api.sh                    接口回归脚本
└── .github/workflows/ci.yml       CI（单测 + Spotless + 镜像部署冒烟）
```

## 测试与 CI

- `./mvnw test` — **158/158** 通过（服务层单测 + 安全用例 + 定时任务）
- `./mvnw spotless:check` — 格式门禁（removeUnusedImports / 行尾空白 / 文件结尾换行）
- CI（GitHub Actions）：两个 job 都跑在 GitHub 托管 runner 上——job1 跑 Spotless + 全量单测；job2 构建镜像、起 MySQL/Redis/App 三件套、跑 `smoke_status.sh` 冒烟

## 关于 AI 参与

这是我在秋招前做的个人学习项目。开发过程中大量使用了 AI 编程工具，
这里如实说明边界，方便你对项目做出判断。

**后端** —— 架构与关键决策由我主导，代码由我编写，AI 主要承担 review、
方案讨论和测试用例补充：

- **我主导**：整体架构设计；两级缓存的选型与「为什么是 L1+L2」的取舍；
  布隆过滤器 / 单飞合并回源 / 随机 TTL 的方案设计；限流策略；Snowflake + Base62
  短码方案；R1–R5 系列压测的实验设计与数据解读；索引与游标分页优化；
  以及上文「已知局限」中每一条的定位
- **AI 辅助**：代码 review 与改进建议、部分单元测试用例、文档润色、重复性样板代码

**前端** —— `miniurl-frontend/` 基本由 AI 生成，我只做了接口对接与联调。
它能用，但**请不要把它当作我前端能力的证明**。

写这段不是免责声明。AI 辅助开发现在很普遍，但「用 AI」和「被 AI 用」是两回事——
我希望你对这个项目的判断建立在**我做的决策**上，而不是代码行数上。
上面「已知局限」里的每一条我都能讲清触发条件和取舍，包括做得不好的地方。

## License

MIT
