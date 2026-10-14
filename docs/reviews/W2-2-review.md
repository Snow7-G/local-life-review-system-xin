# W2-2 异源复核报告（第 2 遍）

> 复核者：Codex（`codex exec -m deepseek/deepseek-v4.1-flash --sandbox read-only`），**只读不改**。
> 对象：W2-2 交付物（`dev.lifeagent.tool` 24 实现类 + 6 测试类 + 4 个 `llm` 加固文件）。
> 复核者声明：**未做任何写操作**。原始日志：`/tmp/w2-2-review-codex3.log`。
> 背景：主体由 DSH 实现，崩溃于收尾；尾部由管理者补完（详见 `agent-core/SELF-REVIEW-W2-2.md`）。

## 判定汇总

| 问 | 判定 | 要点 |
|---|---|---|
| Q1 安全 | **通过** | 危险 API 零命中；无明文凭据（测试用运行时拼接 `"sk-"+"fixture"+...`）；无文件/网络 IO |
| Q2 `required` 语义 | **有条件通过** | 矛盾已被正确指认，裁定在本卡范围内无害；但**未回写卡面**，且集合/嵌套永远必填未来会出问题 |
| Q3 null 容忍自洽性 | **通过**（含 2 处轻微不一致） | schema 必填+null ⇒ 拒；可选+null ⇒ 收。**不存在 schema 与校验器互相矛盾** |
| Q4 测试修改性质 | **通过** | 8 处测试改动**全部属「修正错误的测试」**，无一处放宽断言；且只落在本卡新增测试上 |
| Q5 零调用证据链 | **通过** | 计数法与毒化法均成立且非死代码；`Error` 原样重抛保证毒药必然致命 |
| Q6 硬边界 | **通过**（附 1 条白名单越界） | tool 包零 Spring/零根项目依赖；MCP 零改动；零新依赖 |

## Q4 逐处判定（本次复核重点）

- **前提核验**：`git diff --stat` 全仓仅 4 个 `llm` 主源码文件、81 insertions；测试目录 diff 为 0 行
  → **既有 49 条（28 MCP + 21 llm）确实一字未动**，任务卡红线满足。
- **(a)** `JsonSchemaGeneratorTest` 3 处 required 期望：改为与裁定后实现一致，且是**收紧方向**
  （`names`/`nested` 由"不要求"变"要求"）。判：修正错误的测试（期望值本就来自卡面 `:63`，错的是实现）。
- **(b-1)** `ToolRegistryTest:79` `List<JsonNode> tools`：纯编译期类型对齐，后续断言强度未变。判：修正错误的测试。
- **(b-2)** `ToolRegistryTest:226` 给 `nothing(BareArgs)` 补 `.put("value","valid")`：新语义下 `BareArgs(String)` 必填，
  空对象本就该被拒；`success.result().isNull()` 断言未放宽。判：修正错误的测试。
- **(c-1)** `toolCallChunk` 恒补 `name`：夹具原先不合法（首个 tool-call delta 缺 `function.name`）。判：修正夹具。
- **(c-2)** 改用 `anthropicDoneWire()`：旧夹具无 `message_stop` 无法走完流。判：修正错误的测试。
- **(c-3)** 事件数 3→4：**跟随生产行为变更的期望增项**，4 比 3 更严（逐事件断言）。判：不是放宽，是契约变更的诚实记录。

## 必修项（3）

1. **`required` 裁定必须回写卡面/手册**。`docs/tasks/W2-2-tool-registry.md:59` 与 `:63` 的矛盾目前只存活在
   `SELF-REVIEW-W2-2.md`，卡文档本身仍是矛盾体。**同时需明确 `List<T>`/嵌套 record/枚举的"可不填"表达方式**
   （现状：集合与嵌套永远必填 → 第一支带数组参数的工具就会出现"模型被迫发空数组"）。
2. **`MessageDelta.java` 改动需管理者追认并补记白名单**（或撤改）。卡面白名单漏了它，而 `:105` 又要求改它
   —— 按"只许写这些文件"纪律当前属越界（改动确为 javadoc-only 且为验收所需）。
3. **事件序列契约变更需登记**：缺失 `[DONE]` 时现在会补发 `MessageDelta(StopReason.TOOL_USE)`
   （`OpenAiCompatibleAdapter` diff hunk `@@ -103,7 @@`）。需在 W2-1 契约说明与 W2-3 循环层同步，
   避免消费者按 3 事件实现。

## 建议项（7，非阻塞）

- `ToolRegistryZeroCallTest:86-98` 补 `assertEquals(ToolErrorKind.INVALID_ARGUMENTS, ...)`，与 `:73` 对齐。
- `toolCallChunk` 现恒带 `name`，已丢失"首个 delta 缺 `name`"这一真实网关行为用例，建议单加一条。
- 可选包装类型的 schema 未表达 `null`（`JsonSchemaGenerator` vs `ToolArgumentValidator`）：或补测试固定语义，
  或把 type 写为 `["integer","null"]`。
- `ToolRegistry.invoke(null, args)` 抛 NPE 而非降级为 `INVALID_ARGUMENTS`，W2-3 provider 层需先归一化。
- `ToolRegistry` 把 `e.getMessage()` 原样回传为工具错误（会进模型上下文），接远端 LLM 前建议截断/脱敏。
- `invoke` 不消费 `Permission`（当前无 WRITE/HIGH_RISK 工具，可接受）；W2-3 前建议注册期拒绝非 READ 或留 TODO。
- `SELF-REVIEW-W2-2.md` 的自查正则建议补 `javax\.` 等框架前缀。

## 复核者独立加分项

- SELF-REVIEW 自审数字实测吻合（main 24 类/1280 行、test 6 类/1674 行、`ToolRegistryZeroCallTest` 22 组非法入参）。
