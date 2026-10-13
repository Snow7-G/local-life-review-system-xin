# SELF-REVIEW-W2-1

日期：2026-10-14  
任务：W2-1 `llm` 统一消息模型、工具参数累积、SSE 适配器与重试  
结论：实现要求已逐项落地；本环境可运行的新增测试与非 socket MCP 回归全绿。两条完整验收命令均已真实执行，但分别被沙箱禁止监听 socket、禁止 JVM agent self-attach 的环境限制阻塞，不能标为通过。

## 逐条对照

| 要求 | 实现位置（文件:行） | 是否达成 | 备注 |
|---|---|---|---|
| 1. `Role`、三种 `ContentBlock`、`Message`、`LlmRequest/LlmResponse`、`StopReason/TokenUsage` | `Role.java:4`；`ContentBlock.java:4`；`TextBlock.java:5`；`ToolUseBlock.java:7`；`ToolResultBlock.java:7`；`Message.java:6`；`LlmRequest.java:10`；`LlmResponse.java:6`；`StopReason.java:3`；`TokenUsage.java:3` | 达成 | 均为 enum、record 或 sealed interface。 |
| 1a. 不可变集合、`List.copyOf` | `Message.java:10`；`LlmRequest.java:34,59`；`LlmResponse.java:12` | 达成 | 构造时复制；测试见 `MessageModelTest.java:16-28`。 |
| 1b. `JsonNode` 不泄露可变引用 | `ToolUseBlock.java:10-18`；`ToolResultBlock.java:11-21`；`LlmRequest.java:49-60` | 达成 | 输入与访问器均 `deepCopy`；测试见 `MessageModelTest.java:31-51`。 |
| 2. 密封 `LlmStreamEvent` 与五种统一事件 | `LlmStreamEvent.java:4`；`TextDelta.java:5`；`ToolUseStart.java:5`；`ToolUseArgumentsDelta.java:6`；`ToolUseEnd.java:5`；`MessageDelta.java:4` | 达成 | 厂商 SSE 帧类型为包内类型，不进入 `LlmClient` 公共接口。 |
| 3. 按 tool-use id 累积原始 JSON 分片 | `ToolUseAccumulator.java:17-52` | 达成 | 每个 id 独立 `StringBuilder`，所有入口同步，交错分片不串台。 |
| 3a. 仅在 `ToolUseEnd` 整体解析一次 | `ToolUseAccumulator.java:54-84,100-115` | 达成 | 唯一 `parser.parse` 在 `complete` 的第 63 行；`accept(ToolUseEnd)` 才进入该路径。证明测试单列于下节。 |
| 3b. End 前取参失败 | `ToolUseAccumulator.java:87-98,118-125` | 达成 | pending 状态明确抛出 `tool arguments are incomplete before ToolUseEnd`。 |
| 3c. 畸形 JSON 明确失败，不交出半截结果 | `ToolUseAccumulator.java:71-84` | 达成 | 抛 `LlmProtocolException`，失败状态不可读取；测试 `ToolUseAccumulatorTest.java:69-81`。 |
| 3d. 2/5/N 片、字符串内切分、转义内切分 | `ToolUseAccumulatorTest.java:19-42` | 达成 | 五片用例把 JSON 的 `\"` 转义拆在反斜杠与引号之间；N 片逐字符输入。 |
| 3e. 多工具交错、空参数 | `ToolUseAccumulatorTest.java:44-67` | 达成 | 空参数只在 End 时把空累积解释为 `{}`，仍走一次解析器。 |
| 4. 统一适配器接口，只暴露统一事件 | `LlmClient.java:6-8`；`AbstractHttpLlmAdapter.java:44-47` | 达成 | 两个适配器均实现同一 `stream(request, Consumer<LlmStreamEvent>)` 合约。 |
| 4a. Anthropic `/v1/messages` 事件 | `AnthropicMessagesAdapter.java:15,98-139,141-223` | 达成 | 支持 start/delta/stop、message_delta/message_stop；工具参数仅作为字符串增量输出；截断流明确失败。 |
| 4b. OpenAI `/chat/completions` 事件与 `[DONE]` | `OpenAiCompatibleAdapter.java:15,69-109,174-239` | 达成 | 按 choice/tool index 保存交错工具状态，finish 时发 End，`[DONE]` 终止；缺 `[DONE]` 明确失败。 |
| 4c. SSE 注释、空行、多行 data、跨 buffer UTF-8 | `SseParser.java:17-67,77-89` | 达成 | `InputStreamReader` 的 REPORT 模式保留跨 byte-buffer UTF-8 状态；测试以逐字节 InputStream 验证，见 `SseParserTest.java:16-36` 和 `LlmAdaptersTest.java:23-49`。 |
| 5. 传输仅用 JDK `java.net.http`，不新增依赖 | `AbstractHttpLlmAdapter.java:9-11,26-41,98-105` | 达成 | `agent-core/pom.xml` 未修改；无第三方 HTTP import。 |
| 6. 429/过载指数退避与抖动 | `AbstractHttpLlmAdapter.java:57-84,126-145`；`RetryPolicy.java:6-60` | 达成 | 重试 429、500、502、503、504、529；最大重试数、基数、上限、抖动可配。 |
| 6a. Sleeper/随机源可注入，测试不真等 | `Sleeper.java:5-10`；`AnthropicMessagesAdapter.java:20-27`；`OpenAiCompatibleAdapter.java:20-27` | 达成 | 测试注入收集型 Sleeper 与固定随机源。 |
| 6b. 参数错误不重试、断言次数与退避序列 | `LlmAdaptersTest.java:74-107,122-132` | 达成 | 429→529→成功共三次请求、延迟 100/200ms；400 仅请求一次且无 sleep。 |
| 7. 不改 MCP、根 src/pom、CI、nginx | 变更边界检查 | 达成 | `git diff -- agent-core/src/{main,test}/java/dev/lifeagent/mcp agent-core/pom.xml pom.xml .github nginx-1.18.0` 无输出；现有 MCP 测试文件未改。 |
| 8. 不连接真实模型、不做 W2-2/W2-3 | 全部新增文件 | 达成 | 测试使用可注入 JDK `HttpClient` 假响应，不开网络；未实现 ReAct/工具注册表。 |

## 如何证明 ToolUseEnd 前绝不解析分片参数

核心证明测试是 `ToolUseAccumulatorTest.parserIsNeverInvokedAndArgumentsCannotBeReadBeforeToolUseEnd`（`ToolUseAccumulatorTest.java:84-106`）。测试没有只观察“暂时拿不到参数”，而是注入一个每次实际 JSON 解析都会递增的 `ArgumentsParser`：

1. 第一片仍是半截 JSON 时，`parseCalls == 0`（第 93 行）。
2. End 前调用 `arguments("guarded")` 必须抛 `IllegalStateException`，消息包含 `before ToolUseEnd`（第 94-97 行）；随后再次断言 `parseCalls == 0`，证明早取不会触发投机解析。
3. 第二片到达后，拼接文本已经是合法完整 JSON，但尚未 End，仍断言 `parseCalls == 0`（第 99-100 行）。这排除了“检测到 JSON 看似完整便提前解析”的实现。
4. 输入 `ToolUseEnd` 后，断言解析次数恰为 1（第 101-103 行）；之后多次读取使用已保存结果，次数仍为 1（第 104-105 行）。

生产代码对应的结构性证据是：`append` 仅执行 `StringBuilder.append`（`ToolUseAccumulator.java:44-52`）；全类唯一 `parser.parse` 位于 `complete`（第 63 行）；事件分派只有 `ToolUseEnd` 调用 `complete`（第 112 行）。

## 测试与验收记录

| 命令 | 状态 | 实际结果 |
|---|---|---|
| `MAVEN_OPTS='-Xmx640m' mvn -f agent-core/pom.xml -B -ntp -Dtest='dev.lifeagent.llm.*Test' test` | 已执行，通过 | Tests run: 18, Failures: 0, Errors: 0, Skipped: 0。 |
| `MAVEN_OPTS='-Xmx640m' mvn -f agent-core/pom.xml -B -ntp -Dtest='dev.lifeagent.llm.*Test,dev.lifeagent.mcp.McpClientTest,dev.lifeagent.mcp.JsonRpcCodecTest' test` | 已执行，通过 | 最终版本 Tests run: 32, Failures: 0, Errors: 0, Skipped: 0。包含新增 18 条、McpClient 12 条、JsonRpcCodec 2 条。 |
| `MAVEN_OPTS='-Xmx640m' mvn -f agent-core/pom.xml -B -ntp verify` | 已执行，环境阻塞 | 最终版本发现 46 条：32 条通过，既有 HttpTransport 14 条均在本地 `HttpServer.create` 报 `SocketException: Operation not permitted`；Failures: 0, Errors: 14。没有修改失败测试。 |
| `grep -c '@Test\|@ParameterizedTest' agent-core/src/test/java/dev/lifeagent/mcp/*.java` | 已执行 | 注解计数为 26（12 + 12 + 2）；其中参数化枚举扩展后基线运行数为 28。文件未改。 |
| `MAVEN_OPTS='-Xmx640m' mvn -B -ntp verify` | 已执行，环境阻塞 | 发现 30 条：8 通过、19 环境错误、3 跳过。17 条错误为 Mockito/Byte Buddy 无法 self-attach，2 条为 `SocketException: Operation not permitted`；Failures: 0。 |
| 真实 Anthropic/OpenAI 端点调用 | 未执行 | 任务卡明确不接真实模型端点、不配置 API key。 |
| CI/JDK 8 环境 | 未执行 | 当前执行环境是 JDK 21；最终以管理者/CI 独立验收为准。 |

## 遗留与后续审查

- 完整 `agent-core verify` 和根回归需要在允许 loopback socket、允许 Byte Buddy attach 的验收环境重跑；当前环境错误与本卡代码无调用链关系，但依照诚实要求不标为通过。
- 按 `docs/REVIEW-PROTOCOL.md`，Codex 产物仍需 DSH 做第二遍只读异源复核，随后由管理者做第三遍验收与变异测试。本文件仅代表第一遍自审，不冒充后两遍。

## W2-1R 第 1 轮返工：拒绝尾部 JSON

日期：2026-10-14  
任务：按 `docs/tasks/W2-1R-trailing-json.md` 修复工具参数尾部 JSON 被静默吞掉  
结论：返工代码与两条新增回归测试已落地，变异验证有效；完整验收命令均已执行，但仍被本环境既有的 socket / JVM attach 限制阻塞，未冒充全绿。

### 实现与测试证据

| 要求 | 文件:行 | 测试名 / 结果 |
|---|---|---|
| 默认解析器拒绝尾随 token | `ToolUseAccumulator.java:4,25-30` | 无参构造器创建 `ObjectMapper` 时启用 `DeserializationFeature.FAIL_ON_TRAILING_TOKENS`；解析仍只发生在 `complete` 的第 64 行。 |
| 两个 JSON 分两次 `append` 后必须失败 | `ToolUseAccumulatorTest.java:83-96` | `concatenatedJsonObjectsFailClearlyInsteadOfReturningTheFirstObject`：`{"city":"上"}` + `{"city":"上海"}` 在 End 时抛 `LlmProtocolException`，失败状态不可读取。 |
| Anthropic 非空初始 input 与后续 delta 拼成两个 JSON 必须失败 | `LlmAdaptersTest.java:51-75,247-264` | `anthropicNonEmptyInitialInputFollowedByAnotherJsonObjectFailsClearly`：真实 SSE 事件链经适配器送入累加器，在 `ToolUseEnd` 明确抛 `LlmProtocolException`。 |

### 变异验证

临时把 `ToolUseAccumulator.java:26` 从启用 `FAIL_ON_TRAILING_TOKENS` 改回默认 `new ObjectMapper()`，仅运行两条新增测试。结果：`Tests run: 2, Failures: 2, Errors: 0`，两条都因“预期 `LlmProtocolException`，实际未抛出”变红。随后还原严格配置并用同一命令复跑：`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`，构建成功。当前文件第 26 行已确认是严格配置。

### 返工验收记录

| 命令 | 状态 | 实际结果 |
|---|---|---|
| `MAVEN_OPTS='-Xmx640m' mvn -f agent-core/pom.xml -B -ntp -Dtest=ToolUseAccumulatorTest,LlmAdaptersTest test` | 通过 | Tests run: 16, Failures: 0, Errors: 0, Skipped: 0。包含两条新增测试。 |
| `MAVEN_OPTS='-Xmx640m' mvn -f agent-core/pom.xml -B -ntp -Dtest='dev.lifeagent.llm.*Test' test` | 通过 | Tests run: 20, Failures: 0, Errors: 0, Skipped: 0。返工后的 llm 全套测试全绿。 |
| 临时去掉严格配置后运行两条新增测试 | 预期失败 | Tests run: 2, Failures: 2；两条新增测试均能杀死变异。 |
| 还原严格配置后运行两条新增测试 | 通过 | Tests run: 2, Failures: 0, Errors: 0, Skipped: 0。 |
| `MAVEN_OPTS='-Xmx640m' mvn -f agent-core/pom.xml -B -ntp verify` | 已执行，环境阻塞 | 共发现 48 条；34 通过、0 failure、14 error。新增后的 llm 20/20 全绿；14 个 error 全为既有 `HttpTransportTest` 创建 loopback `HttpServer` 时的 `SocketException: Operation not permitted`。 |
| `MAVEN_OPTS='-Xmx640m' mvn -B -ntp verify` | 已执行，环境阻塞 | Tests run: 30, Failures: 0, Errors: 19, Skipped: 3；17 个 error 为 Mockito/Byte Buddy 无法 self-attach，2 个为 `SocketException: Operation not permitted`。 |

### 范围与遗留

- 本轮只改任务卡允许的 `ToolUseAccumulator.java`、`ToolUseAccumulatorTest.java`、`LlmAdaptersTest.java`、`SELF-REVIEW-W2-1.md`；未改 MCP、依赖、根项目或既有断言。
- 需要在允许 loopback socket 与 JVM agent attach 的验收环境重跑两条完整 `verify`；依照项目协议，仍需 DSH 做异源只读复核。
