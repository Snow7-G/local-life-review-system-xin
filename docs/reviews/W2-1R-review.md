<!-- 本文件是 DSH（deepseek-v4.1-flash）对 Codex W2-1R 返工的异源只读复审 -->
<!-- 复核者声明：只读，未向仓库写入任何内容 -->
<!-- 判定：通过 —— 无 must-fix（上轮唯一 must-fix 已修复并经验证） -->
<!-- 提取自 /tmp/w2-1r-dsh-review.log -->

# W2-1R 返工只读复审报告（复核者：DSH；被审对象：Codex 对 W2-1 的返工）

**结论：通过 —— 无 must-fix。**

复核方式全程只读：未修改任何文件、未 commit、未 push。所有构建与变异实验都在 `/tmp` 的 tar 副本内进行。审查收尾时：`git diff HEAD | sha1sum` → `da39a3ee…`（空 diff），`git status --porcelain -uall | sha1sum` → `9ea6d808…`（34 条未跟踪新增 = 28 主源码 + 5 测试源码 + `SELF-REVIEW-W2-1.md`），`HEAD` 仍为 `1b8c749 [W2-1-0] 出 LLM 流式层任务卡并记录异源复核 must-fix`。仓库零污染，可复核。

---

## 一、我自己执行的验证（不采信自评）

| # | 验证 | 命令 | 结果 |
|---|---|---|---|
| 1 | agent-core 全量 | `MAVEN_OPTS="-Xmx640m" mvn -f agent-core/pom.xml -B -ntp verify`（`/tmp` 副本） | `FINAL_EXIT=0`，**Tests run: 48, Failures: 0, Errors: 0, Skipped: 0**，`BUILD SUCCESS` |
| 2 | 逐类计数 | 同上日志 | llm：`ToolUseAccumulatorTest` 8、`LlmAdaptersTest` 8、`SseParserTest` 2、`MessageModelTest` 2 = **20**；MCP：`McpClientTest` 12、`JsonRpcCodecTest` 2、`HttpTransportTest` 14 = **28**（红线未动） |
| 3 | 默认构造器严格性（三路探针，`/tmp/w21r` 临时测试） | 见下 | `noarg => THREW LlmProtocolException`；`external-lax => RETURNED {"city":"上"}`；`external-strict => THREW LlmProtocolException` |
| 4 | 变异 ①（去掉 `FAIL_ON_TRAILING_TOKENS`） | `this(new ObjectMapper())` | `MUTANT_MVN_EXIT=1`，`Tests run: 17, Failures: 2`：`LlmAdaptersTest…:59` 与 `ToolUseAccumulatorTest…:90` 均 “Expected LlmProtocolException to be thrown, but nothing was thrown.”；**变异体探针 `noarg => RETURNED {"city":"上"}`**（正是被禁止的静默截断）；恢复后 `RESTORED-IDENTICAL`、`RESTORED_MVN_EXIT=0`、16/16 绿 |
| 5 | 变异 M1（把解析提前到 `append`） | `try { parser.parse(partialJson); } catch (IOException ignored) {}` | 红：`ToolUseAccumulatorTest.parserIsNeverInvoked…:108  a partial fragment must never reach the JSON parser ==> expected: <0> but was: <1>` |
| 6 | 变异 M2（`append` 改成覆盖） | `setLength(0)` 后再 append | 红（8 项）：2 failures + 6 errors，覆盖 `parsesTwoFragments…`、`parsesFiveFragments…`、`parsesArbitrarilyMany…`、`interleavedToolCalls…`、`anthropicAdapterEmitsOnlyUnifiedEvents…` 等 |
| 7 | 变异 M3（解析失败静默返回空对象） | catch 里改成 `completed.put(id, 空对象); return` | 红：`ToolUseAccumulatorTest.malformedCompleteJsonFailsClearlyAndNeverReturnsAPartialValue:75`（既有守门测试）、`:90`、`LlmAdaptersTest:59` |
| 8 | SSE body 读超时（JDK 21，`com.sun.net.httpserver` 桩：发头 + 1 个 `data:` 后挂起 6s，`HttpRequest.timeout(2s)`） | `/tmp/tp/ZzTimeoutProbe.java` | `HEADERS at 349ms status=200` → `LINE at 364ms` → **下一次 `LINE at 6317ms`**、`EOF at 6320ms`：2s 的 request timeout **没有**中断 body 读取 |
| 9 | 根工程回归（另一次验证，非本卡必跑） | `MAVEN_OPTS="-Xmx640m" mvn -B -ntp verify`（`/tmp` 副本） | `ROOT_EXIT=0`，**Tests run: 30, Failures: 0, Errors: 0, Skipped: 3**，`BUILD SUCCESS` |
| 10 | 候选修复一行式（仅验证建议，未落地） | `ZzFixProbe`（jackson 2.19.2 jars） | `readerFor+with(flag)` → THREW `MismatchedInputException: Trailing token…`；`copy+enable(flag)` → THREW 同上；**调用方原 mapper 仍宽松**（`caller-mapper-after => RETURNED {"city":"上"}`） |

---

## 二、返工四项逐条判定

| 项 | 判定 | 证据 |
|---|---|---|
| ① 默认构造器启用严格尾部检查（或等价耗尽校验） | **达成** | `ToolUseAccumulator.java:25-27`：`public ToolUseAccumulator() { this(new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)); }`。探针实测 `{...}{...}` → `LlmProtocolException`，不再是 `{"city":"上"}`。全仓解析调用仍唯一一处（`:63`），失败路径 `:72-77` 抛 `LlmProtocolException("Invalid complete JSON arguments for tool use " + id, e)` 并写入 `failed`，不返回半截值。 |
| ② 新测试：两片 `append` 各成完整 JSON → `complete` 必须抛且不得返回第一份 | **达成，且非空转** | `ToolUseAccumulatorTest.java:84-96`：`append("{\"city\":\"上\"}")` + `append("{\"city\":\"上海\"}")` → `:90 assertThrows(LlmProtocolException.class, …)`，` :93` 断言报文含 `trailing`、`:94` 断言含 `Invalid complete JSON`、`:95 assertThrows(IllegalStateException.class, () -> arguments("trailing"))`（证明没有把任何值存下来供后续读取）。变异①下该测试**恰好变红**且变异体真实行为是 `RETURNED {"city":"上"}` —— 断言与缺陷一一对应。 |
| ③ 新 Anthropic 用例：`content_block_start.input` 非空 + 后续 `input_json_delta` 两文档必须明确失败 | **达成，且走真实线路** | 构造于 `LlmAdaptersTest.java:247-264`（`toolStart` 带 `input.city=上`，第 254 行；随后 `anthropicToolDelta(0,"{\"city\":\"上海\"}")`，第 255 行），断言在 `:52-75`：`:59` 捕获 `LlmProtocolException`、`:69-71` 逐字断言 `"Invalid complete JSON arguments for tool use tool-trailing"`、`:72-74` 断言 `arguments("tool-trailing")` 抛 `IllegalStateException`。两文档不是测试手搓进 accumulator，而是由 `AnthropicMessagesAdapter.java:170-172` 把非空 `input` 当片段下发的**真实产品路径**产生。变异①下 `:59` 变红。 |
| ④ 46 条不得减少 | **达成（46 → 48）** | 本轮 48/48 全绿；llm 20（原 18）+ MCP 28（原 28）。逐类计数见上表 #2。 |

---

## 三、四个独立提问的判断

### (a) 严格配置是否真在默认构造路径上？接受外部 `ObjectMapper` 的重载是否仍是宽松的，算不算缺口？

**判断：默认路径确实是严格的（满足本卡）；公开的 mapper 注入构造器确实仍是宽松的，是“已定位的潜在缺口”，但本轮不构成 must-fix。**

- 默认路径严格：源码 `:25-27` + 变异实验双重证据（撤掉标志后默认构造器立刻退回 `RETURNED {"city":"上"}`，两条新测试同时变红）。
- 注入路径宽松：`ToolUseAccumulator.java:29-31` 只做 `this(Objects.requireNonNull(mapper, "mapper")::readTree)`，不加任何严格性；探针实测 `new ToolUseAccumulator(new ObjectMapper())` **静默返回第一份文档**。`AbstractHttpLlmAdapter.java:41` 的 `protected final ObjectMapper mapper` 就是 `new ObjectMapper()`（同样宽松），W2-2 一旦把共享 mapper 传进来就命中。
- 为什么仍判非阻塞：① 本卡把 must-fix 明确限定在“无参构造器（默认 `ObjectMapper`）上启用 `FAIL_ON_TRAILING_TOKENS`”，该项已完成；② 全仓 grep `new ToolUseAccumulator` 共 9 处，**全部**在测试里，且只用无参构造器或包私有 `ArgumentsParser` 构造器（`:33-35` 是测试计量解析次数的接缝）——公开 mapper 构造器目前零调用点，今天没有任何运行路径能触发。所以这是“W2-2 首次复用共享 mapper 之前必须先修”的前置项，而非 W2-1R 的验收失败。
- 建议的最小改法（我已实测两种都有效且**不污染调用方 mapper**）：`this(Objects.requireNonNull(mapper, "mapper").copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)::readTree);`，或在类 javadoc 补一句严格性契约（现在 `:14-17` 的 javadoc 只承诺“只在 ToolUseEnd 解析一次”，没承诺“尾部多余内容必须失败”）。

### (b) 两条新测试是否真覆盖那两个场景？断言是否非空转（断言“不是第一份文档”而非“抛了个异常”）？

**判断：真覆盖、非空转，且比“抛异常”更强。**

- 非空转的硬证据：在宽松 mapper 下 `readTree("{...}{...}")` 会**正常返回**第一份文档、什么都不抛（变异体探针：`RETURNED {"city":"上"}`；直接 Jackson 探针：`raw-lenient => RETURNED {"city":"上"}`）。因此 `assertThrows` 本身就对“静默返回第一份”这一缺陷敏感，不是永真断言；变异①下两条新测试**恰好**变红，变异②③下也持续报警。
- 断言不止于“抛了异常”：两条测试都追加了语义断言 —— 异常报文必须含工具 id 与 `Invalid complete JSON`（`ToolUseAccumulatorTest:93-94`、`LlmAdaptersTest:69-71` 逐字断言），并断言事后 `arguments(id)` 抛 `IllegalStateException`（`ToolUseAccumulatorTest:95`、`LlmAdaptersTest:72-74`），也就是把“不得返回/留存半截结果”从返回值与状态两个方向都钉住了。
- 覆盖边界（不影响判定）：`assertThrows` 严格来说是“不得正常返回”，若实现改成“合并两份文档后返回”也会被判红——这比要求更严，方向上正确。

### (c) 其余五条建议的“非阻塞、留到 W2-2/W2-3”分类，确认还是推翻？

**判断：五条全部确认为非阻塞；其中 2 条我要上调优先级并补一个更准确的定性，但没有一条构成本轮缺陷。**

| 项 | 我的定性 | 依据 |
|---|---|---|
| OpenAI 侧“必须见到 `[DONE]`”偏严 | 确认非阻塞（保留） | 方向上倒向**响亮失败**而不是静默截断，与卡的原则一致；`LlmAdaptersTest:136 truncatedStreamsFailInsteadOfLookingLikeSuccessfulCompletion` 已守住这条语义。风险是真实厂商在最后一帧后直接关流时会被判失败——属 W2-3 真实端点冒烟必须验证的第一项，非本卡缺陷。 |
| `LlmHttpConfig` 脱敏 `toString()` | 确认非阻塞（硬化） | record 无自定义 `toString()`（`LlmHttpConfig.java:9-13`，自动实现会打出 headers 值）；但 llm 主源码 grep `System.out\|Logger\|log.` 命中 **0**，当前没有任何日志/异常上下文会打印它。属于“埋雷”，未引爆。 |
| headers 断言（API key 真的进了请求头） | 确认非阻塞（**覆盖缺口**，不是缺陷） | llm 测试里唯一与 headers 相关的只有 `StubHttpClient.java:151` 自己的访问器，没有测试断言 `config.headers()` 落到 `HttpRequest`；实现本身正确（`AbstractHttpLlmAdapter.java:98-106` 逐条设置）。这是“若错则必然在真实端点认证失败”的廉价保险，建议 W2-3 前补一条。 |
| Anthropic `error` 详情不保留 | 确认非阻塞（诊断性），但**补一条功能提醒** | `AnthropicMessagesAdapter.java:138` 只抛 `"Anthropic SSE stream reported an error"`。诊断性丢失是小问题；更需要 W2-2 注意的是：SSE 内 `overloaded_error` 事件发生在 HTTP 200 流里，**不会**经过 `:1xx` 那套按 HTTP 状态码分类的重试通道，W2-2 的循环/重试策略必须自己处理，否则“过载退避”在流内错误上失效。这仍不是 W2-1 卡内要求（卡 §5 只针对 429/过载的 HTTP 状态）。 |
| SSE body 无读超时 | 确认非阻塞，但**列为五条中最接近缺陷的一条**，W2-2 不得绕过 | 我实测：`HttpRequest.timeout(2s)` 在响应头到达后**不再约束 body 读取**——服务端挂起 6s，读取线程就一直阻塞到 6317ms 才拿到下一行。即 `config.requestTimeout()`（默认 2 分钟）既不是整体截止时间、也不是空闲读超时，一个“握手成功然后装死”的 SSE 服务端会长期占住线程与连接。卡没有要求空闲超时，故不算本卡未达标；但 W2-2 若在没有 deadline 的情况下把流式调用放进 Agent 循环，这就是真实可用性缺陷。修法应是**空闲读超时/循环层 deadline**，而不是给 SSE 加整体 body deadline。 |

### (d) 既有 28 条 MCP 测试与 46 条 llm 测试是否被削弱？

**判断：没有削弱。既有守门断言原样保留且仍然致命。**

- MCP 28 条：4 个测试文件（`FakeMcpServer.java`、`HttpTransportTest.java`、`JsonRpcCodecTest.java`、`McpClientTest.java`）在 `git diff HEAD` 中零改动，本轮 12+2+14 = 28 全绿。
- llm 既有测试：数量只增不减（18 → 20），新增的两条独立方法只增不改；关键守门测试 `parserIsNeverInvokedAndArgumentsCannotBeReadBeforeToolUseEnd` 的五条断言（`:108`、`:112` 的 `parseCalls` 期望 0/0/0/1/1）与既有报文文案逐字保留。
- “未被削弱”的正面证据是**变异杀伤力**：M1（解析提前到 append）→ `:108 a partial fragment must never reach the JSON parser ==> expected: <0> but was: <1>`；M2（append 变覆盖）→ 8 项红；M3（解析失败静默返回空对象）→ 既有 `malformedCompleteJson…:75` 红。断言若被放宽，这些变异体就会存活，实测没有。
- 硬边界：`git diff HEAD` 为空（`da39a3ee…`），即仓库内**任何**已跟踪文件（含断言）本轮零改动，不存在“改断言让测试变绿”的通道。

---

## 四、自评可信度与遗留项

- 回工卡要求“临时移除 `FAIL_ON_TRAILING_TOKENS` → 至少 1 条新测试变红 → 恢复后全绿”，我独立复现：变异红 2 条、恢复后 16/16 与 48/48 全绿，与自评一致。
- 自评记录的两处**环境差异**得到解释：它记 agent-core 有 14 条 `SocketException: Operation not permitted`、根工程 19 条错误；我这边 agent-core 48/48 全绿、根工程 30/0/0/3 `BUILD SUCCESS`。属沙箱网络/自附加权限差异，非代码差异（与本轮改动无关，因为 diff 为空）。
- 一处事实更正：`agent-core` **不在**根 `hm-dianping` 反应堆里（根 `pom.xml` 无 `<modules>`，`agent-core` 自带 `0.1.0-SNAPSHOT` 顶层 pom），两者是彼此独立的构建；本卡验收命令把两者分列是正确的。此外 `SELF-REVIEW-W2-1.md` 的“统计”栏把根工程计入同一反应堆的说法应按此校正（文档口径问题，不影响代码判定）。
- 遗留（全部非阻塞，建议顺序）：① W2-2 接共享 `ObjectMapper` 前修 `ToolUseAccumulator(ObjectMapper)` 严格性（附已验证一行式）；② W2-2 循环层加空闲读超时/deadline；③ W2-2 处理流内 `overloaded_error` 的重试分类；④ W2-3 前补 headers 断言；⑤ `LlmHttpConfig.toString()` 脱敏、`[DONE]` 缺失容忍度、Anthropic error 详情保留。

**最终判定：通过 —— 无 must-fix。** 本轮未向 `/root/projects/llrs-audit` 写入任何内容，未产生 commit；等待授权后可由 Codex 进入 W2-2（建议先办遗留 ①）。
