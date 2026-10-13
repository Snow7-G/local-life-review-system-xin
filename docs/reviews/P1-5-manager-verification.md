# 验收附注（管理者）— P1-5

> **性质**：本文件由管理者（Hermes）添加，用于闭合 `docs/reviews/P1-5-review.md` 所列的
> 「必修 4：流水线卫生」两项。**不修改** `SELF-REVIEW-P1-5.md` 的原始证据（保留审计链），
> 仅在此记录权威的最终状态与必要的更正。

## 1. 为何需要本附注

Codex 复审（`docs/reviews/P1-5-review.md`）判定：**必修 1、2、3 全部通过**，
但「必修 4（流水线卫生）」未达成第 2 次，具体两项：

1. agent-core 的 Surefire 报告里留着**变异运行**的失败记录；
2. 自审的 MD5 / diffstat / 部分行号相对**最终**源码已过期。

这两项属于**验收流程卫生**，均由管理者在本轮独立完成，无需再派执行器。

## 2. 「干净全绿」证据（管理者独立重跑，撰写本文件前最后一次运行）

命令与结果：

```
$ MAVEN_OPTS="-Xmx640m" mvn -f agent-core/pom.xml -B -ntp test
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in dev.lifeagent.mcp.McpClientTest
[INFO] Tests run:  2, Failures: 0, Errors: 0, Skipped: 0 -- in dev.lifeagent.mcp.JsonRpcCodecTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 -- in dev.lifeagent.mcp.HttpTransportTest
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Surefire 报告逐文件复核（已无 `Failures`/`Errors` 残留）：

| 报告 | 结果 |
|---|---|
| `dev.lifeagent.mcp.HttpTransportTest.txt` | Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 |
| `dev.lifeagent.mcp.JsonRpcCodecTest.txt` | Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 |
| `dev.lifeagent.mcp.McpClientTest.txt` | Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 |

根回归：

```
$ MAVEN_OPTS="-Xmx640m" mvn -B -ntp verify
[WARNING] Tests run: 28, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

## 3. 最终源码事实（权威值，取代自审中的过期值）

| 项 | 权威值 |
|---|---|
| `HttpTransportTest.java` MD5 | **`c776cf64295fe93a41454b2b825c312b`**（自审写作 `b76a1ebcd6f6966432a3f544ea49bda0`，已在 polish 与还原后失效） |
| `HttpTransportTest.java` 行数 | **654** |
| `HttpTransport.java` MD5（实现，零改动） | **`dd85c60268ac5b410d4c832053b39299`** ✅ 与复核基准一致 |
| diffstat `HttpTransportTest.java` | **+214 / −43** |
| diffstat `SELF-REVIEW-P1-4.md` | **+43 / −14** |

## 4. 行号归属更正（自审中的笔误）

自审 §5.1 写「`failGeneration()`（`HttpTransport.java:97`）与 `start()`（`:394`）」，
**两处归属写反了**。实际：

| 位置 | 方法 |
|---|---|
| `HttpTransport.java:97` | `start()` 内的 `sessionId = null` |
| `HttpTransport.java:394` | `failGeneration()` 内的 `sessionId = null` |

结论不受影响（两处都是冗余清零点，单点摘除仍绿、两处同摘才红），但归属应以本表为准。

自审引用的测试侧行号经复核**仍然准确**（最终文件）：
`:187`（用例入口）、`:202-203`（`assertNull` 重握手不带旧 id）、`:205`（等待恢复）、
`:255`（恢复后调用带新 id 的断言）、`:308`（「形状不得塌缩」distinct 断言）。

## 5. 管理者独立变异验证（不采信自审与复核的二手结论）

| 变异 | 预期 | 实测 | 结论 |
|---|---|---|---|
| **M1**：`HttpTransport.java` 两处 `sessionId = null` 同时注释 | 变红 | `AssertionFailedError: re-handshake must not replay the expired Mcp-Session-Id ==> expected: <null> but was: <session-local-life-1>`；仅 1 条失败，其余 26 条通过 | ✅ 断言有效 |
| **M2**：`issueSessionIds(旧, 旧)`（两次握手发同一 id） | 变红 | `AssertionFailedError: the recovery handshake must issue a session id different from the expired one ==> expected: <[session-local-life-1, session-local-life-2]> but was: <[session-local-life-1, session-local-life-1]>` | ✅ 「新 id」语义确实被断言 |
| **单点摘除**（仅 :97 或仅 :394） | 不变红 | 27 条全绿 | ✅ 与复核一致，属实现侧两处冗余清零的既有属性，**不为此改实现** |

所有变异均已还原；还原后 `HttpTransport.java` MD5 回到 `dd85c60…`。

## 6. 结论

`docs/reviews/P1-5-review.md` 的 4 项必修，**1/2/3 经异源复核通过，4 由管理者于本文件闭合**。
P1-5 交付物满足 `docs/REVIEW-PROTOCOL.md` 的 DoD：

1. 任务卡验收命令真跑通过（本文件 §2）；
2. 三遍审查记录齐全（`SELF-REVIEW-P1-5.md` 自审、`docs/reviews/P1-5-review.md` 异源复核、本文件管理者验收）；
3. 根回归 **28 / 0 / 0 / Skipped 1** 无新增失败。
