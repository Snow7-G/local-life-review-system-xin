# P1-2 自审记录

自审日期：2026-10-14
任务卡：`docs/tasks/P1-2-mcp-hardening.md`
基线：`agent-core` 11 tests；根回归 `mvn verify` 28 tests / Skipped 1

**结论**：加固 1、加固 2 的实现要求与测试要求逐条达成。`agent-core` 独立模块由 11 → **14 tests 全绿**（新增 3 条，要求 ≥13），根回归 **28 / 0 / 0 / Skipped 1** 与基线逐项一致。两条关键新用例都做了**变异验证**（把实现改回旧行为后用例确实变红），证明它们不是空洞断言、不是"只断言调用了 clear()"。未修改任务卡允许范围之外的任何文件，未 commit、未 push。

## 一、改动文件（全部落在任务卡「只许写这些文件」清单内）

| 文件 | 性质 | 行数 |
|---|---|---:|
| `agent-core/src/main/java/dev/lifeagent/mcp/StdioTransport.java` | 改（加固 1 实现） | 398 |
| `agent-core/src/main/java/dev/lifeagent/mcp/McpClientConfig.java` | 改（新增开关） | 165 |
| `agent-core/src/test/java/dev/lifeagent/mcp/FakeMcpServer.java` | 改（夹具：`dump_env` + 洪泛常量） | 234 |
| `agent-core/src/test/java/dev/lifeagent/mcp/McpClientTest.java` | 改（断言收紧 + 3 条新用例） | 316 |
| `agent-core/SELF-REVIEW-P1-2.md` | 新建（本文件） | — |

`git status --porcelain` 显示 4 个已跟踪文件为 `M`，另有本自审文件为 `??`（新建，即上表第 5 行），无其它新增/删除文件；根 `pom.xml`、`src/` 业务代码、`nginx-1.18.0/`、`.github/`、`agent-core/pom.xml` 均未触碰。

## 二、加固 1：子进程环境隔离

### 2.1 实现要求逐条

| 要求编号 | 实现位置（文件:行） | 是否达成 | 备注 |
|---|---|---:|---|
| 1 `builder.environment().clear()` 后**只**注入 `config.environment()` | `StdioTransport.java:72-83`（`clear()` 在 `:81`，注入在 `:82`） | 是 | `ProcessBuilder.environment()` 返回该 builder 持有的可变 map，就地 `clear()` 即生效；默认分支先清空再 `putAll(config.environment())`。 |
| 2 新增配置项 `inheritParentEnvironment`（默认 false） | `McpClientConfig.java:33`（record 组件）、`:85`（Builder 字段初值 `false`）、`:118-121`（Builder 方法）、`:160`（`build()` 透传） | 是 | 默认值落在 Builder 字段；开关为记录组件，保证构造后不可变。 |
| 2′ compact constructor 里做防御性处理 | `McpClientConfig.java:46`（说明性注释） | **见 5.1 披露** | `boolean` 是基本类型：无 null、无引用别名、无可变内容，不存在需要复制或校验的对象；因此"防御性"落在唯一有意义的位置——Builder 字段初始化为安全默认 `false`。此处只放了注释，没有写无意义代码（如 `if (inheritParentEnvironment) { }`）。请复核判定是否需要补代码级动作。 |
| 3 文档注释说明为什么默认隔离、打开意味着什么 | `McpClientConfig.java:12-29`（record Javadoc 的 Security posture 段 + `@param inheritParentEnvironment`）；`StdioTransport.java:76-80`（实现处注释） | 是 | 写明父环境"routine carries credentials (API tokens, database passwords, cloud keys)"，且隔离是更可预测的行为；同时写明确有需要（如服务端要靠 `PATH` 找辅助二进制）可显式 opt-in。 |

### 2.2 测试要求（用例 A）

| 要求 | 实现位置（文件:行） | 是否达成 | 备注 |
|---|---|---:|---|
| `FakeMcpServer` 支持 `dump_env`，返回**它自己** `System.getenv()` 的键集合 | `FakeMcpServer.java:112`、`:141-156` | 是 | 证据来自子进程内部自证，而非客户端对自己 API 的断言。 |
| 哨兵键由测试真实父进程环境提供 | `McpClientTest.java:287-294`（`parentEnvironmentSentinel()`） | 是 | 取测试 JVM 的 `PATH`；环境为空时直接 `fail`，避免空跑假通过。 |
| 哨兵名传给子进程 | `McpClientTest.java:102-103`（参数 `env-sentinel=<NAME>`）→ `FakeMcpServer.java:36`、`:146`、`:158-163` | 是 | 任务卡建议 `-D`，我改用程序参数，理由见 5.2。 |
| 隔离模式下哨兵键**不在**子进程环境中 | `McpClientTest.java:100-125`（断言在 `:116`、`:118`） | 是 | 不仅断言哨兵不在，还断言**子进程看到的键集合恰好等于注入集合**（`:118` `assertEquals(Set.of(INJECTED_ENV_NAME), childKeys, ...)`）——父进程任何其它变量泄漏进来都会红。 |
| 显式注入的键**在**且值正确 | `McpClientTest.java:121-123` | 是 | `injectedPresent == true` 且 `injectedValue == "injected-by-config"`。 |

**为什么这不是"只断言调用了 clear()"**：断言对象是子进程 `System.getenv()` 的真实快照（`FakeMcpServer.environmentReport()`，`FakeMcpServer.java:141`），客户端全程没有观测 `ProcessBuilder` 内部状态。

### 2.3 正向对照用例（超出卡片要求，但为必要性所在）

`McpClientTest.java:128-148`：同一哨兵、同一套夹具参数，仅把 `inheritParentEnvironment` 打开为 `true`，断言哨兵**在**子进程环境里。
没有这条，"哨兵不在"有可能只是夹具根本没有观测环境的能力造成的假通过；有了它，同一套观测链路在两种模式下给出相反结果，隔离结论才成立。

### 2.4 变异验证：把实现改回旧行为，用例 A 确实变红

把 `StdioTransport.java:81` 的 `childEnvironment.clear();` 注释掉（其余不动），单独运行用例 A：

```
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0 ... in dev.lifeagent.mcp.McpClientTest
org.opentest4j.AssertionFailedError: the child must not see the parent variable PATH ==> expected: <false> but was: <true>
```

即：旧行为（只 `putAll` 不清空）下，**子进程真的能读到父进程的 `PATH`**，用例立刻失败。这就是"隔离后父环境不泄漏"的真实证据。验证完成后已还原文件（还原后 `grep -rn MUTATION agent-core/src/` 无结果；`git diff` 仅剩预期改动）。

## 三、加固 2：stderr 洪泛断言收紧

### 3.1 下界算式（任务卡要求写明）

- 子进程写入总量：`FLOOD_BYTES = 5 * 1024 * 1024 = 5,242,880` 字节（`FakeMcpServer.java:32`）
- 每行 `FLOOD_LINE_BYTES = 1,024` 字节，换行符是该块的**最后一个字节**（`FakeMcpServer.java:34`、`:166-177`；`5,242,880 / 1,024` 整除，无残块）
- 完整行数：`5,242,880 / 1,024 = 5,120` 行
- 客户端用 `BufferedReader.readLine()` 读，**行终止符不进入返回字符串**，故每行贡献 `1,024 − 1 = 1,023` 个字符
- 客户端**能收到**的字符上限：`5,120 × 1,023 = 5,237,760`

**为什么不是 5 MiB**：5,242,880 是**字节**总量，其中 5,120 个字节是行终止符，`readLine()` 交付时被丢弃。按字符计的可见上限必然小于字节总量，差值恰好 5,120。因此照字面写 `>= 5 MiB`（5,242,880）对一个**完全正确**的实现也必然失败（差 5,120 字节）；反过来旧的 `> 4 MiB` 容忍丢掉约 1/5 的洪泛。

测试中的下界由**同一算式**、从夹具的同一批常量推导（`McpClientTest.java:36-50`）：

```java
private static final long FLOOD_MAX_LOGGED_CHARS =
        (long) (FakeMcpServer.FLOOD_BYTES / FakeMcpServer.FLOOD_LINE_BYTES)
                * (FakeMcpServer.FLOOD_LINE_BYTES - 1);
```

并把算出的字面值单独钉死一行：`assertEquals(5_237_760L, FLOOD_MAX_LOGGED_CHARS, ...)`（`McpClientTest.java:91`），防止将来有人改夹具常量时算式语义悄悄漂移而无人察觉。

### 3.2 要求逐条

| 要求编号 | 实现位置（文件:行） | 是否达成 | 备注 |
|---|---|---:|---|
| 1 具名常量 + 同样算式推导下界 + 注释写清"换行不计入字符数" | `FakeMcpServer.java:29-34`；`McpClientTest.java:36-50` | 是 | 注释含完整推导文字（5,120 行、1,023 字符/行、上限 5,237,760）。 |
| 1′ 断言用精确下界，不用 `> 4 MiB` / `>= 5 MiB` | `McpClientTest.java:91-96` | 是 | `assertEquals` 钉字面值 + `>= FLOOD_MAX_LOGGED_CHARS`。原 `> 4 MiB` 松断言已删除。 |
| 2 有界等待，超时给足 | `McpClientTest.java:93` | 是 | 复用既有 `await(...)`（20ms 轮询），超时上限 30s。 |
| 3 调用本身仍须 `ok=true` | `McpClientTest.java:90` | 是 | 既有 `assertTrue(result.path("ok").asBoolean())` 保留，未被削弱。 |
| 新增：`stderrLogger` 抛异常时 drain 不中断 | `McpClientTest.java:151-177` | 是 | logger 在第 3 行抛 `IllegalStateException`（`:158-160`），其余行正常计数；断言：调用仍 `ok=true`（`:167`）、抛出次数恰为 1（`:170`）、最终消费量仍 ≥ 下界（`:169-175`）。 |

**实测证据**：`>= 5_237_760` 这条断言在真机上通过，而 5,237,760 是**理论最大值**，所以通过本身即证明 drain 一个字节都没丢（丢任何一行都会低于上限从而失败）。

### 3.3 变异验证：去掉 `readStderr` 的 try/catch，新用例确实变红

把 `StdioTransport.java:210-214` 的 try/catch 去掉（只留 `logger.accept(line);`），单跑新用例：

```
Exception in thread "mcp-stdio-1-stderr" java.lang.IllegalStateException: stderr sink is broken on purpose
	at dev.lifeagent.mcp.StdioTransport.readStderr(StdioTransport.java:210)
	at dev.lifeagent.mcp.StdioTransport.lambda$createSession$1(StdioTransport.java:179)
	at java.base/java.lang.Thread.run(Thread.java:1583)
[ERROR] dev.lifeagent.mcp.McpClientTest.stderrLoggerFailureDoesNotStopTheDrain ... 
dev.lifeagent.mcp.McpTimeoutException: MCP request timed out: tools/call
```

失败链与任务卡预判完全一致：logger 抛异常 → drain 线程死掉 → **管道写满** → 子进程阻塞在 stderr 写、永远发不出响应 → 调用超时。这既证明 `StdioTransport.java:210-214` 的 try/catch 是承重的，也证明新用例真的能抓住它的缺失（而不是恒真）。验证后已还原。

## 四、验收命令实跑结果（本次自审当场执行）

```
$ MAVEN_OPTS="-Xmx640m" mvn -f agent-core/pom.xml -B -ntp test
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 11.42 s -- in dev.lifeagent.mcp.McpClientTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.045 s -- in dev.lifeagent.mcp.JsonRpcCodecTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
EXIT=0
```

```
$ MAVEN_OPTS="-Xmx640m" mvn -B -ntp verify
[WARNING] Tests run: 28, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
ROOT_VERIFY_EXIT=0
```

- 模块侧 14 ≥ 13（基线 11，净增 3 条，无删除、无 skip）。
- 根回归与基线**逐项相同**（28/0/0/Skipped 1），原功能未被碰坏。`agent-core` 不在根 reactor 内，此结果符合预期，但仍按要求实跑。
- 说明：P1-1 自审记录的沙箱 loopback socket 抖动（`Operation not permitted`）本次**未复现**，根回归全绿。

## 五、偏离与诚实披露

1. **`inheritParentEnvironment` 的 compact constructor 防御性处理未以代码形式出现**（见 2.1 要求 2′）。理由：基本类型布尔没有可防御的对象（无 null、无别名、无突变），安全默认值只能且已经落在 Builder 字段 `:85`。这是本卡唯一一处字面未照做的地方，已显式标注，请复核判定是否需要补代码级动作；若需要，请给出期望语义。
2. **哨兵名用程序参数而非 `-D` 传递**（`env-sentinel=<NAME>`）。理由：夹具本来就通过 `main(String[] args)` 接收参数（既有 `bad-tools` 即如此），而 `-D` 需改命令构造才能落到 JVM 属性上；任务卡已写明"实现方式由你决定"。
3. **额外新增正向对照用例**（`inheritParentEnvironment(true)`）。超出卡片要求，目的是防止用例 A 空洞通过（见 2.3）。
4. `dump_env` 未加入 `tools/list` 返回的工具清单，沿用夹具既有风格（`no_response`、`die` 同样不在清单里），不影响协议路径。
5. 未放宽任何既有断言：本卡唯一被替换的断言正是任务卡点名要求收紧的那条（`> 4 MiB` → `5,237,760`），方向是**变严**；其余断言保持原样。
6. 未实现 HTTP 通道（P1-3），未动 AP-004 其余 6 条建议。
7. 未 commit、未 push。

## 六、遗留与后续建议

- 默认隔离意味着子进程拿不到 `PATH`。若后续接入需要 `PATH`（或其它运行时变量）的真实服务端，调用方需显式 `.inheritParentEnvironment(true)` 或把所需变量写进 `.environment(...)`。这是默认隔离的预期代价，已写入 record Javadoc。
- 本卡只覆盖 stderr 洪泛与父环境泄漏两项；`stderrLogger` 抛异常目前是"吞掉并继续"，未做计数或告警上报，属后续可观测性话题，不在本卡范围。
