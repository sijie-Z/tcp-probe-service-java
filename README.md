# 简易 TCP 连接探测服务（Java 版）

HTTP 接口接收**批量 TCP 连通性探测任务**，后台使用 **Java 21 虚拟线程 + 纯 JDK 自研探测引擎**（`SocketChannel` + `Selector`，未使用任何第三方探测组件）执行探测，按**来源 IP 的令牌桶限流**，结果按提交顺序返回。

> 北京悦游信息技术有限公司 招聘作业，Java 实现。另有一份 Python 实现（`sijie-Z/tcp-probe-service`），
> **两版核心 API 契约一致**（端点、字段、错误码、状态语义、限流参数、`X-Request-ID`），
> 共用同一份端到端冒烟脚本交叉验收；边缘行为差异（尾斜杠处理、405 的 `Allow` 头集合、
> `dns_error` 细节文本等）见文末「与 Python 实现的关系」。
> 完整设计说明见 [docs/DESIGN.md](docs/DESIGN.md)，实现细节问答见 [docs/INTERVIEW_QA.md](docs/INTERVIEW_QA.md)。

## 需求对照

| 题目要求 | 实现方式 |
| --- | --- |
| 开发一个 HTTP 接口服务，对外提供接口 | Spring Boot 3.5（Java 21）：`POST /api/v1/tasks` 提交任务，`GET /api/v1/tasks/{id}` 查询结果，`DELETE` 取消；Swagger UI `/docs` |
| 接收探测任务，后台对指定目标做连通性探测 | 提交后立即返回（202），协调虚拟线程 + 每目标一个探测虚拟线程；`wait=true` 可长轮询直接拿到结果 |
| 返回探测结果 | 每个目标返回：状态、`resolved_ip`、握手耗时 `latency_ms`、失败原因 |
| 支持批量探测 | 单任务最多 200 个目标，全局同时在途连接数上限 100（均可配置） |
| 简单的限流 | 按来源 IP 的令牌桶：默认 5 rps / 突发 10，超限返回 429 + `Retry-After` |
| 不要使用第三方封装好的探测组件 | 探测引擎完全自研：非阻塞 `SocketChannel` + `Selector` 手写带超时握手，见 [probe/ProbeEngine.java](src/main/java/com/example/tcpprobe/probe/ProbeEngine.java) |
| 交付 Git 仓库 + README，拉下来能启动和验证 | 本仓库：`docker compose up` 或本地 Maven 两种方式；`scripts/smoke_test.py` 一键端到端验证 |
| 3 天内完成，优先核心功能稳定、代码整洁 | 64 项测试（含 18 项 HTTP 集成测试）、`mvn test` 全绿 |

## 快速开始

### 方式一：Docker（推荐，免装 JDK/Maven）

```bash
docker compose up -d --build
curl http://127.0.0.1:8000/healthz
```

### 方式二：本地 JDK 21（Maven Wrapper 已内置）

```bash
# Windows：mvnw.cmd -B -DskipTests package
./mvnw -B -DskipTests package
java -jar target/tcp-probe-service-1.0.0.jar
# 或者：./mvnw spring-boot:run
```

> Maven Wrapper 默认从阿里云镜像下载 Maven 发行版（国内网络更快）；如需官方源，修改
> `.mvn/wrapper/maven-wrapper.properties` 的 `distributionUrl` 即可。

启动后：

- 服务信息：<http://127.0.0.1:8000/>
- 交互式 API 文档（Swagger UI）：<http://127.0.0.1:8000/docs>（会 302 跳转到 `/swagger-ui/index.html`，浏览器无感；用 curl 验证请加 `-L`）
- 存活探针：<http://127.0.0.1:8000/healthz>（不参与限流）

## 验证

服务启动后，另开终端执行端到端冒烟脚本（纯标准库，无需额外依赖，与 Python 版共用同一份脚本）：

```bash
python scripts/smoke_test.py --base-url http://127.0.0.1:8000
```

脚本自动验证：健康检查与 `X-Request-ID` 透传 → 批量探测（open / closed / dns_error 三种状态与顺序）→ 异步提交 + 轮询 → 限流（429 与 `Retry-After`）。全部通过退出码为 0。

> Docker 方式启动时注意：容器内的 `127.0.0.1` 是容器自身，而冒烟脚本的 open/closed 探测目标建在宿主机上。
> 这时需要把目标主机指向宿主机（compose 已配置 `extra_hosts`）：
>
> ```bash
> python scripts/smoke_test.py --target-host host.docker.internal
> # 或：make smoke SMOKE_ARGS="--target-host host.docker.internal"
> ```

手工验证示例：

```bash
curl -X POST 'http://127.0.0.1:8000/api/v1/tasks?wait=true&wait_timeout_ms=8000' \
  -H 'Content-Type: application/json' \
  -d '{
        "name": "demo-batch",
        "targets": [
          {"host": "example.com", "port": 443},
          {"host": "127.0.0.1", "port": 22},
          {"host": "no-such-host.invalid", "port": 80}
        ]
      }'
```

响应示例（`wait=true` 且任务在等待窗口内完成，返回 200）：

```json
{
  "id": "5f0c9d3e8a7b4c1d9e2f6a0b3c4d5e6f",
  "name": "demo-batch",
  "status": "succeeded",
  "total": 3,
  "completed": 3,
  "results": [
    {"host": "example.com", "port": 443, "status": "open",
     "resolved_ip": "93.184.216.34", "latency_ms": 23.4, "error": null},
    {"host": "127.0.0.1", "port": 22, "status": "closed",
     "resolved_ip": "127.0.0.1", "latency_ms": null, "error": "connection_refused: 127.0.0.1:22"},
    {"host": "no-such-host.invalid", "port": 80, "status": "dns_error",
     "resolved_ip": null, "latency_ms": null, "error": "dns_error: no-such-host.invalid"}
  ],
  "created_at": "2026-10-29T09:00:00.000000Z",
  "started_at": "2026-10-29T09:00:00.001000Z",
  "finished_at": "2026-10-29T09:00:00.180000Z",
  "error": null
}
```

限流效果（默认突发 10、每秒补充 5）：

```bash
for i in $(seq 1 30); do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST 'http://127.0.0.1:8000/api/v1/tasks' \
    -H 'Content-Type: application/json' -d '{"targets":[{"host":"127.0.0.1","port":22}]}'
done
# 先出现若干 202（突发额度），随后出现 429 与 Retry-After
```

> 注意：每次 `curl` 进程启动约百毫秒，期间令牌桶按 5 rps 持续补充，所以实际要多发一些才会出现 429；
> 想要确定性地验证限流，直接跑 `scripts/smoke_test.py` 的限流步骤（同进程快速连发，突发耗尽即 429）。

## API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/tasks` | 创建探测任务（批量）。默认 202 立即返回；`wait=true&wait_timeout_ms=10000` 时最多等待指定毫秒，任务结束则返回 200 与完整结果 |
| GET | `/api/v1/tasks?limit=20` | 最近任务摘要列表（不含结果明细），按创建时间倒序 |
| GET | `/api/v1/tasks/{task_id}` | 任务详情与结果；结果与提交顺序一一对应，未完成的槽位为 `null` |
| DELETE | `/api/v1/tasks/{task_id}` | 取消未结束的任务（未完成目标以 `cancelled` 补齐，已出结果保留）；**取消幂等**（已取消再取消返回 200），已成功/失败结束返回 409 |
| GET | `/healthz` | 存活探针，不参与限流 |

**请求体**（`POST /api/v1/tasks`）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string(≤100) | 否 | 任务名 |
| `targets` | array(1..200) | 是 | 探测目标列表 |
| `targets[].host` | string(≤253) | 是 | 主机名或 IP 字面量（支持 `[::1]` 形式 IPv6；允许下划线服务名） |
| `targets[].port` | int(1..65535) | 是 | TCP 端口（严格整数，布尔/字符串会被拒绝） |
| `targets[].timeout_ms` | int(1..10000) | 否 | 单目标超时，缺省 3000；不得超过服务端上限 |

**统一错误信封**（所有 4xx/5xx；`Accept` 不兼容 JSON 的 406 由容器按内容协商返回，无信封）：

```json
{
  "error": {
    "code": "RATE_LIMITED",
    "message": "请求过于频繁，请在 1 秒后重试",
    "request_id": "b1e2c3d4...",
    "details": null
  }
}
```

常见错误码：`VALIDATION_FAILED`(422)、`TARGETS_TOO_MANY`(422)、`TIMEOUT_TOO_LARGE`(422)、`TASK_NOT_FOUND`(404)、`TASK_ALREADY_FINISHED`(409)、`TASK_CAPACITY_EXCEEDED`(503)、`RATE_LIMITED`(429)、`INTERNAL_ERROR`(500)。
参数校验失败时 `details` 为字段级错误列表（如 `{"field": "targets.0.host", "message": "..."}`），字段路径与 Python 版一致。

每个响应都带 `X-Request-ID`（调用方传入合法值则透传，否则服务端生成），与响应体 `error.request_id`、ECS 结构化日志中的 `requestId` 一一对应，便于排障。

## 探测结果状态语义

| 状态 | 含义 | 判断依据 |
| --- | --- | --- |
| `open` | 端口可连接 | TCP 三次握手成功；`latency_ms` 为握手耗时（不含 DNS） |
| `closed` | 主机可达、端口未监听 | 对端返回 RST（`ConnectException`）——这是**确定性**结论 |
| `timeout` | 无法判断 | 超时预算内没有任何地址给出明确答复（SYN 无响应） |
| `dns_error` | 域名解析失败 | NXDOMAIN 或 DNS 临时故障 |
| `unreachable` | 网络层不可达 | `NoRouteToHostException`、连接被重置等网络层明确否定 |
| `invalid` | 入参非法 | 主机名/端口不合法，未发起任何连接（防御性兜底，正常请求在 422 处即被拦截） |
| `error` | 服务内部错误 | 探测中出现未预期异常 |
| `cancelled` | 任务被取消 | 取消后未完成的目标 |

> 关键区分：`closed` 说明包到了主机且被明确拒绝，而 `timeout` 什么也证明不了（可能被防火墙静默丢包）。`unreachable` 是网络层的明确否定，比 `timeout` 更强。多地址主机上 `open` 优先于 `closed`（某个地址被拒不代表其他地址不可达）。

## 限流与背压

- **限流**：按来源 IP 的令牌桶，默认稳态 5 rps、突发 10（`RATE_LIMIT_RPS` / `RATE_LIMIT_BURST`）。只作用于 `/api/*`；超限返回 429 + `Retry-After`。空闲桶在 `RATE_LIMIT_IDLE_TTL_SECONDS`（默认 600s）后惰性回收，桶数另有 10 万硬上限，超出时按遍历顺序近似随机淘汰（O(n) 截断、无排序，保证内存有界）。开启限流时 rps 下限为 1/3600，保证 `Retry-After` 反映真实等待时间。
- **客户端 IP 取 TCP 对端地址**（`getRemoteAddr`），不信任 `X-Forwarded-For`。部署在反向代理之后时，限流会退化为按代理 IP 计算，应改由代理层做真实来源限流。
- **背压**：活动任务数达到 `TASK_MAX_ACTIVE`（默认 100）时直接 503 + `Retry-After`，而不是无限排队；单任务目标数受 `TASK_MAX_TARGETS`（默认 200）限制。
- **并发上限**：所有任务共享 `PROBE_MAX_CONCURRENCY`（默认 100）个在途连接许可（`Semaphore`），防止文件描述符耗尽。

## 配置项

全部通过环境变量覆盖（application.yml 中的 `${VAR:default}` 占位符），默认值开箱即用；完整清单见 [.env.example](.env.example)。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_PORT` | `8000` | 监听端口（服务固定绑定 0.0.0.0） |
| `LOG_LEVEL` | `INFO` | 日志级别 |
| `PROBE_DEFAULT_TIMEOUT_MS` | `3000` | 默认单目标超时 |
| `PROBE_MAX_TIMEOUT_MS` | `10000` | 请求可指定的超时上限 |
| `PROBE_MAX_CONCURRENCY` | `100` | 全局同时在途连接数 |
| `TASK_MAX_TARGETS` | `200` | 单任务目标数上限 |
| `TASK_MAX_ACTIVE` | `100` | 活动任务数上限（背压） |
| `TASK_TTL_SECONDS` | `3600` | 终态任务内存保留时长 |
| `TASK_MAX_RETAINED` | `1000` | 内存中最多任务条数 |
| `RATE_LIMIT_ENABLED` | `true` | 是否开启限流 |
| `RATE_LIMIT_RPS` | `5` | 每客户端每秒令牌数 |
| `RATE_LIMIT_BURST` | `10` | 每客户端桶容量（突发上限） |
| `RATE_LIMIT_IDLE_TTL_SECONDS` | `600` | 空闲令牌桶回收时间 |

非法配置（如 `RATE_LIMIT_RPS=abc`、`nan`、`PROBE_DEFAULT_TIMEOUT_MS > PROBE_MAX_TIMEOUT_MS`）在启动时立即报错退出。

## 设计要点

- **虚拟线程并发**：每个任务一个协调虚拟线程、每个目标一个探测虚拟线程；探测是纯 IO 等待，虚拟线程让「一目标一线程」的直观模型不再有平台线程池成本。
- **纯 JDK 探测**：非阻塞 `SocketChannel` + `Selector` 手写带超时握手；`timeout_ms` 是单个目标的总预算（DNS + 最多 8 个地址顺序尝试共享 deadline）；状态判定优先级 `open > closed > timeout > unreachable`。
- **取消链**：`DELETE` 中断协调线程与在途探测线程，`ClosedByInterruptException` 统一转 `CancellationException`；未完成槽位由收尾统一补 `cancelled`，已出结果保留。
- **内存治理**：任务 TTL 惰性清理 + 条数上限淘汰最旧终态任务 + 活动任务背压 + 信号量限制在途连接，四层限制保证资源有界。
- **可观测性**：Spring Boot 内置 ECS 结构化日志（含 `requestId`）、`X-Request-ID` 响应头、`/healthz`、Swagger UI。

完整设计取舍与边界条件见 [docs/DESIGN.md](docs/DESIGN.md)；面试问答见 [docs/INTERVIEW_QA.md](docs/INTERVIEW_QA.md)。

## 测试

```bash
./mvnw test     # Windows: mvnw.cmd test
```

**64 项测试**，覆盖：探测状态分类（真实本地监听器：open/closed/dns_error/invalid/timeout/IPv6/多地址 closed→open，另有注入缝的确定性超时）、令牌桶数学与内存治理（可注入时钟、并发放行计数）、任务编排（顺序、取消幂等、背压、关停）、任务状态（取消占位与迟到结果的并发一致性、终态后拒绝 markRunning、幂等收敛）、主机名校验（IPv4 简写拒绝）、HTTP 层（状态码、错误信封、`X-Request-ID` 单头断言、参数类型错误与 null 元素 422、非 JSON Content-Type 415、429+`Retry-After` 实测数值、并发准入仅 1 个成功、503 背压、异步轮询、500 信封）。测试不依赖外网。

> 环境说明：`closed` 依赖系统对未监听端口回 RST。若机器上防火墙 stealth 模式或代理软件
> **延迟**送达 RST（实测某安全软件延迟约 2 秒），测试与冒烟脚本为 closed 目标预留了大于
> 延迟窗口的预算（集成测试默认 3000ms、冒烟脚本 5000ms），断言仍为严格的 `closed`；
> 若环境彻底静默丢包，返回 `timeout` 同样是诚实结果。

## 目录结构

```
tcp-probe-service-java/
├── src/main/java/com/example/tcpprobe/
│   ├── TcpProbeServiceApplication.java   # 入口
│   ├── config/        # ProbeProperties（校验）、Bean 装配、OpenAPI 元信息
│   ├── domain/        # TaskState（可变、线程安全的任务状态）
│   ├── model/         # 请求/响应 record、状态枚举、错误信封
│   ├── probe/         # ProbeEngine：纯 JDK 自研 TCP 探测
│   ├── ratelimit/     # TokenBucket / ClientRateLimiter（自研）
│   ├── service/       # TaskService：编排、取消、背压；ProbeFn 抽象
│   ├── store/         # TaskStore：TTL + 容量治理
│   ├── support/       # HostRules、@ValidHost、@StrictInt
│   └── web/           # 控制器、过滤器（request id / 限流）、全局异常处理
├── src/test/java/     # 64 项 JUnit 5 测试
├── scripts/smoke_test.py   # 端到端冒烟脚本（与 Python 版共用）
├── docs/              # DESIGN.md / INTERVIEW_QA.md
├── Dockerfile / docker-compose.yml / .dockerignore
├── Makefile / .env.example
└── pom.xml / mvnw / mvnw.cmd
```

## 已知限制与后续改进

- **单实例、内存态**：任务不持久化，进程重启丢失；多实例部署需把 `TaskStore` 换成 Redis/数据库（接口已隔离，替换成本低）。
- **无鉴权**：探测服务天然可被滥用（SSRF / 端口扫描），当前按作业范围未做鉴权与目标白名单；生产化应加 API Key、目标允许清单与审计。
- **DNS 解析超时**：JDK 的 `InetAddress` 同步解析无法强制中止，解析线程在预算超时后可能短暂滞留（已知限制，受虚拟线程成本约束），文档已说明取舍。
- **未实现 Happy Eyeballs（RFC 8305）**：多地址顺序尝试、共享预算；后续可做双栈并发优化。
- **可观测性**：可接入 Prometheus 指标与 OpenTelemetry 链路。
- **Docker 构建**：Dockerfile 采用多阶段构建并已通过 `docker compose config` 静态校验；本机验证环境 Docker 守护进程不可用，镜像构建未实测（本地 jar 方式已实测）。

## 与 Python 实现的关系

同一道题提供了两套独立实现（`sijie-Z/tcp-probe-service` 为 Python 版，本仓库为 Java 版）：

- **核心契约一致**：端点、请求/响应字段、错误码、状态语义、限流参数、`X-Request-ID` 行为对齐（40 项契约对比逐项核对，28 项逐字一致，其余为已修复项与下述边缘差异）；
- **共用同一份冒烟脚本**：`scripts/smoke_test.py` 在两套实现上均 6/6 通过，是契约一致性的交叉验收；
- **内部实现各自 idiom**：Python 版 asyncio 协程 + 事件循环；Java 版虚拟线程 + `SocketChannel/Selector`。两版的取舍对比见 `docs/INTERVIEW_QA.md` 最后一节。

**已知边缘差异**（契约对拨实测记录，均为实现自由度内的行为，不影响主流程）：

| 场景 | Python 版 | Java 版 |
| --- | --- | --- |
| 尾斜杠路径（如 `/api/v1/tasks/`） | 307 重定向 | 按未知路径返回 404 信封 |
| 405 响应的 `Allow` 头 | 仅列出匹配方法 | 列出该路径全部方法（更贴近 RFC） |
| `dns_error` 的 `error` 文本 | 含平台 errno 文案 | 统一为 `dns_error: <host>`（前缀一致） |
| 响应 `Content-Type` | `application/json` | `application/json;charset=UTF-8` |
| 非 JSON Content-Type 的 POST | 422 | 415（HTTP 语义更准确） |
