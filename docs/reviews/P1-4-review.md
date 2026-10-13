<!-- 本文件是 Codex（gpt-5.6-sol）对 DSH（deepseek-v4.1-flash）产物的异源只读复核报告（P1-4） -->
<!-- 复核者声明：只读，未修改任何被审文件 -->
<!-- 提取自 /tmp/p1-4-codex-review.log；日志尾部 EXIT=0 -->
<!-- 结论：不通过，须修 4 项 -->

## ① 安全

- 未发现凭据、外部网络、依赖或业务接口改动；测试服务仅绑定 `127.0.0.1`。
- 已独立核验零实现改动：`git diff -- agent-core/src/main` 为空，`HttpTransport.java` 与 HEAD 的 MD5 均为 `dd85c60268ac5b410d4c832053b39299`。
- 当前仅测试、自审文档有改动；无 staged 内容、无 commit。
- 安全结论：通过。

## ② 逻辑正确性

1. 重握手覆盖：部分通过

- 确实把退避降至 `50ms`：[HttpTransportTest.java:163](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:163)、[HttpTransportTest.java:318](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:318)。
- 确实轮询等待第二次 `initialize` 到达，并检查其 session 头为 `null`：[HttpTransportTest.java:170](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:170)。因此不是原来 5 秒退避下的空断言。
- 确实等待客户端恢复并再次成功调用工具：[HttpTransportTest.java:177](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:177)。

但“使用服务端新发的 session id”没有覆盖：

- 两次握手始终返回同一个 `SESSION_ID`：[HttpTransportTest.java:281](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:281)、[HttpTransportTest.java:363](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:363)。
- [HttpTransportTest.java:187](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:187) 的 `anyMatch` 会被恢复前的 `expire_session` 请求满足，不能证明恢复后的调用携带第二次握手发放的 id。
- 自审所称“不同 id 会破坏红线测试”的取舍不合理：[SELF-REVIEW-P1-4.md:98](/root/projects/llrs-audit/agent-core/SELF-REVIEW-P1-4.md:98)。各测试使用独立夹具实例，完全可以只为重握手用例配置 `旧 id → 新 id`，其他红线测试继续使用固定 id。

2. Notify 覆盖：部分通过

- 用例直接调用真实 `McpTransport.notify(...)`：[HttpTransportTest.java:204](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:204)，并以服务端观测的方法名排除握手残留：[HttpTransportTest.java:208](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:208)。不是只靠握手间接覆盖。
- `McpClient` 本身没有公开 notify 方法，因此直接调用传输层 notify 符合本卡“零实现改动”的边界。
- 204 变体真实存在。

但两个 202 变体实际相同：

- 两者都调用 `sendResponseHeaders(202, -1)`：[HttpTransportTest.java:49](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:49)、[HttpTransportTest.java:371](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:371)。
- 独立检查本机 JDK 21 的 `ExchangeImpl.sendResponseHeaders` 字节码确认：202 配 `-1` 时会自动写入 `Content-Length: 0`。因此所谓“202 无 Content-Length”和“202 显式 Content-Length: 0”在实际 wire 上都是后者，并未覆盖三种不同响应。

3. P1-3 自审表修正：通过

- 编号原因已说明：[SELF-REVIEW-P1-3.md:28](/root/projects/llrs-audit/agent-core/SELF-REVIEW-P1-3.md:28)。
- 要求 6 已如实改为部分覆盖：[SELF-REVIEW-P1-3.md:42](/root/projects/llrs-audit/agent-core/SELF-REVIEW-P1-3.md:42)。
- 要求 7 已补回：[SELF-REVIEW-P1-3.md:43](/root/projects/llrs-audit/agent-core/SELF-REVIEW-P1-3.md:43)。

既有 23 条测试未见断言删除或放宽：测试 diff 删除的四行均为夹具 plumbing，没有删除 `assert*`；其余测试文件无 diff。

## ③ 边界条件

- `assertEquals(1, initializeRequests...)` 放在触发 404 之后：[HttpTransportTest.java:166](/root/projects/llrs-audit/agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java:166)。在负载较高时，50ms 重启可能先发生，造成偶发失败。应在调用 `expire_session` 前确认初始计数。
- “恰好两次 initialize”的断言在第二次刚到达时执行，不能阻止随后第三次重试；宜在恢复完成后再检查。
- 单独删除一个 `sessionId = null` 时变异不红，是 `failGeneration()` 与 `start()` 冗余清理造成，行为仍正确。自审采用两点同时变异的解释合理，不应为了满足错误的单点假设去改实现。
- 未重跑 Maven，以遵守只读要求。现有最新 Surefire XML 是变异运行留下的 1 条预期失败，源码虽已还原，但缺少一次还原后的最终验收报告；修复后须重新跑两条验收命令。

## ④ 有无自作主张

有一项实质性自作主张：自行把“新 session id”降格成“同一个 id 再次被使用”，未满足任务卡要求。

其余夹具重载、保存 transport 引用、参数化测试均属必要测试 plumbing。修改 `SELF-REVIEW-P1-3.md` 虽未列入“只许写”的两文件清单，但任务卡第 3 项明确要求修改，可视为授权。

自审另有轻微数字错误：测试文件实际是 `+126/-4`，不是所写的 `+130/-4`；`130` 是 diff 统计的总变动行数。

结论：不通过。

必须修：

1. 让重握手夹具返回与旧值不同的新 session id，并精确断言恢复后的那一条请求携带新值。
2. 让两个 202 变体在实际 HTTP wire 上确实不同，并验证无 `Content-Length` / `Content-Length: 0` / 204 三种形态。
3. 消除 50ms 计数竞态，更新自审中的结论和统计数字。
4. 修复后重新执行 `agent-core` 测试与根 `verify`，留下还原后的全绿结果。
