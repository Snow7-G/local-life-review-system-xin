# P1-1 自审记录

自审日期：2026-10-13

结论：`agent-core` 的实现要求与 7 条专项测试要求均已实现，独立模块 11 tests 全绿，独立编译通过。根工程 `verify` 实际运行了 28 tests，但当前执行沙箱禁止创建本地 TCP socket，导致两个既有 Redis 集成测试在 `new ServerSocket(0)` 处报 `Operation not permitted`；因此本卡整体 DoD 暂不能标记为全部通过，需在允许 loopback socket 的 CI/管理者环境重跑根回归。

## 规格与范围核对

| 要求 | 实现位置（文件:行） | 是否达成 | 备注 |
|---|---|---:|---|
| 独立 Maven 模块、坐标与版本 | `pom.xml:7` | 是 | `dev.lifeagent:agent-core:0.1.0-SNAPSHOT`。 |
| JDK 21 + compiler plugin | `pom.xml:11`、`pom.xml:34` | 是 | `maven.compiler.release=21`，显式配置 compiler plugin。 |
| 只依赖 Jackson + JUnit(test) | `pom.xml:18` | 是 | 无 Spring、无 MCP SDK。 |
| Δ9：不进入根 reactor | `pom.xml:1` | 是 | 仅新增独立 POM，未修改根 `pom.xml`。 |
| Δ3 / 本卡范围 | `src/main/java/dev/lifeagent/mcp/StdioTransport.java:29` | 是 | 本卡按任务卡“明确不做”只实现 stdio；未提前实现 HTTP。 |

任务卡文字称“实现要求 7 条”，但其实际编号只有 1～6。下表严格保留原编号；实现要求 3 的 7 个子项分别列出，未臆造不存在的第 7 项。

## 实现要求逐条自审

| 要求编号 | 实现位置（文件:行） | 是否达成 | 备注 |
|---|---|---:|---|
| 1 JSON-RPC request / response / notification / error 模型 | `src/main/java/dev/lifeagent/mcp/JsonRpcMessage.java:4`；`JsonRpcRequest.java:13`；`JsonRpcResponse.java:11`；`JsonRpcNotification.java:11`；`JsonRpcError.java:11` | 是 | 支持整数/字符串 id、成功与错误响应，并校验 JSON-RPC 2.0 结构。 |
| 1 Jackson 编解码且单行输出 | `src/main/java/dev/lifeagent/mcp/JsonRpcCodec.java:22` | 是 | 使用 `writeValueAsString`，并显式拒绝编码结果中的裸 CR/LF。 |
| 2 line framing + UTF-8 跨 buffer | `src/main/java/dev/lifeagent/mcp/StdioTransport.java:156`、`StdioTransport.java:175`、`StdioTransport.java:228` | 是 | UTF-8 `Reader/Writer` + `readLine/newLine`；跨多字节边界由有状态 `InputStreamReader` 解码。 |
| 3a ProcessBuilder 命令与 env 配置 | `src/main/java/dev/lifeagent/mcp/McpClientConfig.java:10`；`StdioTransport.java:60` | 是 | 命令、环境均不可变复制后传给 `ProcessBuilder`。 |
| 3b 独立 stdout 线程、pending 按 id 分发、多请求在途 | `src/main/java/dev/lifeagent/mcp/StdioTransport.java:41`、`StdioTransport.java:161`、`StdioTransport.java:175`、`StdioTransport.java:210` | 是 | `ConcurrentHashMap<Long, PendingRequest>`；响应按 id 完成各自 future。 |
| 3c 独立 stderr 持续读取并转发日志 | `src/main/java/dev/lifeagent/mcp/StdioTransport.java:165`、`StdioTransport.java:194` | 是 | 日志 consumer 自身异常也不会中止 drain。 |
| 3d stdin 写入加锁 | `src/main/java/dev/lifeagent/mcp/StdioTransport.java:47`、`StdioTransport.java:228` | 是 | 单一 `ReentrantLock` 包住整行 write/newLine/flush。 |
| 3e 每调用超时、pending 清理、McpTimeoutException | `src/main/java/dev/lifeagent/mcp/StdioTransport.java:86`、`StdioTransport.java:105`；`McpTimeoutException.java:3` | 是 | 超时覆盖并发许可等待和响应等待；finally 再兜底清理 pending。 |
| 3f 进程死亡/连续超时、可配退避重启、重握手、重启期降级 | `src/main/java/dev/lifeagent/mcp/StdioTransport.java:267`、`StdioTransport.java:274`；`McpClient.java:118`、`McpClient.java:124`、`McpClient.java:141` | 是 | 默认退避 1s/5s/30s；不可用期间 `callTool` 直接抛 `McpUnavailableException`。 |
| 3g Semaphore 并发上限 | `src/main/java/dev/lifeagent/mcp/StdioTransport.java:41`、`StdioTransport.java:56`、`StdioTransport.java:255` | 是 | 公平 semaphore；配置校验为正数。 |
| 4 initialize → initialized → tools/list 并缓存 schema | `src/main/java/dev/lifeagent/mcp/McpClient.java:82` | 是 | 任一步或工具定义解析失败均不设置 available；重启也复用完整握手。 |
| 5 对外 API / AutoCloseable | `src/main/java/dev/lifeagent/mcp/McpClient.java:22`、`McpClient.java:59`、`McpClient.java:72`、`McpClient.java:76`、`McpClient.java:174` | 是 | 暴露任务卡指定四个 API；schema 返回 defensive copy。 |
| 6 close 干净退出并唤醒 pending | `src/main/java/dev/lifeagent/mcp/StdioTransport.java:285`、`StdioTransport.java:294`、`StdioTransport.java:356`；`McpClient.java:174` | 是 | 销毁/必要时强杀子进程、关闭管道、interrupt+join 读线程、异常完成 pending、停止生命周期调度器。 |

## 测试要求逐条自审

测试夹具是可执行子进程：`src/test/java/dev/lifeagent/mcp/FakeMcpServer.java:18`。

| 测试要求编号 | 实现位置（文件:行） | 是否达成 | 备注 |
|---|---|---:|---|
| 1 握手成功并解析工具 | `src/test/java/dev/lifeagent/mcp/McpClientTest.java:29`；`FakeMcpServer.java:38` | 是 | 断言 `echo` 的 inputSchema 内容。 |
| 2 并发 5 个调用且 id 不错配 | `src/test/java/dev/lifeagent/mcp/McpClientTest.java:40`；`FakeMcpServer.java:84` | 是 | 5 线程并发；服务端 CountDownLatch 要求五个请求同时到达，再乱序延迟响应并逐一核值。 |
| 3 stderr 5 MiB 洪泛回归 | `src/test/java/dev/lifeagent/mcp/McpClientTest.java:59`；`FakeMcpServer.java:96`、`FakeMcpServer.java:119` | 是 | 真写 5 MiB 后才回复；调用在 15s 内成功，并断言日志 consumer 收到超过 4 MiB。最终全套测试中该用例耗时 0.814s、通过。 |
| 4 超时异常与 pending 清理 | `src/test/java/dev/lifeagent/mcp/McpClientTest.java:73`；`FakeMcpServer.java:92` | 是 | 断言 `McpTimeoutException`、pending=0，随后正常调用仍成功。 |
| 5 子进程死亡后降级失败 | `src/test/java/dev/lifeagent/mcp/McpClientTest.java:85`；`FakeMcpServer.java:95` | 是 | 子进程 `halt(17)`；在 3s 调用期限内解除等待，available=false，后续调用立即失败。 |
| 6 中文/emoji 跨读取边界往返 | `src/test/java/dev/lifeagent/mcp/McpClientTest.java:98`；`FakeMcpServer.java:132` | 是 | 服务端刻意在 emoji 的第 1 个 UTF-8 byte 后 flush，再写余下 bytes。 |
| 7 close 后无残留线程 | `src/test/java/dev/lifeagent/mcp/McpClientTest.java:107` | 是 | 先挂起一个 pending 调用；close 后断言它被唤醒、命名 stdout/stderr 线程数为 0、后续调用被拒绝。 |
| 补充：连续超时触发重启并重握手 | `src/test/java/dev/lifeagent/mcp/McpClientTest.java:127` | 是 | 比较重启前后 PID，并检查重启后的 schema 缓存。 |
| 补充：握手失败不可用 | `src/test/java/dev/lifeagent/mcp/McpClientTest.java:146` | 是 | malformed tools/list 后 `isAvailable=false` 且调用降级失败。 |
| 补充：JSON-RPC 四类与物理单行 | `src/test/java/dev/lifeagent/mcp/JsonRpcCodecTest.java:17`、`JsonRpcCodecTest.java:36` | 是 | 覆盖 request、notification、success response、error response、字符串 id、含换行/中文/emoji 参数。 |

## 验收命令与真实结果

当前沙箱的 `/root/.m2` 只读，命令通过 `MAVEN_OPTS` 额外指定 `/tmp/llrs-m2-p1-1/repository` 可写缓存；未改项目 POM 来规避环境限制。

| 命令 | 结果 | 备注 |
|---|---:|---|
| `mvn -f agent-core/pom.xml -B -ntp test` | 通过 | Tests run: 11, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS。 |
| `mvn -f agent-core/pom.xml -B -ntp -q compile` | 通过 | exit 0，无输出、无警告级错误。 |
| `MAVEN_OPTS="-Xmx640m" mvn -B -ntp verify` | **未通过（环境阻塞）** | Tests run: 28, Failures: 0, Errors: 2, Skipped: 1。`LegacyBlogLikeImporterTest:28` 与 `SeckillStreamIntegrationTest:37` 均在既有测试的 `new ServerSocket(0)` 处抛 `java.net.SocketException: Operation not permitted`；26 个测试通过。根 reactor 未包含 agent-core，本次未修改任何根工程文件。 |

## 未达成、降级与建议

- 实现与专项 7 条测试无已知降级。
- 根回归尚未满足“28 tests 全绿”硬门槛；原因是当前 managed sandbox 禁止 loopback socket，不是测试断言或业务失败。须由管理者/CI 在允许本地 TCP socket 的环境原样重跑 `MAVEN_OPTS="-Xmx640m" mvn -B -ntp verify`，通过前不应把 P1-1 标记为最终验收完成。
- 未修改测试以规避失败，未修改根 POM、业务 `src/`、nginx 或 CI 文件，未 commit。
