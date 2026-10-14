# W2-6 异源复核报告（第 2 遍，异源 DSH）

> 复核者：DSH（`dsh headless`），只读不改、未运行构建。原始日志：`/tmp/w2-6-review-dsh.log`。
> 对象：只读闭环端到端集成测试（`src/test/java/com/hmdp/agent/e2e/` 3 文件 + 自审）。

## 判定汇总

| 问 | 判定 | 要点 |
|---|---|---|
| Q1 真实性 | **通过** | `SpringShopCatalog`/`ToolRegistry`/`ShopTools`/`HookChain`/`AgentLoop`/`PermissionGate` 全是**真实生产类型**；只有 `LlmClient` 与三个业务服务是假的；**生产逻辑零复制** |
| Q2 链路跑通 | **通过** | 事件序列为**列表精确相等**（多一个事件就会红）；`ANSWERED` 有断言；trace 与断言**同源**（同一份 `harness.events`） |
| Q3 半径/预算 | **通过**（边界有缺） | 3 家 → 半径 500 精确剩 1 家（断言精确名单 + size，非弱断言）；`maxPrice=80` 与 `null` 两种都覆盖；过滤逻辑在真实适配器内 |
| Q4 品类解析 | **通过** | `quaryByShop` 第一参数 `== Integer.valueOf(2)`，证明用的是**真解析出的 typeId**（非硬编码）；未知品类返空、不抛、且 `quaryByShop` 0 次 |
| Q5 只读性/熔断 | **有条件通过** | 熔断**有计数证据**（先记录后执行，第 4 次未进入 ⇒ 计数为 3）；只读性非真空（另有正计数）；但「写」的判定是**方法名前缀启发式** |
| Q6 硬边界 | **有条件通过** | 无新依赖；但发现 `SpringShopCatalog.java` mtime 落在本卡窗口内、晚于测试与自审，**与「src/main 零改动」冲突迹象** |
| Q7 诚实性 | **有条件通过** | 8 个 `@Test` 与 7 条必须断言一一对应、行号抽查全中、无恒真断言；三处不精确与「trace 未断言 `tool_result` 行」 |

## 必修项（3）—— 管理者澄清

1. **`SpringShopCatalog.java` 时间线矛盾** ✅ **已核验为虚警**：
   该 mtime（23:37:08）来自**管理者的变异验证**（备份 → 改 `withinRadius` 为恒真 → 跑 e2e → 还原）。
   `git diff HEAD -- src/main/java/com/hmdp/agent/SpringShopCatalog.java` 输出 **0 行**、工作区干净、
   无 `MUTATION` 残留 ⇒ **内容与 HEAD 逐字节一致**。
   `git show --stat HEAD` 证实本卡提交**只含 4 个文件**（1 文档 + 3 测试文件，895 行新增），**零生产代码**。
2. **提交归属** ✅ **答复**：执行器未 commit；交付由**管理者**提交为 `58b1c96`（卡面「不 commit」是对执行器的约束）。
3. **收尾复跑并刷新** ✅ **已完成**：在最终代码状态重跑 ——
   `ReadOnlyChainE2eTest` **8/0/0/0**；根 `mvn verify` **81/0/0/3**；`agent-core` **147/0/0/0**。均 BUILD SUCCESS。

## 建议项（5）

1. 只读断言改白名单：断言 `calls()` 恰等于允许集合，替代前缀启发式
   （`followShop`/`sign`/`like`/`seckillVoucher`/`incrStock` 等不在前缀表内）。
2. 补 `avgPrice==null` 且给出 `maxPrice` 的用例（覆盖 `SpringShopCatalog.java:141`）与 `distance==radius` 边界。
3. trace 用例补断言 `⓷ tool_result` 与 `stopReason: ANSWERED` 行；方法名改为 `...AndAssert...`。
4. 补一条 WRITE 工具被 `denyAll()` 拦下的用例（当前 `PermissionGate` 在只读链上只是旁站）。
5. `ScriptedLlm` 与 transport 套件的替身长期应提取到共享测试工具包（现因「不得改既有测试」只能复制，可接受）。

## 管理者处置（2026-10-14）

- 必修 1、2、3 全部澄清/完成（见上）。
- 建议 1–5 保留为待办：多属覆盖增强，其中建议 1（白名单化只读断言）与建议 3（trace 补断言）
  成本低、价值高，登记进后续卡。
- 本报告与自审的对应更新见 `docs/W2-6-E2E-READONLY.md` 新增的「## 8. 复核后的处置」。
