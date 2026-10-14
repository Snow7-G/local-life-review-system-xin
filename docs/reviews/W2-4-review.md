# W2-4 异源复核报告（第 2 遍，异源 DSH）

> 复核者：DSH（`dsh headless`），**只读不改**。原始日志：`/tmp/w2-4-review-dsh.log`。
> 对象：Spring 侧 `ShopCatalog` 适配器（`src/main/java/com/hmdp/agent/` 5 类 + 2 测试类 + 自审文档）。

## 判定汇总

| 问 | 判定 | 要点 |
|---|---|---|
| Q1 安全 | **通过** | 无危险命令/凭据/socket/文件 IO；无静态可变状态；字段全 final |
| Q2 半径/预算过滤 | **有条件通过** | 机制正确、字段正确、放适配器的理由成立；**但默认配置下恒返空且静默** |
| Q3 品类解析 | **通过**（2 处小隐患） | 经 IShopTypeService 解析、解析不到返空不猜不抛；null category 无校验、库侧名字未 trim |
| Q4 字段映射 | **有条件通过** | clamp 与 score 换算正确；**shopDetail 品类缺失时静默回退成 typeId 数字串**；`typeId.intValue()` 无校验静默截断 |
| Q5 Result 语义 | **通过**（1 建议） | success==false 与类型不符均明确失败；空 List 报「类型不符」语义误导 |
| Q6 只读保证 | **通过** | 仅 4 个读方法；既有写方法未触达；但只读靠约定而非类型约束 |
| Q7 硬边界 | **有条件通过** | 无新路由、未重命名既有方法；工作树干净故零改动无法直接证明 |
| Q8 自审诚实性/测试质量 | **有条件通过** | 未发现虚构测试；但**计数自相矛盾**、一条测试名与实况不符且掩盖了 Q2 必修项 |

## 卡面点名 7 项覆盖

半径 ✔ / 预算含 null ✔ / 解析失败返空不抛 ✔ / success==false ✔ / 溢出钳制 ✔ / data 类型不符 ✔ /
**只读性：形式达成、证据强度不足**（兜底断言不可达 + 坐标透传无覆盖）⇒ 6 项充分，第 7 项需补强。

## 必修项（4）

1. **默认配置下工具恒空且静默**（`SpringShopCatalog.java:70,121-129` + `Shop.java:107-108` +
   `AgentCatalogConfig.java:23-24` + 自审 `:61-65`）：未配置坐标时坐标透传 null ⇒ 所有店 `distance==null`
   ⇒ 全被排除 ⇒ 恒返空。自审只写成逐店规则，未写「整工具恒空」后果。
   **裁定（管理者）**：未配置坐标时**抛显式工具错误**（fail-loud，与 PermissionGate fail-closed 同精神），
   并补 `new SearchOrigin(null, null)` 用例。
2. **更正自审测试计数矛盾**（自审 `:22` 称 21 条全部真跑 vs `:140` 称 47 = 30 + 17）。
3. **改名或改实** `searchWithoutCoordinatesExcludesShopsTheServiceDidNotPlace`
   （`SpringShopCatalogTest.java:263`）——该用例用的是已配置 ORIGIN，全仓无 `SearchOrigin(null,null)` 用例；
   并修正自审 `:65` 的援引。
4. **统一并显式化品类缺失行为**：去掉 `shopDetail` 的 typeId 数字串回退（`SpringShopCatalog.java:139-146`），
   与 `:82` 的 null 对齐，补断言，**禁止把 typeId 冒充品类名**。

## 建议项（6）

1. `:70` 的 `typeId.intValue()` 加范围校验或注释，与卡面「类型收窄要处理」一致。
2. **假服务记录实参**，补断言「`quaryByShop` 收到 origin 坐标 / `current=1` / `sortBy=""`」——消除变异盲区
   （现把坐标改成 null，21 条测试仍全绿）。
3. `requireOne` 空 List 文案改「未找到该店铺」语义；确认 `ShopTools` 对 `shopDetail` 返 null 的处理。
4. `String.valueOf(id)` 在 id 为 null 时产出字面量 `"null"`（`:80,115-116`）改为显式处理。
5. 自审 `:128-143` 归因结论补命令与原始输出摘要。
6. 出处标注对齐（自审头部写 `gpt-5.6-sol`，卡面为 deepseek）；
   **并确认 `HEAD ac909c6` 的提交归属 —— 答：由管理者提交，非执行器。**

## 复核者另注（管理者另立条目）

- `agent-core/.../ShopCatalog.java` 的 javadoc 仍写「The implementation lives in the application and is
  wired in **W2-3**」，实际是 W2-4。本卡不许改 agent-core，属既有文案不一致，已记入待办。
