# W2-4 自审 — Spring 侧 `ShopCatalog` 适配器

> 执行器：Codex（`gpt-5.6-sol`）。交付范围：`src/main/java/com/hmdp/agent/**`、
> `src/test/java/com/hmdp/agent/**`、本文件。
> `agent-core/**` 一行未改（`git status --short agent-core/` 为空）。
> 本文件按 `docs/REVIEW-PROTOCOL.md` 第 1 遍要求，逐条记录「要求 → 实现位置（文件:行）→ 是否达成 → 证据」。
> **W2-4R 返工已追加在文末「返工记录（W2-4R）」**；该节是本轮必修项的最终证据，行号以返工后为准。

---

## 0. 交付清单

| 文件 | 说明 |
|---|---|
| `src/main/java/com/hmdp/agent/SpringShopCatalog.java` | 适配器主体，实现 `dev.lifeagent.tool.ShopCatalog` |
| `src/main/java/com/hmdp/agent/SearchOrigin.java` | 搜索原点（坐标来源，见 §2 决策 1） |
| `src/main/java/com/hmdp/agent/ValueCoercions.java` | `Long→Integer` 钳制、`score` 换算 |
| `src/main/java/com/hmdp/agent/ShopCatalogException.java` | 业务失败 / 类型不符的统一异常（会被 agent-core 转成 `EXECUTION_ERROR`） |
| `src/main/java/com/hmdp/agent/AgentCatalogConfig.java` | 唯一 Spring 装配点，暴露 `ShopCatalog` bean（无路由、无 controller） |
| `src/test/java/com/hmdp/agent/SpringShopCatalogTest.java` | 23 条（W2-4 交付 17 条 + W2-4R 返工 6 条）：半径/预算/品类/失败/钳制/类型/只读/原点 |
| `src/test/java/com/hmdp/agent/ToolRegistryWiringTest.java` | 4 条：经 agent-core `ToolRegistry` 的端到端接线 |

**W2-4 新增测试合计 21 条**（`SpringShopCatalogTest` 17 + `ToolRegistryWiringTest` 4），全部真跑通过（见 §4）。
**W2-4R 追加 6 条**（`SpringShopCatalogTest`，见本文件末「返工记录（W2-4R）」），当前 `com.hmdp.agent.*` 合计 **27 条**真跑通过（13 + 2 + 8 + 4，见 §4）。

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
的传输层/客户端定位可覆盖它，无需改 agent-core 契约）。逐店归则不变：`quaryByShop` 只有拿到
坐标才走 GEO 分支，未携带 distance 的店铺**一律排除**（`SpringShopCatalog.java:126-134`），
不把「无法证明在半径内」当成「在半径内」。

**W2-4R 裁定（管理者）：默认（未配置）原点是错误，不是空结果。** 原实现在原点缺失时坐标透传
`null` ⇒ `Shop.distance` 全为 `null` ⇒ 逐店 `withinRadius` 返回 false ⇒ **`search_nearby` 恒返空
且不报错**。模型会把「配置缺失」误读成「附近没有店」这类业务答案，是一种静默失败。裁定 **fail-loud**：
`origin` 缺 x 或 y 时抛 `ShopCatalogException`（`SpringShopCatalog.java:248-270`，入口
`:67` 先于任何服务调用），消息显式点出配置项
「搜索原点未配置：请在配置中提供 hmdp.agent.search-origin.x / .y」。这与 `PermissionGate` 的
fail-closed 同精神：**宁可显式报错，不要静默给出貌似正常的业务结果**。
证据：`SpringShopCatalogTest$SearchNearby.unconfiguredOriginFailsLoudlyInsteadOfSilentlyReturningNothing`、
`originMissingOneCoordinateAlsoFailsLoudly`（两者都断言**未调用** `quaryByShop`）。

> ⚠️ 契约层面的缺口仍在（工具 schema 不携带位置，但半径语义依赖位置），非本卡可自行消除。
> 本卡以「可配置原点 + 缺配置即报错」最小化落地，是否需要 W2-5 把定位并入调用链仍请复核裁定。

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

### 新增测试（W2-4 那一跑，真跑，21/21 绿）

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

- **47 = 既有 30（`Tests run: 30 / Skipped: 3`）+ `SpringShopCatalogTest` 17。这一跑不含
  `ToolRegistryWiringTest` 的 4 条**（该测试类当时尚未写入）。这正是 §0 与本节旧版计数
  「30 + 21 = 51 ≠ 47」矛盾的来源：47 那一跑发生在接线测试落盘之前；接线测试随后单独跑
  （`Tests run: 4`），合起来才是 21。合计 51 条只能由两次不同的 `mvn` 命令相加得到，
  没有任何一次单跑同时包含全部 21 条 —— 旧版把两次口径混写成一句，属自审笔误，特此更正。
- 逐类核对（`target/surefire-reports/*.txt` 汇总，`tests=` 总和亦为 47）：
  `HmDianPingApplicationTests` 3（Skipped 3）+ 既有 service/controller 类 27 = 30；
  `SpringShopCatalogTest` 17；`ToolRegistryWiringTest` 4（单独一跑）。
- **W2-4R 之后的 agent 包单跑**（`mvn -B -ntp -o test -Dtest='com.hmdp.agent.*Test'`）：
  `Tests run: 27, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS`（13 + 2 + 8 + 4）。
- 更正说明（W2-4R 必修 2）：`Tests run: 47` 的既有部分确为 **30**（含 3 Skipped）；47 − 17 = 30 自洽。
  旧版称「21 条全部在同一跑中真跑通过」是把「全量那跑的 17 条」与「接线单独一跑的 4 条」混写，
  而全量那跑不含接线 4 条。
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
| 工具 schema 无坐标但语义要求半径 | ⚠️ 契约缺口（W2-4R 已缓解） | 以 `SearchOrigin` 可配置原点落地（§2 决策 1）；原点缺配置时 fail-loud 抛错（W2-4R 必修 1），`distance` 为空则逐店排除。请复核裁定是否需 W2-5 并入定位 |
| `score ÷10` 的换算 | ⚠️ 解释性取值 | 依实体注释「乘10保存」，测试固定 4.2/4.6（§3）；如复核认为应原样传，请打回 |
| 只取第一页 | ⚠️ 已知取舍 | 见 §5；如需凑满 N 家需升级实现 |
| 全量 `verify` | 未执行 | 沙箱 socket 限制；交管理者（§4） |

---

## 返工记录（W2-4R）

> 依据 `docs/reviews/W2-4-review.md` 的 4 条必修 + 5 条建议，按 `docs/tasks/W2-4R-review-fixes.md` 执行。
> 只改 `src/main/java/com/hmdp/agent/SpringShopCatalog.java`、`src/test/java/com/hmdp/agent/SpringShopCatalogTest.java`、
> 本文件；`agent-core/**`、`pom.xml`、`.github/**`、既有 service/entity/controller/dto 与既有测试一行未动。
> 真假两说：下面标「真跑」的都是本轮真实执行过的命令；标「未执行」的绝不冒充通过。

### 必修项

**必修 1 · 默认配置下工具恒空且静默 → 裁定 fail-loud（最重要）**
- 实现位置：`SpringShopCatalog.java:67`（`searchNearby` 首行先 `requireConfiguredOrigin()`，**先于任何服务调用**）、
  `SpringShopCatalog.java:248-270`（`requireConfiguredOrigin` / `missingOriginMessage`；缺 x 或 y 即抛
  `ShopCatalogException`，消息含「搜索原点未配置：请在配置中提供 hmdp.agent.search-origin.x / .y」并回显当前 x/y）。
- 文档：本文件 §2 决策 1 已改写，写明「后果 = 恒返空且静默」与「裁定 fail-loud，与 PermissionGate fail-closed 同精神」。
- 证据（真跑）：
  - `SpringShopCatalogTest$SearchNearby.unconfiguredOriginFailsLoudlyInsteadOfSilentlyReturningNothing`：
    断言 `error.getMessage()` 含「搜索原点未配置」+ `hmdp.agent.search-origin.x` + `hmdp.agent.search-origin.y`，
    并断言 `shopService.calls().isEmpty()`（即**未调用 `quaryByShop`**，先报错后不查店铺）。
  - `SpringShopCatalogTest$SearchNearby.originMissingOneCoordinateAlsoFailsLoudly`：
    `new SearchOrigin(120.15, null)`，断言消息含「搜索原点未配置」+ `hmdp.agent.search-origin.y`，
    并断言 `shopService.calls().isEmpty()`。
- 变异验证（反向证明，真跑）：临时把 `:67` 的 `requireConfiguredOrigin();` 注释掉后单跑 agent 包
  → `Tests run: 26, Failures: 2`，失败正是上述两条；恢复后 27/27 绿。

**必修 2 · 更正自审测试计数矛盾**
- 实现位置：本文件 §0（`:22` 一带）与 §4「全量 `mvn -B -ntp test`」（原 `:140` 一带）。
- 核对到的真实数字（对 `target/surefire-reports/*.txt` 逐类求和）：
  `HmDianPingApplicationTests` 3（Skipped 3）+ 既有 service/controller 类 27 = **既有 30**；
  `SpringShopCatalogTest` **17**；`ToolRegistryWiringTest` **4**。全量那一跑 `Tests run: 47 = 30 + 17`，
  **不含** `ToolRegistryWiringTest` 的 4 条（该类当时尚未写入）；接线 4 条随后单独一跑。
  故 30+21=51 只能由两次不同 `mvn` 相加得到，没有任何一次单跑同时含全部 21 条。
- 已改写为一致口径：§0 拆分「W2-4 的 21 = 17 + 4（两次跑）/ W2-4R 后 27 = 13 + 2 + 8 + 4」，
  §4 补逐类求和与「47 那一跑缺接线 4 条」的说明。

**必修 3 · 改名/改实那条测试**
- 处理：删除名不副实的 `searchWithoutCoordinatesExcludesShopsTheServiceDidNotPlace`（它用的是**已配置**
  `ORIGIN`，仅假数据 distance 为 null），**合并为必修 1 的真用例**：
  `unconfiguredOriginFailsLoudlyInsteadOfSilentlyReturningNothing` + `originMissingOneCoordinateAlsoFailsLoudly`
  （`SpringShopCatalogTest.java:330-350`）。
- 同步修正自审：§2 决策 1 里对旧测试名的援引已替换为新用例名（旧 `:65` 的援引已不存在）。
- 另补反向用例 `forwardsOriginCoordinatesCurrentPageOneAndEmptySortToTheService`（`SpringShopCatalogTest.java:311-328`）
  钉住「已配置原点时坐标/current/sortBy 的真实透传」，与原用例想表达的现象对齐。

**必修 4 · 品类缺失行为统一并显式化**
- 实现位置：`SpringShopCatalog.java:144-151`（`categoryNameOf` 去掉 `typeId.toString()` 回退，反查不到即返 `null`），
  与 `:87`（`searchNearby` 的 `typeNames.get(shop.getTypeId())`）保持一致：两条路径同一事实同一输出 `null`。
- 证据（真跑）：
  - `SpringShopCatalogTest$ShopDetailAndVouchers.shopDetailReportsNullCategoryInsteadOfTheNumericTypeId`：
    typeId=9 无对应品类行，断言 `assertNull(detail.category())`。
  - `SpringShopCatalogTest$SearchNearby.searchNearbyReportsNullCategoryWhenTypeNameIsNotInTheTypeTable`：
    同上场景在搜索路径，断言 `assertNull(results.get(0).category())`。
- 变异验证（反向证明，真跑）：临时把 `:150` 改回 `name != null ? name : typeId.toString()`，
  单跑 → `shopDetailReportsNullCategoryInsteadOfTheNumericTypeId` 失败 `expected: <null> but was: <9>`；恢复后绿。

### 建议项（逐条：做 / 未做）

1. **假服务记录实参并断言透传 —— 已做。**
   `Fake` 新增 `arguments` 记录与 `argumentsOf` / `onlyArgumentsOf`（`SpringShopCatalogTest.java:96-104`）；
   `forwardsOriginCoordinatesCurrentPageOneAndEmptySortToTheService`（`:311`）断言 `quaryByShop` 收到
   `typeId=1 / current=1 / origin.x() / origin.y() / sortBy=""`。
   变异验证（真跑）：把 `:75` 的 `origin.x()` 改成 `null` → 该用例失败 `expected: <120.15> but was: <null>`；
   恢复后绿。（旧版此处是盲区：坐标改 null 仍全绿。）
2. **`typeId.intValue()` 范围校验 —— 已做。**
   新增 `intIdOf(Long, String)`（`SpringShopCatalog.java:279-284`）：超出 int 范围抛 `ShopCatalogException`，
   替换原先静默截断的 `typeId.intValue()`（调用点 `:75`）。
3. **`requireOne` 空 List 语义 —— 已做。**
   `SpringShopCatalog.java:213-227`：`null` 与空 `List` 同为「未找到该店铺」→ 返 `null`；
   非空且类型不符仍显式抛错。用例 `emptyShopListIsReportedAsNoDetailRatherThanATypeMismatch`
   （`SpringShopCatalogTest.java:395`）断言空 List ⇒ `assertNull(detail)`。
   变异验证（真跑）：去掉空 List 分支 → 该用例报「返回类型不符：期望 Shop，实际为 EmptyList」；恢复后绿。
4. **`String.valueOf(id)` 的 `"null"` —— 已做。**
   新增 `idText(Long)`（`SpringShopCatalog.java:286-294`），id 为 null 时返 null；
   `shopId`/`voucherId` 三处改用（`:85`、`:102`、`:117-118`）。
   用例 `missingEntityIdIsReportedAsNullNotTheLiteralWordNull`（`SpringShopCatalogTest.java:244`）断言缺失 id ⇒ `null`。
   变异验证（真跑）：把 `idText` 改回 `String.valueOf` → 该用例失败；恢复后绿。
5. **自审归因结论补命令与原始输出摘要 —— 已做。**
   见 §4：全量跑命令、`Tests run: 47 ... Errors: 19` 原文、逐类求和、以及「47 缺接线 4 条」的归因；
   沙箱红项仍归因于既有 Mockito self-attach / `ServerSocket`，与本改动无因果。

### 本轮真跑命令与结果（原文摘要）

```bash
# 1) agent 包回归（本卡要求的验收口径；-Dtest='com.hmdp.agent.*' 在 surefire 3.5.6 下匹配 0 条，改用 *Test 后缀）
export MAVEN_OPTS="-Xmx640m"
mvn -B -ntp -o test -Dtest='com.hmdp.agent.*Test' -Dsurefire.failIfNoSpecifiedTests=false
# → SpringShopCatalogTest$SearchNearby            Tests run: 13
#   SpringShopCatalogTest$ReadOnly               Tests run: 2
#   SpringShopCatalogTest$ShopDetailAndVouchers  Tests run: 8
#   ToolRegistryWiringTest                       Tests run: 4
#   Tests run: 27, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

- **未执行**：`mvn -B -ntp verify`（全量含 Boot 打包）—— 沙箱禁 socket，既有 socket/Mockito 测试必红，按卡面交管理者。
- **未执行**：`mvn -f agent-core/pom.xml verify` —— 本轮未改 agent-core（`git status --short agent-core/` 为空）。
- 说明：卡面给的 `-Dtest='com.hmdp.agent.*'` 在本机 surefire 3.5.6 下选出 0 条（无 `Tests run` 输出、BUILD SUCCESS），
  已改用 `-Dtest='com.hmdp.agent.*Test'` 以获得真实的 27 条执行结果（上述即该命令输出）。
- 未提交、未 push。
