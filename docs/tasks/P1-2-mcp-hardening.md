# 任务卡 P1-2 — MCP 客户端两项高优先级加固

难度：日常
交给：DSH（`dsh headless`）
理由：改动小且规格已被复核精确锁定，属「已定位的 bug / 方案定好的测试」；无需 Codex。

**前置**：P1-1 已交付并提交（`8942e0b`）。本卡只做加固，不做新功能。
**嘉已批准**（2026-10-13「A」）：见 `docs/APPROVALS-PENDING.md`。

## 目标

落地 `docs/reviews/P1-1-review.md` 里两条【高】优先级加固：

1. **子进程环境变量不再继承父进程**
2. **stderr 洪泛断言收紧到真实下界**，并补一条 drain 不中断的回归用例

## 只许写这些文件

- `agent-core/src/main/java/dev/lifeagent/mcp/StdioTransport.java`
- `agent-core/src/main/java/dev/lifeagent/mcp/McpClientConfig.java`（若需新增开关）
- `agent-core/src/test/java/dev/lifeagent/mcp/McpClientTest.java`
- `agent-core/src/test/java/dev/lifeagent/mcp/FakeMcpServer.java`（仅当为并发用例所需）
- 更新 `agent-core/SELF-REVIEW-P1-2.md`（新建）

**禁止**：改根 `pom.xml` 的 `<modules>`、改 `src/` 业务代码、改 `nginx-1.18.0/`、改 `.github/`、
改 P1-1 既有测试的**其它**断言、实现 HTTP 通道（那是 P1-3）。

## 加固 1：子进程环境隔离

现状 `StdioTransport.java:70-72`：

```java
ProcessBuilder builder = new ProcessBuilder(config.command());
builder.redirectErrorStream(false);
builder.environment().putAll(config.environment());
```

`ProcessBuilder` 默认**继承父进程全部环境变量**，而 MCP 子进程是第三方进程，
等于把父进程的 token / 密钥（如 `HMDP_DB_PASSWORD`、`GOAT_KEY`、云厂商凭据）全量交给它。

**要求**：

1. `builder.environment().clear()` 后**只**注入 `config.environment()`。
2. 新增配置项 `inheritParentEnvironment`（默认 **false**），供确有需要时显式打开；
   `McpClientConfig` 里加 Builder 方法，并在 compact constructor 里做防御性处理。
3. 文档注释说明为什么默认隔离（安全），以及打开它意味着什么。

**测试要求**（必须真跑）：

- 用例 A：父进程存在一个哨兵环境变量（测试内 `System.getenv` 无法注入，改为
  **子进程侧自证**）：让 `FakeMcpServer` 支持 `dump_env` 工具，返回它自己
  `System.getenv()` 的键集合；客户端以隔离模式启动，断言哨兵键**不在**其中，
  而 `config.environment()` 里显式注入的键**在**。
  哨兵键通过 `ProcessBuilder` 的父进程环境设置：在测试里用
  `ProcessHandle.current().info().environment()` 不可改，故改为：
  测试通过一个 `-D` 参数把哨兵名传给 FakeMcpServer，由它检查该键是否存在。
  **实现方式由你决定，但必须真实验证「隔离后父环境不泄漏」这一点**，不能只断言代码调用了 clear()。

## 加固 2：stderr 洪泛断言收紧（这条有坑，务必读）

现状 `McpClientTest.java:68`：

```java
assertTrue(loggedCharacters.get() > 4L * 1024 * 1024, ...);
```

服务端 `FakeMcpServer.java:119-130` 写入**恰好** `5 * 1024 * 1024` 字节，其中每 1024 字节的最后
1 个是换行符 `\n`。客户端用 `readLine()` 读，**换行符不进入返回的字符串**。因此：

- 服务端写入总量：`5 * 1024 * 1024` = 5,242,880 字节
- 整行数：`5,242,880 / 1024` = 5,120 行
- 客户端**能收到**的字符上限：`5,120 × 1,023` = **5,237,760**

**若照「贴近 5 MiB」字面断言 `>= 5 MiB`，必然失败**（差 5,120 字节）。

**要求**：

1. 在 `FakeMcpServer` 侧把洪泛规模定义成具名常量（如 `FLOOD_BYTES = 5 * 1024 * 1024`
   和 `FLOOD_LINE_BYTES = 1024`），并在 `McpClientTest` 里以**同样的算式**推导下界，
   写清注释说明换行不计入字符数。断言用推导出的精确下界（当前为 5,237,760），
   **不要**写 `> 4 MiB` 这种松边界，也**不要**写 `>= 5 MiB` 这种必挂的值。
2. 断言前用**有界等待**（复用测试里已有的 `await(...)`）等待消费量达标，
   超时上限给足（如 30s），避免时序抖动导致偶发失败。
3. 调用本身仍须成功返回 `ok=true`。

**测试要求（新增）**：`stderrLogger` 抛异常时，drain **不中断**。

- 让 logger 在第 N 行（如第 3 行）抛 `RuntimeException`，其余正常计数。
- 断言：调用仍成功，且最终消费量**仍达到**上述下界
  （证明 `StdioTransport.java:199-203` 的 try/catch 真的在起作用，
  而不是抛异常后 stderr 停读、管道写满、调用超时）。

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"
mvn -f agent-core/pom.xml -B -ntp test     # 必须 BUILD SUCCESS，且测试数 ≥ 13（原 11 + 新增≥2）
# 回归：基线不许被碰坏
mvn -B -ntp verify                          # 仍须 28 tests 全绿
```

## 当前测试基线

- `agent-core`：**Tests run: 11, Failures: 0, Errors: 0, Skipped: 0**
- 根回归 `mvn verify`：**Tests run: 28, Failures: 0, Errors: 0, Skipped: 1**
- 两个都必须保持全绿（agent-core 允许增加，不允许减少或变红）。

## 交付前必须做的三遍审查（见 `docs/REVIEW-PROTOCOL.md`）

1. **自审**：写 `agent-core/SELF-REVIEW-P1-2.md`，逐条记录
   「要求编号 → 实现位置（文件:行）→ 是否达成 → 备注」。特别写明：
   洪泛下界用的是哪个算式、算出来多少、为什么不是 5 MiB。
2. 之后由**异源模型（Codex）只读复核**你的产物（P1-1 是 Codex 写、DSH 审，本卡反过来）。
3. 最后由管理者独立重跑验收命令。

**禁止**：为让断言通过而放宽既有断言；只写 happy path；在自审里隐瞒未达成项。

## 明确不做

- 不实现 HTTP 通道（P1-3）
- 不动 AP-004 里另外 6 条中低优先级建议（留 W1）
- 不 commit、不 push
