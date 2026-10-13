<!-- 本文件是 Codex（gpt-5.6-sol）对 DSH（deepseek-v4.1-flash）P1-5 返工产物的异源只读复核 -->
<!-- 复核者声明：只读；HttpTransport.java MD5 已核为 dd85c60268ac5b410d4c832053b39299 -->
<!-- 提取自 /tmp/p1-5-codex-review.log；EXIT=0 -->
<!-- 结论：必修1/2/3 通过；必修4（流水线卫生）未达成第 2 次 → 由管理者自行收尾 -->

## ① 安全

- 改动仅限任务卡允许的 3 个文件；无 staged 内容、无 commit。
- `agent-core/src/main/**` 无 diff。
- [HttpTransport.java](/root/projects/llrs-audit/agent-core/src/main/java/dev/lifeagent/mcp/HttpTransport.java:1) MD5 为要求的 `dd85c60268ac5b410d4c832053b39299`。
- 测试服务器仍只绑定 `127.0.0.1`；未发现凭据、依赖、接口或 CI 改动。

安全结论：通过。

## ② 逻辑正确性

1. 必修 1：通过

- 重握手明确配置旧、新两个不同 ID：[HttpTransportTest.java:187](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:187)。
- 服务端按序发放并记录 ID：[HttpTransportTest.java:455](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:455)。
- 保留重握手不带旧 ID 的精确断言：[HttpTransportTest.java:200](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:200)。
- 已摆脱 `anyMatch`：按第二次 `initialize` 切分恢复前后请求：[HttpTransportTest.java:224](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:224)；并精确断言第二条 `tools/call` 携带新 ID：[HttpTransportTest.java:247](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:247)。
- `toolCalls.size()==2` 使恢复后断言不可能因空集合而虚假通过。

M1/M2 均可从当前逻辑复现：同时删除实现第 97、394 行会在 `assertNull` 处红；把第二次握手改成旧 ID 会在发放序列断言处红。

2. 必修 2：通过

三种实际形态为：

- `202, -1` → `Content-Length: 0`
- `202, 0` → `Transfer-Encoding: chunked`，无 `Content-Length`
- `204, -1` → 两者皆无

定义见 [HttpTransportTest.java:57](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:57)，发送后观测见 [HttpTransportTest.java:532](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:532)。

独立反编译本机 JDK 21.0.12.1 的 `ExchangeImpl.sendResponseHeaders` 也确认：长度 `0` 的 HTTP/1.1 分支设置 chunked，`-1` 的普通 202 设置 `Content-length: 0`，204 则抑制该头。三者确实互异。

防塌缩断言存在：[HttpTransportTest.java:307](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:307)。

3. 必修 3：通过

- 初始计数断言已移至触发 404 前：[HttpTransportTest.java:194](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:194)。
- 先等待 `client.isAvailable()`，再断言恰好两次初始化：[HttpTransportTest.java:205](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:205)。
- 既有五连跑证据均绿；之后的源码变化只涉及 notify 观测夹具，没有改动该重握手路径。

4. 必修 4：不通过

虽然 P1-4 的 `+126/-4` 已正确修正于 [SELF-REVIEW-P1-4.md:9](/root/projects/llrs-audit/agent-core/SELF-REVIEW-P1-4.md:9)，但最终验收状态不合格：

- 最新 Surefire 报告仍是 M2 变异留下的 `Tests run: 1, Failures: 1`：[HttpTransportTest.txt:4](/root/projects/llrs-audit/agent-core/target/surefire-reports/dev.lifeagent.mcp.HttpTransportTest.txt:4)。这正是任务卡明确禁止残留的状态。
- 全绿证据对应测试文件 MD5 `b76a…`，但当前文件实际为 `c776cf64295fe93a41454b2b825c312b`；自审仍错误声明 `b76a…`：[SELF-REVIEW-P1-5.md:17](/root/projects/llrs-audit/agent-core/SELF-REVIEW-P1-5.md:17)。
- 自审记录测试 diff 为 `182/41`：[SELF-REVIEW-P1-5.md:238](/root/projects/llrs-audit/agent-core/SELF-REVIEW-P1-5.md:238)，当前实际是 `214/43`。
- 多处行号已过期，例如自审称响应头观测位于 `505-513`，当前实际是 `535-542`。
- [SELF-REVIEW-P1-5.md:262](/root/projects/llrs-audit/agent-core/SELF-REVIEW-P1-5.md:262) 把 `start():97` 和 `failGeneration():394` 的归属写反。

既有断言核验：未发现删除或放宽。原先宽松/空洞的重握手断言均被更强断言替代；其他测试仅有夹具 plumbing，其他测试文件无 diff。

## ③ 边界条件

- JDK 21 chunked 行为判断成立，自审也已注明其 JDK 版本依赖。
- notify 观测已增加按方法过滤和等待机制，避免握手通知及处理器落库时序干扰。
- 当前源码看不到新的逻辑竞态；主要边界问题是最终版本没有留下干净全绿报告。
- 本次遵守只读要求，未运行会写入 `target/` 的 Maven 命令。

## ④ 有无自作主张

无实质性自作主张。新增防塌缩测试、session ID 序列夹具和 notify 观测 plumbing 均在授权文件及任务范围内。

必须修：

1. 在所有变异还原、当前最终源码确定后，重新运行两条验收命令，并让最新 Surefire 报告保持全绿。
2. 按最终源码更新 P1-5 自审的 MD5、diffstat、文件行号及 `start`/`failGeneration` 行号归属。

未修改任何文件，未 commit。
