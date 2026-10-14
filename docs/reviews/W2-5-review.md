# W2-5 异源复核报告（第 2 遍，异源 DSH）

> 复核者：DSH（`dsh headless`），只读不改、未运行任何构建命令。原始日志：`/tmp/w2-5-review-dsh.log`。
> 对象：SSE 传输层（`src/main/java/com/hmdp/agent/transport/` 5 类 + 4 测试类 + `application.yaml` 新增块）。

## 判定汇总

| 问 | 判定 | 要点 |
|---|---|---|
| Q1 安全与凭据 | **通过** | 无硬编码凭据（`${…}` 占位 + `UNSET` 兜底）；无危险命令/越权 IO；对外文本只写固定文案与异常**简单类名**；无 CORS 放开 |
| Q2 六类事件映射 | **有条件通过** | 映射齐全且**穷尽 switch**，逐个单元断言；**但端到端只有 4 类真实产生**：`thinking` 生产上永不出现、`confirm_required` 由传输层合成 |
| Q3 顺序契约 | **有条件通过** | 四条顺序断言（精确列表）+ 跨挂起边界的配对；**未覆盖**同 step 内并行 READ 的"按请求序发射" |
| Q4 挂起不阻塞 | **通过** | 循环侧 `suspend` → `save` → `WAITING_USER`；传输侧随即收流；无任何阻塞原语（grep 全无命中）；测试证明流会结束且状态落库 |
| Q5 恢复 | **通过** | 复用已存 session；**计数式证据**（恢复前后 `searchNearbyCalls` 均为 1）、未知会话不触模型（`streamCalls == 0`） |
| Q6 错误路径 | **有条件通过** | 三层兜底，绝不落到 HTML 500，测试断言 `status == 200` 且帧列表恰为 `["error"]`；**未覆盖**校验分支与 catch 兜底 |
| Q7 硬边界 | **通过**（mtime 间接证据） | 只读端点（`PermissionGate.denyAll()`）；pom/CI/nginx/agent-core/既有代码均未动；**无 WebFlux**；yaml 只增 `hmdp.agent.*` |
| Q8 诚实性与测试质量 | **有条件通过** | **无幽灵测试**（16 = 8+4+4 逐一核实）；两处缺口标注**诚实**；少数行号偏差与计数偏差 |

## 必修项（3）

1. **沙箱外复跑两条基线命令并留证** —— 复核者不能跑 Maven（会写 `target/`），故"不回退"无独立证据。
   **已由管理者完成**（见下「管理者处置」）。
2. **澄清交付状态** —— 执行器自述"未 commit"，但工作树干净。**答：由管理者提交，commit `2392605`**，
   范围仅为本卡文件 + `application.yaml` 的 `hmdp.agent` 块。
3. **修正自述文档的行号/计数错误**：`AgentTransportConfig.java:70→71`、
   `AgentConversationController.java:141→139`、§1 row 12「5 条」→「4 条」。**已由管理者修正。**

## 建议项（12，摘其要）

1. `AgentEventStreamTest` 补 `tool_call.arguments` 字段断言（六个载荷中唯一从未被断言的字段）。
2. 给控制器校验分支与 `catch (RuntimeException)` 兜底各补用例。
3. 「凭据不泄漏」含日志面，建议加 log appender 断言。
4. `sendFailure` 兼捕 `RuntimeException`（当前同步 `drive` 下不可达，属加固）。
5. `aFailureMessageThatCarriesAKeyIsNeverEchoedOutward` 改为直接断言 `response.getContentAsByteArray()`。
6. `reply == null` 与 `sessionId.isBlank()` 判据不一致，`reply=""` 会被放行。
7. 补「同 step 两个并行 READ」用例，验证并发下事件仍按请求序发射。
8. 文档化"当前 stream 实为请求线程内同步跑完后由 Spring 缓冲 flush"——W2-6 若要真增量推送需改异步。
9. `AgentTransportConfigTest` 断路器断言只查非 null，与断言消息「必须共享同一实例」不符，建议 `assertSame`。
10. 清理 `AgentConversationSseTest` 4 处未使用 import。
11. 任务卡写 `application.yml`，实际是 `application.yaml`，建议统一。
12. `search-origin` 实为既有 W2-4 配置，而本卡 `origin` 参数只校验不下发；W2-6 接线前不要宣称"位置提示已生效"。

## 管理者处置（2026-10-14）

- **必修 1（已做）**：沙箱外实跑 `mvn -B -ntp verify` → `Tests run: 73, Failures: 0, Errors: 0, Skipped: 3`；
  `mvn -f agent-core/pom.xml -B -ntp test` → `Tests run: 147, Failures: 0, Errors: 0, Skipped: 0`。两者 BUILD SUCCESS。
- **必修 2（答复）**：执行器未 commit，交付由管理者提交为 `2392605`。
- **必修 3 + 建议 9、10**：已由管理者直接修正（文档行号、共享熔断器断言改为 `assertSame`、清理未使用 import）。
- **建议 11（卡面笔误）**：任务卡确写 `application.yml`，实际文件为 `application.yaml` —— 已在本报告登记，
  后续卡面统一用 `application.yaml`。
- **其余建议**（1–8、12）保留为待办：多属覆盖增强与 W2-6 前置说明。
