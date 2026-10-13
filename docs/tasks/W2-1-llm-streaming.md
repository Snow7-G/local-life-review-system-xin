# 任务卡 W2-1 — agent-core `llm` 模块：统一消息模型 + SSE 流式解析

难度：困难
交给：Codex（`codex exec -m gpt-5.6-sol --sandbox workspace-write`）
理由：流式协议解析 + 分片 JSON 拼装 + 多厂商适配，属「困难：解析器 / 编排」；
手册 §5.2 明确点名这一块是「第一次写时几乎必踩的坑」。

**前置**：P1 全阶段 + W1 升级已完成（HEAD `4f30472`）；`agent-core` 28 条测试全绿、已进 CI。
**依据**：手册 §5.2「模型调用层」+ §3 选型表 + `PROJECT_IMPLEMENTATION_PLAN.md` W2。

## 目标

在 `agent-core` 内新增 `dev.lifeagent.llm` 包，实现：

1. **统一消息模型**（文本 / 工具调用 / 工具结果三种内容块）
2. **SSE 流式解析**（把厂商 SSE 事件流增量地转成统一的事件）
3. **工具调用参数分片拼装**（**本卡最核心的坑**）
4. **两个厂商适配器**：Anthropic 风格（`/v1/messages`）与 OpenAI 兼容风格（`/chat/completions`）
5. **限流/过载重试**（指数退避 + 抖动；参数错误不重试）

**本卡不做**：不写 ReAct 主循环、不做工具注册表、不接真实模型端点（那是 W2-2/W2-3）。

## 只许写这些文件

- `agent-core/pom.xml`（**仅在必须时**；依赖集合仍只允许 Jackson，不得新增第三方库）
- `agent-core/src/main/java/dev/lifeagent/llm/**`（新建）
- `agent-core/src/test/java/dev/lifeagent/llm/**`（新建）
- `agent-core/SELF-REVIEW-W2-1.md`（新建）

**禁止**：改 `dev.lifeagent.mcp.**`（已交付、已进 CI，28 条测试是红线）、改根 `src/**`、
改 `pom.xml`（根）、改 `.github/**`、改 `nginx-1.18.0/**`、新增任何第三方依赖。

## 实现要求

### 1. 统一消息模型（`dev.lifeagent.llm`）

参考手册 §5「llm：统一的消息模型（文本、工具调用、工具结果三种内容块），每家模型一个适配器」。

- `Role` 枚举：`SYSTEM` / `USER` / `ASSISTANT` / `TOOL`
- `ContentBlock` 密封接口，三个实现：
  - `TextBlock(String text)`
  - `ToolUseBlock(String id, String name, JsonNode arguments)`
  - `ToolResultBlock(String toolUseId, JsonNode content, boolean isError)`
- `Message(Role role, List<ContentBlock> content)`
- `LlmRequest(List<Message> messages, List<JsonNode> tools, String systemPrompt, ...)`
  —— `tools` 用 JSON Schema 数组（W2-2 生成，本卡只承载）
- `LlmResponse(List<ContentBlock> content, StopReason stopReason, TokenUsage usage)`
- `StopReason` 枚举至少含 `END_TURN` / `TOOL_USE` / `MAX_TOKENS`
- **不可变**：所有集合字段防御性复制（`List.copyOf`），与既有 `McpClientConfig` 风格一致

### 2. 流式事件（内部事件，非 SSE 事件）

- `LlmStreamEvent` 密封接口，至少含：
  - `TextDelta(String text)`
  - `ToolUseStart(String id, String name)`
  - `ToolUseArgumentsDelta(String id, String partialJson)`  ← **分片，不是完整 JSON**
  - `ToolUseEnd(String id)`                                ← 到此该工具参数才完整可解析
  - `MessageDelta(StopReason stopReason, TokenUsage usage)`
- 适配器把厂商 SSE 字节流转成 `LlmStreamEvent` 流

### 3. ⚠️ 核心：工具调用参数分片拼装（手册 §5.2 点名的坑）

手册原文：「工具调用的参数是一段一段的 JSON 碎片，要攒齐了，等这个内容块结束再整体解析。
**半截 JSON 解析会报错，这是第一次写时几乎必踩的坑**。」

**要求**：
- **在 `ToolUseEnd` 之前，绝不允许解析参数 JSON**。
- 实现一个 `ToolUseAccumulator`：按 tool-use id 累积 `partialJson`，在 `ToolUseEnd` 时
  **一次性整体解析**；解析失败要产生**明确的错误事件/异常**（而不是把半截 JSON 当合法参数交出去）。
- 要能正确处理：参数 JSON 被切成任意多片（含**在字符串字面量中间、在转义序列中间**切分）、
  出现**多个工具调用并发**（两个 id 交错到达）、某个工具调用**没有参数**（参数分片为空）。

**测试要求（必须真跑，覆盖下列每一种）**：
1. 参数 JSON 被切成 2/5/N 片，**含在字符串字面量内部切分**（如 `{"city":"上` + `海"}`）
   与**在转义序列中间切分**（如 `{"q":"a\` + `"b"}`，注意 `\"` 被拆开）
2. **两个工具调用交错到达**（id A、id B 的分片交替），各自拼装正确、不串台
3. **空参数**工具调用（无任何分片）
4. **畸形 JSON**（如 `{"a":` 后直接 End）→ 必须抛出/上报明确错误，**不得静默返回半截结果**
5. **在 `ToolUseEnd` 之前调用取参** → 必须失败（防止误用未完成参数）

### 4. 两个厂商适配器

| 适配器 | 端点风格 | 依据 |
|---|---|---|
| `AnthropicMessagesAdapter` | `/v1/messages`，SSE 事件 `content_block_start` / `content_block_delta` / `content_block_stop` / `message_delta` / `message_stop` | 手册 §5.2 + **Δ7** |
| `OpenAiCompatibleAdapter` | `/chat/completions`，SSE `data:` 行 + `[DONE]`，工具调用走 `choices[].delta.tool_calls[]` | 兼容多数厂商 |

**Δ7（重要，管理者已核实）**：本项目可用的模型网关中，**Claude 系只能走 `/v1/messages`，不走 `/chat/completions`**。
故 Anthropic 适配器是不可省的，不是"将来可选"。

**要求**：
- 适配器实现统一接口（如 `LlmClient` / `LlmAdapter`），对外只暴露 `LlmStreamEvent`，
  **调用方完全不知道背后是哪家**（手册 §5.2「多家模型一个接口」）。
- SSE 解析要点：按行读取；`data:` 前缀；忽略注释行（以 `:` 开头）与空行；
  **`data:` 内容可能是多行拼接**；遇到 `[DONE]` 正常结束。
- **跨 buffer 的 UTF-8 与跨行事件**必须处理正确（既有 `StdioTransport` 的做法可参考）。
- 传输用 **JDK 自带 `java.net.http`**（`HttpClient` + `BodyHandlers.ofLines()` 或等价），
  **不得引入第三方 HTTP 库**。

### 5. 限流与过载重试（手册 §5.2）

- `429` 与「服务过载」类错误：**指数退避 + 随机抖动**重试，可配最大次数与基数。
- **参数错误类（4xx 且非 429）不重试**，直接抛出。
- 重试必须是**可测的**：注入可替换的 `Sleeper`/时钟，使测试不需要真等（**禁止在测试里真 sleep 数秒**）。
- 断言重试次数、退避序列（第 n 次的延迟在下界内且呈指数增长）、以及"参数错误只调用一次"。

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"

# 1. agent-core 全量（既有 28 条 + 本卡新增，必须全绿）
mvn -f agent-core/pom.xml -B -ntp verify

# 2. 既有 MCP 测试必须一条不少（红线）
grep -c "@Test\|@ParameterizedTest" agent-core/src/test/java/dev/lifeagent/mcp/*.java

# 3. 根回归不许被碰坏
mvn -B -ntp verify
```

## 当前测试基线（必须保持）

| 构建 | 基线 |
|---|---|
| `agent-core` | **Tests run: 28, Failures: 0, Errors: 0, Skipped: 0** |
| 根项目 | **Tests run: 30, Failures: 0, Errors: 0, Skipped: 3** |

`agent-core` 只能增加不能减少；**既有 28 条一条不许改、不许删、不许放宽断言**。

## 交付前必须做的三遍审查（见 `docs/REVIEW-PROTOCOL.md`）

1. **自审**：写 `agent-core/SELF-REVIEW-W2-1.md`，逐条记录
   「要求编号 → 实现位置（文件:行）→ 是否达成 → 备注」。
   **单列一节**说明：你怎么证明"分片参数在 ToolUseEnd 前绝不被解析"（引用测试名与断言位置）。
2. 之后由**异源模型（DSH）只读复核**（本卡 Codex 写 → DSH 审）。
3. 最后由管理者独立重跑 + 做一次**变异验证**（管理者会试图破坏分片拼装，看测试是否变红）。

**诚实要求**：没真跑的命令标 `未执行`。若发现既有 28 条中某条因你的改动而失败，停下报告，**不许改它**。

## 明确不做

- 不写 ReAct 主循环 / Hook / 工具注册表（W2-2、W2-3）
- 不接真实模型端点，不配 API key，不发真实网络请求
- 不改 `dev.lifeagent.mcp.**`
- 不 commit、不 push
