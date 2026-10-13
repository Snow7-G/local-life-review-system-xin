<!-- 本文件是 Codex（gpt-5.6-sol）对 DSH（deepseek-v4.1-flash）产物的异源只读复核报告 -->
<!-- 复核者声明：只读，未修改任何被审文件；结论对应当前 StdioTransport.java 哈希 7e8e1fceb4be -->
<!-- 提取自 /tmp/p1-2-codex-review.log；日志尾部 EXIT=0 -->

## ① 安全

- 默认隔离真实生效：[StdioTransport.java:70](/root/projects/llrs-audit/agent-core/src/main/java/dev/lifeagent/mcp/StdioTransport.java:70) 获取子环境，默认分支在 [StdioTransport.java:81](/root/projects/llrs-audit/agent-core/src/main/java/dev/lifeagent/mcp/StdioTransport.java:81) 执行 `clear()`，随后只注入配置项。
- `inheritParentEnvironment` 默认 `false`，显式开启后配置值覆盖父环境：[McpClientConfig.java:33](/root/projects/llrs-audit/agent-core/src/main/java/dev/lifeagent/mcp/McpClientConfig.java:33)、[McpClientConfig.java:85](/root/projects/llrs-audit/agent-core/src/main/java/dev/lifeagent/mcp/McpClientConfig.java:85)、[McpClientConfig.java:118](/root/projects/llrs-audit/agent-core/src/main/java/dev/lifeagent/mcp/McpClientConfig.java:118)。
- 安全原因与 opt-in 风险已说明：[McpClientConfig.java:12](/root/projects/llrs-audit/agent-core/src/main/java/dev/lifeagent/mcp/McpClientConfig.java:12)。
- compact constructor 对新增 `boolean` 没有可做的 null、别名或可变性防御；安全默认值放在 Builder 是合理实现，不认为是缺陷。
- `dump_env` 只存在于测试夹具；只返回环境键名和测试注入值，不会把真实凭据值打印到日志。

## ② 逻辑正确性

### 加固 1

| 任务卡要求 | 判断 |
|---|---|
| `clear()` 后仅注入配置环境 | 达成：[StdioTransport.java:72](/root/projects/llrs-audit/agent-core/src/main/java/dev/lifeagent/mcp/StdioTransport.java:72)-82 |
| 默认 false、提供 Builder 开关 | 达成：[McpClientConfig.java:81](/root/projects/llrs-audit/agent-core/src/main/java/dev/lifeagent/mcp/McpClientConfig.java:81)-121、[McpClientConfig.java:159](/root/projects/llrs-audit/agent-core/src/main/java/dev/lifeagent/mcp/McpClientConfig.java:159) |
| 隔离语义文档 | 达成：[McpClientConfig.java:12](/root/projects/llrs-audit/agent-core/src/main/java/dev/lifeagent/mcp/McpClientConfig.java:12)-28 |
| 子进程侧自证 | 达成：[FakeMcpServer.java:141](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/FakeMcpServer.java:141)-155、[McpClientTest.java:100](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/McpClientTest.java:100)-125 |
| 继承模式正向对照 | 达成：[McpClientTest.java:127](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/McpClientTest.java:127)-147 |

独立判断：测试读取的确是子 JVM 的 `System.getenv()`，而且同时断言父环境哨兵消失、显式注入值存在、键集合符合预期，不是仅验证调用过 `clear()`。删除 `clear()` 后，哨兵和其它父环境键会出现，用例必红。

### 加固 2

- 夹具常量正确：[FakeMcpServer.java:32](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/FakeMcpServer.java:32)-34。
- 算式正确：

  `5 × 1024 × 1024 / 1024 = 5120` 行  
  `5120 × 1023 = 5,237,760`

- 推导与注释位于 [McpClientTest.java:36](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/McpClientTest.java:36)-50；最终断言为精确下界，没有退回 `>4 MiB`，也没有错误使用 `>=5 MiB`：[McpClientTest.java:90](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/McpClientTest.java:90)-96。
- `ok=true` 保留，并有 30 秒有界等待。
- logger 异常用例非空洞：[McpClientTest.java:151](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/McpClientTest.java:151)-176。去掉 [StdioTransport.java:210](/root/projects/llrs-audit/agent-core/src/main/java/dev/lifeagent/mcp/StdioTransport.java:210)-214 的内层捕获后，stderr 线程会在第 3 行退出；计数最多停在 `3 × 1023 = 3069`。无论管道是否立即写满，调用超时或最终下界断言至少一个必然失败，因此该测试确实能抓住回归。

## ③ 边界条件

- 空输入：空环境 Map 会得到空子环境；空命令、blank 参数、null Map 项继续由既有配置校验拒绝。未引入退化。
- 异常路径：logger 的 `RuntimeException` 被隔离；stderr `IOException` 沿用原生命周期处理。`Error` 不捕获是合理边界。
- 并发：配置集合有防御性复制；环境构建位于同步启动区；新增测试计数使用原子类型，没有新增共享可变竞态。
- 资源泄漏：所有客户端测试均使用 try-with-resources；Fake server executor 在 `finally` 中关闭；异常 logger 不会再杀死 drain 线程。
- 平台差异：ASCII `x` 和显式 `\n` 使洪泛算式跨平台稳定。Windows JDK 可能为子进程补入 `SystemRoot`，因此 [McpClientTest.java:118](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/McpClientTest.java:118) 的“环境键集合严格等于一个键”可能不适合未来 Windows CI；当前 Linux CI 不阻塞。
- LAYA/PATH：默认隔离确实会让真实服务端拿不到 `PATH`，可能影响 Python、`uvx`、`/usr/bin/env` shebang 或服务端调用辅助程序。但本卡明确要求只注入配置环境，因此**不应在 P1-2 自动保留 PATH**。后续接入 LAYA 时应使用绝对命令并白名单注入所需的 `PATH`、缓存目录等；只有无法枚举时才显式开启全量继承。无需在本卡补实现。

## ④ 有无自作主张

- `git status` 仅有指定的 4 个修改文件和指定的新自审文件；没有范围外改动。
- 除任务卡点名的洪泛断言外，没有修改既有断言；该断言是收紧而非放宽。
- 没有 HTTP 通道或网络传输实现。
- 额外增加的继承模式正向对照属于必要的防空洞验证，范围合理。
- 小文档瑕疵：[SELF-REVIEW-P1-2.md:19](/root/projects/llrs-audit/agent-core/SELF-REVIEW-P1-2.md:19) 写“无新增文件”，但本自审文件本身是 `??`；前表已明确写“新建”，不构成隐瞒，建议改准。

只读读取现有报告：`agent-core` 14/14 通过；根回归 28 tests、0 failure、0 error、1 skipped。审查期间观察到外部流程短暂执行变异并随后恢复、重跑；最终结论对应当前 `StdioTransport.java` 哈希 `7e8e1f…`。未修改文件、未 commit。

**必须修的问题：无。**
