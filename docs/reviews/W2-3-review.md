# W2-3 异源复核报告（第 2 遍，异源 DSH）

> 复核者：DSH（`dsh headless`），**只读不改**。
> 对象：W2-3 交付物（`hook` / `loop` / `event` 三包，33 实现类 2275 行 + 测试）。
> 原始日志：`/tmp/w2-3-review-dsh.log`。
> 背景：产物由**两条 Codex 会话并发写入**（碰撞事故）+ 管理者修复 3 处损伤，详见 `agent-core/SELF-REVIEW-W2-3.md`。

## 判定汇总

| 问 | 判定 | 要点 |
|---|---|---|
| Q1 安全 | **通过** | 虚拟线程池 try-with-resources 关闭；无危险 API / 凭据 / 越权 IO；事件出口吞监听器异常 |
| Q2 逻辑正确性 | **不通过** | **熔断只对非只读路径生效**，只读路径从不查 `isOpen` → 卡面 `:75` 对 READ 工具不成立 |
| Q3 边界条件 | **有条件通过** | ①同一 callId 发两次 `tool_call`（Defer→resume 重发）②工具抛 `Error` 逃出 `run()` |
| Q4 与需求一致性 | **通过** | 无 Redis / 无 Spring / 未实现 `ask_user`/`update_plan` / 无 SSE 依赖 |
| Q5 三处碰撞修复 | **通过** | 均为正当修复、非放宽断言；删除死夹具恰当 |
| Q6 三节证明 | **不通过** | (i) 成立；**(ii)(iii) 描述的测试不存在** —— 自审写了未写的测试 |
| Q7 硬边界 | **有条件通过** | 零 Spring/零新依赖本轮实跑确认；"115 条一字未改"未在复核者允许读取范围内，标 `未执行` |

## 必修项（P0，6 条）

1. **只读工具绕过熔断**（`AgentLoop.java:507-528`）：`executeChecked` 须在 `registry.invoke` 前消费
   `isOpen`，或在只读段入口拦截。**注意现有两个失败探针都是 `Permission.READ`，修复后需相应调整探针权限。**
2. **补卡面强制的挂起/恢复测试**（卡面 `:89`）：`WAITING_USER` / `resume(` 在测试包零命中。
3. **补步数/token 预算测试**：12 次上限、`WRAP_UP_NOTE` 收尾调用、`BUDGET_EXHAUSTED`/`MAX_STEPS` 均无测试。
4. **改正自审 §2 的 (ii)(iii) 两节**：要么补出 `chatCalls` 计数与熔断断言，要么改写为「未执行/待补测」。
   **不得保留未写测试的证明叙述。**
5. **修 `tool_call` 重复发射**：`Defer` 路径（`:450` 先发、`:464-476` 不写结果、`resume` 重入再发）
   导致同一 callId 两次 `tool_call` 对一次 `tool_result`，违反 `ToolCallEvent` javadoc 与卡面 `:97` 配对契约。
6. **补事件序列/配对测试**（卡面 `:98`）：现仅断言两张有序 id 列表，未按 callId 配对核验，
   也未覆盖 `answer` 至多一次且在最后。

## 建议项（10，非阻塞）

1. `ThinkingEvent`、`ConfirmRequiredEvent` 定义后从未被构造 → 六类事件实际只落 4 类；`Defer` 是天然发射点。
2. `appendBlockedResults`（`:583-589`）在 post-model block 路径写结果却**不发事件**，事件流与历史不一致。
3. `await`（`:530-539`）对工具抛出的 `Error` 会逃出 `run()`；建议 `catch (Throwable)` 转为单行失败结果。
4. `sessionStore` 只在 `suspend` 时 `save`（`:283`），`load`/`remove` 在循环内从未调用
   → `InMemorySessionStore` 无界增长、完成会话仍留 WAITING_USER 快照；`remove` 是死 API。
5. `permissionOf` 对未知/缺失工具默认 `READ`（fail-open）；建议显式拒绝或加注释锁死前提。
6. `PendingPermission.question` 允许空白，而 `PermissionDecision.Defer` 要求非空白，校验口径不一致。
7. `ToolCircuitBreaker` 包私有却被公开构造参数与 `breaker()` 访问器暴露，阈值不可经公开 API 配置。
8. `MAX_STEPS` 与 `BUDGET_EXHAUSTED` 口径与卡面 `:62` 不一致，请裁定并固定。
9. 测试卫生：`LoopHarness.java:34` 的 `readsStarted` 无人 `await`（死闩锁）；
   `HookChainTest.everyHookPointKeepsItsOwnRegistrationOrder` 为**恒真断言**；
   `readB` 调序后 `finishedOrder == [readB, readA]` 近乎同义反复（真正的证据是 `readAOverlapped`）。
10. 自审 §0 测试账目过期：实际 `hook/loop` 测试为 **4 文件 / 794 行**（含 2 夹具），
    而非自审所写「4 类 + 3 夹具 / 1177 行」（该数含已删除的 3 个死夹具）。

## 复核者独立确认的通过项

- Q5：管理者 3 处碰撞修复均为**正当修复**——(a) `runStep` 确实在每个非只读调用处切断只读段，
  且 `resume` 只重放 `subList(index, size)`，故「整步全并发会破坏挂起语义」的理由**成立**；
  (b) 新 javadoc 与实现一致；(c) `readB` 调序只消竞态、未削弱语义。
- Q1：虚拟线程池 `try (ExecutorService ...)` 批次结束即关闭，无线程泄漏。
