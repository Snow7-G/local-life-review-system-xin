# 待批准事项（偏离手册的决定）

依 `docs/REVIEW-PROTOCOL.md`：偏离手册的决定一律标 `待批准`，未经嘉点头不得落地实现。

---

## AP-001 · W1 期间提前做 MCP HTTP 通道

- **手册原文**（§4.1）：「以后要是想多个实例共用一个 LAYA，可以在前面套一个把 stdio 转成 HTTP 的 MCP 代理」——
  即 HTTP 通道是**将来可选**项。
- **拟改为**：W1 就并行实现 HTTP 通道，默认走 HTTP，stdio 保留为可选。
- **理由**：
  1. 主机内存实测总 3.4 GiB、`available` 仅 1.7 GiB。手册 §4.1 的「每个应用实例自带一个 LAYA 子进程（sidecar）」
     在 4 GiB 机型上叠加 JVM + MySQL + Redis 必然 OOM。
  2. 「LAYA 只支持 stdio」只对官方 `laya-mcp-server` 成立；第三方 `ollaya` 已用 HTTP 服务同款权重且
     wire 兼容 TypeSafe，故 HTTP 不是自造轮子。
  3. HTTP 让 LAYA 可外置到别的机器，绕过本机内存瓶颈。
- **影响**：W1 工作量增加；但 stdio 客户端（P1-1）已交付，HTTP 复用同一 `McpClient` 抽象。
- **不批准的后果**：只能按手册原样在单机起 sidecar，本机需要先扩容（花钱 → 另一件需批准的事）。

**状态：待批准**

---

## AP-002 · W1 把 Spring Boot 升级提前到 agent-core 之前

- **手册原文**（§14 第 1 周）：升级 JDK 21 + Boot 3 本就是第 1 周任务，此项**不属偏离**，仅在此登记顺序确认。
- **拟改为**：不变，按手册执行（W1 先升级、回归原功能，再动 agent-core 的只读闭环）。
- **理由**：手册 §15「升级踩坑」明确「宁可推迟后面的功能，也别在半升级状态上继续加代码」。
- **状态：不需批准（照手册执行）**

---

## AP-003 · agent-core 暂不进入根 reactor

- **手册原文**：未提及。（手册 §3 只说 agent-core 是一个独立 Maven 模块。）
- **拟改为**：`agent-core` 独立构建（`mvn -f agent-core/pom.xml`），暂不写进根 `pom.xml` 的 `<modules>`。
- **理由**：CI 跑 **JDK 8**，agent-core 需 **JDK 21**。一旦注册进 reactor，CI 立刻变红，
  违反「基线必须保持全绿」。等 W1 升完 JDK 21 再并入。
- **状态：待批准（我的建议是照此执行）**

---

## AP-004 · DSH 复核报告中的加固建议（非阻塞，尚未落地）

来源 `docs/reviews/P1-1-review.md`「建议清单」。**均未落地**，等指示：

| 优先级 | 建议 | 位置 |
|---|---|---|
| 高 | 子进程 env 改为 `clear()` 后仅注入配置项，避免继承父进程全部凭据 | `StdioTransport.java:72` |
| 高 | stderr 洪泛断言由 `>4 MiB` 收紧到贴近 5 MiB，并补「logger 抛异常时 drain 不中断」用例 | `McpClientTest.java:68` |
| 中 | 补 `JsonRpcCodec` 单行守卫用例（注入 `INDENT_OUTPUT` mapper 断言抛异常） | `JsonRpcCodecTest.java` |
| 中 | 处理 server→client 请求（`sampling/createMessage`、`roots/list`）：至少记日志、回 `-32601`，不要静默丢弃 | `StdioTransport.java:181-183` |
| 中 | 「close 后无残留线程」补充 `mcp-client-N-lifecycle` 与子进程存活断言 | `McpClientTest.java:110-122` |
| 低 | 响应 id 非整数/`null` 时改为丢弃并记日志，而非整会话销毁 | `StdioTransport.java:211-214` |
| 低 | stdout 非 JSON 行容忍策略（连续 N 行才判死），避免启动 banner 触发重启循环 | `StdioTransport.java:185-190` |
| 低 | `decode` 增加尾随内容校验；`callTool` 包装 Jackson 异常为 `McpException` | `JsonRpcCodec.java:39`；`McpClient.java:68` |

**状态：已批准（2026-10-13，嘉以「A」全批）**

批准范围：AP-001 提前做 HTTP 通道、AP-003 agent-core 暂不进根 reactor、
AP-004 的**两条高优先级加固**（见 `docs/tasks/P1-2-mcp-hardening.md`）。
AP-004 其余 6 条中低优先级项不在本次批准内，留待 W1 一并处理。
