# 任务卡 P1-5 — P1-4 返工（异源复核判不通过，须修 4 项）

难度：日常
交给：DSH（`dsh headless`）
理由：问题已被异源复核精确定位到文件:行，属「已定位缺陷 + 方案定好的测试」。**返工第 1 轮。**

**前置**：P1-4 的产物被 Codex 只读复核判 **不通过**，报告见 `docs/reviews/P1-4-review.md`（务必先读）。

## 复核判定摘要（Codex 审 DSH 产物）

认可的部分：零实现改动已独立核验（`agent-core/src/main` 无 diff，MD5 `dd85c60268ac5b410d4c832053b39299`）；
退避确实降到 50ms、确实轮询等待第二次 initialize、确实检查了其 session 头为 null
（不再是原来 5s 退避下的空断言）；notify 用例确实走到真实 `notify(...)` 路径；既有 23 条断言未削弱。

**但有 4 项必须修**，理由如下。

## 只许写这些文件

- `agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java`
- `agent-core/SELF-REVIEW-P1-4.md`（更新）
- `agent-core/SELF-REVIEW-P1-5.md`（新建）

**禁止**：改任何实现代码（`agent-core/src/main/**` 一行都不许动）；改 `McpClientTest.java`；
改范围外文件；动 CI；commit/push。

## 必须修 1：重握手必须用**不同的新 session id**，并精确断言

**现状问题**（复核 `docs/reviews/P1-4-review.md` §②.1）：
夹具两次握手都返回同一个 `SESSION_ID`（`HttpTransportTest.java:281`、`:363`），
所以用例根本没验证「恢复后用服务端**新发**的 id」这一语义。
更糟的是 `:187` 的 `anyMatch` 断言**会被恢复前的 `expire_session` 请求满足**，等于空断言。

**并且**：你在 `SELF-REVIEW-P1-4.md:98` 写的「第二次握手返回不同 id 会破坏红线测试」**不成立** ——
各用例使用**独立夹具实例**，完全可以只为重握手用例配置 `旧 id → 新 id`，其他红线测试继续用固定 id。
这条取舍属于把要求降级，必须改掉。

**要求**：
1. 让夹具支持「吐出的 session id 可配置」，重握手用例配成 **旧 id → 新 id 不同值**
   （例：首次 `session-old`，之后 `session-new`）。
2. 断言**恢复后那一次 `tools/call` 携带的是新 id**。
   不要用 `anyMatch`（会被过期前的请求满足）；要**精确锁定恢复之后**那条请求
   （例如按请求序号切片、或断言「最后一次 `tools/call` 的 sessionId == 新 id」）。
3. 保留对「重握手请求的 `Mcp-Session-Id` 为 null」的断言。
4. 不得因此破坏其他 23 条既有测试（各自的夹具实例互不影响）。

## 必须修 2：两个 202 变体在 wire 上必须真的不同

**现状问题**（复核 `docs/reviews/P1-4-review.md` §②.2）：
两个 202 变体都调用 `sendResponseHeaders(202, -1)`（`:49`、`:371`）。
复核者**反编译 JDK 21 的 `ExchangeImpl.sendResponseHeaders` 字节码**确认：
202 配 `-1` 时会**自动写入 `Content-Length: 0`**。
因此你声称的「202 无 Content-Length」与「202 显式 Content-Length: 0」**在实际 wire 上是同一个东西**，
三种变体实际只覆盖了两种。

**要求**（二选一，但必须诚实且可验证）：
- **方案 A**：让「无 `Content-Length`」这一形态真正出现在 wire 上
  （可尝试在 `sendResponseHeaders` 前手工操作响应头，或改用能精确控制头部的写响应方式），
  并用**服务端实测到的实际头部**证明它与另一种不同。
- **方案 B**：若在 JDK 21 的 `com.sun.net.httpserver` 下确实无法产出「无 Content-Length」的 2xx 响应，
  **如实改文档与用例命名**，明确写出「该形态在当前 JDK 下不可构造/wire 上等价」，
  并只保留**真正不同**的形态（如 `202 + Content-Length: 0` 与 `204`），
  **不得继续声称覆盖了三种不同形态**。

无论选哪个，都要在自审里给出**证据**（实际收到的头部清单），而不是结论性描述。

## 必须修 3：消除 50ms 计数竞态

**现状问题**（复核 §③）：
`assertEquals(1, initializeRequests(server).size())` 放在触发 404 **之后**（`:166`）。
50ms 退避下，重试可能先发生 → 偶发失败。

**要求**：
- 在触发 `expire_session` **之前**就记录/确认初始 initialize 计数为 1，再做触发。
- 「恰好两次 initialize」的断言放到**恢复完成之后**再检查（避免第二次刚到就断言、
  挡不住随后第三次重试）。
- 目标：连续多次运行不抖动（至少 5 次），把运行结果写进自审。

## 必须修 4：重跑验收 + 修正自审数字

1. 重跑并留下**还原后的最终全绿**证据：
   ```bash
   export MAVEN_OPTS="-Xmx640m"
   mvn -f agent-core/pom.xml -B -ntp test     # 须全绿；测试数 ≥27
   mvn -B -ntp verify                          # 须 28 / 0 / 0 / Skipped 1
   ```
   注意：复核时最新 Surefire XML 里留着**变异运行**的 1 条预期失败。
   必须重跑，留下干净的全绿报告，不能让变异残留污染交付证据。
2. 修正 `SELF-REVIEW-P1-4.md` 的数字错误：测试文件是 **`+126/-4`**，不是 `+130/-4`
   （130 是 diff 统计的总变动行数）。
3. 修正 `SELF-REVIEW-P1-4.md:98` 关于「不同 id 会破坏红线测试」的错误取舍说明。

## 变异自检（本卡仍需做，并写进 `SELF-REVIEW-P1-5.md`）

复核者已确认：**单点**去掉 `sessionId = null` 不会变红（`failGeneration()` 与 `start()` 两处冗余清理），
**两处同时去掉**才变红 —— 这是实现冗余导致，实现行为正确，**不要为了迁就单点假设去改实现**。
请复现「两处同时变异 → 新断言变红」，贴出失败输出，然后还原（还原后 MD5 必须回到
`dd85c60268ac5b410d4c832053b39299`）。

## 当前测试基线

- `agent-core`：**Tests run: 27, Failures: 0, Errors: 0, Skipped: 0**
- 根回归 `mvn verify`：**Tests run: 28, Failures: 0, Errors: 0, Skipped: 1**
- 两者必须全绿；`agent-core` 只能增加不能减少。

## 交付前必须做的三遍审查

1. **自审**：写 `agent-core/SELF-REVIEW-P1-5.md`，逐条对「必须修 1-4」记录
   「要求 → 实现位置（文件:行）→ 是否达成 → 证据」。
2. 之后由**异源模型（Codex）只读复核**（本卡 DSH 写 → Codex 审）。
3. 最后由管理者独立重跑 + 变异验证。

**这是一轮返工。若本轮复核仍不过，将升级人工决策（不再无限迭代）。**

## 明确不做

- 不改实现代码
- 不处理 P1-3 复核报告的建议 5-10
- 不动 CI、不 commit、不 push
