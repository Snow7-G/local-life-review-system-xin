<!-- 本文件是 DSH（deepseek-v4.1-flash）对 Codex（gpt-5.6-sol）产物的异源只读复核报告 -->
<!-- 复核者声明：只读，未修改任何被审文件；副作用仅落在 .gitignore 忽略的 agent-core/target/ -->
<!-- 提取自 /tmp/p1-1-dsh-review.log；日志尾部 EXIT=0 -->

# P1-1 异源只读复核报告（DSH 复核 Codex 产物）

**复核对象**：`agent-core/`（14 个 main 源文件 + 3 个 test 源文件 + `pom.xml` + `SELF-REVIEW-P1-1.md`）
**对照物**：`docs/tasks/P1-1-mcp-stdio-client.md`（99 行）
**基线**：HEAD `9b2a052`，`git status --porcelain` = `?? agent-core/`（工作区无其它改动）

## 0. 只读性证明与证据来源

复核未改动任何文件，用哈希清单前后对拍证明：

| 证据 | 结果 |
|---|---|
| `find agent-core -path '*/target' -prune -o -type f -print \| sha256sum` 前/后对比 | `SOURCE_TREE_UNCHANGED=yes`（19 个文件逐字节一致，含 `pom.xml` 与 `SELF-REVIEW-P1-1.md`） |
| `git status --porcelain` | 仅 `?? agent-core/`；`git diff --stat` 空 |
| 审查副作用 | 仅落在 `.gitignore:42` 已忽略的 `agent-core/target/`（`audit-*.log`、`audit-*.sha256`） |
| `grep -rnE "Files\.\|Paths\.\|new File\(\|java\.net\|Socket\|http\|HttpClient\|URL\(\|System\.(out\|err)\.print\|printStackTrace" agent-core/src/main/java/` | 只命中 `StdioTransport.java:70` 的 `new ProcessBuilder`（见 ①），**无**文件系统/网络/标准流打印 |

**我独立重跑的验收命令（不是引用自审）**：

| 命令 | 结果 |
|---|---|
| `mvn -f agent-core/pom.xml -B -ntp test` | `McpClientTest` 9 + `JsonRpcCodecTest` 2 = **Tests run: 11, Failures: 0, Errors: 0, Skipped: 0**，`BUILD SUCCESS`，EXIT=0 |
| `MAVEN_OPTS="-Xmx640m" mvn -B -ntp verify`（卡片:75 回归） | **Tests run: 28, Failures: 0, Errors: 0, Skipped: 1**，`BUILD SUCCESS`，EXIT=0 |
| `git log --oneline -1` | `9b2a052`（未 commit，符合卡片:99「不 push、不 commit」） |

> 说明：本轮沙箱重跑**全绿**。先前一度出现的 2 个 `java.net.SocketException: Operation not permitted`（既有测试自建 loopback socket）本次未复现，属沙箱环境抖动，与 `agent-core` 无关；且 `grep -nE "<module>|agent-core" pom.xml` 为空 —— agent-core 未进根 reactor，结构上也不可能影响根回归。

---

## ① 安全

| 检查点 | 结论 | 证据 |
|---|---|---|
| 危险命令执行 | **合规** | `StdioTransport.java:70` 用 `new ProcessBuilder(config.command())` 传 **List**，不经 shell，无字符串拼接 ⇒ 无命令注入面。无 `Runtime.exec`、无 `sh -c` 硬编码（唯一 `Runtime` 命中是异常类名 `RuntimeException`） |
| 命令白名单 | **无（设计取舍）** | `config.command()` 由调用方给定（`McpClientConfig.java:31-33` 仅校验非空/非 blank）。传 `["sh","-c","..."]` 是调用方责任，配置需可信来源 —— 与卡片:41「命令与 env 由配置传入」一致，属下一卡/上层约束 |
| 凭据泄漏（日志） | **合规** | `stderrLogger` 默认空实现（`McpClientConfig.java:68`），任何路径都不打印 env/参数/凭据；main 源码零 `System.out/err`、零 `printStackTrace` |
| 凭据泄漏（环境变量继承） | **需注意（非阻塞）** | `StdioTransport.java:72` `builder.environment().putAll(config.environment())` 是**增量覆盖**，第三方 MCP 子进程继承**父进程全部环境变量**（含可能的 token/密钥）。更严做法：`environment().clear()` 后只注入配置项。当前无凭据被读取或记录，且 env 由上层配置，故列为改进建议而非缺陷 |
| 未授权文件访问 | **合规** | main 源码无 `Files/Paths/File` 引用，无路径拼接，无目录遍历 |
| 子进程管理风险 | **合规且较好** | `redirectErrorStream(false)`（`:71`）保证 stderr 独立可读（③ 洪泛要求才有意义）；回收链 `destroy()` → `waitFor(300ms)` → `destroyForcibly()` → `waitFor(700ms)`（`:294-300`），再 `close()` 三流（`:306-307`）；`InterruptedException` 分支重新置中断位并强杀（`:302-305`）；`start()/close()` 同在 `lifecycleLock`（`:61`/`:362`）串行化 ⇒ 无孤儿进程（见 ②.3 核验二） |
| 并发安全 | **合规** | `pending` 为 `ConcurrentHashMap`，所有移除走 `remove(k,v)` 身份校验（`:123-128`、`:217-219`、`:288-291`）；会话销毁走 `activeSession.compareAndSet`（`:275`）与 `close()` 的 `getAndSet(null)`（`:363`）互斥 ⇒ 至多一次 teardown |
| 死锁风险 | **未见** | `stdinLock`（写路径）与读线程无反向加锁；`shutdownSession` 的 `joinUnlessCurrent` 跳过自身（`:322-331`），join 上限 1s |

---

## ② 逻辑正确性（对照任务卡逐条）

### 2.1 实现要求 1-6（卡片:35-53）

| # | 卡片要求（行号） | 实现位置 | 判定 | 说明 |
|---|---|---|---|---|
| 1 | JSON-RPC 2.0 四类消息 + Jackson 编码 + **单行输出**（:37-38） | `JsonRpcMessage.java`(sealed)、`JsonRpcRequest/Response/Notification/Error.java`、`JsonRpcCodec.java:22-32` | ✅ | encode 后显式检查裸 `\n`/`\r`，命中即抛 `McpProtocolException("JSON-RPC encoder produced a raw line break")`（`:25-27`）—— 见 2.3 核验三 |
| 2 | 行框定；反序列化容忍 UTF-8 跨 buffer（:39） | `JsonRpcCodec.java:34-`；`StdioTransport.java:157-160` 全链 `StandardCharsets.UTF_8` + `inputReader/outputWriter(UTF_8)` | ✅ | `readLine()` + UTF-8 解码器天然处理跨读边界；实测见 2.1 测试要求 6 |
| 3a | `ProcessBuilder` 拉起子命令，命令与 env 由配置传入（:41） | `StdioTransport.java:70-74` | ✅ | |
| 3b | 独立 stdout 读线程，按 id 完成 pending future，多请求不排队（:42） | 线程 `:163-168`；`readStdout` `:175-192`；`dispatchResponse` `:210-226` | ✅ | 无全局串行队列；在途上限由 `Semaphore` 控制而非排队 |
| 3c | **独立 stderr 读线程必须一直读**（:43，手册 §4.5） | `readStderr` `:194-208`；日志 sink 被 `try/catch` 包裹（`:199-203`，注释「A broken logging sink must never stop draining the child pipe」）；`IOException` 也不终止 drain（`:205-207`） | ✅ | 这一处是卡片点名的最大坑，实现是「即使 sink 抛异常也继续排空」，正确 |
| 3d | stdin 写入加锁（:44） | `write` `:228-245`：`stdinLock.lock()` 覆盖 write+newLine+flush（`:230-243`） | ✅ | 编码在锁外（`:229`），减少临界区 |
| 3e | 每调用超时 → 从 pending 移除 + `McpTimeoutException`（:45） | `request` `:104-110`（`pending.remove` + `recordTimeout` + 抛）/ finally 兜底 `:123-128` | ✅ | 双保险移除；`acquirePermit` 在 `try` **之外**（`:90`），无 permit 过释 |
| 3f | 进程死亡/连续超时 → 退避重启 + 重新握手；重启期间新调用降级失败不排队（:46） | `failSession` `:274-283` → listener → `McpClient.onTransportTerminated:118-122` → `scheduleRestart:124-139` → `restart:141-156`；`callTool` 可用性前置检查 `McpClient.java:63-65` | ✅ | `Math.min(attempt, backoff.size()-1)`（`:129`）把延迟封顶在 30s ⇒ **不会忙重启**；重启中 `available=false` ⇒ 直接抛 `McpUnavailableException`，不排队 |
| 3g | `Semaphore` 限制在途数（可配）（:47） | `:55-57` fair semaphore；`:255-265` `tryAcquire(remaining)`，超时抛 `McpTimeoutException("Timed out waiting for MCP concurrency permit: …")` | ✅ | 公平模式避免饿死；获取失败也归类为超时语义，合理 |
| 4 | 握手 initialize → `notifications/initialized` → `tools/list`，缓存 name+inputSchema；**任一步失败=不可用**（:48-49） | `McpClient.performHandshake:82-116`（params `:83-88`；result 必须为对象 `:92-93`；initialized `:95`；tools 数组校验 `:100-101`；单工具校验 `:105-110`；重名即失败 `:111-113`） | ✅ | 失败路径 `:52-56` 置 `available=false`，`isAvailable()` `:72-74` 三条件与 |
| 5 | `McpClient implements AutoCloseable`，四个对外 API 签名（:50-52） | `callTool:59-70`、`isAvailable:72-74`、`toolSchema:76-80`、`close:174-188` | ✅ | 签名逐字一致；`toolSchema` 返回 `deepCopy()`（`:79`）+ 缓存时也 `deepCopy`（`:111`）⇒ 不泄漏内部可变状态 |
| 6 | `close()` 干净：停读线程、销毁子进程、唤醒所有 pending（:53） | `McpClient.close:175-188` + `StdioTransport.close:357-374`：CAS 幂等、`activeSession.getAndSet(null)`、`completePending`、`shutdownSession`、二次清扫 pending（`:369-373`） | ✅ | 见 2.3 核验二 |

### 2.2 测试要求 1-7（卡片:55-66，「必须真跑，不许只有 happy path」）

| # | 要求（行号） | 测试 | 判定 | 关键点 |
|---|---|---|---|---|
| 1 | 握手 → `tools/list` 工具被解析（:59） | `McpClientTest:30-38` | ✅ | `isAvailable` + `type`/`properties.value.type` + 未知工具 `isEmpty` |
| 2 | 并发 5 个 `tools/call` 各自拿到自己的结果（:60） | `McpClientTest:41-57`；服务端 `FakeMcpServer.java:84-90` | ✅ | 服务端 `CountDownLatch(5)`+`await(3s)`：**5 个必须同时在途**，否则返回 -32001 使断言失败；`delayMillis=(5-value)*40`（`:49`）让响应**逆序**返回，真正检验 id↔调用方匹配而非顺序巧合 |
| 3 | stderr 洪泛 5MB 期间 `tools/call` 仍成功（:61-62，**回归红线**） | `McpClientTest:60-71`；`FakeMcpServer.java:96-99,119-130` | ✅ | 见 2.3 核验一（真实写入 5 MiB + 真实计量断言） |
| 4 | 超时 → `McpTimeoutException` 且 pending 被清理（:63） | `McpClientTest:74-83` | ✅ | `pendingRequestCount()==0`（`:78`）+ **恢复调用仍成功**（`:80-81`）⇒ 不只是抛异常，会话未被打坏 |
| 5 | 中途死亡 → `isAvailable()` false，降级失败不永久挂起（:64） | `McpClientTest:86-96`；服务端 `halt(17)`（`:95`） | ✅ | 死亡调用用 3s 上限且断言 `McpUnavailableException`（而非超时）⇒ 能把「干净降级」与「挂死到超时」区分开 |
| 6 | 中文/emoji 往返不乱码（:65） | `McpClientTest:99-105`；服务端 `respondWithSplitUtf8:132-146` | ✅ | 在 `🙂`（4 字节）**第 1 字节后**切断、flush、`sleep(20)` 再写余下 ⇒ 真正制造「多字节字符被读缓冲切开」，非摆设 |
| 7 | `close()` 后无残留线程（:66） | `McpClientTest:108-125`；辅助 `:173-178` | ✅（有覆盖缺口，见下） | 见 2.3 核验二 |

**额外加分**：`FakeMcpServer.java:46-48` 规定「未收到 `notifications/initialized` 就回 -32000」，因此测试 1 隐含验证了客户端确实发了 initialized 通知 —— 卡片:48 的三步握手被真正跑通，而不是只发 initialize。

### 2.3 三项指定深度核验

**核验一：stderr 洪泛测试是否真写 5 MiB、是否真断言消费量 —— 真。**

- 真写：`FakeMcpServer.java:97` `floodStderr(5 * 1024 * 1024)`；`:119-130` 以 1024 字节行为单位（末字节 `\n`）循环 `System.err.write`，累计恰好 5,242,880 字节，末尾 `flush()`。**不是**象征性写几行。
- 真断言：`McpClientTest.java:63` 用 `stderrLogger(line -> loggedCharacters.addAndGet(line.length()))` 计量，`:68` `assertTrue(loggedCharacters.get() > 4L * 1024 * 1024, …)` —— 阈值 4 MiB 需要客户端实际消费约 4100 行才成立。
- 逻辑闭环：服务端**先写完 5 MiB 再回响应**（`:96-99`），管道缓冲区仅约 64 KiB ⇒ 客户端若不读 stderr，子进程会在 `floodStderr` 内阻塞，`tools/call` 必然在 15s（`:65`）超时失败。所以「>4 MiB」这一断言在管道背压下**等价于**「5 MiB 基本被排空」，测试设计是成立的。
- 可加固处（非阻塞）：阈值应写成 `>= 5 * 1024 * 1024 - 64 * 1024` 或直接断言「服务端写完 5 MiB 的确认」；另外可再补一条「`stderrLogger` 抛异常时 drain 不中断」（该防御已实现于 `StdioTransport.java:199-203`，但无测试覆盖）。

**核验二：`close()` 之后是否真无残留线程 —— 真，且机制上无泄漏路径（测试断言范围偏窄）。**

- 测试侧：`:110-111` 先 `await(countThreads(prefix)==2)` 证明 stdout/stderr 两线程**确实存在过**（前置条件非空），`:121-122` close 后 `await(countThreads(prefix)==0)` + `assertEquals(0, …)`；`:118-120` 同时验证 pending 调用被 `McpUnavailableException` 唤醒（不悬挂）；`:123-124` 验证后续调用被拒。`countThreads`（`:173-178`）用 `Thread.getAllStackTraces()` + 名字前缀过滤，`transportThreadNamePrefix()`（`McpClient.java:170-172`）是**每个 transport 实例唯一**的前缀，不会误判其它测试的线程。
- 实现侧：`StdioTransport.close:357-374` → `shutdownSession:294-312` 关流（`:306-307`）+ `interrupt()` 读线程（`:308-309`）+ `joinUnlessCurrent(1s)`（`:310-311`，`:322-331`）；读循环以 `!closed.get()`（`:179`）退出，`finally` 里 `if (!closed.get())`（`:188`）避免 close 期间再次 teardown ⇒ 无竞态重复销毁。
- **close/restart 竞态我额外推演过（测试未覆盖，但实现是安全的）**：`scheduleRestart` 有 `closed` 前置判断（`McpClient.java:125`）且捕获 `RejectedExecutionException`（`:133-138`，仅在未 close 时重抛）；`restart` 再次判 `closed`（`:143`）；即便竞态中 `restart` 抢先 `transport.start()`，`close()` 的 `getAndSet(null)`（`StdioTransport.java:363`）与 `start()` 的 `activeSession.set`（`:76`）同受 `lifecycleLock`（`:61`/`:362`）保护 ⇒ **新生进程必被 `close()` 回收**，不存在孤儿进程；反之 transport 自身的 `closed` 标志（`:62-64`）会拒绝任何后到的 `start()`。
- 缺口（非阻塞）：`countThreads` 只统计 `mcp-stdio-N-` 前缀，**未覆盖** `mcp-client-N-lifecycle` 调度线程（`McpClient.java:42-46`）。该线程本身也有 `shutdownNow()` + `awaitTermination(2s)`（`:181-184`）且为 daemon，但「close 后无残留线程」这一条的字面覆盖不完整；另外未断言子进程被回收（`ProcessHandle.of(pid).isAlive()`）。

**核验三：单行输出是否真能拒绝裸换行 —— 能，但只在「外部注入 mapper」这条公开路径上可达，且无测试。**

- 守卫存在：`JsonRpcCodec.java:25-27`，命中即抛 `McpProtocolException`。
- 我的实机探针（临时 JVM，未落盘仓库）：默认 `new JsonRpcCodec()` 编码结果 `containsRawLF=false containsRawCR=false` ⇒ **生产链路（`McpClient:39`、`StdioTransport` 走无参构造）该分支不可达**；但 `JsonRpcCodec.java:18` 是 **public** 的 `JsonRpcCodec(ObjectMapper)`，注入 `INDENT_OUTPUT` 的 mapper 后探针确实抛出 `McpProtocolException: JSON-RPC encoder produced a raw line break` ⇒ 这是对公开 API 的**真实防御**（把「悄悄写出多行破坏 framing」变成响亮失败），只是当前无测试触发。
- 测试侧缺口：`JsonRpcCodecTest.java:18-34` 只断言**当前输出**中不含 `\n`/`\r`，**没有**断言「裸换行会被拒绝」，所以卡片:38 的「必须保证单行输出」目前由「Jackson 紧凑输出 + 守卫兜底」共同满足，守卫本身处于未覆盖状态。结论：要求达成，属可加固项。

### 2.4 测试要求 1-7 之外的顺带发现（均非阻塞）

- `StdioTransport.java:211-214`：响应 id 非整数（`canConvertToLong()` 为假）即 `failSession(... "MCP response has a non-integral id")` ⇒ **整会话销毁 + 重启**。JSON-RPC 规范允许服务端对解析错误回 `"id": null`，字符串 id 在规范中也合法；本客户端 id 恒为自增 long，遇此类服务端会触发一次销毁重启（退避封顶 30s，**不会**忙循环）。作为硬化取舍可接受，建议在自审/下一卡显式记录，或降级为「丢弃该响应 + 记日志」。
- `StdioTransport.java:185-190`：**任何**一行不可解析的 stdout（如 Node 服务启动 banner）都会经 `catch (IOException | RuntimeException)` → `failSession` 打掉整个会话。严格符合 MCP「stdout 只允许 JSON-RPC」，但对真实服务端较脆；若 banner 每次启动都打，会形成慢速重启循环。建议下一卡考虑「连续 N 行不可解析才判死」。
- `StdioTransport.java:175-192`：服务端发来的 **request / notification 被静默丢弃**（`:181-183` 只派发 `JsonRpcResponse`），既不应答也不记日志；MCP 的 `sampling/createMessage`、`roots/list` 等 server→client 请求在当前实现下会被对方等到超时。卡片只要求 tools 方向，属正常范围，但必须记入下一卡待办。
- `JsonRpcCodec.decode` 用 `mapper.readTree(line)`（`:39`）：探针显示**一行里出现两个 JSON 对象时只接受第一个，尾随内容被静默忽略**（Jackson `readTree(String)` 语义）。framing 上是宽松而非错误（换行由 `readLine` 切分），建议下一卡用 `readTree` + 尾随校验收紧。

---

## ③ 边界条件

| 类别 | 检查 | 结论 |
|---|---|---|
| 空/非法入参 | `request(null method)`（`:87`）、`validateTimeout`（`:333-338`：null→NPE "timeout"；0/负→`IllegalArgumentException("timeout must be positive")`） | ✅ 空值不落到 IO 层；时长校验在取 permit **之前**（`:88` 先于 `:90`），坏参数不会泄漏 permit |
| | `callTool` 三个 `Objects.requireNonNull`（`McpClient.java:60-62`） | ✅ |
| | `decode(null/"  ")` → `"JSON-RPC line must not be blank"`；`decode("not json")` → `"Could not decode JSON-RPC line"`；缺 `jsonrpc` → `"Invalid JSON-RPC 2.0 message"`；result/error 同时或同时缺失 → `"Response must contain exactly one of result or error"`（均为探针实测） | ✅ 错误信息可定位 |
| | `new JsonRpcRequest("2.0", DoubleNode(1.5), "m", null)` → `IllegalArgumentException: id must be an integer or string`（探针实测） | ✅ 构造期即拒绝非法 id |
| | 空 method 字符串 `"  "` 会被接受（`JsonRpcRequest` 只做 null 检查） | ⚠️ 不可达（transport 侧 `:87` 已拦 null），属可选加固 |
| 配置边界 | `McpClientConfig.java:22-46`：command 空/含 blank、`maxConcurrentRequests<1`、`restartBackoff` 空或含负、`consecutiveTimeoutsBeforeRestart<1`、`handshakeTimeout` 非正 —— **全部拒绝** | ✅ 因 `restartBackoff` 非空被强制，`Math.min(attempt, size-1)`（`StdioTransport`/`McpClient:129`）不会出现 -1 越界 |
| | 默认值：16 / 1s·5s·30s / 3 / 10s / `2025-06-18` / 空 logger（`:60-68`） | ✅ 与卡片:46-47 示例一致 |
| 时间溢出 | `deadlineAfter`（`:340-350`）算术溢出 → `Long.MAX_VALUE`；`toNanosSaturated`（`McpClient.java:158-164`）`ArithmeticException` → `Long.MAX_VALUE` | ✅ 极端超时不会退化成「立即超时」 |
| 异常路径 | 并发取 permit 超时抛 `McpTimeoutException`（`:259`）而非静默等待；`InterruptedException` 分支恢复中断位（`:111-116`、`:261-263`、`:302-305`） | ✅ 不吞中断 |
| | `ExecutionException` 解包：`McpException` 原样抛，其余包 `McpUnavailableException("MCP request failed: …")`（`:117-122`） | ✅ 异常语义单一出口 |
| 并发 | 5 并发（测试 2）真同时在途；id 逆序返回仍正确配对 | ✅ |
| | 写路径在锁内二次校验会话身份/存活（`:232-234`），close 期间的写请求降级为 `McpUnavailableException` 而非 NPE | ✅ |
| 资源泄漏 | pending 有界（受 Semaphore 约束）+ `finally` 必移除（`:123-128`）；`close()` 二次清扫（`:369-373`） | ✅ |
| | 进程/流/线程回收完整（`:294-312`），join 有上限，跳过自 join（`:322-331`） | ✅ |
| | 测试 5 用 `halt(17)` 制造**不经 JVM 清理**的猝死，验证的是父进程侧抢救能力 | ✅ 恰当 |
| 未覆盖边界（非阻塞） | ①服务端 `id:null`/字符串 id → 会话被销毁（2.4）；②stdout 出现非 JSON 行 → 会话被销毁；③`codec.encode(null)` → NPE（仅内部调用）；④`callTool` 的 `mapper.valueToTree(args)` 对不可序列化对象抛 Jackson 的 `IllegalArgumentException`，**不属** `McpException` 体系，调用方按 `McpException` 捕获会漏；⑤id 自增溢出（不可达）；⑥`completePending` 多个 future 共享同一异常实例（无状态，安全） |

---

## ④ 有无自作主张的发挥

| 检查 | 结论 |
|---|---|
| 文件范围（卡片:18-25） | **合规**。新增仅 `agent-core/pom.xml`、`agent-core/src/main/java/dev/lifeagent/mcp/**`(14)、`agent-core/src/test/java/dev/lifeagent/mcp/**`(3)、`agent-core/SELF-REVIEW-P1-1.md`。最后一项**是卡片:86 强制要求**的交付物，非扩范围 |
| 根 `pom.xml` | **未改**：`grep -nE "<module>|agent-core" pom.xml` 无输出；`git diff --stat` 空 ⇒ 遵守卡片:25/Δ9，也未把模块注册进 reactor |
| `src/`、`nginx-1.18.0/`、`.github/`、任何现有测试 | **未改**（哈希清单 19 文件前后一致 + `git status` 仅 `?? agent-core/`） |
| `agent-core/README.md` | 未创建 —— 卡片:23 是「可以」，允许不做 |
| 依赖（卡片:31-32） | **精确合规**：`pom.xml:18-30` 仅 `jackson-databind`(2.19.2) + `junit-jupiter`(5.13.4, test)；**无 Spring、无官方 MCP SDK** |
| 模块规格（卡片:29-33, 30） | **合规**：GA/artifact/version 逐字一致（`:7-9`）；`maven.compiler.release=21`（`:13`）；无 `<parent>`；`mvn -f agent-core/pom.xml -B -ntp test` 独立可用（实测绿） |
| HTTP 传输 | **未实现，且这是正确的**。`PROJECT_IMPLEMENTATION_PLAN.md` Δ3 把 HTTP 放在 W1 并设为默认，但卡片:10/:96 明确「本卡不做 HTTP 传输」，按「任务卡为准」判定：**当前只有 stdio，符合卡片**；HTTP 计入 W1 后续卡 |
| LAYA 业务工具（`laya_preset`/`laya_decide`） | **未封装**，`grep` 无命中，符合卡片:97 |
| Spring/业务代码/数据库 | **未接**，符合卡片:98 |
| push / commit | **未做**，HEAD 仍 `9b2a052`，符合卡片:99 与 AGENTS.md 禁止事项 |
| 为了让测试变绿而改断言 | **无**。3 个测试文件全为新建，既有测试零改动（哈希+git 双证） |
| 自审是否诚实 | **诚实**。`SELF-REVIEW-P1-1.md` 明写当次根 `verify` 出现 2 个既有测试的 `SocketException`，**不**宣称达成完整 DoD 而把判定权交给管理与 CI —— 与我本轮重跑已全绿的结论并不冲突（环境抖动），态度符合卡片:85-86、91-92 |
| 生产代码里的测试钩子 | **轻微扩面**：`McpClient.java:166-172`（`pendingRequestCount()`、`transportThreadNamePrefix()`）、`StdioTransport.java:141-147`（`pendingCount()`、`threadNamePrefix()`）为包级可见，仅为测试可观测性而存在，未出现在卡片:50-52 的对外 API 清单里。无功能影响、不扩大包外可见性，但属实现者自行增加的 API 表面，记录备查 |
| 其它未在卡片要求的行为 | `McpClient` 构造期握手失败时**不抛异常**而是「构造成功但不可用 + 排程重启」（`McpClient.java:52-56`）。卡片:48-49 只要求「任一步失败 = 客户端不可用」，`isAvailable()` 已为 false、调用已降级失败，测试 `:147-155` 覆盖 ⇒ 视为合规取舍，非自作主张 |

---

## 判定

**结论：通过（无条件项）**

- 实现要求 1-6 **逐条达成**，均有可定位痕迹（类/方法/行号已在上表列出）。
- 测试要求 1-7 **逐条达成且为真测试**：11/11 绿（我独立重跑），并发、逆序响应、UTF-8 字节级切断、进程猝死、stderr 5 MiB 洪泛均为真制造故障，不是 happy path。
- 卡片:75 回归红线 **28 tests / 0 failures / 0 errors / 1 skipped / BUILD SUCCESS**（我独立重跑，与卡片:80 基线一致）。
- 文件范围、依赖、模块规格、不 push 不 commit **全部合规**；HTTP/LAYA/Spring **均未越界实现**。
- 安全面：无注入面、无文件访问、无凭据记录、子进程回收完整；唯一实质建议是**子进程 env 全量继承**（`StdioTransport.java:72`），属加固而非缺陷。
- 卡片:85「实现要求 7 条」与正文 1-6 的编号笔误按要求**不计入实现者缺陷**，未在本报告提出。

### 必修清单（阻塞项）

**0 项。** 未发现必须打回重做的安全、正确性或越界问题；上表所有 ⚠️/缺口均为「达成要求之外的加固空间」。

### 建议清单（非阻塞，建议 PR 前或下一卡处理）

| 优先级 | 建议 | 位置 |
|---|---|---|
| 高 | stderr 洪泛阈值由 `>4 MiB` 改为紧贴 5 MiB 下界（或断言服务端已完成 5 MiB 写入），并补一条「`stderrLogger` 抛异常时 drain 不中断」的用例 | `McpClientTest.java:68`；防御已存在于 `StdioTransport.java:199-203` |
| 高 | 子进程环境改为 `clear()` + 仅注入 `config.environment()`，避免继承父进程全部凭据 | `StdioTransport.java:72` |
| 中 | 补 `JsonRpcCodec` 单行守卫用例（注入 `INDENT_OUTPUT` mapper，断言抛 `McpProtocolException`） | `JsonRpcCodecTest.java:18-34`；守卫在 `JsonRpcCodec.java:25-27` |
| 中 | 下一卡处理 server→client 请求（`sampling/createMessage`、`roots/list`）：至少记日志、按需回 `-32601`，而非静默丢弃 | `StdioTransport.java:181-183` |
| 中 | 「close 后无残留线程」补统计 `mcp-client-N-lifecycle` 与子进程存活（`ProcessHandle`）断言 | `McpClientTest.java:110-122,173-178` |
| 低 | 非整数/`null` id 的响应改为「丢弃并记日志」，而非整会话销毁 | `StdioTransport.java:211-214` |
| 低 | stdout 非 JSON 行容忍策略（连续 N 行才判死），避免启动 banner 触发重启循环 | `StdioTransport.java:185-190` |
| 低 | `decode` 增加尾随内容校验；`callTool` 把 `valueToTree` 的 Jackson 异常包装为 `McpException` | `JsonRpcCodec.java:39`；`McpClient.java:68` |

**复核者声明**：本轮为只读复核，未修改仓库任何文件（`SOURCE_TREE_UNCHANGED=yes`），未 commit/push，未调整任何测试断言；副作用仅落在已被 `.gitignore:42` 忽略的 `agent-core/target/`（`audit-BEFORE/AFTER.sha256`、`audit-test.log`、`audit-root-verify.log`）。最终判定权按 AGENTS.md 交给 CI（JDK 8 环境）与管理者。
