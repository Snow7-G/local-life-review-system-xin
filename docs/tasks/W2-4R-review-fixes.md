# 返工卡 W2-4R — 应用 W2-4 异源复核的必修项

难度：日常（已定位缺陷 + 方案定好的测试）
交给：Codex（`codex exec -m deepseek/deepseek-v4.1-flash --sandbox workspace-write`）
理由：**打回原执行器重做**（`docs/REVIEW-PROTOCOL.md` 升级规则）。

**依据**：`docs/reviews/W2-4-review.md`（Q2/Q4/Q7/Q8 有条件通过，4 条必修）。
**前置**：W2-4 已提交（`ac909c6`），根 51 tests 全绿。

## 【环境限制，先读】

沙箱禁 socket，且 maven 可能 offline。不要诊断、不要重试全量、不要改既有测试。
只跑 `src/test/java/com/hmdp/agent/**` 的测试。全量验收由管理者在沙箱外执行。

## 只许写这些文件

- `src/main/java/com/hmdp/agent/**`
- `src/test/java/com/hmdp/agent/**`
- `docs/W2-4-SPRING-ADAPTER.md`（修正与追加）

**禁止**：改 `agent-core/**`（一行都不许）、改 `pom.xml`/`.github/**`、改既有 service/entity/controller/dto、改既有测试。

## 必修项（逐条做）

### 必修 1 —— 默认配置下工具恒空且静默（**最重要**）

现状：原点未配置 ⇒ `origin.x()/y()` 为 null ⇒ 透传给 `quaryByShop` ⇒ `Shop.distance` 全为 null
⇒ `withinRadius` 逐店返回 false ⇒ **恒返空且不报错**。模型会误判成"附近没有店"。

**管理者裁定：fail-loud**。要求：
- `SearchOrigin` 的 x 或 y 为 null（未配置）时，**抛显式工具错误**，消息要能让人一眼看出是配置问题
  （例如「搜索原点未配置：请在配置中提供 agent.search.origin-x / origin-y」），与 `PermissionGate` 的
  fail-closed 同精神——**不要静默返空**。
- 在 `docs/W2-4-SPRING-ADAPTER.md` 写清这一后果与裁定理由。
- **补用例**：`new SearchOrigin(null, null)`（以及只配一个坐标为 null 的情况）断言抛出明确的工具错误，
  且**未调用** `quaryByShop`（不要先查店铺再报错）。

### 必修 2 —— 更正自审的测试计数矛盾

自审 `:22` 称「新增 21 条全部真跑通过」，`:140` 又称「47 = 既有 30 + 新增 17」。30+21=51≠47。
请核对真实数字并改写一致；若确有某一跑未包含 `ToolRegistryWiringTest` 的 4 条，如实说明。

### 必修 3 —— 改名或改实那条测试

`searchWithoutCoordinatesExcludesShopsTheServiceDidNotPlace` 用的是**已配置**的 ORIGIN（假数据 distance 为 null），
全仓没有 `SearchOrigin(null,null)` 用例 ⇒ 测试名与实况不符，且正好掩盖了必修 1。
要求：改为真正的「原点未配置」用例（与必修 1 合并），或改用能反映实况的名字；同步修正自审 `:65` 的援引。

### 必修 4 —— 品类缺失行为统一并显式化

`shopDetail` 在品类反查失败时，把 `typeId` 的**数字字符串**当品类名回退（约 `:139-146`）；
而 `searchNearby` 同一字段会返回 null（约 `:82`）。同一事实两种输出，且把 id 冒充品类名。
要求：两者统一为 **null**（或统一为一个明确的占位语义），**禁止把 typeId 冒充品类名**；补断言。

## 建议项（做得到就做，做不到在文档里标「未做」）

1. 假服务记录**实参**，补断言「`quaryByShop` 收到 `origin.x()/y()`、`current=1`、`sortBy=""`」
   —— 消除变异盲区（现把坐标改成 null，21 条测试仍全绿）。
2. `typeId.intValue()` 加范围校验或注释，与卡面「类型收窄要处理」一致。
3. `requireOne` 遇空 List 的文案改为「未找到该店铺」语义，并补用例。
4. `String.valueOf(id)` 在 id 为 null 时产出 `"null"`，改为显式处理。
5. 自审的归因结论补上命令与原始输出摘要。

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"
mvn -B -ntp test -Dtest='com.hmdp.agent.*' -Dsurefire.failIfNoSpecifiedTests=false
```

## 当前测试基线

根项目 **51** 条全绿（Skipped 3）；agent-core **147** 条全绿。**只能增加不能减少。**
既有测试不许改、不许删、不许放宽断言。

## 交付要求

- 在 `docs/W2-4-SPRING-ADAPTER.md` **追加**「## 返工记录（W2-4R）」一节：逐条列「必修项 → 实现位置（文件:行）→ 证据（测试名 + 断言内容）」。
- **诚实要求**：没真跑的命令标「未执行」；**绝不要写你没写出来的测试**（前几轮自审正因此被复核抓出）。
- 不要 commit，不要 push。
