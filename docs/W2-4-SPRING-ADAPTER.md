# W2-4 自审 — Spring 侧 `ShopCatalog` 适配器

> 执行器：Codex（`gpt-5.6-sol`）。交付范围：`src/main/java/com/hmdp/agent/**`、
> `src/test/java/com/hmdp/agent/**`、本文件。
> `agent-core/**` 一行未改（`git status --short agent-core/` 为空）。
> 本文件按 `docs/REVIEW-PROTOCOL.md` 第 1 遍要求，逐条记录「要求 → 实现位置（文件:行）→ 是否达成 → 证据」。

---

## 0. 交付清单

| 文件 | 说明 |
|---|---|
| `src/main/java/com/hmdp/agent/SpringShopCatalog.java` | 适配器主体，实现 `dev.lifeagent.tool.ShopCatalog` |
| `src/main/java/com/hmdp/agent/SearchOrigin.java` | 搜索原点（坐标来源，见 §2 决策 1） |
| `src/main/java/com/hmdp/agent/ValueCoercions.java` | `Long→Integer` 钳制、`score` 换算 |
| `src/main/java/com/hmdp/agent/ShopCatalogException.java` | 业务失败 / 类型不符的统一异常（会被 agent-core 转成 `EXECUTION_ERROR`） |
| `src/main/java/com/hmdp/agent/AgentCatalogConfig.java` | 唯一 Spring 装配点，暴露 `ShopCatalog` bean（无路由、无 controller） |
| `src/test/java/com/hmdp/agent/SpringShopCatalogTest.java` | 17 条：半径/预算/品类/失败/钳制/类型/只读 |
| `src/test/java/com/hmdp/agent/ToolRegistryWiringTest.java` | 4 条：经 agent-core `ToolRegistry` 的端到端接线 |

**新增测试合计 21 条**（17 + 4），全部真跑通过（见 §4）。

---

## 1. 逐条要求核对（对照任务卡）

| # | 要求（任务卡原文/约束） | 实现位置 | 达成 | 证据 |
|---|---|---|---|---|
| 1 | 在根项目实现 `ShopCatalog`，接三个只读工具到真实服务 | `SpringShopCatalog.java:43,62,90,106` | ✅ | `ToolRegistryWiringTest.registersTheThreeReadOnlyTools`（3 工具、均 `READ`） |
| 2 | 不许重命名 `quaryById`/`quaryByShop`/`queryVoucherOfShop` | `SpringShopCatalog.java:69,91,107`（调用处照用原拼写） | ✅ | 既有 `IShopService` 未改；`mvn compile` 通过 |
| 3 | `radius` 过滤必须在适配器做（服务不收半径） | `SpringShopCatalog.java:76,121-129` | ✅ | `SpringShopCatalogTest.radiusFilterKeepsOnlyShopsWithinTheRequestedDistance`（100/800/2000，r=500 ⇒ 1 家） |
| 4 | `maxPrice` 过滤必须在适配器做；`null` 不过滤 | `SpringShopCatalog.java:76,131-137` | ✅ | `budgetFilterExcludesTooExpensiveShopsAndIsSkippedWhenNull` |
| 5 | `category` 经 `IShopTypeService` 解析为 `typeId` | `SpringShopCatalog.java:148-156` | ✅ | 同名匹配；`shopDetailMapsEntityFieldsAndCategoryName`、`searchNearby*` |
| 6 | 解析不到 ⇒ 返回空列表，**不抛异常、不猜 id** | `SpringShopCatalog.java:64-66` | ✅ | `unknownCategoryReturnsEmptyListWithoutGuessingAnId`（并断言不触发 `quaryByShop`） |
| 7 | `Shop.id`(Long) → `ShopBrief.shopId`(String) | `SpringShopCatalog.java:80` | ✅ | `radiusFilter…` 断言 `shopId=="1"` |
| 8 | `Shop.typeId` → 反查品类名 → `ShopBrief.category` | `SpringShopCatalog.java:82,158-164` | ✅ | `radiusFilter…` 断言 `category=="美食"`；`shopDetail…` 断言 `"KTV"` |
| 9 | `avgPrice`(Long) 越界钳到 `Integer.MAX_VALUE` | `ValueCoercions.java:17-35` | ✅ | `outOfRangeAveragePriceSaturatesInsteadOfWrapping`（`Long.MAX_VALUE`→`Integer.MAX_VALUE`，`Long.MIN_VALUE`→`MIN_VALUE`） |
| 10 | `Voucher.payValue`/`actualValue`(Long) → Integer，同样钳制 | `SpringShopCatalog.java:111-116` + `ValueCoercions.java:17` | ✅ | `voucherListMapsAndClampsVoucherValues` |
| 11 | `Shop.score`(Integer) → `ShopBrief.score`(Double) | `ValueCoercions.java:47` | ✅ | 断言 `4.2`/`4.6`（÷10，理由见 §3） |
| 12 | `Result.success==false` ⇒ 工具错误（**选择：抛 `ShopCatalogException`**） | `SpringShopCatalog.java:220-228` | ✅ | `failedResultBecomesAnExplicitError`、`serviceFailureBecomesAModelVisibleToolError` |
| 13 | `data` 类型不符 ⇒ 明确失败，不许静默返回半截数据 | `SpringShopCatalog.java:179-217` | ✅ | `wrongResultPayloadTypeFailsInsteadOfReturningPartialData`、`wrongListElementTypeFailsInsteadOfSkippingTheBadRow`、`shopDetailFailsWhenResultIsNotAShop`、`voucherListFailsWhenPayloadIsNotAVoucherList` |
| 14 | 适配器与它暴露的一切必须是 READ 语义，不得调写方法 | `SpringShopCatalog.java` 仅调 `quaryByShop`/`quaryById`/`queryVoucherOfShop`/`quaryById()` | ✅ | `ReadOnly.noHappyPathCallsAWriteMethod`、`ReadOnly.errorPathsAlsoAvoidWrites`（见 §4 说明） |
| 15 | 不把 agent-core 的类暴露给既有 controller，不加新路由 | `AgentCatalogConfig.java` 只声明 bean，无 `@RequestMapping` | ✅ | `ManagementRoutesTest` 未改且不在我的改动面；`git status` 仅新增两目录 |
| 16 | `Result.success==false`/`data` 不符要「二选一并写明」 | `SpringShopCatalogException`（抛） | ✅ | §2 决策 2 |
| 17 | 分页取舍写进文档 | 本文件 §5 | ✅ | — |

---

## 2. 需要写明的两个决策

### 决策 1 · 搜索原点（坐标）来自应用侧配置 `SearchOrigin`

`SearchNearbyArgs` 只有 `category / radius / maxPrice`，**没有坐标**，且 agent-core 一行不许改。
而 `IShopService.quaryByShop(typeId, current, x, y, sortBy)` 只有在 `x/y` 非 null 且 `sortBy` 为空时
才走 GEO 分支、才会把 `distance` 填进 `Shop`（`ShopServiceImpl.java:221-270`）。因此「按半径过滤
`distance`」要成立，调用必须带上坐标，而坐标只能由根项目提供 —— 这就是 `SearchOrigin`
（`SearchOrigin.java`，经 `AgentCatalogConfig.java:22-27` 从
`hmdp.agent.search-origin.x / y` 注入，缺省为未配置）。

**取舍与诚实标注**：`SearchOrigin` 把「用户当前位置」建模为应用侧可配置的默认原点（后续 W2-5
的传输层/客户端定位可覆盖它，无需改 agent-core 契约）。**未配置坐标时**，`quaryByShop` 走数据库
分页分支，`Shop.distance` 为 `null`；此时适配器**排除** distance 为空的店铺
（`SpringShopCatalog.java:121-129`）——不把「无法证明在半径内」当成「在半径内」。
该行为由 `searchWithoutCoordinatesExcludesShopsTheServiceDidNotPlace` 覆盖。

> ⚠️ 这是一处**契约层面的缺口**（工具 schema 不携带位置，但半径语义依赖位置），非本卡可自行
> 消除。本卡以「可配置原点」最小化落地，并在复核时提请裁定是否需要 W2-5 把定位并入调用链。

### 决策 2 · `Result.success==false` 一律**抛 `ShopCatalogException`**（而非返回空）

选择抛异常的兑现路径：agent-core `ToolRegistry.invoke` 捕获 handler 抛出的任何 `Exception`，
转成 `ToolFailure(EXECUTION_ERROR, …)`（`ToolRegistry.java:205-231`）。于是：
- 对模型：得到一个可自纠的**工具错误**，而不是「悄悄返回空、看起来像没搜到」；
- 对调用方：`manage`/循环不会因异常整体崩掉。

这与「解析不到品类名 ⇒ 返回空」形成清晰对照：**品类未知是合法输入下的正常空结果**（任务卡明说
返空）；**服务返回 `success=false` 是后端异常**，两者语义不同，故处理不同。
证据：`ToolRegistryWiringTest.serviceFailureBecomesAModelVisibleToolError`。

---

## 3. 单列：为什么半径/预算过滤放在适配器而不在服务层

1. **服务签名物理上不收这两个参数**：`quaryByShop(Integer typeId, Integer current, Double x, Double y, String sortBy)`
   没有半径、没有预算位（`IShopService.java:19`），本卡禁止改既有 service。要在服务层过滤就得改签名，越界。
2. **数据已经在返回结果里**：走 GEO 分支时 `Shop.distance` 已被服务填好（`ShopServiceImpl.java:266`），
   `avgPrice` 本就随实体返回。适配器只是对已取得的数据做一次内存过滤，不额外查库。
3. **职责边界**：服务层的分页/排序/缓存语义是既有原功能（铁律 1 不许改坏）；半径与预算是
   Agent 工具特有的约束，属于「确定性下沉到工具内部」（手册要求），落在适配器即落在工具边界内。
4. **可测性**：过滤规则是纯函数式判断（`withinRadius`/`withinBudget`），用假服务即可精确覆盖边界
   （恰好 80 保留、90 排除、`null` 不过滤），不必启动 Redis/MySQL。

**`score` 换算说明**：实体注释写明「评分，1~5分，乘10保存」（`Shop.java:87`），故 `score=47` 表示
4.7 分。`ShopBrief.score` 为 `Double`，适配器做 `score / 10.0`（`ValueCoercions.java:47`），
使暴露给模型的评分与「1~5 分」直觉一致。**这是我的解释性取值**，已在测试中固定（4.2/4.6），
如复核认为应原样传 `47.0` 请打回。

---

## 4. 测试与证据（真跑）

### 新增测试（真跑，21/21 绿）

```
mvn -B -ntp -o test -Dtest=ToolRegistryWiringTest,SpringShopCatalogTest -DfailIfNoSpecifiedTests=false
→ SpringShopCatalogTest$SearchNearby           Tests run: 9
→ SpringShopCatalogTest$ShopDetailAndVouchers  Tests run: 6
→ SpringShopCatalogTest$ReadOnly               Tests run: 2
→ ToolRegistryWiringTest                       Tests run: 4
→ Tests run: 21, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

| 任务卡测试要求 | 覆盖测试方法 |
|---|---|
| 1 半径过滤（100/800/2000，r=500⇒1） | `SpringShopCatalogTest$SearchNearby.radiusFilterKeepsOnlyShopsWithinTheRequestedDistance` |
| 2 预算过滤（含 `maxPrice=null` 不过滤） | `budgetFilterExcludesTooExpensiveShopsAndIsSkippedWhenNull` |
| 3 品类名解析失败返空不抛 | `unknownCategoryReturnsEmptyListWithoutGuessingAnId` |
| 4 `Result.success==false` 明确错误 | `failedResultBecomesAnExplicitError`（+ 接线层 `serviceFailureBecomesAModelVisibleToolError`） |
| 5 `avgPrice` 溢出钳制 | `outOfRangeAveragePriceSaturatesInsteadOfWrapping` |
| 6 `data` 类型不符明确失败 | `wrongResultPayloadTypeFailsInsteadOfReturningPartialData`、`wrongListElementTypeFailsInsteadOfSkippingTheBadRow`、`shopDetailFailsWhenResultIsNotAShop`、`voucherListFailsWhenPayloadIsNotAVoucherList` |
| 7 只读性（所有路径没调写方法） | `ReadOnly.noHappyPathCallsAWriteMethod`、`ReadOnly.errorPathsAlsoAvoidWrites` |

**只读性测试的诚实说明**：假服务用 `Proxy` 构造，**未 stub 的方法一律抛 `AssertionError`**——
因此适配器哪怕调用一次任何非预期方法（含所有写方法）都会当场失败；`assertNoWrites` 再对记录到的
调用名做写方法前缀断言兜底。这比 Mockito 的 `verifyNoInteractions` 更严，但也意味着**写方法
断言依赖「未被调用」而非「被显式 verify」**：若适配器真调了写方法，先触发的是未 stub 的
`AssertionError` 而非前缀断言。两者共同构成「没调写方法」的证明。

**为什么假服务用 `Proxy` 而不是 Mockito**：本沙箱禁止建 socket，Mockito inline mock maker 需要
self-attach；`task` 卡面明说「mock/stub」皆可，故用手写 `Proxy` 假服务，逻辑等价且沙箱内可跑。

### 全量 `mvn -B -ntp test`（真跑，结果如实记录）

```
mvn -B -ntp -o test
→ Tests run: 47, Failures: 0, Errors: 19, Skipped: 3   BUILD FAILURE
```

- 47 = 既有 30（`Tests run: 30 / Skipped: 3`）+ 新增 17。
- **19 条 Errors 全部是既有测试在沙箱内的基础设施失败**，与本次改动无关：
  - **17 条** = `IllegalState: Could not initialize plugin: interface org.mockito.plugins.MockMaker`
    （Mockito 需 self-attach，沙箱禁 socket）。先于我这条命令，单跑其子集 12/12 同样红，
    证明与我的改动无因果。
  - **2 条** = `java.net.SocketException: Operation not permitted`（`LegacyBlogLikeImporterTest:28`、
    `SeckillStreamIntegrationTest:37` 起 `ServerSocket`）。
- 本卡环境说明明确：绑端口/建 socket 的既有测试在沙箱内必红，「不要诊断它、不要改既有测试」。
  **全量验收由管理者在沙箱外执行**（对照 `pom`/CI）。
- 新增的 21 条测试**零 socket、零 Spring 上下文**，不受该限制。

### 未执行

- `mvn -B -ntp verify`（全量，含 Boot 打包）：**未执行**（沙箱内既有 socket 测试必红，按卡面交管理者）。
- `mvn -f agent-core/pom.xml -B -ntp verify`：**未执行**（本卡未改 agent-core，且沙箱内 mvn 可能
  强制 offline；此为管理者项）。

---

## 5. 单列：分页取舍

`search_nearby` 的语义是「附近 + 半径 + 预算」，而 `quaryByShop` 是**按 `typeId` 分页**的接口
（每页 `SystemConstants.DEFAULT_PAGE_SIZE = 5` 条，`ShopServiceImpl.java:229/234-235`）。

本卡选择 **`current=1`，只取第一页，不翻页**，理由：

1. **排序已由服务保证**：无坐标时按 `id`、有坐标时按距离升序（`ShopServiceImpl.java:238-242`
   `sortAscending()`）。第一页即为「最近的 5 家」，对「附近找个店」这一工具语义足够。
2. **半径/预算过滤会进一步缩小结果**：过滤后再翻页没有意义——若第一页全被半径剔除，第二页可能
   有店但更远，翻页只会返回更不相关的、且**半径语义要求的是近**；此时返回空比返回远店更诚实。
3. **成本与确定性**：Agent 工具调用要控延迟与 token；每多翻一页就多一次 Redis/DB 往返。
   固定 `current=1` 使单次工具调用的成本上界确定。
4. **避免 N+1 与 `FIELD` 排序的副作用**：GEO 分支用 `ORDER BY FIELD(id, …)` 拼字符串
   （`ShopServiceImpl.java:264`），多翻页徒增拼接面。

**已知代价**：若某 `typeId` 前 5 家都超出半径，会返回空列表，即便第 6 家其实在半径内。
对本项目 seed 数据（同 `typeId` 每类约 10 家、坐标相近）实际影响小。
**若后续要求「半径内尽量凑满 N 家」**，需改为循环翻页直至过滤后非空或翻到空页——本卡不做，
登记为已知取舍，请复核裁定是否需要升级。

---

## 6. 未达成 / 降级 / 待裁定

| 项 | 状态 | 说明 |
|---|---|---|
| 工具 schema 无坐标但语义要求半径 | ⚠️ 契约缺口 | 以 `SearchOrigin` 可配置原点落地（§2 决策 1）；`distance` 为空则排除。请复核裁定是否需 W2-5 并入定位 |
| `score ÷10` 的换算 | ⚠️ 解释性取值 | 依实体注释「乘10保存」，测试固定 4.2/4.6（§3）；如复核认为应原样传，请打回 |
| 只取第一页 | ⚠️ 已知取舍 | 见 §5；如需凑满 N 家需升级实现 |
| 全量 `verify` | 未执行 | 沙箱 socket 限制；交管理者（§4） |
