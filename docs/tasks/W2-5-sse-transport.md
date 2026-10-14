# 任务卡 W2-5 — SSE 传输层（把六类 AgentEvent 推到前端）

难度：困难
交给：Codex（`codex exec -m deepseek/deepseek-v4.1-flash --sandbox workspace-write`）
理由：跨层接线（Controller + 事件序列化 + 主循环驱动 + 挂起/恢复），且要守住既有回归红线。

**前置**：W2-3（`c8495df`）主循环与六类事件、W2-4（`5e11842`）Spring 侧 `ShopCatalog` 适配器均已交付。
**依据**：手册 §5.4「流式事件」；`agent-core/src/main/java/dev/lifeagent/event/` 六类事件已就绪但**尚无传输层**。

## 目标

在根项目新增一个 **SSE 端点**，把 `AgentLoop` 产生的六类事件实时推给前端，并支持挂起/恢复。

## 只许写这些文件

- `src/main/java/com/hmdp/agent/**`（可新增传输层类）
- `src/test/java/com/hmdp/agent/**`
- `src/main/resources/application.yaml`（**仅限新增 agent/llm 相关配置项**，不得改动既有配置；
  注意实际文件名是 `application.yaml`，不是 `application.yml`）
- `docs/W2-5-SSE-TRANSPORT.md`（新建）

**禁止**：改 `agent-core/**`（一行都不许）、改 `pom.xml`、改 `.github/**`、改 `nginx-1.18.0/**`、
改既有 service/entity/controller/dto、改既有测试、**新增任何第三方依赖**（SSE 用 Spring MVC 自带的
`SseEmitter`，项目已有 spring-boot-starter-web，**不许引 WebFlux**）。

## 硬约束

1. **新增的是只读性质的对话端点**，不得改变任何业务状态。AGENTS.md 铁律 3 的回归红线是
   `ManagementRoutesTest`——它必须继续全绿，**不许改它的断言**。
2. **凭据只走环境变量**（AGENTS.md 铁律 2）。LLM 的 key **不得**出现在 `application.yml`、
   代码或测试里；配置项只引用 `${GOAT_KEY}` 之类的环境变量占位。提交前自查
   `git diff --cached | grep -iE 'password|secret|token|api[_-]?key'` 不得命中真实值。
3. **六类事件一一映射**：`plan`/`tool_call`/`tool_result`/`thinking`/`confirm_required`/`answer`，
   事件名与 `AgentEvent` 的六个实现类一一对应，不得漏、不得合并。
4. **挂起/恢复**：主循环返回 `WAITING_USER` 时推 `confirm_required` 并**结束本次流**（不阻塞线程）；
   提供**恢复端点**（携带会话 id 与用户回复）从 `SessionStore` 载入后继续推流。
5. 主循环只依赖 `LlmClient` 接口——传输层负责把配置好的 `LlmClient` bean 注入（**可被测试替换**）。

## 测试要求（`src/test/java/com/hmdp/agent/**`）

用**假 `LlmClient`**（脚本化）覆盖，不得真调远端模型：
1. 一次正常对话：断言 SSE 依次推出的事件名与顺序（`tool_call` 必先于对应 `tool_result`；`answer` 至多一个且在最后）。
2. **挂起**：假 LLM 触发 `ask`/Defer → 断言推出 `confirm_required` 且**流结束**（不阻塞），
   会话已存入 `SessionStore`。
3. **恢复**：用会话 id + 用户回复调用恢复端点 → 断言从存好的状态继续、推出后续事件、最终 `answer`；
   并断言已完成的工具调用**未被重复执行**（用调用计数证明）。
4. **错误路径**：LLM 抛异常 / 工具报错 → 断言事件流不中断成 500，而是以可读的错误事件或
   `answer` 收尾（与主循环「错误作为数据」的口径一致）。
5. **不泄漏凭据**：断言任何对外输出（含错误消息与日志）都不含配置里的 key。

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"
mvn -B -ntp verify                              # 根回归必须全绿（含 ManagementRoutesTest）
mvn -f agent-core/pom.xml -B -ntp verify         # agent-core 必须仍 147 全绿
```

## 当前测试基线（必须保持）

| 构建 | 基线 |
|---|---|
| 根项目 | **57** 条全绿（Skipped 3） |
| `agent-core` | **147** 条全绿 |

**只能增加不能减少**；既有测试不许改、不许删、不许放宽断言。
那 3 条 Skipped 是既有语义，不许试图"修好"它们。

## 交付前必须做的三遍审查（见 `docs/REVIEW-PROTOCOL.md`）

1. **自审**：写 `docs/W2-5-SSE-TRANSPORT.md`，逐条记录「要求 → 实现位置（文件:行）→ 是否达成 → 证据」，
   并**单列**说明：SSE 的事件名映射表，以及「挂起时为什么不阻塞线程」。
2. 之后由**异源模型（DSH）只读复核**（本卡 Codex 写 → DSH 审）。
3. 最后由管理者独立重跑 + 变异验证。

**诚实要求**：没真跑的命令标「未执行」。**绝不要写你没写出来的测试**（前几轮已因此被复核抓出两次）。

## 明确不做

- 不改 agent-core（契约已定）
- 不接真实远端 LLM 做端到端（属 W2-6）
- 不做评测集（属 W2-7）
- 不做前端页面
- 不新增任何业务写接口
- 不 commit、不 push
