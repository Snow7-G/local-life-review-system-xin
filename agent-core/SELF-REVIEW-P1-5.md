# P1-5 自审报告（P1-4 返工：复核 4 项必修）

- 任务卡：`docs/tasks/P1-5-p1-4-rework.md`
- 复核依据：`docs/reviews/P1-4-review.md`（异源复核判定：**不通过**，4 项必修，均带「文件:行」定位）
- 本卡性质：**零实现改动**，只改测试与自审文档
- 改动文件（`git status --short`）：
  ```
   M agent-core/SELF-REVIEW-P1-4.md
   M agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java
  ```
  新增：`agent-core/SELF-REVIEW-P1-5.md`（本文件）
- 未 commit、未 push（卡内「明确不做」）
- 零实现改动证明（md5，改动前后一致）：
  ```
  dd85c60268ac5b410d4c832053b39299  agent-core/src/main/java/dev/lifeagent/mcp/HttpTransport.java
  a7815b76e2815dc7d6d998f263b22b8d  agent-core/src/main/java/dev/lifeagent/mcp/McpClient.java
  b76a1ebcd6f6966432a3f544ea49bda0  agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java
  ```
  `git diff --stat -- agent-core/src/main` 输出 **0 行**（`src/main` 下无任何改动）。
- 测试数量：`agent-core` **28**（`McpClientTest` 12 + `JsonRpcCodecTest` 2 + `HttpTransportTest` 14）；
  根回归 `mvn -B -ntp verify` **28 tests / 0 failures / 0 errors / Skipped 1**（与基线逐项一致）。
  用例文件内无 `@Disabled`、无 `assumeTrue`：`grep -n "@Disabled\|assumeTrue\|assumeFalse"` → 无输出。

## 一、要求逐条对照（要求 → 实现位置 → 是否达成 → 证据）

| # | 复核要求 | 实现位置（文件:行） | 是否达成 | 证据 |
|---|---|---|---|---|
| ①-1 | 重握手夹具体必须发出「旧 id → 新 id 两个**不同值**」 | `HttpTransportTest.java:187-189`（用例内配置 `issueSessionIds(SESSION_ID, RENEWED_SESSION_ID)`）；夹具 `:379`（`RENEWED_SESSION_ID = "session-local-life-2"`）、`:439-444`（`issueSessionIds`）、`:446-452`（`issueSessionId()` 按序发放，用尽后重复最后一个） | 达成 | 用例内 `:208-213` 断言服务端**实际发出**的序列 `assertEquals(List.of(SESSION_ID, RENEWED_SESSION_ID), server.issuedSessionIds(), …)`；把两次握手都改成同一个 id 会红（变异 M2，见第五节） |
| ①-2 | 断言**恢复后**的 `tools/call` 带**新** id，不得用 `anyMatch` | `:221-245`（按「第二次 `initialize`」切分请求流水：恢复前一律旧 id、恢复后一律新 id）、`:255-257`（`toolCalls.get(1).sessionId() == RENEWED_SESSION_ID`） | 达成 | 原 `anyMatch(任一新 id)` 已**删除**；新断言在「两次握手都发旧 id」时不变红（旧断言会绿），但在「恢复后请求仍带旧 id」时必红。实测请求流水（8 条，见 §2.3）证明恢复后三条请求全部带新 id |
| ①-3 | 保留「重握手请求的 `Mcp-Session-Id` 为 null」断言 | `:202-203`（`assertNull(rehandshake.sessionId(), "re-handshake must not replay the expired Mcp-Session-Id")`） | 达成 | 两处 `sessionId = null` 同时摘除时该断言变红并打印 `expected: <null> but was: <session-local-life-1>`（变异 M1，见第五节） |
| ①-4 | 修正 `SELF-REVIEW-P1-4.md:98` 的错误论断（「不同 id 会破坏红线测试」） | `SELF-REVIEW-P1-4.md:17-19`（更正入口）、`:106-127`（§4.2 重写并标明旧论断为错）、`:125-126`（指向本报告） | 达成 | 全文已无「不同 id」「会破坏」「红线测试」类表述（`grep -n "不同 id\|会破坏\|红线测试"` → 无输出）；新表述说明每个用例各自 `new FakeHttpMcpServer()` 实例、握手序号从 0 起，因此「旧 id → 新 id」不触红线 |
| ②-1 | 两个 202 变体必须在线上**真正不同** | `:59` `ACCEPTED_WITH_CONTENT_LENGTH_ZERO(202, -1, "0", null)`；`:61` `ACCEPTED_CHUNKED_WITHOUT_CONTENT_LENGTH(202, 0, null, "chunked")`；`:66` `NO_CONTENT(204, -1, null, null)`；`:83-86` `expectedFraming()` 拼期望串；`:505-513` 夹具在 `sendResponseHeaders(...)` **之后**回读真实 header | 达成 | 三种形状实测两两不同（§2.1 客户端侧 + §2.2 处理器侧），`:303-306` 另加「三种形状不得塌缩」的 distinct 断言；旧的字节相同变体对（`ACCEPTED_WITHOUT_BODY` / `ACCEPTED_ZERO_LENGTH`）已删除 |
| ②-2 | 自审必须给出**实际收到的 header 列表**，不得只写结论 | 本文件 §2.1 / §2.2 | 达成 | 逐变体 4 次请求的 `statusCode + headers().map()` 原文；处理器侧 `status=/content-length=/transfer-encoding=` 原文；另附用例内插桩运行的 `[EVIDENCE]` 原文 |
| ②-3 | 不得虚构「裸 202（无 `Content-Length`）」 | `:42-56` 枚举 javadoc 明确写出：`sendResponseHeaders(202, -1)` 由 JDK 自己写 `Content-Length: 0`，手工再设同一 header 得到**字节相同**的响应；真正无 `Content-Length` 的 202 只能由 `(202, 0)`（chunked）得到；同时更正 `SELF-REVIEW-P1-4.md:145-153`（旧的「字节不同」说法） | 达成 | 本报告 §2.2 原始探测输出；`(202, -1)` 与「手工设 `Content-Length: 0` 再发送」探测输出逐字节相同（§2.4 探针 A/B） |
| ③-1 | 初始 `initialize` 计数 == 1 的确认必须放在触发 `expire_session` **之前** | `:194-195`（`assertEquals(1, initializeRequests(server).size(), "the initial handshake must have completed exactly once")`），位置在 `:197-198` 的 404 触发之前 | 达成 | `:192-193` 注释写明理由（构造函数握手同步完成，50ms 重试会与计数竞争）；`:198` 之后才触发 404 |
| ③-2 | 「恰好两次」断言移到恢复**之后** | `:200`（`await(…>= 2, 5s)`）→ `:205-206`（`await(client::isAvailable)` + `assertTrue`）→ `:208-215` 才断言 `issuedSessionIds()` 与「恰好 2 次 `initialize`」 | 达成 | 原「触发 404 后立刻数 `initialize`」的写法已删除；现序为「先等到恢复完成，再数」 |
| ③-3 | 连续 ≥5 次运行不 flake | 同上 | 达成 | 5 连跑全部 `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（§4.1 表） |
| ④-1 | `mvn -f agent-core/pom.xml -B -ntp test` 全绿、用例数 ≥27 | — | 达成 | `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（§4.2） |
| ④-2 | `mvn -B -ntp verify` = 28 / 0 / 0 / Skipped 1 | — | 达成 | 聚合行 `[WARNING] Tests run: 28, Failures: 0, Errors: 0, Skipped: 1` / `BUILD SUCCESS`（§4.3） |
| ④-3 | 更正 `SELF-REVIEW-P1-4.md` 的 `+130/-4` → `+126/-4` | `SELF-REVIEW-P1-4.md:9`（结论段）、`:18`（更正入口）、`:33-40`（`--stat` 原文下方新增的换算说明）、`:233`、`:272`（§6 验证记录） | 达成 | `git show --numstat --format= 0e6a8b3` ⇒ `126 4 …/HttpTransportTest.java`、`12 4 …/SELF-REVIEW-P1-3.md`；`--stat` 的 `130` 是「变更总行数 = 126 插入 + 4 删除」（§4.4） |
| ④-4 | 修复 `SELF-REVIEW-P1-4.md:98` | 同 ①-4 | 达成 | 同 ①-4 证据 |
| — | 变异自检：只摘掉**两处** `sessionId = null` 才变红 | 测试文件 `:202-203`、`:255-257` | 达成 | M1（两处同摘）红、M2（两次握手同 id）红、单处摘除仍绿（§5.1-5.3）；**未为单点变异弯实现**（复核报告结论亦如此要求） |

## 二、第 ② 项：三种 notify 响应形状的实测证据

### 2.1 客户端真正收到的 header（线级原始输出）

探针 `target/wireprobe/WireProbe4.java`（源码在 `target/` 下，该目录已 gitignore；§2.4 给出可复制的等价片段）复刻夹具的三个 `(status, bodyLength)` 三元组，并走 `HttpTransport.notify(...)` 同一条路径
（`BodyHandlers.ofInputStream()` 之后**不读** body 只 close）：

```
JDK: 21.0.12.1
=== ACCEPTED_WITH_CONTENT_LENGTH_ZERO(202, -1) ===
  request #1 -> 202 {content-length=[0], date=[Tue, 13 Oct 2026 17:51:55 GMT]}
  request #2 -> 202 {content-length=[0], date=[Tue, 13 Oct 2026 17:51:55 GMT]}
  request #3 -> 202 {content-length=[0], date=[Tue, 13 Oct 2026 17:51:55 GMT]}
  request #4 -> 202 {content-length=[0], date=[Tue, 13 Oct 2026 17:51:55 GMT]}
  handler observed after sendResponseHeaders: status=202 content-length=0 transfer-encoding=null
=== ACCEPTED_CHUNKED_WITHOUT_CONTENT_LENGTH(202, 0) ===
  request #1 -> 202 {date=[Tue, 13 Oct 2026 17:51:55 GMT], transfer-encoding=[chunked]}
  request #2 -> 202 {date=[Tue, 13 Oct 2026 17:51:55 GMT], transfer-encoding=[chunked]}
  request #3 -> 202 {date=[Tue, 13 Oct 2026 17:51:56 GMT], transfer-encoding=[chunked]}
  request #4 -> 202 {date=[Tue, 13 Oct 2026 17:51:56 GMT], transfer-encoding=[chunked]}
  handler observed after sendResponseHeaders: status=202 content-length=null transfer-encoding=chunked
=== NO_CONTENT(204, -1) ===
  request #1 -> 204 {date=[Tue, 13 Oct 2026 17:51:56 GMT]}
  request #2 -> 204 {date=[Tue, 13 Oct 2026 17:51:56 GMT]}
  request #3 -> 204 {date=[Tue, 13 Oct 2026 17:51:56 GMT]}
  request #4 -> 204 {date=[Tue, 13 Oct 2026 17:51:56 GMT]}
  handler observed after sendResponseHeaders: status=204 content-length=null transfer-encoding=null
done, no ProtocolException thrown
```

把三个形状收到的 header 列表并列（去掉时间戳）：

| 变体 | `(status, bodyLength)` | 客户端收到的分帧 header | 处理器回读到的分帧 |
|---|---|---|---|
| `ACCEPTED_WITH_CONTENT_LENGTH_ZERO` | `(202, -1)` | `content-length=[0]` | `status=202 content-length=0 transfer-encoding=null` |
| `ACCEPTED_CHUNKED_WITHOUT_CONTENT_LENGTH` | `(202, 0)` | `transfer-encoding=[chunked]`（无 `content-length`） | `status=202 content-length=null transfer-encoding=chunked` |
| `NO_CONTENT` | `(204, -1)` | 两者皆无 | `status=204 content-length=null transfer-encoding=null` |

三者互不相同 ⇒ 第 ② 项要求的「两个 202 变体在线上真正不同」成立：
一个是 JDK 自动补的 `Content-Length: 0`，另一个是 `Transfer-Encoding: chunked`（复刻 `notify` 的非读取路径也不会抛 `ProtocolException`，见末行）。

### 2.2 用例内（同一夹具、同一断言路径）的插桩原文

在 `HttpTransportTest` 内临时插桩打印夹具回读值（插桩已还原，测试文件 md5 回到
`b76a1ebcd6f6966432a3f544ea49bda0`）：

```
[EVIDENCE] ACCEPTED_WITH_CONTENT_LENGTH_ZERO -> 202 content-length=0 transfer-encoding=null
[EVIDENCE] ACCEPTED_CHUNKED_WITHOUT_CONTENT_LENGTH -> 202 content-length=null transfer-encoding=chunked
[EVIDENCE] NO_CONTENT -> 204 content-length=null transfer-encoding=null
```

夹具侧代码即最终版本 `:505-513`：

```java
                    exchange.sendResponseHeaders(response.status, response.bodyLength);
                    // only read the framing headers AFTER the call: the JDK writes
                    // Content-Length / Transfer-encoding itself, so they describe the real wire shape
                    observedNotifyStatus = response.status;
                    observedNotifyContentLength =
                            exchange.getResponseHeaders().getFirst("Content-Length");
                    observedNotifyTransferEncoding =
                            exchange.getResponseHeaders().getFirst("Transfer-Encoding");
```

断言侧：逐变体 `:275-276`、`:298-299` 比对 `response.expectedFraming()` 与夹具回读值；
另加 `:303-306` 的「三种形状不得塌缩成更少形状」断言（`distinct().count() == values().length`）。

### 2.3 「旧 id → 新 id + 恢复后带新 id」的实测请求流水

同一次插桩运行里夹具记录到的全部 8 条请求（方法 + `Mcp-Session-Id`）：

```
initialize              -> Mcp-Session-Id: null
notifications/initialized -> session-local-life-1
tools/list              -> session-local-life-1
tools/call              -> session-local-life-1        (expire_session，服务端返回 404)
initialize              -> Mcp-Session-Id: null        (重握手，不带旧 id)
notifications/initialized -> session-local-life-2
tools/list              -> session-local-life-2
tools/call              -> session-local-life-2        (恢复后的 json 调用)
```

这正是 `:221-245` 的相位切分所断言的：以第二条 `initialize` 为界，界前非 `initialize` 请求全部带 `session-local-life-1`，
界后**全部**带 `session-local-life-2`。因为恢复流程是「先 `initialize`，握手成功后才 `available.set(true)`」，
`await(client::isAvailable)`（`:205`）返回时界后请求已全部落库，所以该断言是确定性的，不是竞态。

### 2.4 探针 A/B：为什么「裸 202」不存在（字节相同性证据）

同一探针另跑了两组对照（原始 socket 读裸字节）：

| 探针 | 服务端行为 | 客户端收到的字节/header |
|---|---|---|
| A | `sendResponseHeaders(202, -1)` | `Content-length: 0` |
| B | 手工 `set("Content-Length", "0")` 后 `sendResponseHeaders(202, -1)` | 与 A **逐字节相同** |
| C | `sendResponseHeaders(204, -1)` | 无 `Content-Length` |
| D | `sendResponseHeaders(202, 0)` | `Transfer-Encoding: chunked` + 结束块 `0\r\n\r\n` |
| G | `sendResponseHeaders(200, -1)` | `Content-length: 0` |

结论（也是 `:42-56` 写入枚举 javadoc 的结论）：**P1-4 里「两个 202 变体字节不同」的说法不成立**，
因为 `(202, -1)` 与「手工加 `Content-Length: 0`」在 JDK 21.0.12.1 上是同一个响应；本卡改为
`(202, -1)` vs `(202, 0)`（chunked）这一对**真正**不同的形状，且不再声称存在「无 `Content-Length` 的裸 202」。

可复制的探针骨架（与 `target/wireprobe/WireProbe4.java` 等价，便于复核者独立重跑）：

```java
HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
server.createContext("/cl0", ex -> {
    ex.sendResponseHeaders(202, -1);                       // A
    System.out.println("cl0: " + ex.getResponseHeaders().getFirst("Content-Length")
            + " / " + ex.getResponseHeaders().getFirst("Transfer-Encoding"));
    ex.close();
});
server.createContext("/chunked", ex -> {
    ex.sendResponseHeaders(202, 0);                        // D
    System.out.println("chunked: " + ex.getResponseHeaders().getFirst("Content-Length")
            + " / " + ex.getResponseHeaders().getFirst("Transfer-Encoding"));
    ex.close();
});
server.start();
HttpClient client = HttpClient.newHttpClient();
for (String path : new String[] {"/cl0", "/chunked"}) {
    HttpResponse<InputStream> r = client.send(HttpRequest
            .newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream ignored = r.body()) { /* HttpTransport.notify 不读 body */ }
    System.out.println(path + " -> " + r.statusCode() + " " + r.headers().map());
}
server.stop(0);
```

## 三、第 ① 项：为什么新断言不空洞（对照旧写法）

| 写法 | 在「两次握手都发旧 id」时 | 在「恢复后请求仍带旧 id」时 | 在「正常恢复」时 | 判定 |
|---|---|---|---|---|
| 旧 `anyMatch(新 id)`（已删） | 绿（前 4 条请求就满足了，且坏实现根本不进 `anyMatch`） | 绿（恢复前的请求已满足） | 绿 | **空洞** |
| 旧 `allMatch(id == 旧 || id == 新)`（已删） | 绿 | 绿 | 绿 | **空洞**（同类） |
| 现相位切分（`:221-245`） | 绿 | **红** | 绿 | 有效 |
| 现 `toolCalls.get(1)`（`:255-257`） | 绿（新=旧，值仍相等 ⇒ 所以另配 `:208-213` 的「值必须不同」断言） | **红** | 绿 | 有效（配合 ①-1） |

两处「旧写法」为什么空洞：它们都可以只被**恢复前**的请求满足，因此即使恢复后仍带过期 id 也不会失败。
现写法把「恢复后必须带新 id」压成 `afterRecovery` 上的一条 `allMatch`，恢复后任何一条请求带旧 id 就会红。

## 四、第 ③④ 项：稳定性与回归证据

### 4.1 ③ 5 连跑（同一命令连续 5 次，`target/wireprobe/final-evidence.out`）

命令：`mvn -f agent-core/pom.xml -B -ntp test -Dtest=HttpTransportTest -DfailIfNoSpecifiedTests=false`

| 第几次 | mvn 退出码 | 结果行 | 类耗时 | 重握手用例耗时 | 墙钟(ms) |
|---|---|---|---|---|---|
| 1 | 0 | `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` | 4.006 s | 0.269 s | 15415 |
| 2 | 0 | 同上 | 3.781 s | 0.303 s | 10642 |
| 3 | 0 | 同上 | 4.083 s | 0.297 s | 11069 |
| 4 | 0 | 同上 | 4.059 s | 0.311 s | 10825 |
| 5 | 0 | 同上 | 4.028 s | 0.348 s | 11015 |

5/5 全绿、无 flake；重握手用例耗时 0.269-0.348 s，远低于 5 s 观测窗，也不靠近 50 ms 退避边界。

### 4.2 ④ `agent-core` 全量

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in dev.lifeagent.mcp.McpClientTest
[INFO] Tests run:  2, Failures: 0, Errors: 0, Skipped: 0 -- in dev.lifeagent.mcp.JsonRpcCodecTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 -- in dev.lifeagent.mcp.HttpTransportTest
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 4.3 ④ 根回归

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 1 -- in com.hmdp.HmDianPingApplicationTests
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.hmdp.config.ManagementRoutesTest
[WARNING] Tests run: 28, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

（`ManagementRoutesTest` 红线用例未改且全绿。）

### 4.4 ④ 数字更正：`+130/-4` → `+126/-4`

`git show --numstat --format= 0e6a8b3`（P1-4 那一次提交）：

```
126     4       agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java
12      4       agent-core/SELF-REVIEW-P1-3.md
```

而 `git show --stat 0e6a8b3` 给的是 `130`（这是**变更总行数** = 126 插入 + 4 删除），
复核引用的 `+130/-4` 即由此而来。已在 `SELF-REVIEW-P1-4.md` 就地更正为 `+126 / −4`，
并在原文 `--stat` 输出下方加了说明，未删改历史命令输出。

本卡自身的 diffstat（`git diff --numstat`）：

```
43      14      agent-core/SELF-REVIEW-P1-4.md
182     41      agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java
```

## 五、变异自检（复现命令 → 失败输出 → 还原证明）

输出存于 `target/wireprobe/mutations-final.out`（`target/` 已 gitignore）。所有变异均从备份还原并复核 md5。

### 5.1 只摘掉**单处** `sessionId = null` → 仍绿（两处都试了，与复核报告一致）

```
##### single-site removal: only line 97
97:            // MUTATION sessionId = null;
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.852 s -- in dev.lifeagent.mcp.HttpTransportTest
[INFO] BUILD SUCCESS
##### single-site removal: only line 394
394:        // MUTATION sessionId = null;
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.507 s -- in dev.lifeagent.mcp.HttpTransportTest
[INFO] BUILD SUCCESS
```

原因与复核报告一致：`failGeneration()`（`HttpTransport.java:97`）与 `start()`（`:394`）是**两处冗余清零点**，
单点摘除不改变可观测行为。**本卡按卡要求不为单点变异弯实现**（这属于实现侧的冗余，不是测试的漏洞）。

### 5.2 M1：两处同时摘除 → 红（决定性）

```
##### mutation M1: remove BOTH sessionId = null (expect RED)
97:            // MUTATION sessionId = null;
394:        // MUTATION sessionId = null;
[ERROR] Tests run: 14, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 3.831 s <<< FAILURE! -- in dev.lifeagent.mcp.HttpTransportTest
[ERROR] dev.lifeagent.mcp.HttpTransportTest.rehandshakeAfterExpiredSessionSendsNoStaleSessionIdAndRecovers -- Time elapsed: 0.142 s <<< FAILURE!
[ERROR]   HttpTransportTest.rehandshakeAfterExpiredSessionSendsNoStaleSessionIdAndRecovers:202 re-handshake must not replay the expired Mcp-Session-Id ==> expected: <null> but was: <session-local-life-1>
[ERROR] Tests run: 14, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
restored impl: dd85c60268ac5b410d4c832053b39299  agent-core/src/main/java/dev/lifeagent/mcp/HttpTransport.java
```

诚实说明：变红的首个断言是 `:202` 的「重握手不得重放过期 id」（`:255-257` 的新 id 断言在其后，未被执行到）。
这满足卡里「只摘掉两处才变红」的判据；如需让失败点落在 `:255`，需要实现侧先修掉冗余——本卡明确不做实现改动。

### 5.3 M2：两次握手发同一个 id → 红（证明 ①-1 的夹具配置真的参与断言）

```
##### mutation M2: both handshakes issue the SAME id (expect RED)
        try (FakeHttpMcpServer server = new FakeHttpMcpServer()) {
            server.issueSessionIds(FakeHttpMcpServer.SESSION_ID,
                    FakeHttpMcpServer.SESSION_ID);
[ERROR]   HttpTransportTest.rehandshakeAfterExpiredSessionSendsNoStaleSessionIdAndRecovers:207 the recovery handshake must issue a session id different from the expired one ==> expected: <[session-local-life-1, session-local-life-2]> but was: <[session-local-life-1, session-local-life-1]>
[ERROR] Tests run: 14, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
restored test: b76a1ebcd6f6966432a3f544ea49bda0  agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java
```

### 5.4 非空洞自检：恢复后若仍带旧 id → 红

即上表第三列所依据的检查：把 `:201` 的 `rehandshake` 取成 `get(0)`（等价于「认为恢复前的请求就是恢复后的」）会让
`:202` 立刻失败；反向地，若把 `afterRecovery` 的期望 id 换成旧 id（即容忍过期 id），`:243-245` 的 `allMatch` 会失败。
两种改法都已在本卡开发过程中实测为红（复原后 5 连跑全绿，见 §4.1）。

### 5.5 还原证明

```
restored: dd85c60268ac5b410d4c832053b39299  agent-core/src/main/java/dev/lifeagent/mcp/HttpTransport.java
restored test: b76a1ebcd6f6966432a3f544ea49bda0  agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java
```

`git diff --stat -- agent-core/src/main` → 0 行。

## 六、允许清单与「明确不做」对照

改动仅限卡内允许清单：`HttpTransportTest.java`、`SELF-REVIEW-P1-4.md`、`SELF-REVIEW-P1-5.md`（新增）。
未触碰：`agent-core/src/main/**`（md5 不变）、`McpClientTest.java`、CI 配置、其他文件；未 commit、未 push。

明确不做（卡内列明）：不改实现代码；不处理 P1-3 复核报告的建议 5-10；不动 CI。

## 七、遗留与风险（请复核判定）

1. **实测环境为 JDK 21.0.12.1。** `agent-core/pom.xml:13` 固定 `maven.compiler.release=21`，根 `pom.xml` 未把
   `agent-core` 列为 `<module>`，而 `.github/workflows/ci.yml` 用 JDK 8 跑 `mvn -B -ntp verify`
   （日志中只有 `com.hmdp.*` 用例）——即 CI 目前**不构建** `agent-core`，本卡的三个分帧期望值当前只在
   JDK 21 上被检验。若将来 CI 换 JDK 或把 `agent-core` 纳入构建，需按 §2.4 探针重测后更新
   `HttpTransportTest.java:59/:61/:66` 三个期望值（3 行改动）；`:303-306` 的「形状不得塌缩」断言与 JDK 无关，恒成立。
2. **探针源码位于 `target/wireprobe/`**（随 `target/` 被 gitignore，不入库）。为便于复核者独立复现，
   §2.4 已内嵌等价探针片段；本节证据表中所引用的原始输出文件同样在 `target/wireprobe/` 下
   （`wireprobe4.out`、`final-evidence.out`、`mutations-final.out`）。
3. **单点变异仍为绿**（§5.1）：这是实现侧两处冗余清零点的既有属性，已在 P1-4 复核中确认，本卡按要求不为此改实现。
4. 本卡未运行 `node tests/common.test.cjs` 与 `nginx -t`：两者与 `agent-core` 测试无关，且改动未触及前端/nginx；
   若复核要求全 CI 三条命令的证据，可补跑（约 1 分钟）。
