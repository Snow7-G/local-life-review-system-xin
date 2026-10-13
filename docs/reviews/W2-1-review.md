<!-- 本文件是 DSH（deepseek-v4.1-flash）对 Codex（gpt-5.6-sol）W2-1 产物的异源只读复核 -->
<!-- 复核者声明：只读；全部构建在 /tmp 副本内进行，仓库未被触碰 -->
<!-- 判定：有条件通过（1 项必须修：Jackson 尾部宽松） -->
<!-- 提取自 /tmp/w2-1-dsh-review.log；EXIT=0 -->

## W2-1 只读审查报告（复核者：DSH；被审对象：Codex 产出）

**审查方式**：全程只读 —— 未修改、未 commit。审查前后 `git diff HEAD --stat` 均为 **0 行**，`git status --porcelain -uall` 恒为 **35 条未跟踪新增**（28 主源码 + 5 测试源码 + `SELF-REVIEW-W2-1.md` + 任务卡）；所有构建/变异实验都在 `/tmp` 的 tar 副本里做，工作区零污染。

**我自己执行的验证**（不采信自评结论）：
1. 隔离副本 `mvn -f agent-core/pom.xml -B -ntp test` → **46/46 全绿**：`ToolUseAccumulatorTest` 7、`SseParserTest` 2、`MessageModelTest` 2、`LlmAdaptersTest` 7（llm 共 18），既有 `McpClientTest` 12、`JsonRpcCodecTest` 2、`HttpTransportTest` 14 = **28**（与任务卡基线数字一致）。
2. **变异测试 3 组**（Baseline 18/18 绿）：把解析提前到 `append` → 红，且恰好命中 `ToolUseAccumulatorTest.java:93`（`expected: <0> but was: <1>`）；把追加改成覆盖 → 红（6 处）；解析失败改静默返回空对象 → 红（`:75`）。→ 核心断言**非空转**。
3. Jackson 宽松性实测：`readTree("{\"a\":1}{\"a\":1}")` → 接受 `{"a":1}`；`{"a":1} trailing` → 接受；`{"a":` → 抛 `JsonEOFException`。

---

### ① 安全（凭据 / 网络 / 依赖 / 资源泄漏）

| 维度 | 结论 | 证据 |
|---|---|---|
| 明文凭据 | **干净** | 主/测源码 grep `api_key\|secret\|password\|bearer\|sk-\|token` 仅命中字段名 `max_tokens`/`input_tokens`/`output_tokens`/`prompt_tokens`/`completion_tokens`。`LlmHttpConfig.java:8` javadoc 明确"敏感 header 由调用方运行时提供"。异常文案不含 header/http body：`AbstractHttpLlmAdapter.java:77-83` 只含状态码，`:174-175` 只含 SSE 事件名；非 2xx 响应体被 drain 后丢弃（`:147-156`），不会把服务端回显（可能含密钥）带进异常/日志。 |
| 网络 | **合规** | 仅 JDK `java.net.http`（`AbstractHttpLlmAdapter.java:11-16`、`:44-85`），无 okhttp/retrofit/slf4j 之类新依赖。endpoint 必须是绝对 http(s)（`LlmHttpConfig.java:34-40`），未开启重定向跟随。测试全部走 `StubHttpClient`，无真实出网、无真实 API key。 |
| 依赖 | **无新增** | `agent-core/pom.xml` 仅 `jackson-databind 2.19.2`(compile) + `junit-jupiter 5.13.4`(test)，且 `git diff HEAD -- agent-core/pom.xml` 为空（本就未改）。 |
| 资源泄漏 | **基本到位** | 成功路径 try-with-resources 关 body 并解析（`:118-124`）；Content-Type 不符与非 2xx 都走 `closeErrorBody`（drain ≤ 8192B 后丢弃，`:112-116`、`:147-156`）；重试前旧 body 已关闭。溢出类：`closeErrorBody` 有 8192B 上限，`TokenUsage.totalTokens()` 用 `Math.addExact`（溢出即抛不静默回绕），`RetryPolicy.saturatedNanos` 兜 `ArithmeticException`。 |

**安全侧需留意（非阻塞）**：
- `LlmHttpConfig` 是 record（`:9-13`），**自动 `toString()` 会打印 headers 的值**（典型即 `x-api-key`/`Authorization`）。一旦 W2-2/W2-3 打日志或把 config 塞进异常上下文，密钥即入日志。建议覆写 `toString()` 脱敏（或改 `char[]`/Supplier）。这是唯一"埋雷"点。
- `HttpRequest.timeout` 只覆盖到"拿到响应对象（响应头）"（JDK 语义），**SSE body 开始读之后没有读超时**；服务端挂起会长期占线程+连接，目前只能靠调用方中断线程。建议 W2-2 在循环层加整体 deadline。
- SSE 单事件无大小上限（恶意/异常长 `data:` 行会撑大 `StringBuilder`），低危。

---

### ② 逻辑正确性（任务卡「实现要求」1–5 逐条，`文件:行`）

**要求 1 统一消息模型（卡 L35-49）— 达成**
`Role.java:4-9`；`ContentBlock.java:4`（sealed `permits TextBlock, ToolUseBlock, ToolResultBlock`）；`TextBlock.java:5`；`ToolUseBlock.java:7`（`arguments()` 返回 `deepCopy` `:15-18`）；`ToolResultBlock.java:7`；`Message.java:6`+`:29`（`List.copyOf`）；`LlmRequest.java:10-16`、`DEFAULT_MAX_TOKENS=4_096` `:18`、两个便捷构造器 `:20-31`、紧凑构造器校验 `:33-46`（model 非空白 / maxTokens≥1 / temperature∈[0,2]）、`tools()` 深拷贝 `:48-60`；`LlmResponse.java:6`+`:87-91`；`StopReason.java:3-9`；`TokenUsage.java:3-14`。风格与既有 `McpClientConfig` 一致（record + 紧凑构造器 + `List.copyOf`）。
防御性复制经实证：`MessageModelTest.java:43-48` 改传入 JsonNode 后仍读出「上海」，`request.tools().add(...)` 抛 `UnsupportedOperationException`。

**要求 2 流式事件（卡 L51-59）— 达成**
`LlmStreamEvent.java:4` sealed，permits 5 个事件：`TextDelta.java:5`、`ToolUseStart.java:5`、`ToolUseArgumentsDelta.java:6`（字段名即 `partialJson`，语义=片段而非完整 JSON）、`ToolUseEnd.java:5`、`MessageDelta.java:4`。全部 record 不可变。卡用词「至少含」→ 未增删必要类型，合规。

**要求 3 「ToolUseEnd 之前绝不解析参数 JSON」（卡 L61-79）— 达成，且为结构性保证**
- 全仓解析调用**唯一一处**：`ToolUseAccumulator.java:63`（`parser.parse(rawJson)`）；另一处 `:29` 只是构造器方法引用 `mapper::readTree`（不触发）。grep `readTree|writeValueAsString|partialJson` 交叉确认：`AbstractHttpLlmAdapter.java:160/166-168` 是适配器解析**SSE 事件 JSON**（不是工具参数），两个适配器 `:103`/`:79` 亦然。
- 到 `:63` 的路径也**唯一**：`complete()`（`:55`）← `accept(ToolUseEnd)` `:112`。上游 `append()` `:44-52` 仅做 `StringBuilder.append`（`:51`），**无任何解析分支、无异常路径通向解析器**。
- 读前必失败：`arguments()` `:91-98` 仅在 `completed` 命中时返回已存结果，否则 `stateFailure`（`:118-130`），pending 时抛 `IllegalStateException("tool arguments are incomplete before ToolUseEnd: " + id)` `:120`。
- 解析失败明确：畸形 → `LlmProtocolException("Invalid complete JSON arguments for tool use " + id, e)` `:71-75` 且记入 `failed`，此后 `arguments()` 抛带 cause 的 ISE `:122-125`；非对象 → `:64-67`；空片段 → `"{}"` `:61`（明确的空对象语义，非半截）。
- 测试 1–5 覆盖（`ToolUseAccumulatorTest.java`）：2 片切在字符串字面量内 `:19-21`；5 片切在**转义序列中间** `:24-26`（`"a` + `\` + `"b`）；任意多单片 `:36-38`；两工具交错不串台 `:48-56`；空参数 `:58-67`；畸形 JSON `:69-81`；End 前取参必失败 + 解析器零调用守护 `:83-106`（`:93`/`:97`/`:100` 断言 0 次，`:103` 断言恰好 1 次，`:105` 断言读完复用不重解析）。
- **反向验证（我做的变异）**：`append` 里插一次解析 → 仅此一处测试变红；覆盖式追加 → 红；静默兜底 → 红。断言有效性成立。

**要求 4 两适配器 + SSE 要点（卡 L81-98）— 达成**
- 统一接口面：`LlmClient.java:6-8` 只有 `void stream(LlmRequest, Consumer<? super LlmStreamEvent>)`；`AbstractHttpLlmAdapter.java:18` 是**包私有抽象类**，钩子 `createRequestBody/validateStreamComplete/handleSseEvent` `:87-96` 包内可见；`SseParser.java:11-12` 明示"provider-specific events never escape this package"；两个适配器的公有面只有构造器 + 继承的 `stream`。→ **调用方确实无需知道背后是哪家**（厂商类型无一出圈）。
- Anthropic：只组装 `/v1/messages` 形态（system 独立字段 `AnthropicMessagesAdapter.java:46-49`/`:75-77`，tool_use/tool_result 块 `:58-71`，tools 仅在非空时下发 `:78-81`），事件映射 `:97-141`，`validateStreamComplete` 要求 `message_stop` `:90-95`。
- OpenAI 兼容：`createRequestBody` 带 `stream_options.include_usage`；`[DONE]` 结束、`finish_reason` 映射 `OpenAiCompatibleAdapter.java:69-106`；`mapStopReason` `:241-249`；tool_calls 按 `(choiceIndex, toolIndex)` 聚合 `:165-191`，并校验 id/name 中途不得改变 `:193-209`。
- SSE 逐条：按行读 `SseParser.java:27`；`data:` 前缀 `:42-47`；**`:` 注释行跳过** `:39-41`；**空行结束事件** `:32-38`；**多行 data 用 `\n` 拼接** `:83-89`+`:50`；无 event 名默认 `message` `:65`；**EOF 补发末事件** `:56`；只含注释/空行不产生事件 `:61-63`；**跨 buffer UTF-8** 用 `BufferedReader`+REPORT 解码器 `:20-23`、BOM 仅首行剥离 `:28-30`；`[DONE]` 由 OpenAI 适配器识别为正常结束。实证：`SseParserTest`（CRLF、两行 data 拼接、逐字节）、`LlmAdaptersTest.java:24-49`（逐字节 UTF-8 + emoji + 工具参数字节级分片）。
- 传输 JDK `java.net.http`：`AbstractHttpLlmAdapter.java:11-16`、`:98-106`（POST + `Accept: text/event-stream`）。
- **弱断言提示（不影响达成）**：`LlmAdaptersTest.java:48` 断言 `uri().getPath() == "/v1/messages"`，但 endpoint 是配置传入（`config()` `:155-161`，两个适配器共用同一 URI），适配器根本不构造路径 → 该断言只是复述配置，**无区分力**；Δ7「只能走 `/v1/messages`」实际靠调用方传 endpoint 保证，适配器既不强制也不默认。建议拆成两个不同 endpoint 或断言厂商特有请求体元素，避免虚假安全感。

**要求 5 429/过载重试（卡 L100-105）— 达成**
- 可注入："`Sleeper` 接口 `Sleeper.java:5-10`（`SYSTEM` 是唯一真实 sleep 实现）+ 适配器构造器同时接受 `Sleeper` 与 `DoubleSupplier randomSource`（`AbstractHttpLlmAdapter.java:26-30`）；退避本身是纯函数 `RetryPolicy.delayForRetry(retryIndex, randomSample)`（显式传随机样本，**比注入 Clock 更彻底**）。
- 指数退避 + 抖动下界：`LlmAdaptersTest.java:122-132` 断言 `(0,0.0)=100ms`、`(0,1.0)=150ms`、`(1,0.0)=200ms`、`(1,1.0)=300ms`、`(2,0.0)=400ms` → 下界指数增长 + 抖动上界可见；`maxDelay` 封顶在 `RetryPolicy` 内实现。
- 429/过载序列 + 次数：`:74-91`（429→529→200，请求数 **3**，`sleeps == [100ms, 200ms]`）；重试集含 429/500/502/503/504/529（`AbstractHttpLlmAdapter.java:142-145`）。
- 参数错误不重试：`:93-107`（400 → `LlmHttpException` 且 `statusCode()==400`、请求数 **1**、`sleeps` 空）。`LlmHttpException.java:3-14` 正确保存状态码。
- **无真实 sleep**：所有用例注入 `duration -> { }` 或 `sleeps::add`；实测 `LlmAdaptersTest` 7 条耗时 0.360s、整个 llm 套件 <1.5s，不存在秒级真 sleep。
- 可配置：`RetryPolicy` record(maxRetries, baseDelay, maxDelay, jitterFactor) + `defaults()=(3, 500ms, 30s, 0.25)`。

---

### ③ 边界条件

**需处理的（按严重度）**
1. **【中】Jackson 宽松解析 ⇒ 尾部多余内容被静默丢弃**（触及要求 3「不得静默返回半截结果」的原则）。实测：`readTree("{\"a\":1}{\"a\":1}")` 返回 `{"a\":1}`，`{"a":1} garbage` 同样被接受（无 `FAIL_ON_TRAILING_TOKENS`）；而 `{"a":` 正确抛错。可达路径真实存在：`AnthropicMessagesAdapter.java:170-173` 在 `content_block_start.input` 非空时会先送一份 JSON 给累加器，随后 `input_json_delta` 再接上 `ToolUseAccumulator.java:51`，两份 JSON 拼在一起 → 静默只取第一份（若第一份只是部分参数，就是"静默返回半截"）。当前 `anthropicWire` 的初始 `input` 为空（`LlmAdaptersTest.java:178-219`），**这条分支零覆盖**。
2. **【中低】OpenAI 适配器硬依赖字面 `[DONE]`**：只有收到 `[DONE]` 才置 done，否则 EOF 抛 `"OpenAI SSE stream ended before [DONE]"`（行为被 `LlmAdaptersTest.java:109-120` 固定）。部分兼容网关在 `finish_reason` 后直接关流不发 `[DONE]` → 会被误判截断。建议：`finish_reason` 已收到且所有 tool call 已闭合时，EOF 亦视为正常结束（或加严格开关）。与卡面不冲突（卡只说 `[DONE]` 属正常结束）。
3. **【低】OpenAI 分片字段苛刻度**：缺 `index` 直接抛（`:170-172`）；续片若回传 `name: ""` 会因 `:201-203` non-blank 校验抛错；`arguments` 先于 id/name 到达抛 `:185-188`。标准 OpenAI 流没问题，接真实端点时值得复验。
4. **【低】Anthropic `error` 事件丢弃厂商诊断信息**：`AnthropicMessagesAdapter.java:138` 只抛固定文案 `"Anthropic SSE stream reported an error"`，`error.type/message` 丢掉 → 线上排障困难。建议拼进异常（不含密钥）。
5. **【低】`MessageDelta` 语义不统一**：Anthropic 一次给全（`:213-234`）；OpenAI 分两次（`finish_reason` 一次 `MessageDelta(stop, null)`，末尾 usage chunk 再一次 `MessageDelta(null, usage)`，见 `LlmAdaptersTest.java:52-72` 期望事件表）→ 调用方必须容忍 null 并自行合并，建议在 `MessageDelta` javadoc 写明"字段可空/可多次"。
6. **【低】未知 stop_reason 静默变 `UNKNOWN`**（`OpenAiCompatibleAdapter.java:241-249` 默认分支；Anthropic 同类），厂商新增原因会被吞掉。
7. **【低】覆盖缺口两处**：(a) `LlmHttpConfig` 的 headers 是否真的写进请求头**无断言**（`AbstractHttpLlmAdapter.java:104` 实现正确，但这是 API key 唯一通路）；(b) `closeErrorBody` 的 drain 上限与提前退出（`message_stop`/`[DONE]` 后 `SseParser.java:33-35` 返回 false）后的连接关闭行为无测试看守（实现正确，靠 `:118` try-with-resources）。

**已正确处理、免争议的边界**：空片段→`{}`；非对象 JSON→明确抛错；重复 `start` 同 id→抛 `IllegalStateException`（`:36-42`）；`accept()` 收到非工具事件→明确 `IllegalArgumentException`（`:113-114`）；解析失败后二次取参→带 cause 的 ISE（`:122-125`）；完成后二次 `complete`→ISE（`:126-128`）；BOM 只在首行剥离；CRLF/注释行/空行/无 event 名/无尾空行的 EOF 事件均已覆盖。

---

### ④ 有无自作主张

| 检查项 | 结论 | 证据 |
|---|---|---|
| 新增第三方依赖 | **无** | `agent-core/pom.xml` 只有 Jackson + JUnit；HTTP 走 JDK；`git diff HEAD -- agent-core/pom.xml` 为空。 |
| 改 `dev.lifeagent.mcp.**` | **无** | `git status` 中 mcp 路径 0 条；`git diff HEAD --stat` 全仓 0 行。 |
| 改根 `src/**` / 根 `pom.xml` / `.github/**` / `nginx-1.18.0/**` | **无** | 同上 diff 为空。根 pom 无 `<module>` 聚合 agent-core，CI 用 JDK 21 单独构建（`.github/workflows/ci.yml:16,33-36`）→ `release 21` 不会破坏 JDK 8 的根回归。 |
| 删改放宽既有 28 条断言 | **无** | tracked 全仓 diff 为 0 行即证据；且我在隔离副本跑出 `McpClientTest` 12 + `JsonRpcCodecTest` 2 + `HttpTransportTest` 14 = **28 全绿**，与卡基线一致。自评称这 14 条在其环境因 `SocketException: Operation not permitted` 报错属环境受限——我这边能建本地 socket，28 条全过，**"纯环境问题"的判断成立，基线未破**。 |
| 文件范围 | **合规** | 新增 28 主源码 + 5 测试源码（全在 `agent-core/src/{main,test}/java/dev/lifeagent/llm/**`）+ `agent-core/SELF-REVIEW-W2-1.md`；无越界文件、无脚本、无 target 提交。 |
| 卡 L142-147「明确不做」 | **遵守** | 无 `loop/tool/hook` 包、无 ReAct/工具注册表；无真实端点、无 API key 配置；未 commit（HEAD 仍为 `4f30472`）。 |
| 实现选择上的自主项 | **合理，不越权** | 新增 `ToolUseAccumulator` 独立 public 类（卡只要求"分片累加"，但 W2-2 主循环正需此物）；用纯函数 + `DoubleSupplier` 达致"可注入时钟"，等效且更强。 |

---

## 结论

**有条件通过**

- 要求 1/2/4/5 逐条达成，证据充分；要求 3 的"End 前不解析"经代码结构核对 + 变异测试双向验证，成立且非空转。
- 唯一 must-fix 落在要求 3 的原则句（"不得静默返回半截结果"）上：解析器对尾部多余内容过于宽松。

**must-fix（1 条，改动小、范围封闭）**

1. **让"尾部多余内容"变成明确失败**：`ToolUseAccumulator.java:63` 使用的解析器需拒绝尾随 token —— 在无参构造器（`:25` 附近的默认 `ObjectMapper`）上启用 `DeserializationFeature.FAIL_ON_TRAILING_TOKENS`（或解析后校验输入已耗尽），并补一条测试：`append("{\"city\":\"上\"}") + append("{\"city\":\"上海\"}")`（两片拼成两份 JSON）必须抛 `LlmProtocolException`，**不得**返回第一份。同时补 Anthropic "`content_block_start.input` 非空 + 后续 `input_json_delta`"（`AnthropicMessagesAdapter.java:170-173`）用例，确认此时是明确报错而非静默截断。

**建议（非阻塞，可在 W2-2/W2-3 前处理）**

- OpenAI 侧放宽"必须见到 `[DONE]`"（③1）；`AnthropicMessagesAdapter.java:138` 保留厂商 `error` 细节（③4）。
- 给 `LlmHttpConfig` 加脱敏 `toString()`；`MessageDelta` 注明字段可空/可多次（①、③5）。
- 补两条断言：headers（API key 通路）真的进了请求头；`LlmAdaptersTest.java:48` 的路径断言改为有区分力的写法（②要求4、③7）。
- W2-2 循环层加整体 deadline，覆盖 SSE body 无读超时（①）。

**自评可信度**：命令日志与实现基本对得上，未发现夸大；两处偏差已记录 —— (a) 自评把既有 28 条中的 14 条记为环境错误，我这边 28 条全过，属环境差异而非缺陷；(b) 自评未提及 Jackson 尾部宽松这条真实缺口，也把它列为"要求 3 全达成"，本报告据此下调为有条件通过。
