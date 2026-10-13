# 任务卡 P1-1 — MCP stdio 客户端（agent-core 第一块）

难度：困难
交给：Codex（`codex exec -m gpt-5.6-sol --sandbox workspace-write`）
理由：协议实现 + 并发正确性 + 子进程管道管理，多文件结构，命中「困难」档。

## 目标

在仓库根新建一个**独立 Maven 模块** `agent-core/`，实现手册 §4.2 要求的 MCP stdio 客户端。
本卡只做：JSON-RPC 编解码 + stdio 传输 + 握手 + 超时 + 退避重启。**不做** HTTP 传输、不做 LAYA 业务工具封装（留给下一卡）。

## 必读文件

- `AGENTS.md`（铁律与验证命令，必须遵守）
- `PROJECT_IMPLEMENTATION_PLAN.md`（尤其 Δ3、Δ9）
- 参考手册 §4.1 / §4.2 / §4.5 / §4.6（手册在仓库外，要点已抄在本卡下方）

## 只许写这些文件

- `agent-core/pom.xml`（新建）
- `agent-core/src/main/java/dev/lifeagent/mcp/**`（新建）
- `agent-core/src/test/java/dev/lifeagent/mcp/**`（新建）
- 可以为 `agent-core` 加一份 `agent-core/README.md`

**禁止**：改根 `pom.xml`（不许把 agent-core 注册进 `<modules>`，见 Δ9）、改 `src/`、改 `nginx-1.18.0/`、改 `.github/`、改任何现有测试。

## 模块规格

- `groupId dev.lifeagent`，`artifactId agent-core`，`version 0.1.0-SNAPSHOT`
- **JDK 21**（`maven.compiler.release=21`），编译器用 `maven-compiler-plugin`
- 依赖**只允许**：`com.fasterxml.jackson.core:jackson-databind`、`org.junit.jupiter:junit-jupiter`(test)。
  **不许引 Spring、不许引任何 MCP 官方 SDK** —— 全自研是方案要求。
- 必须能 `mvn -f agent-core/pom.xml -B -ntp test` 独立构建，不依赖根 pom。

## 实现要求（逐条可验收）

1. **JSON-RPC 2.0 消息模型 + 编解码**：request / response / notification / error。
   用 Jackson 序列化，**必须保证单行输出**（消息内不允许出现裸换行，否则破坏 framing）。
2. **行框定（line framing）**：stdio 下一行一条消息。反序列化要能容忍多字节 UTF-8 跨 buffer 边界。
3. **`StdioTransport`**：
   - `ProcessBuilder` 拉起子命令（命令与 env 由配置传入）
   - **独立 stdout 读线程**：逐行读，按 `id` 找 `pending` map 里的 `CompletableFuture` 完成它。多条请求可同时在途，不排队。
   - **独立 stderr 读线程（关键，手册 §4.5 明示最容易踩）**：必须一直读，转发到日志接口。未读会导致管道写满、子进程卡死、所有调用超时。
   - **stdin 写入加锁**：否则两条消息会交错成一行。
   - **每调用超时**：超时后从 pending 移除并抛 `McpTimeoutException`。
   - **进程死亡/连续超时 → 带退避重启**（退避序列可配，如 1s/5s/30s），重启后重新握手；重启期间新调用直接按降级失败，不排队等。
   - **并发上限**：用 `Semaphore` 限制在途请求数（可配）。
4. **握手**：`initialize`（带 protocolVersion + clientInfo）→ 收响应 → 发 `notifications/initialized` → `tools/list`，
   缓存工具名与 inputSchema。任一步失败 = 客户端不可用。
5. **对外 API**：`McpClient implements AutoCloseable`，暴露
   `JsonNode callTool(String name, Map<String,Object> args, Duration timeout)`、
   `boolean isAvailable()`、`Optional<JsonNode> toolSchema(String name)`、`close()`。
6. **`close()` 必须干净**：停读线程、销毁子进程、唤醒所有 pending future（避免悬挂线程）。

## 测试要求（必须真跑，不许只有 happy path）

用 `src/test/java/.../FakeMcpServer.java`（自己写一个 `public static void main`，测试里当子进程拉起）来覆盖：

1. 握手成功 → `tools/list` 返回的工具能被解析。
2. 并发 5 个 `tools/call` 同时在途，每个都拿到**自己**的结果（id 不错配）。
3. **stderr 洪泛测试**：FakeMcpServer 持续往 stderr 写大量数据（如 5MB），
   期间 `tools/call` 仍必须在超时内成功返回。**这条是回归红线**，证明了 §4.5 的坑被修掉。
4. 超时：FakeMcpServer 对某个方法故意不回 → 抛 `McpTimeoutException`，且 pending 被清理。
5. 进程中途死亡 → 客户端 `isAvailable()` 变 false，调用按降级失败而不是永久挂起。
6. 中文/emoji 参数往返不乱码。
7. `close()` 后无残留线程（用线程名断言或 `Thread.getAllStackTraces()` 校验）。

## 验收命令

```bash
cd /root/projects/llrs-audit
mvn -f agent-core/pom.xml -B -ntp test          # 必须 BUILD SUCCESS
mvn -f agent-core/pom.xml -B -ntp -q compile    # 必须无警告级错误
# 回归：基线不许被碰坏
export MAVEN_OPTS="-Xmx640m"; mvn -B -ntp verify   # 仍须 28 tests 全绿
```

## 当前测试基线

`mvn verify` → **Tests run: 28, Failures: 0, Errors: 0, Skipped: 1**，59s，BUILD SUCCESS。
**必须保持全绿。**

## 交付前必须做的三遍审查

1. **自审**（你做）：对照下面「实现要求」7 条逐条打勾，跑通验收命令，写下你没做到/降级的地方。
   在 `agent-core/SELF-REVIEW-P1-1.md` 里写明：要求编号 → 实现位置（文件:行）→ 是否达成 → 备注。
2. 之后由**异源模型（DSH）只读复核**你的产物 —— 所以代码要写得能被逐条核对，
   每个要求都留下可定位的痕迹（类名/方法名/测试名与要求对应）。
3. 最后由管理者独立重跑验收命令并对照任务卡核验。

**禁止**：为了让自审好看而调整测试断言；禁止只写 happy path；
禁止改本卡之外的文件。有把握不了的取舍，写进自审记录里说明，不要自作主张扩范围。

## 明确不做

- 不实现 HTTP 传输（下一卡）
- 不封装 LAYA 业务工具（`laya_preset` / `laya_decide` 等留给下一卡）
- 不接 Spring、不接业务代码、不改数据库
- 不 push、不 commit（提交由管理者统一做）
