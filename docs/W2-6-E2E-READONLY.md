# W2-6 只读闭环端到端集成测试（服务级）— 自审

对应任务卡：`docs/tasks/W2-6-e2e-readonly.md`
产出（全部新增，无生产代码）：`src/test/java/com/hmdp/agent/e2e/**`、本文件。

## 0. 一句话结论

「附近找个火锅店」已在**真实生产类**串起来的只读链路上跑通，7 条必须断言逐条有测试，新增
**8** 条测试全绿；`agent-core` 与 `src/main/**` 一行未改。

## 1. 接线图（哪个真实类接了哪个假服务，数据怎么流）

```
                    ┌──────────────────────────────────────────────┐
                    │  E2eFixtures.ScriptedLlm  （假：脚本化模型）    │
                    └───────────────────────┬──────────────────────┘
         LlmRequest(history + tools)        │  ① 先要 search_nearby
         ← 工具结果 JSON 回填给模型 ②        ▼  ③ 拿到结果再给最终回答
   ┌──────────────────────────────────────────────────────────────┐
   │  dev.lifeagent.loop.AgentLoop           （真实，主循环）        │
   │    ├─ HookChain（真实；空链 + 一个记录型 PostToolHook）          │
   │    └─ ToolRegistry（真实）                                      │
   └───────────────────────┬──────────────────────────────────────┘
            ToolCallRequest  │  registry.invoke("search_nearby", {...})
                             ▼
   ┌──────────────────────────────────────────────────────────────┐
   │  dev.lifeagent.tool.ShopTools            （真实，三个只读工具）  │
   └───────────────────────┬──────────────────────────────────────┘
            ShopCatalog 接口 │  SpringShopCatalog implements ShopCatalog
                             ▼
   ┌──────────────────────────────────────────────────────────────┐
   │  com.hmdp.agent.SpringShopCatalog        （真实适配器，非替身）  │
   │    resolveTypeId / withinRadius / withinBudget 都在这里生效      │
   └──┬────────────────────┬──────────────────────┬─────────────────┘
      │ quaryByShop        │ queryVoucherOfShop    │ quaryById/解析品类
      ▼                    ▼                       ▼
  ┌───────────────┐  ┌────────────────────┐  ┌────────────────────┐
  │ IShopService  │  │ IVoucherService    │  │ IShopTypeService   │
  │ 假(Recording) │  │ 假(Recording)      │  │ 假(Recording)      │
  └───────────────┘  └────────────────────┘  └────────────────────┘
```

- **真实类**：`AgentLoop`、`HookChain`、`ToolRegistry`、`ShopTools`、`SpringShopCatalog`。
  它们全部是生产代码，测试只是 new 出来并接好（`E2eHarness.java:88`–`:104`，其中 `:104` 是构造 `AgentLoop` 的调用点）。
- **假**：只有 `LlmClient`（`ScriptedLlm`）与三个业务服务（`RecordingService` 动态代理）。
  三个服务返回带 `distance` 的 `Shop`/`ShopType`，因此**半径、预算、品类解析都真的在
  `SpringShopCatalog` 里跑**，不是替身里算的。
- **数据流**：主循环把历史消息交给脚本模型 → 模型发 `tool_call` → 循环经 `ToolRegistry` 调到
  `ShopTools` → `ShopTools` 委托 `SpringShopCatalog` → 适配器解析品类、查假服务、按半径/预算过滤
  → 结果序列化成 JSON 作为 tool 结果回填进下一次 `LlmRequest` → 模型据结果给 `answer`。
- 沙箱无 socket、无 MySQL/H2/Redis：假服务是 `Proxy` 手写桩，不经网络；故不启 Spring 上下文。

## 2. 必须断言逐条对照

| # | 任务卡要求 | 实现位置（文件:行） | 是否达成 | 证据 |
|---|---|---|---|---|
| 1 | 整条链路跑通，事件序列精确 plan→tool_call→tool_result→answer 且 `ANSWERED` | `ReadOnlyChainE2eTest.java:81` | 是 | 断言 `eventNames()` 精确等于该四元组、`stopReason==ANSWERED`、`llm.streamCalls()==2`、`quaryByShop` 被调 1 次 |
| 2 | 半径真的生效（3 家 100/800/2000，半径 500 只剩 1 家） | `ReadOnlyChainE2eTest.java:112` | 是 | 假服务返回 3 家；断言模型请求里最后一个 TOOL 结果数组 `size()==1` 且仅含「近火锅」 |
| 3 | 预算真的生效（`maxPrice=null` 不过滤） | `ReadOnlyChainE2eTest.java:138` | 是 | `maxPrice=80` 时 90 元店被剔除（1 家）；`maxPrice=null` 时两家都在 |
| 4 | 品类解析真的生效；解析不到返空不抛 | `ReadOnlyChainE2eTest.java:171`、`:188` | 是 | 火锅→typeId=2（断言透传给 `quaryByShop` 的 `args[0]`）；品类服务 `quaryById` 被调 2 次（解析+反查）；未知品类：`quaryByShop` 调用 0 次、返回空数组、链路仍 `ANSWERED` |
| 5 | 只读性（全程无写方法被调用，被调则显式失败） | `ReadOnlyChainE2eTest.java:210` | 是 | 三个服务合并 `writeCalls()` 为空；且 `quaryByShop`/`quaryById` 各 1 次证明非真空 |
| 6 | 熔断在真实链路生效（连续失败 3 次后第 4 次不再进入业务方法） | `ReadOnlyChainE2eTest.java:235` | 是 | 4 次 `tool_call`；`quaryByShop` 仅被调 3 次；第 4 次结果为含「熔断」的文本 |
| 7 | 手动 trace：事件序列与工具参数打印为可读证据 | `ReadOnlyChainE2eTest.java:265`（`renderTrace` 在 `:286`） | 是 | 见 §4 逐字输出 |

补充：`HookChain` 真实参与——记录型 `PostToolHook` 被触发一次，断言在
`ReadOnlyChainE2eTest.java:100`，把“钩子挂点真的在链路上”钉住。

### 关于「为什么是服务级」

任务卡那一节的理由本卡照做：不启 Spring 上下文、不连 MySQL/H2/Redis。假服务在 `IShopService`
层提供 `distance`，所以半径过滤走的正是生产代码 `SpringShopCatalog.withinRadius`；真拉库的 e2e
不在本卡范围。

## 3. 熔断边界说明（诚实记录）

本测试证明的熔断语义是：**第 4 次调用返回熔断提示，且 `quaryByShop` 调用次数从第 4 次起不再增加**
（3 次失败后为 3，第 4 次仍为 3）。之所以能精确断言“第 4 次未进入业务方法”，是因为生产代码
`AgentLoop.executeChecked` 里对只读工具也消费熔断器（`isOpen` 命中即返回 `CIRCUIT_OPEN`），
该分支在本测试中真实执行。这里不夸大：第 5 次及以后的短路由同一 `isOpen` 分支覆盖，
本卡只对第 4 次给出计数证据。

## 4. 那条完整 trace 的输出（逐字）

由 `Trace#printTheFullConversationAsReadableEvidence` 打印，来源
`target/surefire-reports/TEST-com.hmdp.agent.e2e.ReadOnlyChainE2eTest.xml` 的 `<system-out>`：

```
===== W2-6 只读闭环 trace：附近找个火锅店 =====
用户: 附近找个火锅店
stopReason: ANSWERED  toolCalls=1  answer="为你找到 1 家 500 米内的火锅店：顺时针火锅（人均 88 元，评分 4.6）。"
--- 事件序列 -----
⓵ plan
⓶ tool_call search_nearby {"category":"火锅","radius":500}
⓷ tool_result
⓸ answer
--- 模型最终收到的工具结果 -----
[ {
  "shopId" : "1",
  "name" : "顺时针火锅",
  "category" : "火锅",
  "avgPrice" : 88,
  "score" : 4.6
} ]
```

## 5. 命令与结果（诚实标注）

| 命令 | 状态 | 结果 |
|---|---|---|
| `mvn -B -ntp -o test -Dtest='ReadOnlyChainE2eTest'` | **已执行** | `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` → BUILD SUCCESS |
| `mvn -B -ntp verify`（根全量回归） | **未执行** | 沙箱禁 socket、Maven 可能 offline；按管理者指示，全量由沙箱外执行 |
| `mvn -f agent-core/pom.xml -B -ntp verify` | **未执行** | 同上；本卡未改 `agent-core` 一行 |
| `mvn test-compile`（offline） | **已执行** | BUILD SUCCESS（`-o` 复用本地仓库） |

## 6. 硬边界自查

- `agent-core/**`：`git status` 无改动（未触碰）。
- `src/main/**`、`pom.xml`、`.github/**`、`nginx-1.18.0/**`：未改动。
- 既有测试：未改、未删、未放宽断言。
- 新增第三方依赖：无（只用 JDK 代理 + 既有 Jackson/JUnit）。
- 未执行 `git commit` / `git push`。
- 只新增：`src/test/java/com/hmdp/agent/e2e/{E2eFixtures,E2eHarness,ReadOnlyChainE2eTest}.java`
  与本文件。

## 7. 遗留问题或建议

- 本卡证明的是“服务级”；真实 MySQL/Redis 的 e2e 属后续另立卡（任务卡已声明）。
- 根回归与 `agent-core` 基线（73 / 147）请在沙箱外由管理者复跑确认；本卡只保证新增 8 条全绿、
  既有测试文件零改动（对既有基线的唯一影响是新增 8 条）。

---

## 8. 复核后的处置（管理者执行，2026-10-14）

依据：`docs/reviews/W2-6-review.md`（Q1/Q2/Q3/Q4 通过，Q5/Q6/Q7 有条件通过，3 条必修 + 5 条建议）。

### 必修 1 —— `SpringShopCatalog.java` 时间线矛盾 → **虚警，已用 git 证伪**

复核者发现该文件 mtime 落在本卡窗口内（23:37:08）、晚于测试与自审，因而怀疑「src/main 被改动」。
**真因**：那是管理者的**变异验证**（备份 → 把 `withinRadius` 改为恒真 → 跑 e2e 观察变红 → 还原），
写盘导致 mtime 前移。**内容未变**，证据：

- `git diff --stat HEAD -- src/main/java/com/hmdp/agent/SpringShopCatalog.java` → **0 行**
- `git status --porcelain` → **空**（工作区干净）
- `git show --stat HEAD` → 仅 **4 文件**：`docs/W2-6-E2E-READONLY.md` + e2e 下 3 个测试文件（895 行新增）
- `grep -c MUTATION src/main/java/com/hmdp/agent/SpringShopCatalog.java` → **0**

即：**本卡确为零生产代码改动**。副作用教训：**变异验证会改变被变异文件的 mtime**，
故 `mtime` 不能作为「某文件本轮未被改动」的证据（本轮复核正是被此误导）。

### 必修 2 —— 提交归属

执行器未 commit（符合卡面约束）；交付由**管理者**提交为 `58b1c96`。

### 必修 3 —— 收尾复跑（最终代码状态）

| 命令 | 结果 |
|---|---|
| `mvn -B -ntp -o test -Dtest=ReadOnlyChainE2eTest` | `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` |
| `mvn -B -ntp verify`（根） | `Tests run: 81, Failures: 0, Errors: 0, Skipped: 3` |
| `mvn -f agent-core/pom.xml -B -ntp test` | `Tests run: 147, Failures: 0, Errors: 0, Skipped: 0` |

三者均 BUILD SUCCESS。

### 建议项

- 建议 1（只读断言白名单化）与建议 3（trace 补断言 `tool_result`/`stopReason` 行）：成本低、价值高，登记进后续卡。
- 建议 2、4、5：保留待办。

### 已采纳的复核指正

复核指出 §1 的行号 `:88–:101`「未覆盖真正 `new AgentLoop` 的 `:104`」——经核对
`E2eHarness.java:104` 确为构造 `AgentLoop` 的调用点，**该指正成立，已在 §1 修正**。
