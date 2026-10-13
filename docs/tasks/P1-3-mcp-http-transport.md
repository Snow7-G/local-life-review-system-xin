# 任务卡 P1-3 — MCP HTTP 通道（引入传输层抽象）

难度：困难
交给：Codex（`codex exec -m gpt-5.6-sol --sandbox workspace-write`）
理由：需要把 `McpClient` 与 `StdioTransport` 解耦成传输抽象，属**多文件重构 + 协议实现**，
并要保持 14 条既有测试全绿——命中「困难：多文件重构 / 编排」。

**前置**：P1-1、P1-2 已交付（`8942e0b`、`84cc416`）。
**依据**：AP-001 已获嘉批准（`docs/APPROVALS-PENDING.md`）——
手册 §4.1 把 HTTP 视为"将来可选"，现按批准提前到 W1 实现。

## 为什么必须做（别忘了这个理由）

本机实测总内存 3.4 GiB / `available` 仅 1.7 GiB。手册 §4.1 的「每个应用实例自带一个 LAYA 子进程
（sidecar）」在本机必 OOM。HTTP 通道让 LAYA 可外置到别的机器，绕开内存瓶颈。
另：P1-2 的默认环境隔离使 stdio 子进程拿不到 `PATH`（Codex 复核已指出），
HTTP 通道没有子进程，天然绕开该问题——**后续接 LAYA 首选 HTTP**。

## 目标

让 `agent-core` 支持两种 MCP 传输，且对 `McpClient` 完全透明：

1. 抽出 `McpTransport` 接口，`StdioTransport` 与新增 `HttpTransport` 都实现它
2. `HttpTransport` 实现 **MCP Streamable HTTP** 传输（JSON-RPC over HTTP + SSE）
3. 既有 14 条测试**全部保留且全绿**；HTTP 侧新增测试

## 只许写这些文件

- `agent-core/src/main/java/dev/lifeagent/mcp/**`
- `agent-core/src/test/java/dev/lifeagent/mcp/**`
- 新建 `agent-core/SELF-REVIEW-P1-3.md`

**禁止**：改根 `pom.xml` 的 `<modules>`、改 `src/` 业务代码、改 `nginx-1.18.0/`、改 `.github/`、
改 `agent-core/pom.xml` 的依赖集合、**新增任何第三方依赖**（HTTP 用 JDK 自带 `java.net.http`，
测试服务端用 JDK 自带 `com.sun.net.httpserver`）。

## 允许的测试改动（重要边界）

- **允许**：为适配新构造方式而改测试里的**构造/装配代码**（如 `builder()` 辅助方法）。
- **禁止**：删除任何既有测试方法；放宽/删除任何既有**断言**；降低断言强度。
- **硬指标**：既有 14 条测试方法**一条不少**，且全部通过。

## 实现要求

### 1. 传输抽象

抽出接口（包 `dev.lifeagent.mcp`）：

```java
public interface McpTransport extends AutoCloseable {
    void start();
    JsonNode request(String method, JsonNode params, Duration timeout);
    void notify(String method, JsonNode params);
    boolean isRunning();
    int pendingCount();
    String threadNamePrefix();
    void failCurrent(String message, Throwable cause);
    void close();
}
```

`StdioTransport implements McpTransport`（方法可见性按接口要求放宽为 `public`，行为不变）。

配置拆分：把 stdio 专有项（`command` / `environment` / `inheritParentEnvironment`）
从 `McpClientConfig` 移到新的 `StdioTransportConfig`，`McpClientConfig` 只保留**两者共用**的项
（`maxConcurrentRequests` / `restartBackoff` / `consecutiveTimeoutsBeforeRestart` /
`handshakeTimeout` / `protocolVersion` / `clientName` / `clientVersion`）。
`McpClient` 改为接收 `McpTransport`（构造注入），不再自己 new `StdioTransport`。

> 若你认为有更小的改动面能同时满足「接口透明 + 两种传输可用」，可以自行取舍，
> 但必须在自审里写清取舍理由，并保证既有 14 条测试的**断言**一字未改。

### 2. HttpTransport（MCP Streamable HTTP）

- 端点：单个 URL（如 `http://127.0.0.1:PORT/mcp`）。
- **POST** 发 JSON-RPC 消息；请求头至少：
  - `Content-Type: application/json`
  - `Accept: application/json, text/event-stream`
  - 若已持有会话：`Mcp-Session-Id: <id>`
  - 握手完成后：`MCP-Protocol-Version: <协商版本>`
- **响应两种形态都要处理**：
  - `Content-Type: application/json` → 直接解析单个 JSON-RPC 响应
  - `Content-Type: text/event-stream` → 逐 `data:` 行解析 SSE，**按 id 匹配**取回本次调用的响应；
    遇到不匹配 id 的事件要跳过而非报错（可能有通知或他人响应）
- **会话管理**：`initialize` 的响应头若含 `Mcp-Session-Id`，后续请求必须带上；
  服务端返回 `404` 表示会话失效 → 标记传输不可用并走既有重启/重握手路径。
- 与 stdio 一致的能力：**每调用超时**、**并发上限（信号量）**、**按 id 分发 pending**、
  **不可用时立即降级失败**、**`close()` 干净退出**。
- 网络异常（连接拒绝、读超时）→ `McpUnavailableException`；HTTP 4xx/5xx 按可重试性区分，
  但**不得**把 HTTP 状态码本身泄漏成业务异常给上层。

### 3. 测试（HTTP 侧，必须真跑）

用 JDK 自带 `com.sun.net.httpserver.HttpServer` 起**真实本地 HTTP 服务端**（不是 mock 框架）：

1. 握手成功：`initialize` 回带 `Mcp-Session-Id`，`tools/list` 返回工具 → schema 缓存可用。
2. **会话 ID 传递**：断言后续请求确实带上了 `initialize` 返回的 `Mcp-Session-Id`。
3. `tools/call` 走 `application/json` 与走 `text/event-stream`（SSE，且**混入一个不匹配 id 的事件**）
   两种形态都能正确取回结果。
4. 并发：≥3 个调用同时在途，各自拿到自己的结果（id 不错配）。
5. 超时：服务端故意不回 → `McpTimeoutException`，且 pending 被清理。
6. 会话失效：服务端对已失效会话回 `404` → 传输变为不可用，调用降级失败而非挂起。
7. `close()` 后无残留线程、且后续调用被拒绝。
8. HTTP 侧的 UTF-8 中文/emoji 往返正确。

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"
mvn -f agent-core/pom.xml -B -ntp test     # 必须 BUILD SUCCESS；测试数 ≥ 22（原 14 + HTTP 侧 ≥8）
# 回归：基线不许被碰坏
mvn -B -ntp verify                          # 仍须 28 tests 全绿
```

## 当前测试基线

- `agent-core`：**Tests run: 14, Failures: 0, Errors: 0, Skipped: 0**
- 根回归 `mvn verify`：**Tests run: 28, Failures: 0, Errors: 0, Skipped: 1**
- 两者都必须保持全绿；`agent-core` 只能增加不能减少。

## 交付前必须做的三遍审查（见 `docs/REVIEW-PROTOCOL.md`）

1. **自审**：写 `agent-core/SELF-REVIEW-P1-3.md`，逐条记录
   「要求编号 → 实现位置（文件:行）→ 是否达成 → 备注」；并**单列一节**说明：
   - 配置拆分的取舍，以及为什么既有 14 条测试的断言一字未改
   - 既有测试的**装配代码**改了哪些、为什么改（不得借装配之名改断言）
2. 之后由**异源模型（DSH）只读复核**你的产物（本卡是 Codex 写 → DSH 审）。
3. 最后由管理者独立重跑验收命令，并由管理者做一次**变异验证**。

**禁止**：删除/放宽既有断言；只写 happy path；实现真实 LAYA 接入（那是 W2）；
在 `agent-core` 里引入 Spring 或任何第三方依赖。

## 明确不做

- 不接真实 LAYA 服务端（W2）
- 不做 LAYA 业务工具封装（`laya_preset` 等）
- 不改业务 `src/`、不动根 reactor
- 不 commit、不 push
