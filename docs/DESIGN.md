# 设计说明（DESIGN，Java 版）

本文记录「简易 TCP 连接探测服务」Java 实现的设计决策、边界条件与取舍。接口速查与启动方式见 [README](../README.md)；与 Python 版的差异见文末 §12。

## 1. 需求拆解

题目原文要点 → 设计回应：

1. **HTTP 接口服务**：Spring Boot 3.5 + Spring MVC；业务逻辑与框架解耦，`web/` 只做协议转换。
2. **接收探测任务 + 后台探测 + 返回结果**：任务模型（创建 → 轮询结果），提交立即返回 202；另提供 `wait=true` 长轮询作为便利糖。
3. **批量探测**：一个任务携带 1..N 个目标，结果与提交顺序一一对应（`null` 表示尚未完成）。
4. **简单限流**：按来源 IP 的令牌桶（自研，不引第三方限流库），429 + `Retry-After`。
5. **不用第三方探测组件**：探测引擎纯 JDK 实现（`SocketChannel` + `Selector`），见 §5。
6. **工程习惯/完整性/文档/逻辑缜密**：配置校验、ECS 结构化日志、统一错误信封、64 项测试、Docker、冒烟脚本、本文档与问答手册。

### 明确不做（作业范围内的裁剪）

- 鉴权 / 目标白名单（README 已知限制中说明生产化方向）；
- 任务持久化与多实例共享（`TaskStore` 已隔离，可替换 Redis）；
- WebSocket 推送（轮询足够，减少状态面）。

## 2. 总体架构

```
                    ┌────────────────────────────────────────────────┐
HTTP 客户端 ───────▶ │  Servlet Filter 链                              │
                    │  RequestContextFilter（X-Request-ID / 访问日志） │
- **指标**：`GET /metrics` 输出 Prometheus 文本格式计数器（任务提交/拒绝、任务终态、目标结果分布、限流/体积拦截），重启归零，手写零依赖。
                    │  RateLimitFilter（按 IP 令牌桶）                 │
                    └───────────────┬────────────────────────────────┘
                                    ▼
                    ┌──────────────────────────────────────┐
                    │  Spring MVC（web/ 包）                │
                    │  TaskController / InfoController       │
                    │  GlobalExceptionHandler（统一错误信封） │
                    └───────────────┬──────────────────────┘
                                    ▼
                    ┌──────────────────────────────────────┐
                    │  TaskService（service/ 包）            │
                    │  提交/等待/取消/背压/关停              │
                    └──────┬───────────────────┬───────────┘
          每任务一个协调虚拟线程        全局 Semaphore（在途连接上限）
                           ▼                   ▼
                    ┌──────────────────────────────────────┐
                    │  ProbeEngine（probe/ 包）             │
                    │  SocketChannel + Selector（纯 JDK）    │
                    └──────────────────────────────────────┘
                           │
                    TaskStore（store/ 包）—— ConcurrentHashMap（TTL + 容量治理）
```

## 3. 任务与目标的状态机

任务（TaskStatus）：

```
PENDING ──▶ RUNNING ──▶ SUCCEEDED
   │            │
   │            ├────────▶ FAILED      （编排层未预期异常，防御性分支）
   │            └────────▶ CANCELLED   （DELETE 或服务关停）
   └────────────▶ CANCELLED            （协调线程获得调度前即被取消，兜底收敛）
```

- `SUCCEEDED` 表示**所有目标都产出了结果**（单个目标失败不算任务失败——`timeout` 也是有效结果）。
- 终态任务保留 `TASK_TTL_SECONDS`（默认 1h），到期后已读/写入时惰性清理。

目标（TargetStatus）判定矩阵（按优先级从高到低）：

| 优先级 | 状态 | 触发条件 |
| --- | --- | --- |
| 1 | `open` | 任一地址连接成功（握手完成） |
| 2 | `closed` | 任一地址返回 RST（`ConnectException`）且全部地址尝试完仍无 open：主机可达、端口明确拒绝 |
| 3 | `dns_error` | 解析失败，直接返回（不进入地址尝试） |
| 4 | `timeout` | 有地址尝试超时且无任何确定性结论 |
| 5 | `unreachable` | 全部尝试均为网络层不可达（`NoRouteToHostException`、重置等） |
| — | `invalid` | 入参非法，不发网络 I/O（API 层 422 之外的第二道防线） |
| — | `error` | 未预期异常兜底 |
| — | `cancelled` | 任务取消后补齐的未完成目标 |

**为什么 `closed` 优先于 `timeout`、又低于 `open`**：RST 是确定性证据（包到了主机、端口没监听）；同一域名解析出多个地址时，只要任一地址明确拒绝，就说明"该端口在某条可达路径上未监听"，比其余地址的静默超时更可信。但 closed 不能覆盖"其他地址可能 open"，所以记 best-so-far 继续尝试到预算用完。

### 预算模型

`timeout_ms` 是**单个目标的全部时间预算**：DNS 解析 + 全部地址尝试共享同一个 deadline（`System.nanoTime()`）。每次尝试的 `Selector.select` 超时 = 剩余预算；预算耗尽即收敛为已收集的最佳结论。这样"一个目标最多花 timeout_ms"的承诺对调用方是确定性的，不会因为多 A/AAAA 记录成倍放大。

## 4. 探测引擎（probe/ProbeEngine.java）

- **纯 JDK**：`InetAddress`（虚拟线程 + `Future.get(剩余预算)` 限时）、非阻塞 `SocketChannel` + `Selector` 手写带超时握手。没有 ping/nmap 等第三方探测组件。
- **IPv4/IPv6**：`InetAddress.getAllByName` 默认双栈；IP 字面量（含 `[::1]` 写法）跳过 DNS 解析。
- **地址尝试**：最多取前 8 条解析结果顺序尝试，共享剩余预算。
- **延迟度量**：`latency_ms` 只统计成功连接那一次握手的 `nanoTime` 差值（不含 DNS、不含此前失败地址的耗时），毫秒保留 3 位小数。
- **取消语义**：探测期间线程被中断 → `ClosedByInterruptException` → 统一转 `CancellationException("probe cancelled")` 抛出，不会被误分类为 timeout/unreachable/error。
- **可测试性**：`probeWith(host, port, timeoutMs, resolver, connector)` 是包内可见的可注入入口，测试注入脚本化解析器/连接器即可确定性构造多地址、超时、取消场景；`classify(Throwable)` 是纯函数。

**DNS 超时的取舍**：JDK `InetAddress` 的解析 API 是同步且不可中断的；把解析放进独立虚拟线程并用 `Future.get(剩余预算)` 限时，探测本身能按时返回，但解析线程在预算超时后可能短暂存活到操作系统级解析结束。这是 JDK 的固有限制，记录为已知限制。

## 5. 并发模型

- **虚拟线程（Java 21）**：每个任务一个协调虚拟线程（`Executors.newThreadPerTaskExecutor`），每个目标一个探测虚拟线程。探测是纯 IO 等待，虚拟线程让"一目标一线程"的直观模型不再有平台线程池的容量约束。
- **全局 `Semaphore(PROBE_MAX_CONCURRENCY)`**：真正的并发上限是"在途 TCP 连接数"，批量任务再多也不会打爆 fd。
- **背压**：`TASK_MAX_ACTIVE` 限制未终态任务数，超限 503；`TASK_MAX_TARGETS` 限制单任务规模。
- **TaskState 线程安全**：结果槽位用 `AtomicReferenceArray`（每个探测线程只写自己的下标）；完成计数用 `AtomicInteger`；终态收敛 `finish()` 用 `synchronized` + 终态判断保证只生效一次；`fillMissing()` 用 CAS 补齐未完成槽位，不会覆盖已出结果。
- **取消链**：`DELETE` → `cancel()` 对协调 `Future.cancel(true)` 并逐个取消探测 `Future`（中断在 `Selector.select` 上即关闭 channel 并抛 `ClosedByInterruptException`）→ 槽位为 null 的目标补 `cancelled` → 任务终态 `CANCELLED`。若协调线程还没被调度（PENDING），直接兜底收敛，不会悬挂。
- **关停**：`@PreDestroy` 取消所有在途任务并 `shutdownNow` 两个 executor，等待清理完成（配合 `server.shutdown=graceful`）。

## 6. 限流

**算法：令牌桶（连续补充）**

```
tokens(t) = min(capacity, tokens(t0) + (t - t0) * rps)
```

- 桶容量 = `RATE_LIMIT_BURST`（允许的瞬时突发），补充速率 = `RATE_LIMIT_RPS`。
- 拒绝时 `Retry-After = ceil((1 - tokens) / rps)`（至少 1 秒，上限 3600 秒防极端配置溢出）。
- **为什么是令牌桶而不是固定窗口**：固定窗口有窗口边界双倍突发问题；令牌桶天然支持突发 + 平滑速率，实现也就几十行。
- **限流键 = TCP 对端 IP**（`getRemoteAddr`）。**不信任 `X-Forwarded-For`**：该头可被客户端伪造；部署在代理后时应由代理层限流。
- **内存治理**：每个 IP 一个桶；桶空闲超过 `RATE_LIMIT_IDLE_TTL_SECONDS` 惰性清扫（每 1024 次检查或桶数超 4096 触发，且两次清扫至少间隔 1 秒，避免桶数超阈值后每个请求都做全表扫描）；桶数另有 10 万硬上限，超出时按遍历顺序近似随机淘汰（O(n) 截断、无排序；攻击场景下只要求内存有界，不追求精确 LRU）。
- **并发**：`ClientRateLimiter.check` 用 `synchronized` 保护单临界区——常规路径为 O(1) 内存运算；清扫是 O(n) 遍历（无排序），受 1 秒冷却与 10 万硬上限约束，与网络探测的耗时相比可忽略；这个取舍写在类注释里。
- 只作用于 `/api/*`：`/healthz` 与 `/` 不受限。

## 7. 存储与内存治理

单实例内存存储（`store/TaskStore.java`，`ConcurrentHashMap`），三条规则让内存有界：

1. **TTL 惰性清理**：终态任务超过 `TASK_TTL_SECONDS` 在 `add/find/recent` 时清理；
2. **条数上限**：超过 `TASK_MAX_RETAINED` 时按 `finishedAt` 淘汰最旧的终态任务；**运行中的任务永不淘汰**；
3. **背压**：活动任务数达到 `TASK_MAX_ACTIVE` 后直接拒绝新任务（503）。

结果存储按提交顺序用固定长度数组占位：`results[i] == null` 表示未完成，探测线程写自己的槽位，不需要排序或加锁。

## 8. HTTP 层与错误模型

- **过滤器顺序**：`RequestContextFilter`（最高优先级，生成/透传 `X-Request-ID`，写响应头 + MDC + 访问日志）→ `RateLimitFilter`（只作用于 `/api/*`）。
- **统一错误信封**：`GlobalExceptionHandler`（`@RestControllerAdvice`）把业务异常、参数校验失败、404/405/415、未处理异常统一转换为 `{"error": {code, message, request_id, details}}`。
- **参数校验栈**：Jakarta Bean Validation（`@Valid`）+ 自定义 `@ValidHost`（与探测引擎共用 `HostRules` 的规则）+ 自定义 `@StrictInt` 反序列化（拒绝 `true` 被当成 1 的 Jackson lax 行为）。
- **可观测性**：Spring Boot 内置 ECS 结构化日志（含 MDC `requestId`）、`X-Request-ID` 贯穿响应头/响应体/日志、`/healthz`、Swagger UI（`/docs`）。

## 9. 配置

`application.yml` 中所有配置项用 `${ENV_VAR:default}` 占位符引用环境变量（与 Python 版同名同义），`ProbeProperties` 是 record + Bean Validation，跨字段约束（默认超时 ≤ 上限、限流开启时 rps ≥ 1e-6、nan/inf 拒绝）在启动时校验，非法配置立即失败退出。

## 10. 测试策略

- **真实本地监听器**：open 用 `ServerSocket`（不 `accept`，内核完成握手即可）；closed 用"绑定后立即关闭"制造 RST；dns_error 用 RFC 2606 的 `.invalid` 保留 TLD（保证 NXDOMAIN 且不外呼）。
- **确定性超时/取消**：注入 `Connector` 抛 `SocketTimeoutException` / 阻塞在可中断的 `CountDownLatch` 上，不依赖网络黑洞。
- **可注入时钟**：令牌桶与存储测试注入假时钟（`DoubleSupplier`），避免 `sleep`。
- **打桩编排层**：`TaskServiceTest` 用假 `ProbeFn` 验证顺序、取消幂等、背压、关停，不触发真实网络。
- **HTTP 集成测试**：`@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate`，覆盖状态码、错误信封、`X-Request-ID`、429、503、异步轮询与 500 信封。

> 环境差异提示：`closed` 分支依赖系统对未监听端口回 RST。个别环境（防火墙 stealth 模式、代理软件）会**延迟**送达 RST（实测某安全软件延迟约 2 秒），因此集成测试中 closed 用例显式用 5000ms 预算（其余默认 3000ms）、冒烟脚本 5000ms（均大于延迟窗口），断言仍为严格的 `closed`；若环境彻底静默丢包，返回的 `timeout` 同样是诚实结果。
- **CI**：GitHub Actions 每次 push/PR 自动执行 lint + 全部测试 + 启动服务跑端到端冒烟（`.github/workflows/ci.yml`）。

## 11. 安全考量

- 服务本身是"内网工具型"定位：**无鉴权**，任何人可让它探测任意目标（SSRF/端口扫描面）。作业范围内接受；生产化必须加 API Key、目标白名单、出口网络策略与审计日志。
- 限流只降低滥用速率，不解决授权问题。
- 请求体大小上限 `MAX_BODY_BYTES`（默认 1 MiB，BodySizeLimitFilter 按 Content-Length 拦截，413 统一信封）：避免超大 JSON 占用内存；无 Content-Length 的分块请求由 Tomcat 连接层兜底。
- 不信任 `X-Forwarded-For`；日志不记录请求体（当前请求体本就无凭据）。

## 12. 与 Python 版对比

同一道题的另一套实现（`sijie-Z/tcp-probe-service`）使用 Python 3.12 + FastAPI + asyncio。两版**核心契约一致**（端点、字段、错误码、状态语义、限流参数、`X-Request-ID` 行为；40 项契约对比逐项核对，差异项已完成对齐），并且**共用同一份冒烟脚本** `scripts/smoke_test.py`，在两套实现上均 6/6 通过。仍存在的边缘差异（实现自由度内，已在 README 记录）：尾斜杠 307 vs 404、405 的 `Allow` 头集合、`dns_error` 细节文本、Content-Type 的 charset、非 JSON Content-Type 时 422 vs 415。

内部差异：

| 维度 | Java 版 | Python 版 |
| --- | --- | --- |
| 并发模型 | 虚拟线程（一目标一线程） | asyncio 协程 + 单事件循环 |
| 探测 I/O | `SocketChannel` + `Selector` 手写超时 | 非阻塞 socket + `loop.sock_connect` |
| DNS 超时 | 独立虚拟线程 + `Future.get` | executor + `asyncio.wait_for` |
| HTTP 框架 | Spring Boot MVC | FastAPI |
| 错误信封 | `@RestControllerAdvice` | 全局异常处理器 + 中间件 |
| 限流实现 | `synchronized` 单临界区 | 单事件循环内无锁 |
| 校验 | Jakarta Validation + 自定义注解/反序列化 | Pydantic + 自定义校验器 |

## 13. 后续改进路线

1. **多实例**：`TaskStore` 抽象成接口，Redis（任务 hash + TTL）实现，任务调度用队列。
2. **鉴权与授权**：API Key → JWT；目标允许清单（CIDR 白名单）与每租户配额。
3. **指标**：Micrometer + Prometheus（任务状态计数、探测耗时直方图、限流命中数）；OpenTelemetry 链路。
4. **探测增强**：Happy Eyeballs（双栈并发首包）、可配置探测类型。
5. **请求体大小限制**：已实现（`MAX_BODY_BYTES`，413 统一信封）。
