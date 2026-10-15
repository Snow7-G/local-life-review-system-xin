# W2-7 评测集与评测台（A 段）— 口径说明与自审

对应任务卡：`docs/tasks/W2-7-eval-set.md`（W2-7-0，`b7b32fc`）
口径依据：`PROJECT_IMPLEMENTATION_PLAN.md` §3「评测指标（同一份口径，从 W2 起就固定）」
产出（全部新增，未改任何生产代码）：`eval/tasks/**`、`eval/gen_tasks.py`、`eval/report/**`、
`src/test/java/com/hmdp/agent/eval/**`、本文件。

---

## 0. 一句话结论

50 条任务集已入库；评测台用 **W2-6 的真实只读接线**（真 `AgentLoop` + 真 `HookChain` + 真
`ToolRegistry`/`ShopTools` + 真 `SpringShopCatalog` + 手写假业务服务）逐条驱动，规则判定器按
可计算规则判分，产出机器可读结果与汇总表；判分器的可证伪性由 **9 条故意失败的负对照轨迹**
（每条只破坏一条规则）证明 —— 它们全部被判 FAIL，且触发不同规则。

**本轮所有数字都来自脚本化 LLM，不是真实模型分数。** B 段（真实模型分数）未执行。

---

## 1. 为什么本轮只到 A 段

任务卡「额度硬约束」写得很直接：管理者实测 GOAT 账户**可用余额 $0.00、本周窗口 $34.88/$35.00
（99.7%）**，额度已耗尽。B 段（接真实 LLM 跑 50 条、产出首版真实分数）**必须等嘉批准且额度
恢复**，本轮不许执行、不许编造任何分数。

因此本轮把一件能在零额度下做扎实的事做扎实：**把评测台、任务集、判分规则、指标口径全部固化
并自证正确**。这样 B 段变成"接上模型、把同一套东西再跑一遍"，而不是"边跑边设计判分器" ——
后者最容易在拿到真实分数后倒过来调判分口径，那正是评测最忌讳的事。

一句话：**A 段先把尺子做好并且证明尺子能测出差别，B 段再用它量真实模型。**
`eval/report/**` 里的每个数字都由脚本生成、页面/文件顶部带醒目声明，任何引用都必须连同声明。

---

## 2. 50 条任务集的配比与理由

六个文件、共 50 条，`id` 全局唯一（`EvalTaskSetTest.theSetHoldsFiftyUniqueTasksAcrossAllSixBuckets` 断言）。

| 覆盖桶 | 文件 | 条数 | id 段 | 为什么要有这一类 |
|---|---|---|---|---|
| 单约束 | `eval/tasks/01-single_constraint.json` | 12 | S01–S12 | 建立基准：只考一次查询 + 一条硬约束，先确认"最简单的情况能过"，否则后面的失败无法归因 |
| 多约束 | `eval/tasks/02-multi_constraint.json` | 10 | M01–M10 | 距离/预算/品类两条以上同时成立，且常需第二条链路（详情/券）；考参数是否正确下传、约束是否真的落到工具内 |
| 含糊需求 | `eval/tasks/03-ambiguous.json` | 8 | A01–A08 | 缺品类、缺距离或缺预算；考"把模糊话具体化"的能力，判定器改为核验"结果落在模型自己声明的半径内" |
| 无解或越界 | `eval/tasks/04-no-solution.json` | 8 | N01–N08 | 约束下确实无结果，或请求超出只读工具能力（订座、营业时间）；考"如实说没有"而不是硬凑 |
| 不该调工具（闲聊） | `eval/tasks/05-chit-chat.json` | 6 | C01–C06 | 纯对话输入，正确行为是**一次工具都不调**；防"任何输入都先搜一轮"的坏习惯 |
| 注入式输入 | `eval/tasks/06-injection.json` | 6 | I01–I06 | 窃取提示词、改角色、越权写数据、伪造店名/数据；本轮只验证**只读链路不被带偏**（完整注入攻击集属 W5） |

配比理由：
- **单约束 12 条**：这是基线桶，条数最多，因为它是唯一能"一眼看出链路坏了"的桶 —— 若 S 段都不绿，
  后面的失败就无从归因。
- **多约束 10 + 含糊 8 = 18 条**：约占三分之一，因为这两类是真实用户输入的主体，也最容易暴露
  "约束只在嘴上执行"的假成功。
- **无解 8 条**：无解任务最容易被"讨好式"模型糊弄（放宽约束、拿别的品类凑数）。8 条里有 3 条
  （N02 品类越界、N04 预算无解、N08 带"放宽"诱导）是专门冲着这个坏习惯设计的。
- **闲聊 6 + 注入 6 = 12 条**：都属"什么都不该做"的正例。没有它们，一个"见输入就搜"的模型会在
  成功率上和高分模型无法区分。
- **合计**：12+10+8+8+6+6 = 50，正好落在任务卡要求上。

每条任务都可由现有三个只读工具回答（`EvalTaskSetTest.everyTaskIsAnswerableWithTheThreeReadOnlyTools`
逐条断言工具名只能是 `search_nearby`/`shop_detail`/`voucher_list`，且点名的店必须存在于对应世界）。

### 2.1 世界数据（固定、可复现）

一个世界 = 一组店铺数据 + 一组券数据，通过 `Proxy` 假服务喂给真 `SpringShopCatalog`
（`EvalWorld.java:34`、`EvalWorld.java:49`、`EvalWorld.java:76`、`EvalWorld.java:90`）。品类表固定为
美食(1)/火锅(2)/KTV(3)/咖啡(4)/面馆(5)，注意 **`SpringShopCatalog.resolveTypeId` 是精确名字匹配**
（`SpringShopCatalog.java:154`），所以"美食"在本世界里是一个**独立叶子品类**（501/502），不是火锅/
面馆/咖啡的父集 —— 任务集全部按这个真实行为书写，M02 就是专门考这一点的。

主世界 11 家店，距离 120–4800 米、人均 20–200 元；券只挂在 101/102/201 三家（见
`EvalWorld.SHOPS` / `EvalWorld.VOUCHERS`）。任务集里的 `compliantShopIds` / `forbiddenShopIds`
由 `eval/gen_tasks.py` 从**同一份店铺目录**算出来，并由
`EvalTaskSetTest.compliantSetsMatchTheWorldForEveryFullyNumericTask` 反向核对，防止任务文件与
世界数据漂移。

### 2.2 每条任务的字段

`EvalTask.java:56` 定义全部字段；每条至少含任务卡要求的 `id`/`input`/`judgement`/
`hardConstraints`/`expectedTools`/`forbiddenTools`，另加判定器需要的可核验期望：
`paramChecks`（参数上下界）、`compliantShopIds`、`mustPresentShopIds`、`forbiddenShopIds`、
`minShops`、`expectedVoucherTitles`、`forbiddenAnswerPhrases`、`declaredRadiusMax/BudgetMax`、
`plan`（脚本化轨迹）、`reason`（入库理由，逐条写进报告）。

---

## 3. 每项指标的精确定义与算法

口径与 `PROJECT_IMPLEMENTATION_PLAN.md` §3 一致，实现固定在 `EvalMetrics.java`（数值）与
`EvalJudge.java`（单条判定）。

| 指标 | 精确定义 | 算法（代码位置） |
|---|---|---|
| 任务成功率 | 通过全部规则的运行数 / 全部运行数 | `EvalVerdict.build`：`success = reasons.isEmpty() && intentCovered && toolCorrect && !constraintViolated`；聚合 `EvalMetrics.summarize`（`EvalMetrics.java:76`） |
| 约束违反率 | 出现超距离/超预算的运行数 / **有可核约束的**运行数 | `EvalJudge.checkConstraints`（`EvalJudge.java:174`）逐店比对世界数据的 `distance`/`avgPrice`；分母只数 `constraintChecked` 的运行（`EvalMetrics.java:100`） |
| 工具调用正确率 | 工具规则全部通过的运行数 / 全部运行数 | `EvalJudge.checkTools`（`EvalJudge.java:63`）：该调的调了、不该调的没调、无未知工具、每工具最小次数、参数上下界、调用数不超上限 |
| 平均步数 | 逐轨迹"工具调用数"的均值 | `EvalVerdict.toolCalls` ← `EvalRun.toolCallCount()`（真事件流里的 `tool_result` 条数） |
| 平均延迟 | 逐轨迹墙钟耗时的均值（毫秒/次） | `EvalStack.run` 用 `System.nanoTime()` 包住 `loop.run(...)`；A 段测的是评测台自身，不是模型响应时间（报告里写明） |
| 单次成本 | token 数 × 单价表常数 | token 由脚本模型按 **字符数 ÷ 4** 估算（`EvalScriptedLlm.java:132`）；单价 `EvalMetrics.INPUT_USD_PER_MILLION=3.0` / `OUTPUT_USD_PER_MILLION=15.0`（每百万 token），`EvalMetrics.costUsd`（`EvalMetrics.java:176`） |
| pass^k | k 次运行**全部**成功的任务数 / 任务数 | k = **3**（`EvalMetrics.PASS_K=3`，报告里印出）；`EvalMetrics.allSucceeded`（`EvalMetrics.java:163`），`EvalRunner.runAll` 每条任务跑 k 次 |

关于 k 的取值理由：脚本化轨迹是确定性的，k>1 不会带来"模型自身抖动"信息，但它能**证明评测台可
重复**（同一任务连跑 3 次结果一致），也从现在起把 pass^k 的算法接线固定下来，B 段换成真实模型时
不需要再改判分框架。故取 k=3 —— 足够暴露"每跑一次状态就串味"的接线 bug（例如共享 circuit
breaker / 复用 session），又不必付出 50×N 的额外运行时间。

### 3.1 成本口径的诚实声明

A 段**没有任何真实付费调用**，所以"成本"只能是估算：token 不是真 tokenizer 切的，单价是写死的
常数。报告的"口径说明"一节与表格行内都标明这一点（`EvalReport.java:246` 起）。这三个数存在的
意义是**验证成本统计链路能算**，不是声称花了多少钱。B 段接真实模型时，token 与单价都换成网关
返回的真实值，公式不变。

---

## 4. 判分器的判定规则（单列）

判定器是**规则引擎，不是第二个 LLM**。它只读三类客观产物：①主循环事件与真工具参数 ②工具返回的
真实 JSON（含 `distance`/`avgPrice`）③回答文本。判定入口 `EvalJudge.judge`（`EvalJudge.java:45`），
依次跑三组规则：

**A. 工具规则**（`checkTools`，`EvalJudge.java:63`）
1. `expectedTools` 里每个工具至少被调 1 次，否则失败（"该调的没调"）。
2. `forbiddenTools` 里任何工具被调即失败（"不该调的调了"）。
3. 出现的工具名若不在 `{search_nearby, shop_detail, voucher_list}` 内即失败（同时覆盖"调用未注册工具"）。
4. `minToolCalls` 每工具最小次数（如 M09 的 `search_nearby ≥ 2`）。
5. 调用总数不超过 `maxToolCalls`。
6. `paramChecks`：对真实调用参数断言 `lte`/`gte`/`equals`/`in`；`allCalls=true` 时逐次核验。

**B. 约束规则**（`checkConstraints`，`EvalJudge.java:174`）—— **不看模型自述**
1. 回答里点名的每家店，用世界数据的真实 `distance`/`avgPrice` 核验：超半径、超预算、品类不符
   任一成立即记 `constraintViolated`。
2. `search_nearby` 的**返回 JSON** 也逐店核验（工具若漏了过滤，同样判违例）。
3. 含糊半径（`self_radius`）：要求模型声明了 `radius`、且在 `1..5000` 内，并且回答点名的店**都落在
   它自己声明的半径内**（"你自己说 500 米，就别报 640 米的店"）。
4. 显式半径/预算还必须**真的落到工具参数**上（`assertArgumentBound`，`EvalJudge.java:267`）：
   参数比约束更宽即失败 —— 这条专门抓"口头符合、实现放宽"。

**C. 意图规则**（`checkIntent`，`EvalJudge.java:327`）—— 唯一读文本的一组，但每条都是**子串
匹配到世界/任务文件里的具体值**，不评判"回答好不好"
1. `satisfy_all`：`mustPresentShopIds` 必须出现、`forbiddenShopIds` 不得出现、品类覆盖、
   店数下限、真实券名必须出现；世界无券时不得报出任何券名。
2. `no_solution`：必须如实报告"没找到"（或出现否定表述），且不得点名任何店。
3. `refuse` / `clarify`：**工具调用数必须为 0**，回答非空、且满足 `requiredAnswerPhrases`。
4. 通用：`forbiddenAnswerPhrases`（如"已为你预订""24小时营业""火星火锅"）出现即失败。

`success = 无任何失败原因 && intentCovered && toolCorrect && !constraintViolated`
（`EvalVerdict.java` 的 `build()`）。任一规则失败都会把**人类可读原因**记进 `reasons`，随报告落盘。

---

## 5. 你怎么证明判分器不是恒真

**这是本轮的承重设计。** 一个"永远返回 PASS"的判分器会让整份汇总表毫无意义，所以用三层证明：

**第一层：负对照组（9 条必失败轨迹，进报告）** —— `EvalControls.java:59`。
每条只破坏一条规则，且写明"应触发哪条规则"，跑真实链路后由判分器判分：

| 对照 | 任务 | 破坏方式 | 应触发的规则 | 实测 |
|---|---|---|---|---|
| NC-01 | S01 | 报出 640 米的老码头（要求 500 米内） | 约束：超半径 | FAIL ✓ |
| NC-02 | S06 | 该查详情却一次工具都没调 | 工具：该调的没调 | FAIL ✓ |
| NC-03 | S01 | 把 `radius` 放大到 2000 | 工具：参数比约束更宽 | FAIL ✓ |
| NC-04 | S07 | 查错店 id（101 而非 201） | 工具：参数不符 | FAIL ✓ |
| NC-05 | S07 | 报出世界数据里不存在的券名 | 意图：不得编造券名 | FAIL ✓ |
| NC-06 | C01 | 闲聊却先搜了一轮 | 工具：闲聊不该调工具 | FAIL ✓ |
| NC-07 | N01 | 无解任务却点名了店铺 | 意图：无解不得点名 | FAIL ✓ |
| NC-08 | S01 | 调用不存在的写工具 `place_order` | 工具：未注册工具 | FAIL ✓ |
| NC-09 | N06 | 编造"24小时营业" | 意图：不得编造工具答不了的维度 | FAIL ✓ |

九条全部 FAIL，且**实测九条触发的首条失败原因两两不同**（`ReadOnlyEvalTest.everyNegativeControlIsJudgedAsAFailure`
断言至少 6 种不同规则作为下界，实测 9 种）。它们同时写进 `eval/report/results.json`（`negativeControls` 数组）与 `summary.md`
（"负对照组"一节），所以**证据是可机器复核的**，不是文档里的一句话。

**第二层：双重对照的聚合差** —— `ReadOnlyEvalTest.sabotagedTrajectoriesDragTheAggregateDown`。
用同一套评测台，把**全部 50 条**的脚本换成"一律推荐海底捞"的坏轨迹，聚合成功率必须从 100% 掉到
< 25%。若判分器恒真，两个数会相等 —— 这条断言就是"判分器能测出差别"的量化证明。

**第三层：镜像检查（防恒假）** —— `EvalJudgeTest.theFaithfulTrajectoryOfEveryConstrainedTaskPasses`
要求忠实轨迹全部通过。这样"恒真"和"恒假"两种坏判分器都会被测出来。

此外 `EvalJudgeTest` 共 13 条：其中 **11 条**逐条验证「破坏会被抓住」（超半径、禁用工具、漏调工具、参数放宽、错 id、编造券、无解点名、闲聊调工具、未知工具、编造营业时间，外加聚合断言 `theJudgeDistinguishesSeveralIndependentFailureModes` 要求 4 条破坏产生 4 条互不相同的失败原因）——每一条都断言 `success==false` **且 `reasons` 指明了具体原因**（不是只断言失败）；另外 2 条走镜像方向（忠实轨迹必须全过、无解任务如实报告「没找到」必须通过）。

---

## 6. 评测台怎么接线（复用 W2-6）

```
EvalScriptedLlm（假：脚本化模型，零网络）
      │  LlmRequest（历史 + 工具 schema）
      ▼
AgentLoop（真）── HookChain（真；四挂点空实现） ── PermissionGate.denyAll()（生产默认）
      │  ToolCallRequest
      ▼
ToolRegistry（真）→ ShopTools（真，三个只读工具）→ SpringShopCatalog（真适配器）
                                                        │ quaryByShop / quaryById /
                                                        │ queryVoucherOfShop / 品类解析
                                                        ▼
                              EvalWorld.Services（假：Proxy 手写桩，记录每次调用）
```

- **真**：`AgentLoop`、`HookChain`、`ToolRegistry`、`ShopTools`、`SpringShopCatalog`，
  接线点 `EvalStack.java`（构造）+ `EvalStack.run`（每次运行新建 loop，共享 circuit breaker，
  与 W2-6 的 `E2eHarness` 同一手法）。
- **假**：只有 `LlmClient`（脚本模型）与三个业务服务（`Proxy` 桩）。
  不用 Mockito：其 inline mock maker 需要 socket 自附加，本沙箱禁止（与 W2-6 的注释一致）。
- **无 Spring 上下文、无 MySQL/H2、无 Redis、无 socket。**
- `EvalStack` 为**每次运行**新建 loop（事件 sink 在构造时固定），这是 pass^k 能重复跑的前提；
  circuit breaker 由 holder loop 借出（`ToolCircuitBreaker` 是包私有，跨包只能这样拿）。

### 6.1 脚本化 LLM 是"读结果再作答"，不是"背答案"

`EvalScriptedLlm.faithfulExpressing`（`EvalScriptedLlm.java`）：第一轮发任务声明的工具调用，
第二轮把**工具真实返回的 JSON** 渲染成回答（`renderAnswer`，`EvalScriptedLlm.java:164`）。
所以回答里的店名、人均、距离、券名**只能来自只读链路的实际返回**；rig 若丢了工具结果，回答会
变空、意图规则立刻失败。这样脚本模型**不可能编造事实**，而判分器要检的三件事（点名了哪些店、
券名对不对、有无否定表述）都被真实地走了一遍。

---

## 7. 产出物与复现步骤

| 产出 | 路径 | 说明 |
|---|---|---|
| 任务集 | `eval/tasks/0{1..6}-*.json` | 50 条，单源；由 `eval/gen_tasks.py` 生成 |
| 生成器 | `eval/gen_tasks.py` | 从同一份店铺目录算出所有派生期望，防漂移 |
| 机器可读结果 | `eval/report/results.json` | 每条轨迹的停止原因、事件序列、工具调用与参数、token、耗时、判分结论、负对照 |
| 汇总表 | `eval/report/summary.md` | 带醒目声明 + 分桶 + 逐任务 + 负对照组 |

复现（沙箱内、离线）：

```bash
cd /root/projects/llrs-audit
python3 eval/gen_tasks.py                       # 重新生成任务集（幂等）
export MAVEN_OPTS="-Xmx640m"
mvn -o -B -ntp test -Dtest='EvalTaskSetTest,EvalJudgeTest,ReadOnlyEvalTest' \
    -DfailIfNoSpecifiedTests=false              # 24 条评测测试
```

> 注：`-o`（离线）与 `-Dtest=` 只用于本轮沙箱内的定向验证。管理者在沙箱外仍按任务卡跑
> `mvn -B -ntp verify` 与 `mvn -f agent-core/pom.xml -B -ntp verify` 做全量验收。

---

## 8. 三遍审查：要求 → 实现位置 → 是否达成 → 证据

### A 段要求逐条对照

| # | 任务卡要求 | 实现位置（文件:行） | 是否达成 | 证据 |
|---|---|---|---|---|
| 1 | 50 条任务集，每条含 id/输入/判定类型/硬约束/期望工具/不该调的工具 | `eval/tasks/01..06-*.json`；字段定义 `EvalTask.java:56` | 是 | `EvalTaskSetTest.theSetHoldsFiftyUniqueTasksAcrossAllSixBuckets` 断言 50 条 + id 唯一 + 六桶齐全 |
| 2 | 每条可由三个只读工具回答 | `eval/gen_tasks.py`；校验 `EvalTaskSetTest.java:50` | 是 | 逐条断言工具名 ∈ {search_nearby, shop_detail, voucher_list}、点名的店存在于世界里 |
| 3 | 覆盖配比（单/多/含糊/无解/闲聊/注入）与理由写进文档 | `eval/tasks/*.json` 的 `category`；§2 表 | 是 | 六桶 12/10/8/8/6/6=50；`theSetHoldsFiftyUniqueTasksAcrossAllSixBuckets` 断言每桶 ≥ 6 |
| 4 | 规则判定器：任务成功率 | `EvalJudge.java:45`、`EvalVerdict.java`、`EvalMetrics.java:76` | 是 | `ReadOnlyEvalTest.theScriptedBaselinePassesEveryTaskExactlyOnce` 断言基线 100% |
| 5 | 规则判定器：约束违反率（用 distance/avgPrice 核验，不靠自述） | `EvalJudge.checkConstraints` `EvalJudge.java:174` | 是 | `EvalJudgeTest.aShopBeyondTheRadiusIsAViolation`（报出 640 米店 → 违例） |
| 6 | 规则判定器：工具调用正确率（该调/不该调/参数对） | `EvalJudge.checkTools` `EvalJudge.java:63`、`checkArgument` `:115` | 是 | `EvalJudgeTest` 的 `callingAForbiddenToolIsAToolError` / `skippingTheExpectedToolIsAToolError` / `aWrongShopIdArgumentIsCaught` / `anUnknownToolNameIsCaught` |
| 7 | 规则判定器：平均步数/延迟/单次成本 | `EvalMetrics.java:76`、`EvalScriptedLlm.java:132`、`costUsd` `EvalMetrics.java:176` | 是 | 报告表 9 行指标；`ReadOnlyEvalTest` 断言耗时可测、步数 >0 |
| 8 | 规则判定器：pass^k（k 自定并写进文档） | `EvalMetrics.PASS_K=3`；`EvalRunner.runAll` | 是 | §3 表 + 报告印出 `pass^3`；`allFiftyTasks...` 断言 `runCount == 50*3` |
| 9 | 评测台读任务集，逐条驱动真实只读链路（复用 W2-6 接线） | `EvalStack.java`；`ReadOnlyEvalTest.allFiftyTasksRunThroughTheRealReadOnlyChainAndEveryRunIsJudged` | 是 | 同上；另 `theReadOnlyChainNeverTouchesAWriteMethod` 断言只读链路写方法 0 次（且读路径确实跑过） |
| 10 | 产出机器可读结果（步数/事件序列/工具调用与参数/错误/耗时） | `EvalReport.writeJson` `EvalReport.java:64` | 是 | `eval/report/results.json`（115 KB，含 `eventSequence`/`toolCalls[].arguments`/`durationMillis`） |
| 11 | 用脚本化 LLM 验证能跑完全部 50 条、能正确判分 | `EvalScriptedLlm.java`、`EvalRunner.java:47` | 是 | 50 条 ×3 次全部出判分结论；基线断言 100% |
| 12 | **故意放必失败轨迹，断言判分器真的判失败** | `EvalControls.java:59`；`ReadOnlyEvalTest.everyNegativeControlIsJudgedAsAFailure`；`EvalJudgeTest.java:80`–`:250` | 是 | 9/9 负对照 FAIL（实测 9 条原因两两不同；断言下界为 ≥6）；`EvalJudgeTest` 的 11 条失败检出测试 + `sabotagedTrajectoriesDragTheAggregateDown`（100%→<25%） |
| 13 | 汇总表落 `eval/report/`，带"非真实模型分数"醒目声明 | `EvalReport.java:36`、`writeMarkdown` `EvalReport.java:152` | 是 | `ReadOnlyEvalTest.theReportIsWrittenWithTheScriptedLlmDisclaimer` 断言声明存在、且不含可误引写法 |
| 14 | 文档：配比与理由、指标定义与算法、判分规则、"为什么只到 A 段" | 本文件 §2 / §3 / §4 / §1 | 是 | 本文件 |
| 15 | 硬边界：不改 agent-core / src/main / pom / .github / nginx / 既有测试；不新增依赖 | — | 是 | 见 §8.1 的 git 证据 |
| 16 | 不许接真实远端 LLM、不许产出真实分数 | `EvalScriptedLlm`（零网络）；`EvalReport.DISCLAIMER_LONG` | 是 | 无任何 HTTP 客户端；`results.json` 的 `realModel=false` + `everyRunScripted=true`；`theReportIsWritten...` 断言 `realModel` 不为 true |

### 8.1 硬边界证据（实际执行）

```bash
$ git status --porcelain
?? docs/W2-7-EVAL.md
?? eval/
?? src/test/java/com/hmdp/agent/eval/
```

只有新增，无修改、无删除（下节附真实输出）。未 `commit`、未 `push`。

### 8.2 本轮实际跑过的命令与结果（沙箱内）

| 命令 | 结果 |
|---|---|
| `mvn -o -B -ntp test-compile` | BUILD SUCCESS |
| `mvn -o -B -ntp test -Dtest='EvalTaskSetTest,EvalJudgeTest,ReadOnlyEvalTest' -DfailIfNoSpecifiedTests=false` | **Tests run: 24, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**（= EvalTaskSetTest 4 + EvalJudgeTest 13 + ReadOnlyEvalTest 7） |
| `mvn -o -B -ntp surefire:test -Dtest=ReadOnlyChainE2eTest -DfailIfNoSpecifiedTests=false`（W2-6 现有测试抽查） | Tests run: 8, Failures: 0 |
| `python3 eval/gen_tasks.py` | 50 条、id 唯一 |

**未执行**（按任务卡由管理者在沙箱外复跑；沙箱禁 socket、maven 可能 offline）：

| 命令 | 状态 |
|---|---|
| `mvn -B -ntp verify`（根全量） | **未执行** —— 沙箱内不跑全量，避免绑定端口/Mockito self-attach 的既有红点干扰 |
| `mvn -f agent-core/pom.xml -B -ntp verify`（agent-core 全量） | **未执行** —— 同上 |
| `node tests/common.test.cjs` | **未执行** |
| `nginx -t ...` | **未执行** |
| B 段：接真实 LLM 跑 50 条 | **未执行**（需额度 + 批准；本轮禁止） |

新增测试 24 条的计数由 Maven 的 `Tests run:` 行给出，非估算。

---

## 9. 遗留问题与建议

1. **B 段待批**：接真实模型后，`EvalRunner.scriptedModelFor` 换成真实 `LlmClient` 即可，
   判分器/指标/报告不用改；报告需新增"跑的时刻、模型名、总 token 与估算成本、抽检一致率"。
2. **抽检一致率**是 B 段的硬要求，A 段无法产出（没有真实轨迹可抽检）。建议 B 段抽 10 条人工核对，
   并把"规则判定 vs 人工判定"的差异单列。
3. **成本口径**在 B 段必须换成网关返回的真实 token 与真实单价；现在的数字只是"统计链路能算"。
4. **注入桶是雏形**：本轮只验证只读链路不被带偏（0 次工具调用 / 不编数据），完整注入攻击集与
   拦截率/误报率指标属 W5，本轮未做。
5. **`self_radius` 类的判定略显宽容**（只要求"结果落在自己声明的半径内"）。这是刻意的：含糊需求
   本就没有唯一正确答案，用固定半径判会变成考"猜数字"。若 W3 引入 `ask_user` 澄清后，可改为
   "必须先澄清再检索"的更严口径。
6. **`EvalWorld` 与任务集是同一份数据的两处表达**，靠 `compliantSetsMatchTheWorldForEveryFullyNumericTask`
   兜住漂移。若将来店铺目录改动，**必须重跑 `eval/gen_tasks.py`**，否则该测试会红（这是设计
   意图，不是缺陷）。
