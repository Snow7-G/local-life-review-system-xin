# SELF-REVIEW-P1-3 — MCP HTTP 通道

## 结论

任务卡要求的传输抽象、配置拆分、构造注入、Streamable HTTP（JSON/SSE）、会话管理、并发/超时/关闭语义和 9 条 HTTP 真服务端测试均已实现；`agent-core` 共发现 23 条测试，既有 14 条测试单独运行全绿。

当前执行环境禁止创建本地 socket。`HttpServer.create(...)` 在任何测试逻辑执行前即抛出 `java.net.SocketException: Operation not permitted`，根回归中原有的两个 socket 集成测试也因同一策略失败。因此本次自审不能把“23 条全绿”和“根 28 条全绿”标成已完成，必须在允许 loopback socket 的 CI/管理者环境补跑任务卡两条原命令。

## 实现要求逐条核对

| 要求编号 | 实现位置（文件:行） | 是否达成 | 备注 |
|---|---|---|---|
| 实现 1-a：抽出 `McpTransport` | `src/main/java/dev/lifeagent/mcp/McpTransport.java:9-27` | 是 | 公共接口的方法集合与任务卡一致，继承 `AutoCloseable`。 |
| 实现 1-b：`StdioTransport implements McpTransport`，方法公开且行为保持 | `src/main/java/dev/lifeagent/mcp/StdioTransport.java:29-173` | 是 | 请求、通知、并发、pending、线程名前缀和失败/关闭入口均由接口公开；原 stdout/stderr、写锁、超时重启逻辑保留。 |
| 实现 1-c：配置拆分 | `src/main/java/dev/lifeagent/mcp/McpClientConfig.java:8-15`；`src/main/java/dev/lifeagent/mcp/StdioTransportConfig.java:9-60` | 是 | 公共配置严格只保留任务卡列出的七项；`command/environment/inheritParentEnvironment` 和同属 stdio 的 `stderrLogger` 进入 `StdioTransportConfig`。 |
| 实现 1-d：`McpClient` 构造注入传输 | `src/main/java/dev/lifeagent/mcp/McpClient.java:28-58` | 是 | 客户端只依赖 `McpTransport`，不再创建具体传输。包内 `McpTransportFailureSource`（`McpTransport.java:29-33`）仅传递不可用事件，避免改动任务卡规定的公共接口，同时保留原即时退避重启行为。 |
| 实现 2-a：单 URL、POST 与必需请求头 | `src/main/java/dev/lifeagent/mcp/HttpTransport.java:61-84,117-123,233-277` | 是 | 使用 JDK `java.net.http`；POST UTF-8 JSON；发送 `Content-Type`、`Accept`、会话 ID 与协商后协议版本。 |
| 实现 2-b：JSON 与 SSE 响应 | `src/main/java/dev/lifeagent/mcp/HttpTransport.java:183-228,304-353` | 是 | JSON 校验当前 id；SSE 按事件读取 `data:`，跳过通知或不匹配 id，直到匹配响应。 |
| 实现 2-c：会话与协议版本 | `src/main/java/dev/lifeagent/mcp/HttpTransport.java:263-302,356-372` | 是 | 捕获 initialize 响应头中的 `Mcp-Session-Id`；从 initialize 结果保存协商版本；后续请求附带二者。 |
| 实现 2-d：404 会话失效与重握手 | `src/main/java/dev/lifeagent/mcp/HttpTransport.java:279-293,390-406`；`src/main/java/dev/lifeagent/mcp/McpClient.java:137-174` | 是 | 404 转为不含状态码的不可用异常、关闭当前 generation、清空 pending，并通知客户端走既有退避重握手。 |
| 实现 2-e：超时、并发上限、pending 按 id 分发、快速失败 | `src/main/java/dev/lifeagent/mcp/HttpTransport.java:43-49,103-181,375-428` | 是 | 公平信号量限制并发；单调用 deadline；pending identity + generation 防止错配/旧回调污染；不可用时 `liveGeneration()` 立即拒绝。 |
| 实现 2-f：异常映射与 HTTP 状态脱敏 | `src/main/java/dev/lifeagent/mcp/HttpTransport.java:195-228,247-259,279-293` | 是 | 连接/读 I/O 失败映射 `McpUnavailableException`；调用 deadline 映射 `McpTimeoutException`；408/425/429/5xx 视为可重试不可用，其他 4xx 视为协议拒绝；异常消息不含状态码。 |
| 实现 2-g：干净关闭 | `src/main/java/dev/lifeagent/mcp/HttpTransport.java:455-478` | 是 | 幂等关闭，取消 exchange、完成全部 pending、关闭 JDK HttpClient、停止并等待命名 executor。取消后的异步回调在 `HttpTransport.java:183-194` 按 pending identity 丢弃。 |
| 实现 3：真实 JDK HTTP 服务测试 | `src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:34-361` | 已实现；动态验收受环境阻塞 | 使用 `com.sun.net.httpserver.HttpServer`，没有 mock 框架或新增依赖，共新增 9 条测试。测试源码已编译。 |

## 测试要求 1-8 对照

| 测试编号 | 实现位置（文件:行） | 是否达成 | 备注 |
|---|---|---|---|
| 测试 1：握手、会话头、工具列表/schema 缓存 | `src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:38-66,240-251,308-317` | 已实现；待 socket 环境动态复验 | 分两条测试验证 schema 缓存、Session/Protocol/Content-Type/Accept 请求头。 |
| 测试 2：会话 ID 传递 | `src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:49-66,233-247` | 已实现；待动态复验 | 对 initialize 之后的每个请求断言返回的 session ID。 |
| 测试 3：JSON 与混入错误 id 的 SSE | `src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:68-87,294-306,320-328` | 已实现；待动态复验 | SSE 先发送 id=999999，再发送本次请求 id。 |
| 测试 4：至少 3 个并发调用不串 id | `src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:89-107,277-288` | 已实现；待动态复验 | 三个调用由 latch 保证同时在途，同时断言各自参数与最大并发数。 |
| 测试 5：超时与 pending 清理 | `src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:109-119,269-272` | 已实现；待动态复验 | 服务端延迟 3 秒，客户端 150ms 超时；断言异常、pending=0、单次超时后仍可用。 |
| 测试 6：404 会话失效、降级失败且不泄漏状态码 | `src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:121-133,273` | 已实现；待动态复验 | 断言不可用、消息无 `404`、后续调用快速拒绝。 |
| 测试 7：close 无残留线程并拒绝后续调用 | `src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:135-155,168-181` | 已实现；待动态复验 | 先建立 pending，再 close；断言 pending 调用被不可用异常终止、命名线程归零、后续调用拒绝。 |
| 测试 8：UTF-8 中文/emoji | `src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:157-166,275-276` | 已实现；待动态复验 | `上海探店🙂火锅` 完整往返。 |

## 配置拆分与改动面取舍

采用任务卡指定的严格拆分，没有保留兼容性的“大而全”配置：

- `McpClientConfig` 仅含两种传输共用的七项。
- `StdioTransportConfig` 承担命令、环境隔离开关及 stderr sink；`stderrLogger` 虽未在任务卡括号中单列，但 HTTP 不存在 stderr，把它留在公共配置会破坏“只保留两者共用项”的硬边界。
- `HttpTransport` 只需端点 URI + 公共配置，没有引入第三个配置对象，减少 API 和装配面。
- 公共 `McpTransport` 没有加入任务卡之外的生命周期方法。包内失败信号只供 `McpClient` 保持原有即时重启语义，具体请求/握手仍完全通过 `McpTransport`，客户端不知道 stdio/HTTP 类型。

## 既有测试装配改动与断言不变证明

既有 14 条测试方法全部保留：`McpClientTest` 12 条、`JsonRpcCodecTest` 2 条。

只改了 `McpClientTest` 的构造/装配代码：

- `src/test/java/dev/lifeagent/mcp/McpClientTest.java:85,102,133,155,193,235,254`：局部 builder 声明类型改为测试内的 `TestClientBuilder`，测试行为与断言不变。
- `McpClientTest.java:263-277`：`newClient` 现在分别 build 公共配置和 stdio 配置，再构造注入 `StdioTransport`。
- `McpClientTest.java:317-357`：新增测试专用聚合 builder，把原 fluent 装配调用路由到对应的两个配置 builder。

没有修改 `JsonRpcCodecTest`、`FakeMcpServer`，没有删除或改名任何既有测试。执行：

```bash
git diff -U0 -- agent-core/src/test/java/dev/lifeagent/mcp/McpClientTest.java \
  | rg '^[-+].*(@Test|assert[A-Za-z]*\\()'
```

无输出，证明既有 `@Test` 行和断言调用行均未增删或改写；逐段 diff 也确认多行断言的参数/消息没有变化。

## 安全、逻辑和边界自审

- 安全：未新增依赖、凭据、业务写接口或真实 LAYA 连接；stdio 默认环境隔离逻辑原样保留；HTTP 状态码不进入上层异常消息。
- 逻辑：请求以 `id + pending identity + generation` 三层条件分发；旧会话回调不能完成新会话请求；SSE 不匹配 id 被跳过。
- 超时/取消竞态：调用线程先从 pending 原子移除再取消 exchange；取消回调发现 identity 已不存在即关闭响应体并返回，不会把单次超时误判成整个传输死亡。
- 并发：信号量许可在所有路径释放；pending 使用 `ConcurrentHashMap` 条件移除；失败时只清理当前 generation。
- 关闭：client 先关闭生命周期 executor，再关闭 transport；HTTP 和 stdio 均幂等完成 pending、停止各自线程/进程。
- 范围：只修改任务卡允许的 MCP 主/测试源码及本文件；未改 `agent-core/pom.xml`、根 reactor、业务 `src/`、nginx、CI；未 commit、未 push。

## 验证记录

### 已通过

1. 主/测试源码完整编译：

   ```bash
   MAVEN_OPTS='-Xmx640m' mvn -f agent-core/pom.xml -B -ntp -DskipTests test
   ```

   `BUILD SUCCESS`，编译 17 个主源码文件、4 个测试源码文件。

2. 既有 14 条回归：

   ```bash
   MAVEN_OPTS='-Xmx640m' mvn -f agent-core/pom.xml -B -ntp \
     -Dtest=JsonRpcCodecTest,McpClientTest test
   ```

   `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。

3. `git diff --check`：通过；既有测试断言增删检查：无输出。

### 当前环境阻塞（未伪报通过）

1. 任务卡 agent-core 原命令：

   ```bash
   MAVEN_OPTS='-Xmx640m' mvn -f agent-core/pom.xml -B -ntp test
   ```

   发现 23 条测试；既有 14 条通过，9 条 HTTP 测试均在 `HttpTransportTest.java:209` 创建 `HttpServer` 时被沙箱拒绝：`java.net.SocketException: Operation not permitted`。结果为 `Tests run: 23, Failures: 0, Errors: 9, Skipped: 0`。失败发生在测试服务端创建阶段，尚未进入被测 HTTP 逻辑。

2. 根回归原命令：

   ```bash
   MAVEN_OPTS='-Xmx640m' mvn -B -ntp verify
   ```

   仍发现 28 条；25 条通过、1 条按基线跳过，两个既有 socket 集成测试 `LegacyBlogLikeImporterTest` 与 `SeckillStreamIntegrationTest` 同样报 `java.net.SocketException: Operation not permitted`。结果为 `Tests run: 28, Failures: 0, Errors: 2, Skipped: 1`。

## 遗留问题 / 后续验收

代码层未发现未实现或降级项。DoD 唯一未闭环的是受执行沙箱 socket 策略阻塞的动态验收；管理者/CI 必须在允许绑定 `127.0.0.1` 的环境重跑上述两条原命令，预期分别为 23 条（满足 ≥22）全绿、根 28 条保持基线全绿。若该环境出现功能性失败，应按新失败栈打回本卡，不能用跳过测试或放宽断言处理。
