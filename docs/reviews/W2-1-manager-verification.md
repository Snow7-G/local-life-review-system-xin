# W2-1 管理者验收记录（含 W2-1R 返工）

> 由管理者（Hermes）撰写。三遍审查链：Codex 自审 → DSH 异源只读复核 → 管理者独立验收。
> 相关文件：`agent-core/SELF-REVIEW-W2-1.md`、`docs/reviews/W2-1-review.md`、`docs/reviews/W2-1R-review.md`。

## 1. 交付内容

`agent-core` 新增 `dev.lifeagent.llm` 包（33 个实现类 + 5 个测试类）：

| 块 | 内容 |
|---|---|
| 统一消息模型 | `Role`、`ContentBlock`（`TextBlock`/`ToolUseBlock`/`ToolResultBlock`）、`Message`、`LlmRequest`、`LlmResponse`、`StopReason`、`TokenUsage`；全部不可变（`List.copyOf`） |
| 流式事件 | `LlmStreamEvent`（`TextDelta`/`ToolUseStart`/`ToolUseArgumentsDelta`/`ToolUseEnd`/`MessageDelta`） |
| 工具参数拼装 | `ToolUseAccumulator` —— **手册 §5.2 点名的核心坑** |
| 厂商适配 | `AnthropicMessagesAdapter`（`/v1/messages`）、`OpenAiCompatibleAdapter`（`/chat/completions`）、`AbstractHttpLlmAdapter`、`SseParser` |
| 重试 | `RetryPolicy` + `Sleeper`（**可注入时钟，测试不真 sleep**） |

## 2. 手册 §5.2 那个坑：做成了可验证的硬指标

手册原文：「工具调用的参数是一段一段的 JSON 碎片，要攒齐了，等这个内容块结束再整体解析。
半截 JSON 解析会报错，**这是第一次写时几乎必踩的坑**。」

覆盖的 9 条测试场景：

| 场景 | 测试 |
|---|---|
| 分片切在**字符串字面量中间** | `parsesTwoFragmentsSplitInsideAStringLiteral` |
| 分片切在**转义序列中间** | `parsesFiveFragmentsIncludingASplitEscapeSequence` |
| 任意多单片 | `parsesArbitrarilyManySingleCharacterFragments` |
| **多工具调用交错**不串台 | `interleavedToolCallsNeverMixTheirFragments` |
| 空参数 | `noFragmentsMeansAnEmptyObjectAtToolUseEnd` |
| 畸形 JSON 必须明确失败 | `malformedCompleteJsonFailsClearlyAndNeverReturnsAPartialValue` |
| **尾随 JSON 不得静默吞掉**（W2-1R 新增） | `concatenatedJsonObjectsFailClearlyInsteadOfReturningTheFirstObject` |
| **End 前取参必须失败** | `parserIsNeverInvokedAndArgumentsCannotBeReadBeforeToolUseEnd` |
| **注入宽松 mapper 也不得放宽**（管理者新增） | `injectedLaxMapperStillRejectsTrailingContent` |

## 3. 异源复核抓到的问题及处置

### 3.1 W2-1 复核（DSH 审）判「有条件通过」，1 项 must-fix

**缺陷**：`ToolUseAccumulator` 默认 Jackson 对**尾随 token 宽容**。
若两片碎片拼出两份 JSON，会**静默返回第一份**，违反任务卡「解析失败必须明确报错、不得静默返回半截结果」。
复核指出自审把它列为「要求 3 全达成」，属**未披露的真实缺口**。

**管理者独立复现**（用 agent-core 精确 classpath）：

```
DEFAULT ObjectMapper.readTree("{\"city\":\"上海\"}{\"city\":\"北京\"}")
  → 静默返回 {"city":"上海"}        ❌
启用 FAIL_ON_TRAILING_TOKENS
  → MismatchedInputException        ✅
```

**处置**：按流程**打回原执行器 Codex**（W2-1R，返工第 1 轮），未换模型顶替。
返工内容：默认构造器启用 `FAIL_ON_TRAILING_TOKENS` + 补两条测试
（accumulator 双文档、Anthropic `input` 非空 + `input_json_delta` 双文档）。

### 3.2 W2-1R 复审（DSH 审）判「通过 —— 无 must-fix」

复核者独立复现变异：撤掉严格标志后**恰好 2 条**新测试变红，
且变异体真实行为是 `RETURNED {"city":"上"}`（静默返回），证明断言与缺陷一一对应、非空转。
复核者还自做 M1/M2/M3 三个变异，确认既有断言未被削弱。

### 3.3 复核提出的**潜伏缺口**——管理者当场修掉（未留到 W2-2）

复核指出：公开的 `ToolUseAccumulator(ObjectMapper)` 构造器若被传入**宽松 mapper**，
会**重新引入刚修掉的缺陷**。复核判为「非阻塞、但 W2-2 复用共享 mapper 前必须先修」，
依据是「全仓 9 处调用点全部在测试里，且无人用该构造器」。

**管理者决策：立即修，不留雷。** 理由：W2-2 的循环层极可能复用
`AbstractHttpLlmAdapter` 里那个宽松 mapper（复核亦点名此风险），
而这是一行改动的事——留着就是明知会踩的坑。

修法：把严格性做进类内部，不再依赖调用方传入的 mapper 是否严格
（`withStrictParsing(...)`，`ToolUseAccumulator.java:29-42`）。
并**补一条针对性测试**（`injectedLaxMapperStillRejectsTrailingContent`），
避免我自己犯「改了没测」的错。

**管理者变异验证**：撤掉 `withStrictParsing` 的严格化 →
`injectedLaxMapperStillRejectsTrailingContent:155` 精确变红
（`Expected LlmProtocolException to be thrown, but nothing was thrown`）；已还原。

## 4. 最终验收证据（管理者独立执行）

### 4.1 测试

```
$ MAVEN_OPTS="-Xmx640m" mvn -f agent-core/pom.xml -B -ntp verify
[INFO] Tests run: 12 ... dev.lifeagent.mcp.McpClientTest
[INFO] Tests run:  2 ... dev.lifeagent.mcp.JsonRpcCodecTest
[INFO] Tests run: 14 ... dev.lifeagent.mcp.HttpTransportTest
[INFO] Tests run:  2 ... dev.lifeagent.llm.MessageModelTest
[INFO] Tests run:  9 ... dev.lifeagent.llm.ToolUseAccumulatorTest
[INFO] Tests run:  8 ... dev.lifeagent.llm.LlmAdaptersTest
[INFO] Tests run:  2 ... dev.lifeagent.llm.SseParserTest
[INFO] Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

演进：28（P1）→ 46（W2-1）→ 48（W2-1R）→ **49**（管理者补 mapper 注入用例）。

### 4.2 独立探针（真实 classpath，非测试框架）

```
默认构造器        -> ✅ LlmProtocolException
宽松 mapper 注入   -> ✅ LlmProtocolException      （修复前：静默返回第一份）
正常分片合成       -> {"city":"上海"}               （未误伤正常路径）
两个 JSON 拼接     -> ✅ LlmProtocolException
```

### 4.3 硬边界核验

| 检查 | 结果 |
|---|---|
| `git diff agent-core/src/main/java/dev/lifeagent/mcp/` | 空 ✅（MCP 包一行未动） |
| `git diff agent-core/src/test/java/dev/lifeagent/mcp/` | 空 ✅（既有 28 条断言未动） |
| `git diff agent-core/pom.xml` 的依赖变化 | 无 ✅（零新第三方依赖） |
| 根 `pom.xml` / `src/**` / `.github/**` / `nginx-1.18.0/**` | 未动 ✅ |
| 根回归 `mvn -B -ntp verify` | 见 §4.4 |

### 4.4 根回归

`Tests run: 30, Failures: 0, Errors: 0, Skipped: 3` / `BUILD SUCCESS`（27 个真实用例通过）

## 5. 留给 W2-2 的非阻塞项（复核建议，采纳并入下一卡前置）

1. **OpenAI 侧放宽「必须见到 `[DONE]`」** —— 部分网关不发 `[DONE]`（`OpenAiCompatibleAdapter`）
2. `AnthropicMessagesAdapter` 保留厂商 `error` 细节，便于排障
3. `LlmHttpConfig` 加**脱敏 `toString()`**（避免日志泄漏 key 通路）
4. 补断言：headers（API key 通路）真的进了请求头；`LlmAdaptersTest:48` 的路径断言改为有区分力的写法
5. SSE body **无读超时** → W2-2 循环层加**整体 deadline**（复核点出的真实风险）
6. `MessageDelta` 注明字段可空/可多次

## 6. 结论

**W2-1 验收通过。** 三遍审查完整：Codex 自审 → DSH 异源复核（判有条件通过、
抓出自审未披露的真实缺口）→ 打回返工（第 1 轮）→ DSH 复审（通过、无 must-fix）
→ 管理者独立验收（含 2 处变异验证 + 独立探针）+ 主动修掉复核点出的潜伏缺口。

`agent-core` 49 条全绿，MCP 包零改动，零新依赖，根回归无回退。
