# 返工卡 W2-3R — 应用 W2-3 异源复核的必修项

难度：日常（已定位的缺陷 + 方案定好的测试）
交给：Codex（`codex exec -m deepseek/deepseek-v4.1-flash --sandbox workspace-write`）
理由：**打回原执行器重做**（`docs/REVIEW-PROTOCOL.md` 升级规则）；且本卡全部是已定位缺陷与指定测试。

**依据**：`docs/reviews/W2-3-review.md`（异源 DSH 复核，Q2/Q6 判不通过、Q3 有条件通过）。
**前置**：W2-3 已提交（`c16272b`），agent-core 136 tests 全绿。

## 【环境限制，先读】

你的沙箱禁止建 socket。`dev.lifeagent.mcp.HttpTransportTest` 的 14 条会报
`SocketException: Operation not permitted`——**这是环境限制，不是你的 bug**。
不要诊断它、不要改 mcp 包、不要重试 maven 全量。只跑你改动相关的测试，全量验收由管理者在沙箱外执行。

## 只许写这些文件

- `agent-core/src/main/java/dev/lifeagent/loop/**`
- `agent-core/src/test/java/dev/lifeagent/loop/**`
- `agent-core/SELF-REVIEW-W2-3.md`（追加「返工记录」一节，不要删原文）

**禁止**：改 `dev.lifeagent.mcp.**`、`dev.lifeagent.llm.**`、`dev.lifeagent.tool.**`、
`dev.lifeagent.hook.**`、`dev.lifeagent.event.**`、根 `src/**`、任何 `pom.xml`、`.github/**`、新增第三方依赖。
**既有 136 条不许改、不许删、不许放宽断言**（本卡要求新增的除外）。

## 必修项（逐条做，缺一不可）

### 必修 1 —— 只读工具绕过熔断（**最重要**）

现状：`AgentLoop.executeChecked`（约 `:507-528`）只 `recordBreaker`，**从不查 `isOpen`**；
熔断只在 `runGatedCall`（约 `:479-485`）里被消费。因此卡面
`docs/tasks/W2-3-reasoning-loop.md` 的「同一工具连续失败 3 次 ⇒ 该工具后续调用直接返回『已熔断』、
不再真正执行」**对 `Permission.READ` 工具不成立**。

要求：
- 只读路径也必须在**进入业务方法前**消费熔断状态：已熔断 ⇒ 直接产出「已熔断」失败结果，**不调用** `registry.invoke`。
- 与写路径口径一致（同一 `ToolCircuitBreaker`、同一 `(sessionId, toolName)` 键）。
- **调整现有探针权限**：`LoopHarness.ProbeTools` 的 `probe_fail` / `probe_flaky` 现为 `Permission.READ`，
  正因如此该缺口测不出来。请为熔断测试提供能触发的探针（可新增 `Permission.WRITE` 探针，
  或保留 READ 探针并让只读路径同样消费熔断）。
- **补测试**（卡面点名要求的证明）：连续失败 3 次后，第 4 次调用**工具方法体未被进入**
  （用失败计数器断言不再增长），且返回「已熔断」类失败结果；**一次成功即重置**（用先失败后成功的探针验证）。

### 必修 2 —— 补挂起/恢复测试（卡面 `:89` 明确要求）

卡面要求「提供 `resume(session, userReply)` 从存好的状态继续」且**必须有一条用假工具验证的测试**。
现状：`WAITING_USER` / `resume(` 在测试包**零命中**。

要求：用假工具 + 一个 `Defer` 的 `PermissionGate` 写出测试，断言：
- 第一次 `run` 返回 `LoopStopReason.WAITING_USER`，会话已存入 `SessionStore`；
- `resume` 后**只重放未执行的尾部**，已完成的调用**不被重复执行**（用调用计数证明）；
- 恢复后能正常走到 `ANSWERED`。

### 必修 3 —— 补步数与 token 预算测试

现状：12 次上限、`WRAP_UP_NOTE` 收尾调用、`BUDGET_EXHAUSTED` / `MAX_STEPS` **均无测试**。

要求：分别补测试断言
- 工具调用达到上限后：追加收尾提示、**最后一次调用不带任何工具**、以预期原因结束且保留已有文本；
- token 预算耗尽走同一收尾路径。

### 必修 5 —— 修 `tool_call` 重复发射

现状：`runGatedCall` 在**权限闸门之前**就 `emit(ToolCallEvent)`（约 `:450`）；`Defer` 时不写结果
（`:464-476`），`resume` 重入 `runGatedCall` 又发一次 ⇒ 同一 `callId` **两次 `tool_call` 对一次
`tool_result`**，违反 `event/ToolCallEvent.java` javadoc 与卡面 `:97` 的配对契约。

要求：保证**每个 `callId` 恰好一个 `ToolCallEvent`**。可记录"已发过事件的 callId"，或把发射移到闸门通过之后——
**但注意**：若移到闸门之后，`confirm_required` 场景下模型将看不到该工具"曾被请求"，请自行判断哪种更符合契约并在 javadoc 说明。

### 必修 6 —— 补事件序列/配对测试（卡面 `:98`）

要求：按 `callId` 核验 `tool_call` 与 `tool_result` **一一配对**（含并行场景与 Defer/resume 场景），
并断言 `answer` **至多一个且在最后**。

## 同时修掉的两处 Q3 缺陷（复核列为边界条件不通过）

- **工具抛 `Error` 会逃出主循环**：`await`（约 `:530-539`）把工具抛出的 `Error` 包成 `IllegalStateException`
  抛出 `run()`，与卡面「工具报错不抛异常、整理成一句话交回」相悖。改为捕获 `Throwable` 并转为单行失败结果。
- （顺带，建议项）`appendBlockedResults`（约 `:583-589`）写结果却不发事件，与 `appendBudgetExhausted` 做法不一致——请对齐。

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"
mvn -f agent-core/pom.xml -B -ntp test -Dtest='Loop*Test,ParallelExecutionTest,HookChainTest,Permission*Test,Circuit*Test,Session*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

## 当前测试基线

`agent-core` **136** 条全绿（mcp 28 + llm 21 + tool 66 + hook/loop/event 21）；根项目 30 全绿（Skipped 3）。
**只能增加不能减少。**

## 交付要求

- 在 `agent-core/SELF-REVIEW-W2-3.md` **追加**「## 7. 返工记录（W2-3R）」一节：
  逐条列「必修项 → 实现位置（文件:行）→ 证据（测试名 + 断言内容）」。
- **诚实要求**：没真跑的命令标「未执行」。**不要写你没写出来的测试**——上一轮自审正因为叙述了不存在的测试而被复核抓出。
- 不要 commit，不要 push。
