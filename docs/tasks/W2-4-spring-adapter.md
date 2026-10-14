# 任务卡 W2-4 — Spring 侧 `ShopCatalog` 适配器（agent-core 抽象 → 真实业务服务）

难度：困难
交给：Codex（`codex exec -m deepseek/deepseek-v4.1-flash --sandbox workspace-write`）
理由：跨模块接线 + 需要读懂既有服务层语义（GEO 查询的分页/排序、类型名解析、字段映射与范围过滤）。

**前置**：W2-3 已交付（`c8495df`）—— agent-core 147 条全绿，`dev.lifeagent.tool.ShopCatalog` 抽象已就绪。
**依据**：手册 §3「agent-core 与业务无关」+ §5.3；`agent-core/src/main/java/dev/lifeagent/tool/ShopCatalog.java` 的 javadoc
明说「The implementation lives in the application and is wired in W2-3」——本卡就是补这一环。

## 目标

在**根项目**里实现 `ShopCatalog`，把 agent-core 的三个只读工具接到真实的业务服务。
**agent-core 一行不改**（它只拥有契约）。

## 只许写这些文件

- `src/main/java/com/hmdp/agent/**`（新包，放适配器与其配置）
- `src/test/java/com/hmdp/agent/**`（新测试）
- `docs/W2-4-SPRING-ADAPTER.md`（新建，记录映射决策）

**决策 A 已由嘉批准（2026-10-14）**：根项目依赖 agent-core 契约。该项已由**管理者**落地，你**不需要**再改：
根 `pom.xml` 已加 `dev.lifeagent:agent-core:0.1.0-SNAPSHOT` 依赖；`.github/workflows/ci.yml` 已在根 `verify`
之前插入 `mvn -f agent-core/pom.xml -B -ntp install`；agent-core 已 install 进本机本地仓库。
**依赖方向 = 应用依赖框架**（agent-core 拥有契约，根项目提供业务实现）。

**禁止**：改 `agent-core/**`（一行都不许）、改既有的任何 service/entity/controller/dto 类、
再改 `pom.xml`、再改 `.github/**`、改 `nginx-1.18.0/**`。
**若既有根项目 30 条测试中某条因你的改动失败，停下报告，不许改它。**

## 关键约束（读透再动手）

1. **不许重命名既有方法**。`IShopService` 里的 `quaryById` / `quaryByShop` 是既有拼写（不是错别字需修），
   `IVoucherService.queryVoucherOfShop` 同理。照用，不要"顺手修正"。
2. **`quaryByShop(Integer typeId, Integer current, Double x, Double y, String sortBy)` 不收半径、不收预算。**
   所以 `SearchNearbyArgs.radius` 与 `maxPrice` 的过滤**必须在适配器里自己做**：
   用返回结果里每个 `Shop.distance` 过滤 `distance <= radius`，用 `Shop.avgPrice` 过滤 `avgPrice <= maxPrice`（`maxPrice` 为 null 时不过滤）。
   分页参数自己给（例如 `current=1`），若结果不足再决定是否翻页——**在 `docs/W2-4-SPRING-ADAPTER.md` 写明你的取舍**。
3. **`category` 是品类名（如"火锅"），而 `quaryByShop` 要的是 `typeId`。**
   需经 `IShopTypeService` 把名字解析成 id。解析不到时：返回**空列表**（不要抛异常，也不要猜一个 id）。
4. **字段映射**（注意类型收窄，越界要处理）：
   - `Shop.id`(Long) → `ShopBrief.shopId`(String)；`Shop.name` → `name`
   - `Shop.typeId` → 经 `IShopTypeService` 反查**品类名** → `ShopBrief.category`(String)
   - `Shop.avgPrice`(Long) → `ShopBrief.avgPrice`(Integer)：超出 `Integer` 范围时钳到 `Integer.MAX_VALUE`
   - `Shop.score`(Integer) → `ShopBrief.score`(Double)
   - `Voucher.id`(Long) → `VoucherBrief.voucherId`(String)；`payValue`/`actualValue`(Long) → Integer，同样钳制
5. **`Result` 包装**：既有服务返回 `com.hmdp.dto.Result`（`success`/`errorMsg`/`data`/`total`）。
   `success == false` → 视为工具错误（抛异常或返回空，二选一并写明）；`data` 的类型要**安全转换**
   （可能是 `List<Shop>`，也可能是别的），类型不符时给出明确错误，不许静默返回半截数据。
6. **只读**：适配器与它暴露的一切都必须是 READ 语义，**不得**调用任何写方法或改任何服务状态。
7. **不许把 agent-core 的类暴露给既有 controller**，也不许在既有 controller 里加新路由（AGENTS.md 铁律 3）。

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"
mvn -B -ntp verify                                        # 根回归必须全绿
mvn -f agent-core/pom.xml -B -ntp verify                  # agent-core 必须仍 147 全绿
```

## 当前测试基线（必须保持）

| 构建 | 基线 |
|---|---|
| 根项目 | `Tests run: 30, Failures: 0, Errors: 0, Skipped: 3` |
| `agent-core` | **147** 条全绿 |

**只能增加不能减少**；既有测试不许改、不许删、不许放宽断言。
**注意**：根项目的 30 条里 **3 条是 Skipped**（那是对既有功能的既有语义），不许试图"修好"它们让它们跑起来。

## 测试要求（新增测试放在 `src/test/java/com/hmdp/agent/**`）

用**假服务**（mock/stub `IShopService`/`IVoucherService`/`IShopTypeService`）覆盖：
1. **半径过滤**：返回 3 家店，distance 分别 100/800/2000，`radius=500` ⇒ 只剩 1 家。
2. **预算过滤**：`maxPrice=80` ⇒ 排除 avgPrice 高于 80 的；`maxPrice=null` ⇒ 不按预算过滤。
3. **品类名解析失败** ⇒ 返回空列表，且**不抛异常**。
4. **`Result.success == false`** ⇒ 明确错误（抛异常或空，与你在文档里的选择一致）。
5. **`avgPrice` 溢出**（如 `Long.MAX_VALUE`）⇒ 钳到 `Integer.MAX_VALUE`，不抛。
6. **`data` 类型不符**（例如返回一个非 List）⇒ 明确失败，不返回半截数据。
7. **只读性**：断言适配器在所有路径下都没调用既有服务的任何写方法。

## 交付前必须做的三遍审查（见 `docs/REVIEW-PROTOCOL.md`）

1. **自审**：写 `docs/W2-4-SPRING-ADAPTER.md`，逐条记录「要求 → 实现位置（文件:行）→ 是否达成 → 证据」，
   并**单列**说明：半径/预算过滤为什么放在适配器而不是服务层，以及分页取舍。
2. 之后由**异源模型（DSH）只读复核**（本卡 Codex 写 → DSH 审）。
3. 最后由管理者独立重跑 + 变异验证。

**诚实要求**：没真跑的命令标「未执行」。**绝不要写你没写出来的测试。**

## 明确不做

- 不改 agent-core（抽象已定，一行不动）
- 不做 SSE 传输层 / 不做前端（W2-5）
- 不做端到端评测集（W2-5）
- 不写 ReAct 主循环相关（已交付）
- 不加新路由、不加写接口
- 不 commit、不 push
