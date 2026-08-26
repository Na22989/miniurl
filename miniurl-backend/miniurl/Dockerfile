# 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 利用 Docker 缓存：先复制依赖定义文件
COPY pom.xml ./
# --mount=type=cache 把 /root/.m2 挂成持久缓存卷：依赖放卷里、不进镜像层（RUN 层保持极小），
# 图层缓存失效（pom 变更）时依赖仍在卷中，只下增量；配合 CI 清理用 --filter until=72h 跨轮存活。
# 曾踩坑：CI cleanup 用裸 prune -f 每轮清空整个 build cache（含缓存卷）→ 次轮必然全量重编。
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline \
    && mvn -B dependency:resolve-plugins

# 复制源码并构建（此时依赖已缓存）
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B clean package -DskipTests \
    && cp target/app.jar /build/app.jar

# 运行阶段
FROM eclipse-temurin:17-jre AS runtime

# 该镜像自带非 root 用户（uid/gid 1000，用户名为 ubuntu）。
# 不再 adduser/addgroup 创建同名用户（会抢 1000 导致构建失败），直接用数字 UID 更抗镜像改名。

WORKDIR /app

# 从构建阶段复制 JAR，并设置所有者为 1000:1000
COPY --from=builder --chown=1000:1000 /build/app.jar app.jar

# /app 由 WORKDIR 创建时归 root；显式交给 1000:1000，
# 否则应用（logging.file.name=logs/miniurl.log）在 /app/logs 写日志会权限不足
RUN chown -R 1000:1000 /app

# 暴露端口
EXPOSE 9191

# 切换到非 root 用户（镜像自带 ubuntu 用户）
USER 1000:1000

# 启动应用（MaxRAMPercentage 依赖容器感知，JDK17 默认开启，无需显式 UseContainerSupport）
# -Duser.timezone=Asia/Shanghai：与 MySQL（TZ: Asia/Shanghai）时区对齐，避免 UTC 差 8 小时
# 导致 expireTime 过期判断、link_access_log 时间、趋势查询窗口错位
ENTRYPOINT ["java", \
    "-Duser.timezone=Asia/Shanghai", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:InitialRAMPercentage=50.0", \
    "-jar", "app.jar"]