# 实现细节问答（面试准备，Java 版）

按面试官最可能的提问路径整理，答案都指向代码里的真实实现。

## 一、需求理解

**Q1：为什么做成异步任务 + 轮询，而不是同步接口？**
探测耗时由目标决定（不可达目标要等满超时，批量时更久），同步接口会把 HTTP 连接拖到几十秒。异步任务模型：提交立即返回 202 + 任务 id，后台虚拟线程执行，客户端轮询；另给 `wait=true` 长轮询糖（内部 `CompletableFuture.get(timeout)`），手工验证体验最好。

**Q2：批量里有一个目标失败，任务算什么状态？**
任务仍然是 `succeeded`。`timeout`、`closed`、`dns_error` 都是**有效探测结果**，不是任务失败。任务级 `failed` 只用于编排层未预期异常（防御性分支）。

**Q3：为什么要把 `closed` 和 `timeout` 分开？**
`closed` 是对端回了 RST——主机可达、端口明确拒绝；`timeout` 是预算内什么都没收到，什么也证明不了（可能被防火墙静默丢包）。处置动作完全不同。多地址时 `closed` 记为 best-so-far 继续尝试，`open` 优先级更高（某个地址被拒不代表其他地址不可达）。

**Q4：`invalid` 在 API 层已经 422 拦掉了，探测引擎里为什么还判一次？**
`ProbeEngine.probe` 是独立入口，未来可被其他调用方复用，不能假设调用方已经校验过。第二道防线放在引擎入口，成本是几行代码。

## 二、Java / Spring 设计

**Q5：为什么用 Java 21 虚拟线程？和平台线程池比有什么好处？**
探测是纯 IO 等待（等 SYN-ACK/RST/超时），"一目标一线程"是最直观的模型。虚拟线程的创建与阻塞成本极低，不需要为"池多大"做艺术；同时保留同步代码的直观性（不用回调/CompletableFuture 链）。真正的资源上限由全局 `Semaphore` 控制在"在途 TCP 连接数"，而不是线程数。

**Q6：为什么不用 `Socket.connect(addr, timeout)` 而要手写 `SocketChannel + Selector`？**
`Socket` 的 connect 带超时但**不可中断**：取消任务时无法打断阻塞中的 connect，只能等它超时。`SocketChannel` 是 `InterruptibleChannel`——线程中断会关闭通道并抛 `ClosedByInterruptException`，正好用于取消；配合 `Selector.select(剩余毫秒)` 同时拿到"超时"和"可中断"两个能力。

**Q7：`timeout_ms` 预算怎么分配？**
单个目标的**总预算**：DNS 解析 + 最多 8 个候选地址的每次 connect 共享同一个 deadline（`System.nanoTime()`）。每次尝试的超时 = 剩余预算。这样"一个目标最多花 timeout_ms"对调用方是确定性的；如果每个地址各自给满超时，一个域名 4 条记录就会最坏花 4 倍时间。

**Q8：DNS 解析的超时怎么做？有什么坑？**
`InetAddress.getAllByName` 是同步且不可中断的。做法是把它丢到独立虚拟线程，用 `Future.get(剩余预算)` 限时等待；超时后探测按时返回 timeout。坑：`Future.cancel(true)` 只能中断线程，若底层解析器不响应中断，线程会存活到系统级解析结束——这是 JDK API 的固有限制，文档已写明，且受虚拟线程成本约束可控。

**Q9：`TaskState` 被多个线程写，怎么保证线程安全？**
每个探测线程只写自己的结果槽位（`AtomicReferenceArray` 固定下标），完成计数用 `AtomicInteger`；终态收敛 `finish()` 用 `synchronized` + 终态判断保证幂等；`fillMissing()` 用 CAS 补齐 null 槽位，不会覆盖已出结果。没有全局锁，只有每任务内部的细粒度同步。

**Q10：取消是怎么实现的？**
`DELETE` → `cancel()`：终态幂等判断 → `requestCancel` → 逐个 `Future.cancel(true)` 中断探测线程（`select` 抛 `ClosedByInterruptException` → 引擎转 `CancellationException`，槽位留空）→ 协调线程取消 → 未完成槽位补 `cancelled`，已出结果保留。若协调线程尚未调度（PENDING）直接兜底收敛，不会悬挂。重复取消返回 200（幂等），已成功/失败结束返回 409。

**Q11：进程关停时在途任务怎么办？**
`@PreDestroy` 遍历在途任务执行同样的取消流程，然后 `shutdownNow` 两个虚拟线程 executor 并等待清理（配合 Spring Boot `server.shutdown=graceful`）。

**Q12：为什么 `TaskStore` 用 `ConcurrentHashMap` 而不用 `HashMap` + 锁？**
读写来自 Tomcat 的多个请求线程（以及可能的探测线程回调），`ConcurrentHashMap` 的并发读写语义足够；常规读写是短小的 put/remove/get，唯一需要原子性的「活动计数检查 + 注册」复合操作已下沉到 `TaskStore.tryAddActive()`（synchronized），并发提交不会突破 max-active 背压。

**Q13：`application.yml` 里为什么用 `${ENV:default}` 占位符而不是 `@ConfigurationProperties` 直接绑定环境变量？**
Spring 的 relaxed binding 会把 `PROBE_DEFAULT_TIMEOUT_MS` 解释成 `probe.default.timeout.ms`（下划线当层级分隔），和 `probe.default-timeout-ms` 对不上；显式占位符让环境变量名与 Python 版保持一致、可读可审计。`ProbeProperties` 仍然是强类型 record + Bean Validation。

**Q14：为什么自研限流，不用 Bucket4j / Resilience4j？**
题目要求"简单的限流"，令牌桶 ~60 行可读实现，无额外依赖、行为完全可控；第三方库的配置面反而增加答辩成本。且限流键需要按来源 IP（未来可换 API Key），自研替换最直接。

**Q15：限流桶的并发与内存治理？**
`ClientRateLimiter.check` 一个 `synchronized` 临界区（常规路径 O(1)；清扫为 O(n) 遍历、无排序，受 1 秒冷却与硬上限约束，注释说明了取舍）。内存治理：每 key 一个桶 + `lastSeen`，每 1024 次检查或桶数超 4096 触发惰性清扫（两次清扫至少间隔 1 秒，避免桶数超阈值后每个请求都做全表扫描），桶数还有 10 万硬上限，超出按遍历顺序近似随机淘汰（保证内存有界）。

**Q16：`Retry-After` 怎么算？**
`ceil((1 - tokens) / rps)`，至少 1 秒。开启限流时 rps 下限为 1/3600，保证等待时间不超过 3600 秒；响应头仍以 3600 秒封顶作为防御性边界。响应头整数秒。

**Q17：为什么参数校验要写 `@StrictInt`？**
Jackson 默认 lax 模式会把 JSON `true` 反序列化成 1、把数字字符串当整数，与 API 文档的 int 语义不符。`@StrictInt` 是自定义反序列化器，只接受 `VALUE_NUMBER_INT`，在请求边界直接 422，和 Python 版的 StrictInt 行为一致。

**Q18：`@ValidHost` 为什么要和探测引擎共用校验规则？**
`HostRules` 是单一事实来源：Bean Validation 校验请求参数，引擎入口做第二道防线（可能被绕过 HTTP 直接调用）。规则里 IPv4 手工解析（避免 `InetAddress.getByName("999.1.1.1")` 触发 DNS 查询）、IPv6 依赖 `InetAddress`（含冒号不会走 DNS）、主机名允许下划线（Docker 服务名）。

**Q19：统一错误信封是怎么实现的？**
`@RestControllerAdvice` 的 `GlobalExceptionHandler` 把业务异常、`MethodArgumentNotValidException`、`ConstraintViolationException`、`HttpMessageNotReadableException`、404/405/415 和兜底 `Exception` 全部映射成 `{"error": {code, message, request_id, details}}`；`RateLimitFilter` 在过滤器层直接写同样的 JSON（过滤器不经过 advice）。`request_id` 从 `RequestContextFilter` 写入的 request attribute 取——这保证 500 路径也能拿到 id（过滤器比异常处理更外层，attribute 还在）。

**Q20：`X-Request-ID` 怎么贯穿？**
`RequestContextFilter`（最高优先级）优先透传调用方的合法值（≤64 字符、限定字符集），否则生成 32 位 hex；写入 request attribute、MDC、响应头；ECS 日志格式会把 MDC 的 `requestId` 作为字段输出。排障时一条 id 串起访问日志、异常日志和客户端看到的响应。

## 三、测试

**Q21：怎么测试超时分支？为什么不真的连黑洞 IP？**
`ProbeEngineTest` 通过包内可见的 `probeWith(...)` 注入 `Connector`，直接抛 `SocketTimeoutException`，确定性、零网络依赖。真实黑洞地址在不同网络环境下可能快速 `NoRouteToHost`，不稳定。

**Q22：怎么让本地端口表现为 open / closed？**
open：`ServerSocket` 监听但**不 accept**——内核在 backlog 未满时会完成三次握手，客户端 connect 成功；closed：绑定端口拿到随机端口号后立即关闭，后续 connect 得到 RST。注意本机安全软件可能把 RST 延迟约 2 秒才送达，所以 closed 用例用 5000ms 预算断言严格 `closed`。

**Q23：为什么要有 HTTP 集成测试，单测不够吗？**
状态码、错误信封、过滤器顺序、`X-Request-ID`、429/503 这些都是"组装后"才成立的行为；`@SpringBootTest(RANDOM_PORT)` 用真实 HTTP 栈验证，才能防住"Bean 装配/顺序"类问题（例如本次开发中就抓到了与 Spring Boot 自动配置的 bean 名冲突）。

**Q24：PENDING 状态下取消的兜底路径怎么保证？**
`cancel()` 在 `Future.cancel(true)` 后检查任务状态：如果还是 PENDING（协调线程从未调度），直接 `fillMissing + finish(CANCELLED)`；否则最多等 2 秒让协调线程收尾，超时也兜底收敛。`TaskState.finish()` 幂等，先到者生效。

## 四、扩展与取舍

**Q25：多实例部署怎么改？**
`TaskStore` 已经是独立类，换成 Redis（任务 hash + TTL）即可；任务调度从进程内虚拟线程改成队列（Redis Streams / Kafka），探测 worker 多实例消费。`TaskService` 的编排逻辑不动。

**Q26：无鉴权 + 可探测任意目标是安全问题吗？**
是（SSRF / 端口扫描面），README 已知限制写明：作业范围内按"内网工具"定位接受；生产化要加 API Key、目标允许清单（CIDR 白名单）、出口网络策略与审计日志。限流只降速，不做授权。

**Q27：如果让你继续做一周，改什么？**
① 鉴权 + 目标白名单；② Redis 存储 + 队列多实例；③ Micrometer/Prometheus 指标（任务计数、探测耗时直方图、限流命中）；④ Happy Eyeballs 双栈并发首包；⑤ 请求体大小限制与每租户配额。

## 五、两套实现对比（Python 版 + Java 版）

**Q28：为什么同一道题做了两套实现？**
题目说"无其他硬性要求，开放式"，重点是工程习惯与逻辑缜密。两套独立实现共享同一份 API 契约和同一份冒烟脚本（在两边都 6/6 通过），能直观展示"同一份设计在不同语言栈落地"的能力，也方便对照取舍。

**Q29：asyncio 和虚拟线程的关键差异？**
两者都是"大量并发 IO 等待"的解：asyncio 单事件循环 + 协程，调度在用户态、无锁；虚拟线程是 JVM 调度的轻量线程，代码保持阻塞式同步风格、调试栈更直观。Python 版把状态写入集中在单事件循环（无需锁）；Java 版用 `AtomicReferenceArray`/`synchronized finish` 处理多线程写入。取消语义两边一致：`CancelledError` ↔ `CancellationException`，未完成槽位统一补 `cancelled`。

**Q30：两版的错误分类为什么完全一致？**
语义是契约的一部分：`open/closed/timeout/dns_error/unreachable/invalid/error/cancelled` 的判定矩阵、优先级（open > closed > timeout > unreachable）和 `latency_ms` 口径（仅握手阶段）在两版中逐条对齐，冒烟脚本才能无缝复用。差异只在各自语言的错误类型映射（`ConnectException` ↔ `ConnectionRefusedError`，`ClosedByInterruptException` ↔ `CancelledError`）。
