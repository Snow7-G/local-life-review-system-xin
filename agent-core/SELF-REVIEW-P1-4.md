# P1-4 自审记录 — 补齐 P1-3 的两处测试覆盖缺口

自审日期：2026-10-14
任务卡：`docs/tasks/P1-4-p1-3-gaps.md`
基线：`agent-core` **23 tests**；根回归 `mvn verify` **28 tests / 0 failures / 0 errors / Skipped 1**

**结论**：本卡两处测试缺口均已补上，第三条「修正 P1-3 对照表」已完成。
`agent-core` 由 **23 → 27 tests 全绿**（要求 ≥25）；根回归 **28 / 0 / 0 / Skipped 1** 与基线逐项一致。
**零实现改动**：`git diff --stat` 只显示测试与自审文档（`HttpTransportTest.java` +126 / −4，
`SELF-REVIEW-P1-3.md` +12 / −4），
`HttpTransport.java` 的 md5 与改动前一致（`dd85c60268ac5b410d4c832053b39299`）。

变异自检有一个**必须如实披露的偏差**：任务卡假设「去掉 404 路径的 `sessionId = null` 一处」即可让新断言变红，
实测**单点都不变红**——`failGeneration(...)` 与 `start()` 两处都会清 `sessionId`，互为冗余掩盖。
**两处同时去掉**时新断言确实变红（失败输出见第五节）。这不是断言失效，而是卡里对变异点的假设过于乐观；
细节与证据见第五节，请复核与管理层判定是否需要进一步收紧。
**P1-5 更正入口**：本文经异源复核判定**不通过**（`docs/reviews/P1-4-review.md`，4 项必修）。
④ 项要求的文字更正（`+130/-4` → `+126/-4`、§4.2 的错误论断）已就地改在本文，均以「更正（复核发现）」标出；
①②③ 项属于测试代码问题，已在 P1-5 返工中修复，证据见 `agent-core/SELF-REVIEW-P1-5.md`。

## 一、改动文件

| 文件 | 性质 | 行数 |
|---|---|---:|
| `agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java` | 改（本卡新增 2 条用例 + 夹具开关） | 483 |
| `agent-core/SELF-REVIEW-P1-4.md` | 新建（本文件） | — |
| `agent-core/SELF-REVIEW-P1-3.md` | 改（任务卡第 3 项：对照表修正） | — |

`git diff --stat` 输出（仅本卡授权的文件；实现代码一行未动）：

```
 agent-core/SELF-REVIEW-P1-3.md                        |  16 ++-
 .../java/dev/lifeagent/mcp/HttpTransportTest.java     | 130 ++++++++++++++++-
 2 files changed, 138 insertions(+), 8 deletions(-)
```

更正（复核发现）：上面这段 `--stat` 输出本身是真的，但第一列是该文件的**总变更行数**
（126 insertions + 4 deletions = 130），**不是插入数**。以 `git show --numstat --format= 0e6a8b3`
的权威输出为准：`HttpTransportTest.java` 为 `126 4`，`SELF-REVIEW-P1-3.md` 为 `12 4`，
两文件合计 `138 insertions / 8 deletions`，与上面 summary 行对得上。本文后续一律写 `+126 / −4`。

`git diff -- agent-core/src/main/java/dev/lifeagent/mcp/HttpTransport.java` 无输出。
未触碰 `McpClient.java`、`McpTransport.java`、`StdioTransportConfig.java`、`McpClientConfig.java`、
`StdioTransport.java`、`McpClientTest.java`、根 `pom.xml`、业务 `src/`、CI；未 commit、未 push。

## 二、要求逐条对照

| 要求编号 | 实现位置（文件:行） | 是否达成 | 备注 |
|---|---|---|---|
| 补 1：夹具能观测到真实重握手 | `HttpTransportTest.java:163-165`（`server.newClient(Duration.ofMillis(50))`）；夹具 `:318-323` 把该值装进 `restartBackoff(List.of(restartBackoff))` | 是 | 原夹具固定 `5s`（HEAD 版 `:223`），用例 5 秒内跑完、根本观测不到重握手。新用例走 `newClient(Duration)` 重载把退避压到 `50ms`，原 `expiredSessionMakesTransportUnavailableAndFurtherCallsFailFast`（`:149`）仍用默认 `5s`，其 fail-fast 断言一字未动。 |
| 补 2：第二次 `initialize` 的 `Mcp-Session-Id` 为 `null` | `HttpTransportTest.java:170-173` | 是 | `await(...)` 等到 `initializeRequests(server).size() >= 2` 后取 `get(1)`，断言 `assertNull(rehandshake.sessionId(), "re-handshake must not replay the expired Mcp-Session-Id")`。 |
| 补 3：重握手真实发生了（不是空跑） | `HttpTransportTest.java:168`、`:175` | 是 | 先断言 404 阶段只有 1 次 `initialize`，再断言恢复后**恰好 2 次**（`"the recovered handshake must reach the server exactly once more"`）。没有这一对断言，`get(1)` 会越界或断言到空跑。 |
| 补 4：重握手完成后 `isAvailable()` 恢复且 `callTool` 成功 | `HttpTransportTest.java:176-181` | 是 | `await(client::isAvailable, 5s)` + `assertTrue(client.isAvailable())` + `callTool("json", …)` 并断言往返参数值 `after-rehandshake`。 |
| 补 5：重建后请求带的是**服务端新发**的 session id | `HttpTransportTest.java:182-190` | 是 | 非 `initialize` 请求的 `sessionId()` 必须为 `null` 或等于夹具新发的 `SESSION_ID`；且至少一条 `tools/call` 带 `SESSION_ID`（消息 `"the recovered call must carry the session id issued by the re-handshake"`）。夹具两次握手都发同一个 id（见 4.2）。 |
| 补 6：notify 用例真正走到 `notify(...)` | `HttpTransportTest.java:194-200`、`:334-336` | 是 | 夹具在 `newClient` 里持有它构造的 `HttpTransport`，经包内 `notifyTransport()` 暴露；用例直接调 `server.notifyTransport().notify("notifications/list_changed", …)`，不依赖握手时的 `notifications/initialized` 间接成功。 |
| 补 7：三种响应变体（202 无 body / 202 + `Content-Length: 0` / 204） | `HttpTransportTest.java:41-64`（`NotifyResponse` 枚举 + `@ParameterizedTest @EnumSource`）、`:193-213` | 是 | `ACCEPTED_WITHOUT_BODY(202,-1,false)`、`ACCEPTED_ZERO_LENGTH(202,-1,true)`、`NO_CONTENT(204,-1,false)`；夹具 `:367-374` 按第三字段决定是否显式写 `Content-Length: 0`。 |
| 补 8：断言不抛异常且 transport 仍在运行 | `HttpTransportTest.java:201-211` | 是 | `assertDoesNotThrow(() -> …notify(…))`；`assertEquals("notifications/list_changed", server.notifyMethod(), …)`；`assertTrue(server.notifyTransport().isRunning())`；`assertTrue(client.isAvailable())`；末尾再发一次 `callTool` 证明通道未被 notify 打死。 |
| 补 9：夹具开关（不污染其它用例） | `HttpTransportTest.java:330-336`（`setNotifyResponse`、`resetNotifyObservation`）、`:367-374` | 是 | 默认 `notifyResponse = ACCEPTED_WITHOUT_BODY`，默认行为与 P1-3 时代原样（原代码是 `"notifications/initialized".equals(method)` → `202,-1`；现改为 `startsWith("notifications/")` → `202,-1`，对既有路径字节等价）。 |
| 卡 3：修正 `SELF-REVIEW-P1-3.md` 对照表 | `agent-core/SELF-REVIEW-P1-3.md` 第「测试要求对照」节 | 是 | 补回要求 7（notify）、如实标注要求 6 的实际覆盖强度、说明「补丁前编号」的成因。见第三节。 |

### 2.1 未做项（按卡「明确不做」）

- 未改任何实现代码。`HttpTransport.java` 只在变异自检时被临时改动，**已按字节还原**（md5 复核一致，见第五节）。
- 未处理复核报告建议 5-10（SSRF 白名单、`isAvailable` 副作用、notify 超时、字节上限、Content-Type 嗅探、404 诊断）。
- 未动 CI 配置。
- 未 commit、未 push。

## 三、任务卡第 3 项：`SELF-REVIEW-P1-3.md` 对照表修正

三处都改了，原文其它内容一字未动（含当时的环境阻塞记录——那是执行器当时的真实观测，不属于本卡授权改动范围）：

1. **补回被遗漏的要求 7**：原表标题是「测试要求 1-8 对照」，编号却从 6 直接跳到 8，notify 整条缺失。
   现补为「测试 7：notify 202 空 body 不解析响应体且传输仍存活」，标注当时**未实现**（夹具只有被动的
   `notifications/initialized`），并把本卡补上的用例位置与结论链接过去。
2. **如实标注要求 6 的实际覆盖强度**：原表「测试 6」写的是全达成。实际它只覆盖了
   「不可用 + 不泄漏 404 + 快速失败」三件事；**「重握手请求不带旧 `Mcp-Session-Id`」当时完全没断言**，
   而且夹具 `restartBackoff = [5s]` 使得重握手在用例生命周期内不可能被观测到——那条断言即使写了也是空跑。
   现按实际覆盖强度改写，并注明该缺口由 P1-4 用例
   `rehandshakeAfterExpiredSessionSendsNoStaleSessionIdAndRecovers` 补齐。
3. **说明「补丁前编号」的成因**：`docs/tasks/P1-3-mcp-http-transport.md` 在派单后被打过补丁。
   补丁提交 `8a2466f`（`[P1-3-1]`）的时间晚于执行器启动 **31 秒**，执行器读到的是补丁前版本：
   补丁前测试清单为 6 = 404 会话失效/降级失败、7 = `close()`、8 = UTF-8；
   补丁 `8a2466f` 把 notify 插入为**新的第 7 项**，`close()` / UTF-8 顺延为 8 / 9，
   同时给 404 那条加了「MUST 不带旧 `Mcp-Session-Id` 重新 initialize」与相应断言要求，
   并把门槛从 8 条提到 9 条（≥22 → ≥23）。
   因此原表用「补丁前编号」不是执行器漏读，**责任在管理者**：任务卡在派单后才被修补，执行器没有收到通知。
   本次修正保留补丁前编号并加注新旧编号映射，避免已交付记录的编号语义被静默改写。

## 四、为什么这些断言不是空洞的

### 4.1 新断言为什么能真正观测到重握手（引用 `restartBackoff` 实际取值）

- **旧状态**：`HttpTransportTest.java:223`（HEAD 版）硬编码 `.restartBackoff(List.of(Duration.ofSeconds(5)))`。
  404 用例在 5 秒退避到点之前就已断言完并返回，`McpClient.scheduleRestart()` 排的
  `lifecycleExecutor.schedule(this::restart, …)` 从未执行，服务端**一次重握手都没看到**。
- **新状态**：新用例走 `newClient(Duration)` 重载 → `newClient(16, Duration.ofMillis(50))` →
  `.restartBackoff(List.of(Duration.ofMillis(50)))`（`HttpTransportTest.java:165`、`:310`、`:318-322`）。
  50ms 远小于用例的 5 秒观测窗，重握手必然发生。
- **关键是"等到"而不是"睡一会儿"**：`:170` 用 `await(() -> initializeRequests(server).size() >= 2, Duration.ofSeconds(5))`
  轮询真实到达的请求，`:175` 再断言**恰好 2 次**。若重握手没发生，第 170 行就会超时失败；
  若多发生一次（例如重复入队），第 175 行会红。断言 `get(1).sessionId() == null`（`:172-173`）
  读的是服务端记录的真实请求头，不是客户端自述。
- 补充量纲：`RequestRecord.sessionId()` 来自夹具 `:352-360` 对
  `exchange.getRequestHeaders().getFirst("Mcp-Session-Id")` 的记录，是线上字节级证据。

### 4.2 关于“重建使用了新 session id”的诚实说明

任务卡写的是「重建使用了**新的** session id」。

**更正（复核发现）**：本节初稿声称「夹具两条硬约束使这一点**不可能**被差异化断言」、
「若重握手发放一个不同的新 id，该红线用例必红」，**这个论断是错的**：

- 每条用例在自己的 `try (...)` 里 `new FakeHttpMcpServer()`（`HttpTransportTest.java:185`），
  服务端实例、监听端口、`requests` 列表都是**逐用例独立**的。别的用例把两次握手配成
  「旧 id → 新 id」，与红线用例 `sessionAndNegotiatedProtocolHeadersAreSentAfterInitialize`
  （`:77-94`）毫无交集，**不可能**让它变红。
- 那条红线用例断言的是「**它自己那台夹具**发的每个非 `initialize` 请求都带
  `FakeHttpMcpServer.SESSION_ID`」；只要同一用例内两次握手发同一个 id 就满足，不需要全局唯一。

所以真实情况不是「被红线约束下的不可测维度」，而是本卡把要求**降级**了：两次握手发同一个 id 时，
`anyMatch(r -> SESSION_ID.equals(r.sessionId()))` 无法区分「沿用旧 id」与「拿到新 id」——
一条在触发 404 之前就已发出的 `expire_session` 调用就能让它满足（复核原文：`:187` 断言空洞）。
降级需要如实报告，而不是用一条**错误的技术论断**去证明它不可避免。

P1-5 已按任务卡修好：夹具支持 `issueSessionIds(旧, 新)`，并对恢复后的 `tools/call` 逐条断言 id，
详见 `SELF-REVIEW-P1-5.md` 第 1 条。

### 4.3 notify 用例走的是哪条代码路径

1. 用例调用 `server.notifyTransport().notify("notifications/list_changed", ObjectNode)`
   （`HttpTransportTest.java:198-200`）→ 直达 `HttpTransport.notify(String, JsonNode)`
   （`HttpTransport.java:232-260`），**不经过** `McpClient`。
2. `notify(...)` 用 `httpClient.send(request, BodyHandlers.ofInputStream())` 发出 POST，
   响应体在 try-with-resources 里被**直接关闭、从不解析**（`HttpTransport.java:245-248`），
   随后只做 `validateResponseStatus(generation, statusCode)`。
3. 夹具 `notifications/` 分支（`HttpTransportTest.java:367-374`）按变体回 202/204 且无 body。
   三种变体的差别正落在这一行：`ACCEPTED_ZERO_LENGTH` 会先显式写 `Content-Length: 0` 再 `sendResponseHeaders(202, -1)`。
   **这里有一个必须记下的 JDK 行为**：若按直觉写 `sendResponseHeaders(202, 0)`，
   JDK 会改用 chunked 编码，JDK `HttpClient` 随即死在
   `java.net.ProtocolException: Invalid status line: "0"`，`notify(...)` 的 `catch (IOException)` 会把它
   映射成 `McpUnavailableException` 并 `failGeneration(...)`——用例会红，但红的原因跟被测语义无关。
   实测中变体 [2] 就是这样先红过一次；正确写法是 `sendResponseHeaders(202, -1)` + 显式
   `Content-Length: 0` 头。这一条正是"202 + `Content-Length: 0`"与"202 无 body"的真实差别所在。
   
   **P1-5 更正（实测）**：上面这条描述只对了一半，而且把 `Invalid status line` 归因到了被测语义之外的地方。
   P1-5 用原始 socket 探针 + 复刻 `notify(...)` 调用路径实测（证据见 `SELF-REVIEW-P1-5.md` 第二节）：
  ① `sendResponseHeaders(202, -1)` 由 JDK 自己写成 `HTTP/1.1 202 Accepted\r\nContent-length: 0\r\n\r\n`，
     再手工 `.set("Content-Length", "0")` 得到的是**逐字节相同**的响应，所以原 `ACCEPTED_WITHOUT_BODY`
     与 `ACCEPTED_ZERO_LENGTH` 在线上**没有区别**，「三种形状」不成立（复核原文：`:49`、`:371`）；
  ② `sendResponseHeaders(202, 0)` 确实切到 chunked，但按 `BodyHandlers.ofInputStream()` + 立即关闭不读
     的真实路径连跑 6 次都正常拿到 202，未复现 `Invalid status line: "0"`；
  ③ 「202 且无 `Content-Length`」这一形状只能经由 chunked 构造。
   P1-5 已据此重写为三种**实测互不相同**的形状（`202`+`Content-Length: 0` / `202`+`Transfer-Encoding: chunked` / `204`），
   并由服务端在 `sendResponseHeaders` 之后读回**真实响应头**逐变体断言。
4. 断言面覆盖了"不抛异常"（`assertDoesNotThrow`）、"夹具真的收到了通知"
   （`notifyMethod()`）、"传输仍 running"（`isRunning()`）、"客户端仍可用"（`isAvailable()`），
   并在末尾补发一次 `callTool` 证明通道没被打死。
5. **诚实备注**：夹具的 `notifyMethod` 会被握手期的 `notifications/initialized` 一并写入。
   本卡在发通知前调用 `resetNotifyObservation()`（`HttpTransportTest.java:334-336`）把它清空，
   因此 `assertEquals("notifications/list_changed", …)` 断言的是**这一条**通知，而不是握手的残留。

## 五、变异自检（复现命令、失败输出、还原证明）

### 5.1 探针 A：只去掉 `failGeneration(...)` 的 `sessionId = null`（任务卡描述的那一处）

```bash
# HttpTransport.java:394 的 `sessionId = null;` 临时删除
MAVEN_OPTS=-Xmx640m mvn -f agent-core/pom.xml -B -ntp test -Dtest='HttpTransportTest'
```

```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.679 s -- in dev.lifeagent.mcp.HttpTransportTest
[INFO] BUILD SUCCESS
```

**没有变红。** 原因：`McpClient.restart()`（`McpClient.java:160-176`）重握手前会重新 `transport.start()`，
而 `start()`（`HttpTransport.java:86-101`）自己也会 `sessionId = null`，把 `failGeneration` 的缺失掩盖掉了。

### 5.2 探针 B：只去掉 `start()` 的 `sessionId = null`

```
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.002 s -- in dev.lifeagent.mcp.HttpTransportTest
[INFO] BUILD SUCCESS
```

**同样没有变红。** 原因对称：404 时 `failGeneration(...)` 已经先把 `sessionId` 清成 `null`。

### 5.3 探针 C：两处同时去掉 → 断言确实变红（决定性证据）

跑**全量** `agent-core` 而不是单条用例，这样能同时证明"只有新断言红、既有 26 条一条没红"：

```bash
MAVEN_OPTS=-Xmx640m mvn -f agent-core/pom.xml -B -ntp test
```

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 11.62 s -- in dev.lifeagent.mcp.McpClientTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.079 s -- in dev.lifeagent.mcp.JsonRpcCodecTest
[ERROR] Tests run: 13, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 3.092 s <<< FAILURE! -- in dev.lifeagent.mcp.HttpTransportTest
[ERROR] dev.lifeagent.mcp.HttpTransportTest.rehandshakeAfterExpiredSessionSendsNoStaleSessionIdAndRecovers -- Time elapsed: 0.127 s <<< FAILURE!
org.opentest4j.AssertionFailedError: re-handshake must not replay the expired Mcp-Session-Id ==> expected: <null> but was: <session-local-life-1>
	at dev.lifeagent.mcp.HttpTransportTest.rehandshakeAfterExpiredSessionSendsNoStaleSessionIdAndRecovers(HttpTransportTest.java:172)
[ERROR]   HttpTransportTest.rehandshakeAfterExpiredSessionSendsNoStaleSessionIdAndRecovers:172 re-handshake must not replay the expired Mcp-Session-Id ==> expected: <null> but was: <session-local-life-1>
[ERROR] Tests run: 27, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
```

注：`Failures: 1` 且失败点唯一——既有 26 条（含原 fail-fast 用例
`expiredSessionMakesTransportUnavailableAndFurtherCallsFailFast`）在实现被破坏的情况下**依然全绿**，
说明红的确实是"新断言观测到了旧会话 id 被重放"这一件事，而不是被别的连带失败污染。

失败值 `session-local-life-1` 正是夹具发的会话 id：清空逻辑一旦整体失效，
重握手请求头里就会原样带上过期会话 id——**断言捕捉的正是任务卡要守的那个缺陷**，不是别的东西顺带红。

### 5.4 由变异得出的结论（请复核判定）

新断言**是**有效的（5.3 证明），但**不是**"单点可变异"的：
`failGeneration`（`:394`）与 `start()`（`:97`）两处清理互为冗余，去掉任一处，另一处仍能让断言保持绿色。
因此任务卡「把 404 路径的 `sessionId = null` 去掉，新断言必须变红」这一假设**不成立**，
本卡按 5.3 的方式（两处同删）完成了等效验证。若复核认为应当有一个单点变异就能红的断言，
那需要**实现侧**调整（例如让重握手路径不再清会话、把清空责任唯一化），那属于实现改动，
超出本卡「零实现改动」的授权——请示管理层另开卡。

### 5.5 还原证明

```bash
md5sum agent-core/src/main/java/dev/lifeagent/mcp/HttpTransport.java
dd85c60268ac5b410d4c832053b39299  agent-core/src/main/java/dev/lifeagent/mcp/HttpTransport.java
```

该 md5 与变异开始前记录的完全一致；`git diff -- agent-core/src/main/java/dev/lifeagent/mcp/HttpTransport.java`
无输出；还原后重跑全量 `agent-core` 为 **27 / 0 / 0 / 0，BUILD SUCCESS**（见第六节第 1 条）。
`git diff --stat` 最终只剩 2 个文件：`HttpTransportTest.java`（+126 / −4）与 `SELF-REVIEW-P1-3.md`（+12 / −4）。

## 六、验证记录

1. agent-core 验收命令（任务卡原命令）：

   ```bash
   export MAVEN_OPTS="-Xmx640m"
   mvn -f agent-core/pom.xml -B -ntp test
   ```

   ```
   [INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 11.59 s -- in dev.lifeagent.mcp.McpClientTest
   [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.055 s -- in dev.lifeagent.mcp.JsonRpcCodecTest
   [INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.786 s -- in dev.lifeagent.mcp.HttpTransportTest
   [INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
   [INFO] BUILD SUCCESS
   ```

   23 → **27**（≥25 要求满足）。`HttpTransportTest` 由 9 条测试方法（Maven 计 9）增至 **11 条方法 /
   13 条测试**：新增 `rehandshakeAfterExpiredSessionSendsNoStaleSessionIdAndRecovers`
   （1 条）与 `notifyAcceptsEmptySuccessResponseAndKeepsTransportRunning`（3 个参数化变体）。
   既有 9 条一条未删、未改断言。

2. 根回归验收命令（任务卡原命令）：

   ```bash
   export MAVEN_OPTS="-Xmx640m"
   mvn -B -ntp verify
   ```

   ```
   [INFO] Results:
   [WARNING] Tests run: 28, Failures: 0, Errors: 0, Skipped: 1
   [INFO] BUILD SUCCESS
   ```

   与基线 **28 / 0 / 0 / Skipped 1** 逐项一致。

3. `git diff --stat`：仅 `HttpTransportTest.java`（126 insertions / 4 deletions）与
   `SELF-REVIEW-P1-3.md`（12 insertions / 4 deletions）；`HttpTransport.java` 无 diff。

## 七、遗留问题

1. **变异单点性**（见 5.4）：如需单点可变异，需实现侧调整，请管理层决策是否另开卡。
   补充实测：全量跑 3 次均为 27 全绿（11.6s / 11.9s / 11.7s 量级），`50ms` 退避未引入抖动。
2. **`isAvailable()` 的副作用**：`McpClient.isAvailable()` 在 transport 未运行时会给
   `onTransportUnavailable()` 点火（复核建议 6）。本卡的新用例只在重握手成功后调用它（`:176-177`），
   不构成误用；该副作用本身按卡「明确不做」留待接线 LAYA 时评估。
3. **夹具 `notifyMethod` 的语义**：它是"最近一次 `notifications/*` 方法名"，不是"最后一次 notify 调用"。
   本卡用 `resetNotifyObservation()` 规避（4.3 第 5 点），如实记录以免后续复读者误解。
