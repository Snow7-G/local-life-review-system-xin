# 返工卡 W2-7A-R — 应用 W2-7 A 段异源复核的必修项

难度：日常（已定位缺陷 + 方案定好的测试）
交给：Codex（`codex exec -m deepseek/deepseek-v4.1-flash --sandbox workspace-write`）
理由：**打回原执行器重做**（`docs/REVIEW-PROTOCOL.md` 升级规则）。

**依据**：`docs/reviews/W2-7A-review.md`（Q2/Q3/Q6 有条件通过，5 条必修，其中 3 条需改代码）。
**前置**：W2-7 A 段已提交（`dd53d2b`）并推送，根 105 全绿。

## 【环境限制】沙箱禁 socket、maven 可能 offline。不要诊断、不要重试全量、不要改既有测试。
只跑 `src/test/java/com/hmdp/agent/eval/**` 的测试。全量验收由管理者在沙箱外执行。

## 只许写这些文件

- `src/test/java/com/hmdp/agent/eval/**`
- `eval/**`
- `docs/W2-7-EVAL.md`（追加「## 返工记录（W2-7A-R）」）

**禁止**：改 `agent-core/**`、改 `src/main/**`、改 `pom.xml`/`.github/**`/`nginx-1.18.0/**`、改既有测试、
新增第三方依赖。**仍然禁止 B 段**（不许接真实 LLM、不许产出真实分数）。

## 必修项（逐条做）

### 必修 1 —— 三处死判定代码（`EvalJudge` 里永不执行的规则）

复核逐分支核对后确认这三处在当前任务集下**永不可达**：
- `EvalJudge.java:94` 附近 —— 工具调用上限（全任务集 `maxToolCalls` 出现 **0 次**）
- `EvalJudge.java:157` 附近 —— 「参数不在允许集合」（63 处 `paramChecks` 中 `allowed` 出现 **0 次**）
- `EvalJudge.java:395` 附近 —— `minCompliantNamed`（出现 **0 次**）

要求：**三选一，逐处明确处理**，不许留灰色地带：
(a) 补任务集用例使其可达并**配负对照证明它会判失败**；或
(b) 删掉该判定代码；或
(c) 保留但在文档里**显式登记**为「为 B 段/W5 预留、当前无数据」，且代码注释同样写明。
**判分器里留着永不执行的规则会让读者误以为已被覆盖**，这是本条的核心问题。

### 必修 2 —— 预算与品类两条核心约束必须各有负对照

现状：**19 条任务带预算约束、27 条带品类约束，但没有任何用例断言「超预算 / 错品类会判失败」**，
而「约束违反率」正是本卡的核心指标 ⇒ 该指标目前只在半径维度被证明过。

要求：各补至少一条负对照（用真实链路 + 故意破坏的轨迹），断言：
- 回答点名了**超预算**的店 ⇒ 判 FAIL 且 `constraintViolated()` 为真，原因点名该店
- 回答点名了**品类不符**的店 ⇒ 判 FAIL 且原因点名
并把它们纳入既有的负对照集合（`EvalControls`）与聚合断言（忠实 vs 破坏的成功率对比）。

### 必修 3 —— 负对照必须断言「打中了它该打的那条规则」

现状：`Control.intendedRule` 已记录（如 NC-01「约束规则：超半径的店不得被报出」），
但 `everyNegativeControlIsJudgedAsAFailure` **只断言 `reasons` 非空**，从不校验实际触发的规则是否
就是 `intendedRule` ⇒ 负对照「打没打中目标」目前靠人工读报告。

要求：改为**断言每条 control 的判定原因与该规则的可识别标记匹配**（给每条规则一个稳定的原因标记，
判定原因里必须出现对应标记）；并把 `distinctReasons` 的下界从 `>= 6` 收紧到**实测值**（复核实测 9 条两两不同）。

## 建议项（做得到就做，做不到在文档标「未做」）

1. `EvalTaskSetTest` 只断言每桶 `count >= 6`，改为断言**精确比例 12/10/8/8/6/6**，锁住配比。
2. `checkConstraints` 分支(1) 目前读 `EvalWorld` 数据而非**该次 `search_nearby` 返回的 JSON**；
   两源同源故结论不变，建议改为优先取工具返回 JSON、world 兜底（更贴合卡面「用工具返回的 distance/avgPrice 核验」）。
3. `containsAnyNegative` 的 `"无"`/`"没有"` 过宽，会把「无需预约」误判为「如实报告没找到」，
   建议收紧为词边界或更长短语。
4. `minToolCalls` / `gte` / `mustPresentCategories` / `minShops` / `self_radius` 五个子规则各补一条负对照，
   把「未被断言」的面收敛到零。

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"
mvn -B -ntp test -Dtest='com.hmdp.agent.eval.*Test' -Dsurefire.failIfNoSpecifiedTests=false
```

## 当前测试基线

根项目 **105** 条全绿（Skipped 3）；agent-core **147** 条全绿。**只能增加不能减少。**
既有测试不许改、不许删、不许放宽断言。

## 交付要求

- 在 `docs/W2-7-EVAL.md` **追加**「## 返工记录（W2-7A-R）」一节：逐条列「必修项 → 实现位置（文件:行）→ 证据（测试名 + 断言内容）」。
- 对必修 1 的三处，**逐处写明你选了 (a)/(b)/(c) 哪个及理由**。
- **诚实要求**：没真跑的命令标「未执行」；绝不要写你没写出来的测试；绝不要给出没真跑出来的分数。
- 不要 commit，不要 push。
