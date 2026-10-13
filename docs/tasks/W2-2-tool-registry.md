# 任务卡 W2-2 — `tool` 工具框架 + 只读工具声明

难度：日常
交给：DSH（`dsh headless`）
理由：规格明确、无算法难点，属「日常编码」。**并因成本纪律**：Codex/Sol 占集群开销 99.5%，
且当前 5 小时窗口已用 78.3%，日常活必须走 DSH。

**前置**：W2-1 已交付（`c4e7100`）—— `agent-core` 49 条全绿，含 `llm` 包。
**依据**：手册 §5.3「工具注册」+ §6「工具清单」+ `PROJECT_IMPLEMENTATION_PLAN.md` W2。

## 目标

在 `agent-core` 内新增 `dev.lifeagent.tool` 包：

1. `@AgentTool` 注解 + 注册表
2. **参数 record 自动生成 JSON Schema**（手册 §5.3）
3. **执行前按 schema 校验参数**（校验不过就作为错误交回，不进业务代码）
4. 权限三档 `Permission`（`READ` / `WRITE` / `HIGH_RISK`）
5. 三个只读工具的**声明**（绑到 agent-core 自己的抽象接口，见下）

**架构约束（重要）**：手册 §3 要求「agent-core 是一个和业务无关的模块」。
所以本卡**不得**让 agent-core 依赖 Spring 或根项目的任何类。
业务工具的**声明**放在 agent-core，绑到一个抽象接口（如 `ShopCatalog`）；
真正的 Spring 实现留到 W2-3 由根项目提供。

## 只许写这些文件

- `agent-core/src/main/java/dev/lifeagent/tool/**`
- `agent-core/src/test/java/dev/lifeagent/tool/**`
- `agent-core/SELF-REVIEW-W2-2.md`（新建）
- **加固项需要时**可改：`agent-core/src/main/java/dev/lifeagent/llm/` 下的
  `OpenAiCompatibleAdapter.java`、`AnthropicMessagesAdapter.java`、`LlmHttpConfig.java`

**禁止**：改 `dev.lifeagent.mcp.**`、改根 `src/**`、改任何 `pom.xml`、
改 `.github/**`、改 `nginx-1.18.0/**`、新增任何第三方依赖。

## 实现要求

### 1. `@AgentTool` 注解（手册 §5.3 原样风格）

```java
@AgentTool(name = "search_nearby", permission = Permission.READ,
    description = "按位置和品类搜附近的店。用户提到距离、附近、周边时用；只问某家店的详情时别用")
public List<ShopBrief> searchNearby(SearchNearbyArgs args) { ... }
```

要求：
- 注解含 `name` / `description` / `permission`
- 参数用 **record** 表示（手册 §5.3）
- 字段上支持 `@Desc("品类，比如火锅、KTV")` 之类说明，用于生成 schema 的 `description`

### 2. JSON Schema 自动生成（核心）

从 record 组件生成 JSON Schema，含：
- `type: "object"`、`properties`、`required`
- 类型映射：`String`→`string`、`int/Integer`→`integer`、`long`→`integer`、`double/Double`→`number`、
  `boolean`→`boolean`、`List<T>`→`array`、嵌套 record → 递归 `object`
- `@Desc` 文本进入 `description`
- **`Integer`/`Long` 等可空包装类型不入 `required`；原始类型入 `required`**（这与手册
  §5.3 的 `Integer maxPrice` "可不填" 语义一致）

**测试**：对 `SearchNearbyArgs(@Desc("品类，比如火锅、KTV") String category, @Desc("半径，单位米，最大 5000") int radius, @Desc("人均预算上限，单位元，可不填") Integer maxPrice)`
生成 schema 并**逐字段断言**，含 `required` 恰为 `["category","radius"]`（`maxPrice` 不在内）。

### 3. 执行前参数校验

- 按 schema 校验传入参数：类型不符、必填缺失 → **作为错误结果交回**，**绝不进入业务方法**
- 测试：用 `spy`/计数器证明**校验失败时业务方法零调用**（这是承重断言，不许只断言返回了错误）

### 4. `ToolRegistry`

- 注册工具（按 `@AgentTool.name`），重名必须拒绝
- 导出「模型可见工具列表」（JSON Schema 数组，供 W2-1 的 `LlmRequest.tools`）
- 按 name 查找工具；**未知 name 必须明确失败**
- 测试：注册、重名拒绝、导出列表内容正确、未知 name 失败、**校验失败不调用业务方法**

### 5. 三个只读工具的声明 + 抽象接口

定义抽象接口（agent-core 自有的、与业务无关的）：

```java
public interface ShopCatalog {
    List<ShopBrief> searchNearby(SearchNearbyArgs args);
    ShopDetail shopDetail(ShopDetailArgs args);
    List<VoucherBrief> voucherList(VoucherListArgs args);
}
```

并声明三个只读工具（`Permission.READ`），参数/返回用本包自己的轻量 record：
- `search_nearby(args: SearchNearbyArgs)` —— 品类、半径、人均上限
- `shop_detail(args: ShopDetailArgs)` —— 店铺 id
- `voucher_list(args: VoucherListArgs)` —— 店铺 id

**要求**：工具**描述**写清「什么时候该用、什么时候别用」（手册 §6 明确此点比参数说明更影响模型选择）。
并在测试里**断言这三个工具的 schema 里没有写操作**、`permission` 均为 `READ`。

### 6. 顺带修 W2-1 复核的 6 条非阻塞加固

依 `docs/reviews/W2-1-review.md` 与 `docs/reviews/W2-1R-review.md`：

1. **OpenAI 侧放宽「必须见到 `[DONE]`」**：部分网关不发 `[DONE]`，流正常结束即正常结束
2. `AnthropicMessagesAdapter` **保留厂商 `error` 细节**（便于排障）
3. `LlmHttpConfig` 加**脱敏 `toString()`**（避免日志泄漏 key 通路）——**不得**回显 key 值
4. 补断言：headers（API key 通路）真的进了请求头（不要断言 key 明文，断言头存在与格式）
5. `MessageDelta` 注明字段可空/可多次（javadoc）
6. SSE body **无读超时** —— 本卡只在 `LlmHttpConfig`/适配器层记 TODO 注释指向 W2-3 的循环层 deadline；
   **不在本卡实现循环层**

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"

# 1. agent-core 全量（49 条 + 本卡新增，必须全绿）
mvn -f agent-core/pom.xml -B -ntp verify

# 2. 既有测试一条不少（红线）
grep -c "@Test\|@ParameterizedTest" agent-core/src/test/java/dev/lifeagent/mcp/*.java
grep -c "@Test\|@ParameterizedTest" agent-core/src/test/java/dev/lifeagent/llm/*.java

# 3. 根回归不许被碰坏
mvn -B -ntp verify
```

## 当前测试基线（必须保持）

| 构建 | 基线 |
|---|---|
| `agent-core` | **49** 条全绿（28 MCP + 21 llm） |
| 根项目 | `Tests run: 30, Failures: 0, Errors: 0, Skipped: 3` |

**只能增加不能减少**；既有 49 条**不许改、不许删、不许放宽断言**。

## 交付前必须做的三遍审查（见 `docs/REVIEW-PROTOCOL.md`）

1. **自审**：写 `agent-core/SELF-REVIEW-W2-2.md`，逐条记录「要求 → 实现位置（文件:行）→ 是否达成 → 证据」。
   **单列一节**说明：你怎么证明「schema 校验失败时业务方法零调用」。
2. 之后由**异源模型（Codex）只读复核**（本卡 DSH 写 → Codex 审）。
3. 最后由管理者独立重跑 + 变异验证。

**诚实要求**：没真跑的命令标 `未执行`。**若既有 49 条中某条因你的改动失败，停下报告，不许改它。**

## 明确不做

- 不写 ReAct 主循环、不做 `ask_user`/挂起恢复（W2-3）
- 不写 Spring 实现（`ShopCatalog` 的实现留 W2-3）
- 不做 SSE 前端事件（`plan`/`tool_call` 等，W2-3）
- 不改 MCP 包、不改根项目、不 commit / push
