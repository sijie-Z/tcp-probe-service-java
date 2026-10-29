# tcp-probe-service（Java 版）容器镜像：多阶段构建
# 构建：docker build -t tcp-probe-service-java:latest .
# 运行：docker run --rm -p 8000:8000 tcp-probe-service-java:latest
#
# 注意：构建阶段用 -Dmaven.test.skip=true（连测试源码都不编译），因此
# .dockerignore 中排除了 src/test —— 两处约定必须保持一致；
# 若把这里改成 -DskipTests（仍会编译测试源码），必须同时删掉 .dockerignore 里的 src/test。

# ---- 构建阶段：Maven + JDK 21，产出可执行 jar ----
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# 先只拷 pom.xml 预热依赖：pom 不变时可命中缓存层，改业务代码不会触发依赖重新下载
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# 再拷源码并打包；产物：target/tcp-probe-service-1.0.0.jar
COPY src ./src
RUN mvn -B -q package -Dmaven.test.skip=true

# ---- 运行阶段：只带 JRE 与可执行 jar，镜像更小、攻击面更少 ----
FROM eclipse-temurin:21-jre

WORKDIR /app

# 非 root 用户运行（固定 uid 10001，便于权限审计与宿主机目录映射）
RUN useradd --create-home --uid 10001 appuser

# Spring Boot repackage 后原 jar 被重命名为 *.jar.original，
# 因此 target/*.jar 只会匹配到 Spring Boot 可执行 fat jar
COPY --from=build /build/target/*.jar /app/app.jar

USER 10001

EXPOSE 8000

# JVM 堆上限取容器内存限额的 75%，避免容器内 JVM 把内存吃满被 OOM Kill
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# 存活探针：temurin 镜像含 bash 但没有 curl，用 bash 的 /dev/tcp 建立到 8000 端口的 TCP 连接探活；
# 连接失败时 bash 退出码非 0，容器被标记为 unhealthy
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8000"]

# 固定监听 8000（容器内端口）；如需换宿主机端口，改 compose 的端口映射即可，
# 若要换容器内监听端口，请同步调整 EXPOSE、HEALTHCHECK 与 compose 端口映射
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
