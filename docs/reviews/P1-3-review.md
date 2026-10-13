<!-- 本文件是 DSH（deepseek-v4.1-flash）对 Codex（gpt-5.6-sol）产物的异源只读复核报告 -->
<!-- 复核者声明：只读，全部构建在 /tmp 副本内进行，仓库未被触碰；HEAD 8a2466f -->
<!-- 复核者自建 6 个审计探针（P1-P6，仅存在于 /tmp，永不进仓库）实证动态行为 -->
<!-- 提取自 /tmp/p1-3-dsh-review.log；日志尾部 EXIT=0 -->

## 审查证据基础（仓库未被触碰）

只读复核，`git status --porcelain` 审查前后一致（HEAD `8a2466f`，4 改 + 5 未跟踪），我的所有构建都在 `/tmp` 副本里跑，未写入仓库。

我自己独立复跑了卡里的两条验收命令（自审说环境不允许建 socket，`agent-core/SELF-REVIEW-P1-3.md` 因此两条都没跑；本环境可跑）：

| 命令（副本内） | 结果 |
|---|---|
| `mvn -f agent-core/pom.xml -B -ntp test` | **BUILD SUCCESS**，shipped 用例 23 条全绿（McpClientTest 12 + JsonRpcCodecTest 2 + HttpTransportTest 9） |
| `mvn -B -ntp verify`（root） | **BUILD SUCCESS**，`Tests run: 28, Failures: 0, Errors: 0, Skipped: 1` —— 与卡 L125-129 基线一字不差 |

另外我在副本里写了一个**只存在于 /tmp、永不进仓库**的探针类（6 个用例，全部通过）来实证动态行为：P1 404 重握手、P2 notify 202/204、P3 SSE 跳过、P4 302 重定向、P5 close 后线程清点、P6 userinfo 端点。

---

## ① 安全

**端点/URL 处理**：`HttpTransport.java:77-85` 只校验 scheme ∈ {http,https} 且 `getHost()!=null`，异常信息不含 URI（无凭据泄漏到日志）。**无 host 白名单、不拒绝 userinfo、不拒绝环回/链路本地地址**。可达性目前为零：`grep` 全仓库，`HttpTransport` 除测试外**没有任何生产代码构造它**（mcp 包外无引用），所以现在不存在 SSRF 面；但下一条任务把它接到配置/LLM 输入那一刻，这个空白就变成 SSRF 面 —— 应在接线时补白名单。

**重定向**：`HttpClient.newBuilder()` 未设 `followRedirects` → JDK 默认 `NEVER`；实测 P4：302+Location → 受害服务端命中 **0** 次，失败类型 `McpProtocolException: MCP HTTP request was rejected`，transport 仍 running。**不存在重定向型 SSRF，也不存在跨主机重发凭据**。✓

**凭据**：`requestBuilder`（`HttpTransport.java:263-274`）只设 Content-Type / Accept / Mcp-Session-Id / MCP-Protocol-Version，**没有提供任何认证头入口**（卡也没要求，属已知空白）。P6 实测：`http://user:secret@127.0.0.1:PORT/mcp` 被构造函数**接受**，请求**成功**，但服务端看到的 `Authorization` 为 `null` —— JDK HttpClient 静默丢弃 userinfo，结果是"运维以为带了凭据、实际匿名连接"。不是泄漏，是静默错配，建议 `validateEndpoint` 直接拒绝 userinfo。

**请求头**：握手前不发会话/版本头，`captureSession`（`HttpTransport.java:295-302`）只在 `"initialize".equals(method) && generation == activeGeneration && running` 时记录，且服务端给的值**原样存储回传、无字符集/长度校验**。若服务端回一个含控制字符的 `Mcp-Session-Id`，JDK `header()` 会抛 IAE —— 好消息是它在 `try` 内（`HttpTransport.java:117`）被 `catch (RuntimeException e)`（`:162`）兜住，降级为 `McpUnavailableException("Could not send MCP HTTP request")` + `failGeneration`，**不会裸抛、不会静默挂死**，方向是安全的。

**资源泄漏**：`inFlight` 许可与 `pending` 条目都在 `finally` 释放/移除（`HttpTransport.java:176-180`），超时路径 `cancel(true)` + `recordTimeout`（`:126/:138-143`），无泄漏路径。P5 实测 close 后 `mcp-http-*` 线程 **0** 个，且 JDK 的 `HttpClient`/`SelectorManager` 残留线程 **空列表**（自建线程池交给 client，随 `close()` 一起收）。✓ 未做上限的只有响应体读取：`readAllBytes`（`:305`）与 SSE 逐行累积（`:313-340`）无字节上限，只有"已被信任的服务端"才能触发，列为加固建议。

## ② 逻辑正确性（逐条核对）

**实现要求 §1 传输抽象** ✓：`McpTransport.java:9-27` 方法集与卡 L51-60 一致；`StdioTransport.java:29` 实现之、`:35/:52` codec 内建与公平信号量；配置拆分 `StdioTransportConfig.java`（command/env/logger）与 `McpClientConfig.java`（删掉的正是 `builder(List<String> command)`/`environment`/`inheritParentEnvironment`/`stderrLogger`/`clientVersion`，即 stdio 专属项，属必要连带，非顺手改）；`McpClient.java:36` 改为注入 `McpTransport` ✓。**超出卡 sketch 的只有一处**：包级接口 `McpTransportFailureSource`（`McpTransport.java:29-33`），代码注释与自审都写了取舍理由，符合卡 L71-72 的豁免条件。

**实现要求 §2 HttpTransport** ✓：单一 endpoint、两种响应形态（`:210-217`，`isContentType` 在 `:480-484` 正确剥离 `; charset=utf-8` 且大小写无关）、notify 只校验状态码**从不解析 body**（`:233-262` → 202/204 + 空 body 合法）、404 → `McpUnavailableException` 触发 `failGeneration` 清会话并重握手、**所有异常信息不含任何状态码数字**（`:279-292`、`:284/:287/:290/:292`）✓ 符合"不得把 HTTP 状态码泄漏成业务异常"。`ExecutionException` 分支不双重包装（`:155-160` 先 `unwrap` 再原样重抛 `McpException`）✓。

**测试要求 1-9**：

| # | 卡行 | 用例（文件:行） | 判定 |
|---|---|---|---|
| 1 | 102 | `HttpTransportTest.java:39-48`，真 `HttpServer` fixture `:186+`，断言 `echo` 的 schema 可用 | ✓ |
| 2 | 103 | `:50-67`，断言非 initialize 请求全带 `SESSION_ID` + `PROTOCOL_VERSION` + 两个头值 | ✓ |
| 3 | 104-105 | `:69-77`（JSON 形态）+ `:79-88`（SSE，`:85` 还断言 fixture **确实**注入过不匹配 id 事件） | ✓ |
| 4 | 106 | `:90-108`，3 并发且逐条校验结果与自身入参对应，`:105` 断言服务端观测到 3 并发 | ✓ |
| 5 | 107 | `:110-120`，`McpTimeoutException` + `pendingRequestCount()==0` + 仍 available | ✓ |
| 6 | 108-110 | `:122-134` | **◐ 只覆盖一半** |
| 7 | 111 | 无独立用例 | **✗ 覆盖缺口** |
| 8 | 112 | `:136-156`，在途调用以 `McpUnavailableException` 结束 + 线程归零 + 后续调用被拒 | ✓ |
| 9 | 113 | `:158-166`，`"上海探店🙂火锅"`（4 字节 emoji）往返 | ✓ |

**要求 6 的实证结论**：用例只断言了类型、`!message.contains("404")`（`:128`）、`isAvailable()==false`（`:129`）与 fail-fast（`:131-133`）；**卡明文要求的"断言重握手请求里没有 `Mcp-Session-Id` 头"没有断言**，而且 fixture 配的是 `restartBackoff=[5s]`（`:223`），断言在 5 秒内全部结束 → **该用例根本观测不到任何重握手**。但这是**测试缺口而非实现缺陷**：探针 P1（`restartBackoff=[50ms]`）实测服务端请求序列为 `initialize|null, notifications/initialized|s1, tools/list|s1, tools/call|s1, initialize|null, notifications/initialized|s2, tools/list|s2` —— 重握手**不带**旧会话 id、改用新 `s2`、之后 `toolSchema` 恢复、`callTool` 成功；fail-fast 延迟 **0 ms**，不挂起。实现是对的，卡要的那句断言可以（也应该）补上。

**要求 7 的实证结论**：仓库里没有任何针对 notify 响应的显式断言，该路径只在每次握手的 `notifications/initialized` 上被间接走过（fixture `:249` 回 202），一旦有人改成"解析 notify 响应体"，只有间接失败信号。探针 P2 实测实现侧完全正确：`202 + 空 body`、`202 + Content-Length: 0`、`204` 三种都不抛异常且 transport 仍 running ✓；附带的 400 + id-less JSON-RPC error → `McpProtocolException`（不误判为可用性故障、不停机）也合理。

## ③ 边界条件

- **空 body**：notify 不解析 ✓（实测）；但对**请求**回 202/空体会落到 `:215-217` 的"unsupported content type"或 JSON 解码失败 → 该调用报 `McpProtocolException`、会话存活。卡只约束 notify，可接受；建议顺带允许"空 Content-Type 时按 body 嗅探"。
- **超时**：`request` 有 deadline（含发送前 `remaining<=0` 兜底 `:134-136`）✓；`notify` 的 deadline 写死为 `config.handshakeTimeout()`（`:236`），调用方**无法指定**，慢 notify 会阻塞到默认 10s。
- **并发**：`ConcurrentHashMap` + `AtomicLong` id + 按 id 匹配回调（`:183-232`、`:356-374`）✓；并发上限由公平信号量封顶（`:43/:416-429`），`cachedThreadPool` 无上限但被信号量间接封顶；探针 P3a 亦验证"通知事件 + 他人 id 响应"均被跳过。✓
- **会话失效**：`failGeneration`（`:390-407`）清 sessionId/generation，重建期间调用 0ms 失败、不挂起 ✓。副作用：`validateResponseStatus` 对**任何** 404 都按"会话失效"处理，因此"URL 配错导致永久 404"会变成无限退避重启循环（有退避、不刷屏，但诊断信息单一）。
- **线程回收**：P5 实测 close 后前缀线程 0、JDK HttpClient 线程 0 ✓；在途调用被 `cancel(true)` 并以 `McpUnavailableException` 结束 ✓。
- **平台差异（重要）**：`maven.compiler.release=21`（`agent-core/pom.xml:13`）、测试依赖 JDK 自带 `com.sun.net.httpserver`；而 `.github/workflows/ci.yml` 是 `java-version: '8'`，root `pom.xml` **没有 `<modules>`**（实测 grep 无结果）→ **agent-core 这 23 条用例在 CI 里完全不会被执行**，P1-3 及后续 agent 代码的回归没有任何自动化守护。这是本次改动最大的工程风险，且**超出任务卡范围，需要人工决定**（加 JDK 21 job 或 root 加 module + toolchain）。
- SSE 细节：`data:` 为非 JSON 时（如 `data: keepalive-ping`）会**抛 `McpProtocolException("Could not decode JSON-RPC line")` 令该次调用失败**（探针 P3b），而会话存活（`clientAvailable=true`）；标准 SSE 注释行 `:` 已被忽略。规范上 data 事件本就应是 JSON-RPC，属可接受的严格性，仅列为建议。

## ④ 有无自作主张

- **无新增第三方依赖** ✓：`agent-core/pom.xml` 仍只有 jackson-databind 2.19.2 + junit-jupiter 5.13.4，`git diff` 对任何 pom 为空。
- **无范围外文件改动** ✓：`git diff --stat -- pom.xml agent-core/pom.xml .github src tests docs` 输出为空；4 个改动文件全部在卡 L27-36 许可的 mcp 包内，新增 `SELF-REVIEW-P1-3.md` 亦在许可清单内；mcp 包外无任何文件引用新 API。
- **既有 14 条测试断言一字未改** ✓（我独立复核过，不采信自审）：`HEAD:McpClientTest.java` 与工作区做归一化的 `assert|fail(|@Test|void ` 行 diff **为空**，两侧 `assert`/`fail` 行数均 37 且内容与顺序一致；`JsonRpcCodecTest.java` 无 diff；`FakeMcpServer.java` sha256 两侧一致（`14bb6355…7777d`）。`McpClientTest.java` 的 +69 行全部是装配代码（新增 `TestClientBuilder`、重写 `newClient`/`builder`），**没有删除或放宽任何断言**。
- **超范围设计只有一处**（已披露并有理由）：`McpTransportFailureSource`；`StdioTransport` 的 public 化是卡 L62 明文许可；`JsonRpcCodec` 改为内部创建是配置拆分的必然连带。
- **自审诚实度**：自审如实写明"环境不允许建 socket、两条验收命令未跑"，未伪造绿；但它的对照表标题是「测试要求 1-8 对照」，**整条漏掉了要求 7（notify 202/空 body）并把 close/UTF-8 重编号为 7/8**，使验收矩阵看起来 9/9 齐全 —— 属自审缺陷（非代码问题），须在原文档补齐。

另有两点实现观察，值得在上层复用前处理：`McpClient.isAvailable()`（`McpClient.java:84-92`）**有副作用** —— transport 未运行时它会调 `onTransportUnavailable()`（清空 schema 缓存 + 调度重启），一个"查询"方法触发状态变更与重启，测试断言里也在调用它（`HttpTransportTest.java:118/:129/:152`），当前用例下无害但语义意外；`onTransportUnavailable` 与 `restart` 的并发边界（重启期间再次失败）建议补一条"连续两次失效"的用例。

---

## 判定：**有条件通过**

实现本身质量高：23 条用例我独立复跑全绿、root 28 条基线未被碰坏、重定向不可利用、无凭据泄漏、无线程/许可泄漏、404 重握手行为经探针实证完全正确、既有断言零改动。**没有发现实现级必修缺陷**，问题是覆盖与流程：

**必须修（卡内）**
1. `HttpTransportTest.java:122-134` 补上卡 L109-110 明文要求的断言：把 `restartBackoff` 调小（如 50ms），等待并断言**重握手请求的 `Mcp-Session-Id` 为 null**，且重建后 `toolSchema`/`callTool` 恢复（探针 P1 即此写法，稳定通过；现在的 5s 退避让该用例永远看不到重握手）。
2. 补一条 notify 显式用例：服务端对 `notifications/*` 回 202 + 空 body（并覆盖 `Content-Length: 0`、204 变体），断言不抛异常且 `isRunning()` 仍为 true（探针 P2 三种变体均通过）。
3. 自审对照表补回要求 7、如实标注要求 6 的覆盖强度（只覆盖 fail-fast，未覆盖重握手头）。

**必须上报（卡外，需人工决定）**
4. CI 覆盖缺口：`.github/workflows/ci.yml` 为 JDK 8、root `pom.xml` 无 `<modules>` → agent-core 23 条用例不进 CI，等于无回归守护。

**建议（可选，不阻塞）**
5. `validateEndpoint`（`HttpTransport.java:77-85`）拒绝 userinfo（P6 实证凭据被静默丢弃、实际匿名连接）；接线时再加 host 白名单/内网策略。
6. `McpClient.isAvailable()` 去掉副作用（改名 `refreshAvailability()` 或把自愈挪到内部路径）。
7. `notify` 超时不要写死 `handshakeTimeout`（`:236`），允许调用方指定。
8. `readAllBytes`（`:305`）与 SSE 行缓冲（`:313-340`）加字节上限；`captureSession`（`:295-302`）对服务端会话 id 做字符集/长度校验（当前非法字符经 `:162` 降级为 `McpUnavailableException`，方向安全）。
9. 空/缺失 `Content-Type` 的响应直接判协议错误（`:215-217`），可考虑嗅探；SSE 非 JSON `data:`（探针 P3b）可改为跳过而非失败该次调用。
10. "从未持有会话却收到 404"（多为 URL 配错）与真正的会话失效可分别诊断，避免无信息量的无限退避重启循环。
