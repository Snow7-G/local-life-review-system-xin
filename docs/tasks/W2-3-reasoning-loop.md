# 任务卡 W2-3 — ReAct 主循环 + 四挂点 Hook + 事件流

难度：**困难**（架构级：主循环是面试重点，边界多）
交给：Codex（`codex exec -m deepseek/deepseek-v4.1-flash --sandbox workspace-write`）
理由：命中「架构调整 / 编排」规则——主循环是 agent-core 的心脏，含步数/预算/并行/熔断/挂起多个边界，
且要与既有 `llm`、`tool` 两包对接。

**前置**：W2-1（`c4e7100`）`llm` 包、W2-2（`572669f`）`tool` 包均已交付并复核通过。
**依据**：手册 §5「自研 Agent 框架」、§5.1「主循环」、§5.4「流式事件」、§4.3「内部服务 vs 模型可见工具」。

## 目标

在 `agent-core` 内新增三个包，**不依赖 Spring、不依赖根项目、不碰 Redis**：

1. `dev.lifeagent.hook` —— 四挂点 + `HookResult`
2. `dev.lifeagent.loop` —— ReAct 主循环（步数/预算/并行/错误熔断/权限闸门）
3. `dev.lifeagent.event` —— SSE 事件类型（`plan`/`tool_call`/`tool_result`/`thinking`/`confirm_required`/`answer`）

## 只许写这些文件

- `agent-core/src/main/java/dev/lifeagent/hook/**`
- `agent-core/src/main/java/dev/lifeagent/loop/**`
- `agent-core/src/main/java/dev/lifeagent/event/**`
- `agent-core/src/test/java/dev/lifeagent/hook/**`
- `agent-core/src/test/java/dev/lifeagent/loop/**`
- `agent-core/src/test/java/dev/lifeagent/event/**`
- `agent-core/SELF-REVIEW-W2-3.md`（新建）

**禁止**：改 `dev.lifeagent.mcp.**`、`dev.lifeagent.llm.**`、`dev.lifeagent.tool.**`、
改根 `src/**`、改任何 `pom.xml`、改 `.github/**`、新增任何第三方依赖。
**若既有 115 条中某条因你的改动失败，停下报告，不许改它。**

## 实现要求

### 1. `hook` —— 四挂点（手册 §5）

四个挂点，顺序固定，护栏都挂这里：

| 挂点 | 时机 | 可否拦截 |
|---|---|---|
| `PreModelHook.beforeModel(TurnContext)` | 调模型**前** | 可 `block`（如注入检测） |
| `PostModelHook.afterModel(TurnContext, AssistantTurn)` | 调模型**后**、执行工具**前** | 可 `block` |
| `PreToolHook.beforeTool(TurnContext, ToolCall)` | 工具执行**前** | 可 `block` / 可改写入参 |
| `PostToolHook.afterTool(TurnContext, ToolCall, ToolOutcome)` | 工具执行**后** | 只能观察，**不可改结果** |

要求：
- `HookResult`：`pass()` / `block(String reason)`（手册 §4.3 风格），另可带"提示语追加"（`inject(String note)`）
- `Hook` 基接口；四个子接口分别继承。**挂点顺序 = 注册顺序**，须有测试证明顺序稳定
- `TurnContext` 至少暴露：`latestUntrustedText()`（手册 §4.3 用它做注入检测）、`stepIndex()`、`sessionId()`
- **`block` 的语义必须明确**：pre-model 被 block ⇒ **本次不调模型**，循环以"被拦截"结束并把 reason 作为最终答复；
  pre-tool 被 block ⇒ **该工具不执行**，把 reason 作为工具错误结果交回模型（模型可换路）
- 一个 Hook 抛异常 ⇒ 视为 `block` 并记录（fail-closed），**不许让异常穿透主循环**

### 2. `loop` —— ReAct 主循环（手册 §5.1）

骨架：拼上下文 → 调模型 → 看是否要工具 → 过权限与 Hook → 执行 → 结果塞回 → 再调，直到
**最终回答**或**触发上限**。逐条落实手册的四个边界：

**(a) 步数与预算**
- 单次任务**最多 12 次工具调用**（可配置，默认 12）；超出 ⇒ 停止调工具，追加一条系统提示
  要求模型「用现有信息收尾」，再给模型**最后一次**不带工具的调用；仍不出最终答复 ⇒ 以
  `LoopStopReason.BUDGET_EXHAUSTED` 结束并保留已有文本
- **token 总预算**（可配置）：累计 `TokenUsage` 超预算时同上处理
- 终止原因用枚举显式表达（至少：`ANSWERED` / `BUDGET_EXHAUSTED` / `MAX_STEPS` / `BLOCKED` / `ERROR`）

**(b) 并行调用**
- 模型一轮要了**多个只读工具** ⇒ 每个工具开一个**虚拟线程**并发执行（`Executors.newVirtualThreadPerTaskExecutor()`，JDK 21）
- **写工具一律串行**（`Permission != READ` 的串行执行，且保持声明顺序）
- 混合一轮（只读 + 写）⇒ 只读部分并行，写部分串行
- 必须有测试证明**真的并发**（如用 `CountDownLatch`/屏障证明两个只读工具同时在执行），
  且**结果顺序与请求顺序一致**（不得因并发而乱序回填）

**(c) 出错处理**
- 工具报错**不抛异常**，整理成模型看得懂的**一句话**作为工具结果交回，让它自己决定换参数重试或换路
- **同一工具连续失败 3 次 ⇒ 熔断**：该工具后续调用直接返回"已熔断"错误，不再真正执行
- 熔断状态按 `(sessionId, toolName)` 计；成功一次即重置计数

**(d) 权限闸门（补齐 W2-2 复核指出的缺口）**
- 循环执行工具前**必须消费 `Permission`**：`READ` ⇒ 直接放行；`WRITE`/`HIGH_RISK` ⇒ 走
  可注入的 `PermissionGate` 接口
- `PermissionGate` **默认实现必须 fail-closed**（拒绝），并在 javadoc 注明「真正的确认令牌流程是 W5」
- 被拒 ⇒ 作为工具错误结果交回模型（不是异常）

**(e) 挂起与恢复（本卡只做抽象，不接 Redis）**
- 定义 `LoopSession`（消息历史 + 已用步数 + 已用 token + 状态）与 `SessionStore` 接口（save/load）
- 提供**内存实现** `InMemorySessionStore` 供测试；Redis 实现留后续卡
- 循环遇到 `ask_user` 类信号时**不阻塞**：把 `LoopSession` 存起来、状态标 `WAITING_USER`、返回
  `LoopStopReason.WAITING_USER`；提供 `resume(session, userReply)` 从存好的状态继续
- **本卡不实现 `ask_user` 工具本身**（属 W3），只实现挂起/恢复的机制 + 一条用假工具验证的测试

### 3. `event` —— SSE 事件类型（手册 §5.4）

六类事件：`plan` / `tool_call` / `tool_result` / `thinking` / `confirm_required` / `answer`。

- 每类一个不可变类型（record 优先），共同实现 `AgentEvent` 密封接口
- 主循环通过 `Consumer<AgentEvent>` 推事件（**不要在 agent-core 里引 SSE/Web 框架**）
- 顺序契约：`tool_call` 必先于对应的 `tool_result`；`answer` 至多一个且在最后
- 必须有测试断言事件序列（含并行场景下 `tool_call`/`tool_result` 的配对正确）

## 硬边界（逐条自查并在自审中给证据）

1. `agent-core` 仍**零 Spring / 零根项目依赖**（`grep -rE "org\.springframework|com\.hmdp"` 须为空）
2. **不新增任何第三方依赖**（只用 JDK + 既有 Jackson）
3. 既有 `llm`(49) / `tool`(66) / `mcp`(28) 共 **115 条一字不改**，全绿
4. **不接 Redis**、不写 Spring Bean、不碰根项目
5. 主循环**不得**直接 `new` 具体适配器——只依赖 `llm` 包既有接口（可注入假 LLM 做测试）

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"
mvn -f agent-core/pom.xml -B -ntp verify      # 必须全绿
mvn -B -ntp verify                            # 根回归必须全绿
```

## 当前测试基线（必须保持）

| 构建 | 基线 |
|---|---|
| `agent-core` | **115** 条全绿（28 MCP + 21 llm + 66 tool） |
| 根项目 | `Tests run: 30, Failures: 0, Errors: 0, Skipped: 3` |

**只能增加不能减少**；既有 115 条不许改、不许删、不许放宽断言。

## 交付前必须做的三遍审查（见 `docs/REVIEW-PROTOCOL.md`）

1. **自审**：写 `agent-core/SELF-REVIEW-W2-3.md`，逐条记录「要求 → 实现位置（文件:行）→ 是否达成 → 证据」。
   **单列三节**分别说明：(i) 你怎么证明只读工具**真的并发**；(ii) 你怎么证明**熔断**真的生效；
   (iii) 你怎么证明 **pre-model 被 block 时模型一次都没被调用**。
2. 之后由**异源模型（DSH）只读复核**（本卡 Codex 写 → DSH 审）。
3. 最后由管理者独立重跑 + 变异验证。

**诚实要求**：没真跑的命令标 `未执行`。

## 明确不做

- 不实现 `ask_user` / `update_plan` 工具本身（W3）
- 不接 Redis / 不写 Spring Bean（后续卡）
- 不做 LAYA 注入检测 Hook 的具体实现（W5，本卡只提供挂点）
- 不做确认令牌签发/校验（W5）
- 不做工具结果瘦身 / 上下文压缩（W7）
- 不做 SSE 传输层（本卡只定义事件类型）
- 不 commit、不 push
