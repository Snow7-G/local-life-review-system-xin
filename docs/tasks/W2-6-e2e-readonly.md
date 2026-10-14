# 任务卡 W2-6 — 只读闭环端到端集成测试（服务级，零基础设施依赖）

难度：困难
交给：Codex（`codex exec -m deepseek/deepseek-v4.1-flash --sandbox workspace-write`）
理由：把已交付的六个部件（llm / tool / loop / hook / event / Spring 适配器）第一次真正串起来跑一遍，
需要跨包设计夹具与断言；不写新功能，只写集成测试与其最小夹具。

**前置**：W2-1 `llm`（`c4e7100`）、W2-2 `tool`（`572669f`）、W2-3 `loop`+`hook`+`event`（`c8495df`）、
W2-4 Spring 适配器（`5e11842`）、W2-5 SSE 传输层（`d1e27e7`）均已交付并复核通过。
**依据**：手册 W2 完成标志「跑通『附近找个火锅店』」；PROJECT_IMPLEMENTATION_PLAN.md 的 W2「只读闭环」。

## 为什么是「服务级」而不是「真拉 Spring 上下文」

管理者已核实：既有测试**全部**用 `MockMvcBuilders.standaloneSetup` + Mockito，仓库里**没有任何 H2 schema
文件**，唯一 `@SpringBootTest` 是 `HmDianPingApplicationTests`（3 条 Skipped）。本机**没有 MySQL**（无客户端、
3306 无监听、无 docker），只有 Redis 在跑。所以真拉库的 e2e 不可靠。
**本卡明确只做服务级**：不启 Spring 上下文、不连 MySQL/H2/Redis，用假服务提供 `distance` 数据。
真基础设施的 e2e 属后续另立卡。

## 目标

新增一个**端到端集成测试**：一条用户消息「附近找个火锅店」从主循环进去，经模型要工具、工具经
`SpringShopCatalog` 落到假业务服务、结果回填、最终产出回答——把整条只读链路串起来验证一次。

## 只许写这些文件

- `src/test/java/com/hmdp/agent/e2e/**`（新包，放集成测试与夹具）
- `docs/W2-6-E2E-READONLY.md`（新建，记录接线图与断言）

**禁止**：改 `agent-core/**`（一行都不许）、改 `src/main/**`（本卡**不产出任何生产代码**）、
改 `pom.xml`、改 `.github/**`、改 `nginx-1.18.0/**`、改既有测试、新增任何第三方依赖。

## 接线要求（被测对象必须是生产类，不许复制一份）

必须真实实例化并串起：
- `dev.lifeagent.loop.AgentLoop`（主循环）+ `HookChain`（真实实现，可空链或带一个记录型 hook）
- `dev.lifeagent.tool.ToolRegistry` + `dev.lifeagent.tool.ShopTools`（三个只读工具的真实声明）
- `com.hmdp.agent.SpringShopCatalog`（**真实的适配器**，不是替身）
- 假 `IShopService` / `IVoucherService` / `IShopTypeService`（提供带 `distance` 的店铺数据）
- 脚本化 `LlmClient`（复用 W2-5 已有的 `AgentTransportFixtures.ScriptedLlm`，或在其基础上扩展）

## 必须断言（每条都要有测试）

1. **整条链路跑通**：脚本 LLM 先要 `search_nearby`、拿到结果后给最终回答；断言最终 `LoopStopReason.ANSWERED`
   且事件序列恰为 `plan` → `tool_call` → `tool_result` → `answer`（顺序精确）。
2. **半径真的生效**：假服务返回 3 家店（distance 100/800/2000），半径 500 ⇒ 模型收到的工具结果里**只有 1 家**。
3. **预算真的生效**：`maxPrice` 过滤走同一条链（`maxPrice=null` 时不过滤）。
4. **品类解析真的生效**：`category="火锅"` 经品类服务解析为 typeId 后再查店；解析不到时返回空且不抛。
5. **只读性**：断言全程没有任何写方法被调用（用记录的假服务；若某写方法被调用则显式失败）。
6. **熔断在真实链路上生效**：让假服务连续失败 3 次，第 4 次**不再进入业务方法**（计数证明）。
7. **手动 trace**：把这一次完整会话的事件序列与工具调用参数打印/落到测试输出（作为"跑通"的可读证据）。

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"
mvn -B -ntp verify                              # 根回归必须全绿
mvn -f agent-core/pom.xml -B -ntp verify         # agent-core 必须仍 147 全绿
```

## 当前测试基线（必须保持）

| 构建 | 基线 |
|---|---|
| 根项目 | **73** 条全绿（Skipped 3） |
| `agent-core` | **147** 条全绿 |

**只能增加不能减少**；既有测试不许改、不许删、不许放宽断言。

## 交付前必须做的三遍审查（见 `docs/REVIEW-PROTOCOL.md`）

1. **自审**：写 `docs/W2-6-E2E-READONLY.md`，逐条记录「要求 → 实现位置（文件:行）→ 是否达成 → 证据」，
   并**单列**一张**接线图**（哪个真实类接了哪个假服务，数据怎么流），以及第 7 项那条完整 trace 的输出。
2. 之后由**异源模型（DSH）只读复核**（本卡 Codex 写 → DSH 审）。
3. 最后由管理者独立重跑 + 变异验证。

**诚实要求**：没真跑的命令标「未执行」。**绝不要写你没写出来的测试**（前几轮已因此被复核抓出两次）。

## 明确不做

- 不启 Spring 上下文、不连 MySQL/H2/Redis
- 不产出生产代码（本卡只加测试与文档）
- 不调真实远端 LLM（零额度消耗）
- 不做评测集（W2-7，需另行批准额度）
- 不改 agent-core
- 不 commit、不 push
