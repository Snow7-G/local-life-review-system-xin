# SELF-REVIEW — W2-2 tool 工具框架（执行器自审 + 管理者补完记录）

> 本文件由**管理者**补写。原执行器（DSH）在收尾阶段崩溃（模型侧 `INVALID_REQUEST: 400
> invalid request error`，`EXIT=1`），未及产出本文件。按 `docs/REVIEW-PROTOCOL.md` 第 1 遍
> 要求，逐条记录「要求 → 实现位置 → 是否达成 → 证据」，并**单列**管理者补完的改动，
> 供第 2 遍异源复核逐项核验。

## 0. 交付范围与执行者

| 项 | 值 |
|---|---|
| 任务卡 | `docs/tasks/W2-2-tool-registry.md` |
| 主体实现者 | DSH（`dsh headless`，deepseek-v4.1-flash） |
| 执行结局 | **崩溃在收尾**：主源码编译通过后，于编辑 `ToolArgumentValidator` 与修复测试期崩溃 |
| 补完者 | 管理者（本集群 Hermes） |
| 新增实现类 | 24 个（`agent-core/src/main/java/dev/lifeagent/tool/`，1280 行） |
| 新增测试类 | 6 个（含 `RecordingHttpClient` 夹具，1674 行） |
| 改动既有文件 | `llm/AnthropicMessagesAdapter`、`llm/OpenAiCompatibleAdapter`、`llm/LlmHttpConfig`、`llm/MessageDelta`（W2-1 加固项） |

## 1. 逐条要求核对

| # | 要求（摘自任务卡） | 实现位置 | 达成 | 证据 |
|---|---|---|---|---|
| 1 | `@AgentTool(name/description/permission)` | `AgentTool.java` | ✅ | `ToolRegistryTest` 注册/重名用例 |
| 2 | `@Desc` 字段说明进 `description` | `Desc.java`、`JsonSchemaGenerator` | ✅ | `JsonSchemaGeneratorTest`（含空白 `@Desc` 降级为无描述） |
| 3 | `Permission` 三档 READ/WRITE/HIGH_RISK | `Permission.java` | ✅ | 三个只读工具均声明 `READ` |
| 4 | record 自动生成 JSON Schema（类型映射全） | `JsonSchemaGenerator.java` | ✅ | `AllTypes` 覆盖 string/int/Integer/long/boolean/double/Double/enum/List/数组/Map/嵌套 record |
| 5 | **可空包装类型不入 `required`，其余入** | `JsonSchemaGenerator.isRequiredComponent` | ✅（**语义由管理者裁定，见 §3**） | `requiredContainsEveryComponentExceptNullableWrappers` |
| 6 | 校验失败必须**作为错误结果交回**（非抛异常） | `ToolRegistry.invoke` 返回 `ToolFailure` | ✅ | `ToolRegistryZeroCallTest.invalidArgumentsNeverReachTheBusinessMethod` |
| 7 | 校验失败时**业务方法零调用** | 校验先于绑定与调用 | ✅ | `ToolRegistryZeroCallTest` 用 `CountingCatalog` 计数 + 毒化 `Proxy` catalog 双证 |
| 8 | `ToolRegistry`：注册/重名拒绝/导出模型可见列表/未知 name 明确失败 | `ToolRegistry.java` | ✅ | `ToolRegistryTest` 24 条 |
| 9 | 三个只读工具绑 `ShopCatalog` 抽象（业务无关） | `ShopTools.java`、`ShopCatalog.java` | ✅ | 实现类零 Spring / 零根项目引用（见 §4 依赖核验） |
| 10 | 加固：OpenAI 放宽须见 `[DONE]`（流正常结束即结束） | `OpenAiCompatibleAdapter` | ✅ | `LlmHardeningTest` 13 条 |
| 11 | 加固：Anthropic 保留 error 细节 | `AnthropicMessagesAdapter` | ✅ | 同上 |
| 12 | 加固：`LlmHttpConfig` 脱敏 `toString`，不回显 key | `LlmHttpConfig.java` | ✅ | `streamingRequestsCarryTheRequiredAndConfiguredHeaders` |
| 13 | 加固：补 headers 断言 | `LlmHardeningTest` | ✅ | `bothAdaptersSendTheSameRequiredHeadersToTheirOwnEndpoint` |
| 14 | 加固：`MessageDelta` javadoc | `MessageDelta.java` | ✅ | 源码 javadoc |
| 15 | 加固：SSE body 无读超时只记 TODO 指向 W2-3 | `AbstractHttpLlmAdapter` | ✅ | TODO 注释指向 W2-3 |
| 16 | 硬边界：MCP 包一行不动 | — | ✅ | `git diff --stat` 无 `dev/lifeagent/mcp/**` |
| 17 | 硬边界：既有 49 条测试不许改删放宽 | — | ✅ | `llm` 包 49 条全绿，断言未动 |
| 18 | 硬边界：零新依赖 | — | ✅ | `pom.xml` 无改动 |
| 19 | 验收：agent-core `verify` 全绿 | — | ✅ | `Tests run: 115, Failures: 0, Errors: 0` |
| 20 | 验收：根回归不许坏 | — | ✅ | `Tests run: 30, Failures: 0, Errors: 0, Skipped: 3` |

## 2. 「怎么证明 schema 校验失败时业务方法零调用」

任务卡点名要求单列，证据链如下（`ToolRegistryZeroCallTest`）：

1. **计数法**：`CountingCatalog` 对 `searchNearby`/`shopDetail`/`voucherList` 各持一个
   `AtomicInteger`。对 22 组非法入参逐一 `invoke` 后断言三个计数器**全为 0**，且
   `lastSearchArgs`/`lastDetailArgs`/`lastVoucherArgs` 均为 `null`（连参数都未被绑定）。
2. **毒化法**：`poisonCatalog()` 用 `Proxy` 造一个**任何方法调用即抛错**的 `ShopCatalog`。
   若校验顺序错误导致业务方法被进入，该测试会以异常（而非断言失败）崩掉——即"进入过"
   必然留下可观测痕迹，反之全部用例通过即证明**从未进入**。
3. **顺序保证**：`ToolRegistry.invoke` 中 `ToolArgumentValidator.validate(...)` 严格先于
   `mapper.convertValue(...)` 与 `definition.handler().execute(...)`（`ToolRegistry.java:209-223`），
   源码顺序本身即第 3 重证据。

## 3. 管理者裁定：`required` 语义（**任务卡原文自相矛盾，此处定案**）

- **矛盾**：任务卡 §③ 文字写「原始类型入 `required`」，但同卡给的期望值却是
  `["category","radius"]`——而 `category` 是 `String`（非原始类型）。执行器照文字实现
  （`required=[radius]`），测试照期望值断言（`[category,radius]`），必然红。
- **裁定依据**：手册 §5.3 的示例 `SearchNearbyArgs(String category, int radius, Integer maxPrice)`
  中，**只有 `Integer maxPrice` 被标注"可不填"**。即"可空包装类型"才是可选语义的载体，
  `String`/枚举/数组/嵌套 record 无此标记 ⇒ 应入 `required`。
- **落地**：`isRequiredComponent = !NULLABLE_WRAPPERS.contains(type)`，
  `NULLABLE_WRAPPERS = {Integer,Long,Short,Byte,Float,Double,Boolean,Character,BigInteger,BigDecimal}`。
- **影响**：3 处 schema 测试期望同步修正（`JsonSchemaGeneratorTest`）；`ToolArgumentValidator`
  的 `null` 容忍规则本就按「不在 `required` 即允许 `null`」实现，**无需改动**。
- **责任归属**：**任务卡缺陷，管理者责任**，非执行器能力问题。

## 4. 依赖与硬边界核验命令

```bash
# 业务无关性：tool 包不得出现 Spring / 根项目类
grep -rE "org\.springframework|com\.hmdp" agent-core/src/main/java/dev/lifeagent/tool/ || echo "clean"
# MCP 包零改动
git diff --stat -- agent-core/src/main/java/dev/lifeagent/mcp/ | wc -l   # 期望 0
# 零新依赖
git diff -- agent-core/pom.xml | wc -l                                  # 期望 0
```

## 5. 诚实声明

- DSH 崩溃后**未再调用任何外部模型**完成 W2-2 收尾；§2 中列出的全部命令均由管理者**真实执行**，
  输出见会话记录与 `docs/reviews/` 下第 2 遍复核报告。
- 本文件不宣称"复核已通过"——第 2 遍（异源只读复核）与第 3 遍（管理者验收）结论另行记录。
