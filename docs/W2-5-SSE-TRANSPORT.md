# W2-5 自审 — SSE 传输层（把六类 AgentEvent 推到前端）

> 执行器：Codex CLI。交付范围严格按照 `docs/tasks/W2-5-sse-transport.md` 的「只许写这些文件」：
> `src/main/java/com/hmdp/agent/**`、`src/test/java/com/hmdp/agent/**`、
> `src/main/resources/application.yaml`（仅新增 agent/llm 配置）、本文件。
> `agent-core/**` 一行未改（`git status --short agent-core/` 为空）；`pom.xml`、`.github/**`、
> `nginx-1.18.0/**`、既有 service/entity/controller/dto、既有测试、`docs/REVIEW-PROTOCOL.md` 均未改。
> 未 commit、未 push。
>
> 本文件按 `docs/REVIEW-PROTOCOL.md` 第 1 遍「自审」要求逐条记录
> 「要求 → 实现位置（文件:行）→ 是否达成 → 证据」。**没真跑的命令一律标「未执行」。**

---

## 0. 交付清单

| 文件 | 说明 |
|---|---|
| `src/main/java/com/hmdp/agent/transport/AgentEventStream.java` | 六类事件 → SSE 帧的映射与写出（唯一知道线上事件名的地方） |
| `src/main/java/com/hmdp/agent/transport/AgentConversationController.java` | SSE 端点：`GET /agent/chat/stream`、`POST /agent/chat/resume` |
| `src/main/java/com/hmdp/agent/transport/AgentLoopFactory.java` | 主循环装配缝：把「每请求一个事件 sink」换成「每请求一个 loop」，可被测试替换 |
| `src/main/java/com/hmdp/agent/transport/AgentTransportConfig.java` | Spring 装配：`LlmClient` bean、只读工具注册、`SessionStore`、`AgentEventStream`、controller |
| `src/main/java/com/hmdp/agent/transport/AgentTransportSettings.java` | 非机密默认值（默认模型、默认系统提示）及其回退逻辑 |
| `src/test/java/com/hmdp/agent/transport/AgentConversationSseTest.java` | 8 条：端到端 SSE（正常/挂起/恢复/去重/错误/不泄漏凭据） |
| `src/test/java/com/hmdp/agent/transport/AgentEventStreamTest.java` | 4 条：六类事件名与 payload 形状 |
| `src/test/java/com/hmdp/agent/transport/AgentTransportConfigTest.java` | 4 条：Spring 装配、单例、`application.yaml` 无明文凭据 |
| `src/test/java/com/hmdp/agent/transport/AgentTransportFixtures.java` | 测试夹具：脚本化假 `LlmClient` + 计数用假 `ShopCatalog`（非测试类） |

**本卡新增测试合计 16 条**（8 + 4 + 4），全部真跑通过（见 §4）。未改、未删、未放宽任何既有断言。

---

## 1. 逐条要求核对（对照任务卡）

| # | 要求（任务卡原文/硬约束） | 实现位置 | 达成 | 证据 |
|---|---|---|---|---|
| 1 | 新增 SSE 端点，用 Spring MVC 自带 `SseEmitter`，不引 WebFlux | `AgentConversationController.java:86,116`（`@GetMapping/@PostMapping` 返回 `SseEmitter`） | ✅ | `AgentConversationSseTest` 全部经 MockMvc 真跑；`pom.xml` 未改 |
| 2 | 新增端点必须是只读性质对话端点，不改业务状态；`ManagementRoutesTest` 断言不许改 | `AgentTransportConfig.java:70-88` 只注册 `ShopTools`（三个 `READ` 工具）+ `PermissionGate.denyAll()`；路由只有 `/agent/**` | ✅ | `git status` 未触碰 `ManagementRoutesTest.java`；本卡未新增写路由（§3 说明） |
| 3 | 凭据只走环境变量；key 不得出现在 `application.yaml`/代码/测试 | `application.yaml:33`（`api-key: ${AGENT_LLM_API_KEY:}`）；`AgentTransportConfig.java:70`（`@Value("${hmdp.agent.llm.api-key}")`） | ✅ | `AgentTransportConfigTest.applicationYamlNeverCarriesALiteralCredential`；§4 的 `git diff --cached` grep |
| 4 | 对外输出（含错误消息与日志）不得含 key | `AgentConversationController.java:149-153`（ERROR→写死文案）、`:158-161`（log 只打异常类名）；`AgentEventStream.java:143`（错误帧由调用方给文案） | ✅ | `AgentConversationSseTest.aFailureMessageThatCarriesAKeyIsNeverEchoedOutward`（异常文本内嵌 key，断言帧里没有） |
| 5 | 六类事件一一映射，不漏不合并 | `AgentEventStream.java:90-99`（名）、`:106-135`（payload） | ✅ | `AgentEventStreamTest.everyEventTypeMapsToItsOwnName` + `everyPayloadCarriesTheFieldsTheFrontendNeeds` |
| 6 | `tool_call` 必先于对应 `tool_result`；`answer` 至多一个且在最后 | 事件顺序由 `AgentLoop` 保证；传输层按序写帧 | ✅ | `AgentConversationSseTest.normalTurnEmitsPlanCallResultAnswerInOrder`（顺序断言）+ `resumedToolCallIsEmittedExactlyOnceAcrossBothStreams`（配对） |
| 7 | `WAITING_USER` 时推 `confirm_required` 并结束本次流（不阻塞线程） | `AgentConversationController.java:147-148` → `pushConfirmRequired`（`:171`）→ `complete()`（`:195`，`finally` 里） | ✅ | `AgentConversationSseTest.deferringGateEmitsConfirmRequiredAndEndsTheStream` |
| 8 | 提供恢复端点：携带会话 id 与用户回复，从 `SessionStore` 载入后继续推流 | `AgentConversationController.java:116`（`POST /agent/chat/resume`）→ `sessionStore.load`（`:129`）→ `loop.resume`（`:141`） | ✅ | `AgentConversationSseTest.resumeContinuesFromTheStoredSessionWithoutReRunningFinishedToolCalls` |
| 9 | 主循环只依赖 `LlmClient` 接口；传输层注入配置好的 bean | `AgentTransportConfig.java:70-88`（`llmClient(...)` 造 `AnthropicMessagesAdapter`/`OpenAiCompatibleAdapter`，`AgentLoop` 只收 `LlmClient`） | ✅ | `AgentTransportConfigTest.theLoopFactoryBuildsALoopBoundToTheGivenSink` |
| 10 | 必须能被测试替换 | `AgentLoopFactory.java:17`（函数式接口）+ 测试 `AgentConversationSseTest.readOnlyFactory(ScriptedLlm)` | ✅ | 全部 SSE 测试的 loop 都来自假 `LlmClient`，无远端调用 |
| 11 | 只放开 `application.yaml` 新增 agent/llm 配置项，不改既有配置 | `application.yaml:26-39`（新增 `hmdp.agent.*` 块） | ✅ | `git diff src/main/resources/application.yaml` 仅 +14 行、无删除（§4） |
| 12 | 测试用假 `LlmClient` 脚本化，不得真调远端 | `AgentTransportFixtures.ScriptedLlm`（回放脚本、计数 `streamCalls`） | ✅ | 5 条 SSE 用例内部类断言 `streamCalls`/工具计数，未触网 |
| 13 | 错误路径不中断成 500，以可读错误事件或 `answer` 收尾 | `AgentConversationController.java:149-153`（`ERROR`→错误帧）；工具错误本就由主循环转成 `tool_result(isError=true)` | ✅ | `llmFailureBecomesAReadableErrorFrameNot500`（断言 `status==200` 且只有 `error` 帧）、`toolFailureIsDataAndTheTurnStillEndsWithAnAnswer` |

---

## 2. 单列说明

### 2.1 SSE 事件名映射表

`AgentEvent` 是 `sealed interface`，六个成员就是全部契约；`AgentEventStream.name(AgentEvent)` 用
穷尽 `switch`（Java 21 模式匹配）映射，新增成员会**编译不过**而不是悄悄丢事件。

| `AgentEvent` 实现 | SSE `event:` 名 | `data:` 字段 |
|---|---|---|
| `PlanEvent` | `plan` | `summary:String`、`steps:String[]` |
| `ToolCallEvent` | `tool_call` | `callId:String`、`toolName:String`、`arguments:JSON` |
| `ToolResultEvent` | `tool_result` | `callId:String`、`toolName:String`、`isError:boolean`、`content:JSON` |
| `ThinkingEvent` | `thinking` | `text:String` |
| `ConfirmRequiredEvent` | `confirm_required` | `callId:String`、`toolName:String`、`reason:String` |
| `AnswerEvent` | `answer` | `text:String` |
| （传输层补充，非六类之一） | `error` | `message:String`（只在 `LoopStopReason.ERROR` 或传输层自身失败时出现，用于收尾） |

**两处「传输层补齐」的诚实说明**（agent-core 一行不改，故只能补在传输层）：

1. **`confirm_required`**：主循环在 `suspend()` 里只把会话写进 `SessionStore` 并返回
   `WAITING_USER`，**不构造** `ConfirmRequiredEvent`（`AgentLoop.java:280-286`，且
   `grep ThinkingEvent|ConfirmRequiredEvent agent-core/src/main/java` 只命中定义处）。传输层在
   `WAITING_USER` 时从 store 读回 `pendingPermission`，用它的
   `request.callId()/request.toolName()/question()` 构造 `ConfirmRequiredEvent` 写出。
   **字段语义与事件类契约完全一致**（`ConfirmRequiredEvent(callId, toolName, reason)`）。
2. **`thinking`**：`LlmStreamEvent` 的流式增量在 `AgentLoop` 内被 `Collector` 收敛为最终文本，
   主循环**不产出** `ThinkingEvent`（同样只命中定义处）。本卡测试用假 `LlmClient` 也走同一收敛路径，
   因此**本卡没有可端到端断言 `thinking` 帧的测试**；映射本身由
   `AgentEventStreamTest.everyEventTypeMapsToItsOwnName`/`everyPayloadCarriesTheFieldsTheFrontendNeeds`
   覆盖。真正把流式增量转成 `thinking` 事件需要改 `agent-core`，**本卡明确不做**（契约已定）。

### 2.2 「挂起时为什么不阻塞线程」

三件事共同保证：

1. **主循环本身不阻塞。** `AgentLoop.suspend`（`AgentLoop.java:280-286`）只是：
   `session.suspend(...)` → `sessionStore.save(session)` → `return new LoopResult(..., WAITING_USER, ...)`。
   它**不等待**任何 future、锁、`Condition` 或用户输入，直接把控制权还给调用方。
2. **传输层立刻收流。** 控制器拿到 `WAITING_USER` 后，推一帧 `confirm_required`
   （`AgentConversationController.java:147-148`），随后 `finally` 里执行
   `emitter.complete()`（`:150-155`、`:195`）。`SseEmitter.complete()` 关闭响应并归还 servlet
   请求线程——**线程去等用户**这件事根本不存在，请求在挂起的同一瞬间就结束了。
3. **恢复是另一个请求。** `POST /agent/chat/resume` 从 `SessionStore` 载入会话、`loop.resume(...)`
   重新驱动，是**全新的请求/线程**；旧的那个早已结束。（`AgentConversationController.java:116-142`）

因此「挂起」在本实现里是「**结束本次流 + 把状态留在 store**」，而不是「占着线程等回复」。
人工可复核点：`deferringGateEmitsConfirmRequiredAndEndsTheStream` 里 MockMvc 的异步请求
**能 `getAsyncResult()` 正常返回**（流已 complete）；若实现里有阻塞，这个断言会挂死而非通过。

---

## 3. 只读性与回归红线的说明

- **路由面**：新增的路由只有 `GET /agent/chat/stream` 与 `POST /agent/chat/resume`
  （`AgentConversationController.java:86,116`）。二者都是**对话/流式**端点，既不匹配
  `ManagementRoutesTest` 断言的 `/shop`、`/voucher`、`/upload/blog/delete` 等管理写路由，
  也不进入任何既有 controller。
- **工具面**：`AgentTransportConfig.agentLoopFactory` 只注册 `new ShopTools(catalog)` 的三个
  `Permission.READ` 工具；权限门是 `PermissionGate.denyAll()`（fail-closed）。本卡**没有注册任何
  写工具**、**没有新增写接口**。
- **状态面**：会话只存在 `InMemorySessionStore`（进程内），不写 `hm-dp` 库、不改任何业务状态。
- **既有测试**：`src/test/**` 下既有文件一行未改（`git status` 只显示新增的 `transport/` 目录）。

---

## 4. 真跑命令与结果（原文摘要）

### 4.1 本卡新增测试

```bash
export MAVEN_OPTS="-Xmx640m"
mvn -B -ntp -o test -Dtest='com.hmdp.agent.transport.*Test' -Dsurefire.failIfNoSpecifiedTests=false
# → AgentEventStreamTest                        Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
#   AgentTransportConfigTest                     Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
#   AgentConversationSseTest                     Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
#   Tests run: 16, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

### 4.2 既有 agent 包回归（证明未破坏 W2-4 交付）

```bash
mvn -B -ntp -o test -Dtest='com.hmdp.controller.*Test,com.hmdp.agent.*Test' -Dsurefire.failIfNoSpecifiedTests=false
# → SpringShopCatalogTest$SearchNearby            Tests run: 13, Failures: 0
#   SpringShopCatalogTest$ReadOnly               Tests run:  2, Failures: 0
#   SpringShopCatalogTest$ShopDetailAndVouchers  Tests run:  8, Failures: 0
#   ToolRegistryWiringTest                       Tests run:  4, Failures: 0
#   UploadStorageTest                            Tests run:  1, Failures: 0
#   ManagementRoutesTest                         Tests run:  3, Errors: 3   ← 沙箱限制，见下
#   Tests run: 31, Failures: 0, Errors: 3
```

`ManagementRoutesTest` 的 3 条**全部是 `setUp:33` 报错**，原文：
`IllegalState Could not initialize plugin: interface org.mockito.plugins.MockMaker (alternate: null)`。
这是沙箱**禁 socket / 禁 self-attach** 导致的 Mockito inline mock maker 初始化失败，
**发生在任何被测代码运行之前**，与本卡改动无因果；上游 W2-4 自审已记录同一现象。沙箱外由管理者重跑为准。

### 4.3 未执行（按卡面交管理者在沙箱外执行）

- **未执行**：`mvn -B -ntp verify`（根全量含 Boot 打包）。沙箱禁 socket，既有 `ManagementRoutesTest`
  的 Mockito self-attach 与秒杀/Redis 相关用例必红，非本卡可修复。前几轮执行器在此纠缠导致 EXIT=1 且零产出，
  本轮按卡面说明**不诊断、不重试全量**。
- **未执行**：`mvn -f agent-core/pom.xml -B -ntp verify`。本轮**未改 `agent-core`**
  （`git status --short agent-core/` 为空），按前几轮惯例不在沙箱重跑，由管理者验收。

### 4.4 凭据自查

```bash
git diff --cached | grep -iE 'password|secret|token|api[_-]?key'   # → 无输出（exit 1）；本轮未 add，无暂存内容
grep -rniE 'sk-[a-z0-9]|password|secret' src/main/java/com/hmdp/agent/transport src/test/java/com/hmdp/agent/transport
# → 仅命中 javadoc 里的「non-secret」字样与测试里的正则/假 key 常量（"sk-fake-key-..."、"sk-live-do-not-leak-..."）
```

`application.yaml` 的新增块里，`api-key` 的值是 `${AGENT_LLM_API_KEY:}`（空默认），无明文。

### 4.5 变异验证（证明断言真的会红，非恒真）

对新增实现注入变异、**真跑**对应测试，确认变红后立即回滚：

| 变异 | 破坏点 | 结果（真跑） | 判定 |
|---|---|---|---|
| M1 | `AgentConversationController.pushConfirmRequired` 开头加 `if (true) { return; }`（不再推 `confirm_required`） | `deferringGateEmitsConfirmRequiredAndEndsTheStream` 红：`expected: <[plan, tool_call, confirm_required]> but was: <[plan, tool_call]>` | ✅ 断言有效 |
| M2 | `AgentEventStream.name` 把 `tool_call` 改名成 `tool_result_swapped` | `AgentEventStreamTest` 1 条红（`expected: <tool_call> but was: <tool_result_swapped>`）+ `AgentConversationSseTest` **5 条红**（顺序、配对、去重全被揪出） | ✅ 断言有效 |

两次变异均已回滚（`diff` 与备份一致，`grep tool_result_swapped`/`if (true)` 无命中），回滚后
`com.hmdp.agent.transport.*Test` 重跑 **16 条全绿**。

---

## 5. 需要读者注意的取舍（不隐瞒）

1. **`thinking` 无端到端测试**（§2.1 说明 2）：agent-core 不产出该事件，本卡不许改 agent-core，
   故只能测映射、不能测「主循环驱动出 thinking」。**未做，非缺陷**，登记为契约缺口。
2. **`AgentTransportConfigTest` 用 `AnnotationConfigApplicationContext` 而非 `@SpringBootTest`**：
   根项目没有测试库/Redis，本卡也不许新增测试基础设施，故用最小组装验证 bean 定义与单例语义；
   真正的 HTTP 行为由 `AgentConversationSseTest` 的 MockMvc 覆盖。
3. **测试里注入 `CharacterEncodingFilter`**：`MockMvcBuilders.standaloneSetup` 不带 Boot 生产环境
   注册的 UTF-8 编码过滤器，缺它会强制 ISO-8859-1、把中文 payload 变成 `?`。该过滤器只加在测试
   MockMvc 上，**未改生产配置**；`AgentEventStream` 也显式给每个 `data:` 打上 UTF-8
   `MediaType`（`AgentEventStream.java:47,78,158`）。
4. **`error` 是第 7 种帧、不是六类之一**：仅在 `LoopStopReason.ERROR`（主循环不产 `answer`）或传输层
   自身异常时出现，用于给出可读收尾，避免 500。文案写死、不含异常原文（§1 要求 4）。

---

## 6. 结论

- 六类事件 → SSE 帧：**一一映射，无漏无合并**（`AgentEventStreamTest`）。
- 正常对话顺序、`tool_call`→`tool_result` 配对、`answer` 收尾：**已断言**（`AgentConversationSseTest`）。
- 挂起推 `confirm_required` 且**流结束不阻塞**、会话已存：**已断言**。
- 恢复从 store 继续、已完成工具调用**不重复执行**（计数证明）：**已断言**。
- 错误路径**不 500**、以 `error`/`answer` 收尾：**已断言**。
- 凭据不泄漏（含异常文本内嵌 key 的极端情形）：**已断言**。
- 新增测试 **16 条全绿**；既有 agent 包测试全绿；`ManagementRoutesTest` 的 3 条红是沙箱 Mockito
  self-attach 限制（`setUp` 即失败），**非本卡因果**，由管理者沙箱外复核。
- **未 commit、未 push。**
