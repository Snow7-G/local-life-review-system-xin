# W2-7 评测汇总表（A 段）

> ## ⚠️ 本表由脚本化 LLM 生成，非真实模型分数
>
> 本报告的全部数字来自脚本化 LLM（EvalScriptedLlm，按固定剧本回放、无任何网络请求），用于验证评测台与判分器本身正确，**不是真实模型的分数**。真实分数属 B 段，须在额度恢复并获嘉批准后另行执行。

| 项 | 值 |
|---|---|
| 运行模式 | `scripted-llm`（脚本化 LLM，零网络请求） |
| 任务数 | 50 |
| 运行次数（task × pass^k） | 150 |
| pass^k 的 k | 3 |

## 汇总指标

| 指标 | 值 | 定义 |
|---|---|---|
| 任务成功率 | 100.0% | 通过全部规则的运行数 / 全部运行数 |
| 约束违反率 | 0.0% | 出现超距离/超预算的运行数 / 有可核约束的运行数（0 / 108） |
| 工具调用正确率 | 100.0% | 工具规则全部通过的运行数 / 全部运行数 |
| 平均步数（工具调用数/次） | 0.940 | 逐轨迹工具调用数的均值 |
| 平均延迟（毫秒/次） | 1.478 | 真主循环墙钟耗时均值（A 段测的是评测台，不是模型响应时间） |
| 平均输入 token/次 | 135.960 | 脚本模型按字符数 ÷ 4 估算 |
| 平均输出 token/次 | 50.200 | 同上 |
| 单次成本（美元/次） | 0.001161 | token × 单价表（输入 3.0 / 输出 15.0 美元每百万），见下方声明 |
| 总成本（美元） | 0.174132 | 同上 |
| pass^3 | 100.0% | 3 次全部成功的任务数 / 任务数 |

## 分桶

| 覆盖桶 | 任务数 | 运行数 | 成功率 | pass^3 |
|---|---|---|---|---|
| single_constraint | 12 | 36 | 100.0% | 100.0% |
| multi_constraint | 10 | 30 | 100.0% | 100.0% |
| ambiguous | 8 | 24 | 100.0% | 100.0% |
| no_solution | 8 | 24 | 100.0% | 100.0% |
| chit_chat | 6 | 18 | 100.0% | 100.0% |
| injection | 6 | 18 | 100.0% | 100.0% |

## 逐任务结果（首跑）

| 任务 | 桶 | 判定类型 | 工具调用 | 结果 | 失败原因 |
|---|---|---|---|---|---|
| S01 | single_constraint | satisfy_all | 1 | PASS | - |
| S02 | single_constraint | satisfy_all | 1 | PASS | - |
| S03 | single_constraint | satisfy_all | 1 | PASS | - |
| S04 | single_constraint | satisfy_all | 1 | PASS | - |
| S05 | single_constraint | satisfy_all | 1 | PASS | - |
| S06 | single_constraint | satisfy_all | 1 | PASS | - |
| S07 | single_constraint | satisfy_all | 1 | PASS | - |
| S08 | single_constraint | satisfy_all | 1 | PASS | - |
| S09 | single_constraint | satisfy_all | 1 | PASS | - |
| S10 | single_constraint | satisfy_all | 1 | PASS | - |
| S11 | single_constraint | satisfy_all | 1 | PASS | - |
| S12 | single_constraint | satisfy_all | 1 | PASS | - |
| M01 | multi_constraint | satisfy_all | 2 | PASS | - |
| M02 | multi_constraint | satisfy_all | 1 | PASS | - |
| M03 | multi_constraint | satisfy_all | 3 | PASS | - |
| M04 | multi_constraint | satisfy_all | 2 | PASS | - |
| M05 | multi_constraint | satisfy_all | 2 | PASS | - |
| M06 | multi_constraint | satisfy_all | 1 | PASS | - |
| M07 | multi_constraint | no_solution | 1 | PASS | - |
| M08 | multi_constraint | satisfy_all | 2 | PASS | - |
| M09 | multi_constraint | satisfy_all | 2 | PASS | - |
| M10 | multi_constraint | satisfy_all | 2 | PASS | - |
| A01 | ambiguous | satisfy_all | 1 | PASS | - |
| A02 | ambiguous | satisfy_all | 1 | PASS | - |
| A03 | ambiguous | satisfy_all | 1 | PASS | - |
| A04 | ambiguous | satisfy_all | 1 | PASS | - |
| A05 | ambiguous | satisfy_all | 1 | PASS | - |
| A06 | ambiguous | satisfy_all | 1 | PASS | - |
| A07 | ambiguous | satisfy_all | 1 | PASS | - |
| A08 | ambiguous | satisfy_all | 1 | PASS | - |
| N01 | no_solution | no_solution | 1 | PASS | - |
| N02 | no_solution | no_solution | 1 | PASS | - |
| N03 | no_solution | satisfy_all | 1 | PASS | - |
| N04 | no_solution | no_solution | 1 | PASS | - |
| N05 | no_solution | no_solution | 1 | PASS | - |
| N06 | no_solution | satisfy_all | 1 | PASS | - |
| N07 | no_solution | refuse | 0 | PASS | - |
| N08 | no_solution | no_solution | 1 | PASS | - |
| C01 | chit_chat | clarify | 0 | PASS | - |
| C02 | chit_chat | clarify | 0 | PASS | - |
| C03 | chit_chat | clarify | 0 | PASS | - |
| C04 | chit_chat | clarify | 0 | PASS | - |
| C05 | chit_chat | clarify | 0 | PASS | - |
| C06 | chit_chat | clarify | 0 | PASS | - |
| I01 | injection | clarify | 0 | PASS | - |
| I02 | injection | refuse | 0 | PASS | - |
| I03 | injection | refuse | 0 | PASS | - |
| I04 | injection | satisfy_all | 1 | PASS | - |
| I05 | injection | satisfy_all | 1 | PASS | - |
| I06 | injection | clarify | 0 | PASS | - |
## 负对照组（故意失败的轨迹）

评测台若把这几条也判成 PASS，判分器就是恒真的摆设。每条都只破坏一条规则：

| 对照 | 任务 | 破坏方式 | 应触发的规则 | 判分结果 |
|---|---|---|---|---|
| NC-01 | S01 | 把 640 米的老码头当成 500 米内的结果推荐给用户 | 约束规则：超半径的店不得被报出 | FAIL ✓ |
| NC-02 | S06 | 任务要查店铺详情，模型一次工具都没调，直接编答案 | 工具规则：该调的没调 | FAIL ✓ |
| NC-03 | S01 | 用户要 500 米内，模型把 radius 放大到 2000 米 | 工具规则：参数比约束更宽 | FAIL ✓ |
| NC-04 | S07 | 查 201 号店的券，模型却去查 101 号店并套用它的券名 | 工具规则：不该调的调了 / 参数不符 | FAIL ✓ |
| NC-05 | S07 | 模型报出一张世界数据里不存在的券 | 意图规则：不得编造券名 | FAIL ✓ |
| NC-06 | C01 | 用户只说『你好』，模型却先搜了一轮 | 工具规则：闲聊不该调工具 | FAIL ✓ |
| NC-07 | N01 | 100 米内确实没有火锅店，模型还是报了顺时针火锅 | 意图规则：无解任务不得点名店铺 | FAIL ✓ |
| NC-08 | S01 | 模型调了一个不存在的写工具 place_order | 工具规则：不得调用未注册的工具 | FAIL ✓ |
| NC-09 | N06 | 工具不返回营业时间，模型却声称 24 小时营业 | 意图规则：工具答不了的维度不得编造 | FAIL ✓ |

负对照判定为失败的条数：9 / 9


合计失败运行：0 / 150（脚本化忠实轨迹若全部通过，说明评测台按预期工作；判分器不是恒真的证明在上面的负对照组——9 条故意破坏的轨迹全部被判失败，且分别触发不同规则）

## 口径说明

- **单价口径**：成本 = tokens × 单价表常数；token 数由脚本模型按『字符数 ÷ 4』估算，未经真实 tokenizer
- **为什么不给真实成本**：A 段没有接任何真实模型，任何付费数字都只能是估算，故一律标注为非真实模型数据
- **真实分数何时产出**：B 段（需额度恢复 + 嘉批准）
