# tcp-probe-service（Java 版）常用开发与运维命令（GNU make）
# 约定：Maven 统一用 `mvn -B`（批处理模式，日志不刷进度条），可用 `make MVN=/path/to/mvn build` 覆盖。
# 前置：本机需安装 JDK 21+ 与 Maven 3.9+；Docker 相关目标需 Docker Desktop / Docker Engine + Compose v2。

JAVA       ?= java
MVN        ?= mvn
JAR        ?= target/tcp-probe-service-1.0.0.jar
IMAGE      ?= tcp-probe-service-java:latest
SMOKE_ARGS ?=
PYTHON     ?= python

.PHONY: build test run docker-build docker-up docker-down smoke load

# 快速打包（跳过测试执行，产物 target/tcp-probe-service-1.0.0.jar）
build:
	$(MVN) -B -DskipTests package

# 运行全部测试
test:
	$(MVN) -B test

# 本地前台启动服务（先打包再 java -jar；Ctrl+C 退出；默认端口 8000，可用 APP_PORT 覆盖，如 APP_PORT=9000 make run）
run: build
	$(JAVA) -jar $(JAR)

# 构建 Docker 镜像（镜像名与 docker-compose.yml 中的 image 保持一致）
docker-build:
	docker build -t $(IMAGE) .

# 后台启动容器（必要时自动重新构建）
docker-up:
	docker compose up -d --build

# 停止并移除容器
docker-down:
	docker compose down

# 端到端冒烟测试（要求服务已在运行；本地或容器方式均可）
# 服务在容器里时，容器内的 127.0.0.1 不是宿主机，需要：
#   make smoke SMOKE_ARGS="--target-host host.docker.internal"
smoke:
	$(PYTHON) scripts/smoke_test.py $(SMOKE_ARGS)

# 轻量压测（建议先以 RATE_LIMIT_ENABLED=false 启动服务）
load:
	$(PYTHON) scripts/load_test.py
