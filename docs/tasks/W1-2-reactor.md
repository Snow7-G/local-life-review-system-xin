# 任务卡 W1-2 — agent-core 并入根 reactor（顺带修复 CI 覆盖缺口）

难度：日常
交给：DSH（`dsh headless`）
理由：结构变更明确、无算法难度；风险点在「不许破坏两套已绿的测试」。

**前置**：W1-1 已提交（`e7dfd24`）—— 根项目现为 **JDK 21 + Spring Boot 4.1.1**；
`agent-core` 独立构建 28 条全绿；根回归 27 真实用例全绿。

## 为什么现在做

`agent-core` 至今**不在 CI 里**（根 `pom.xml` 无 `<modules>`，CI 曾跑 JDK 8）。
`docs/reviews/P1-3-review.md` 把「agent-core 23 条用例不进 CI = 无回归守护」列为必上报项。
W1-1 已把 CI 升到 **JDK 21**，前提条件消除 —— 现在把它并入 reactor，使 CI 自动覆盖它。
（即 `docs/APPROVALS-PENDING.md` AP-003 与 DSH 复核建议的 A 方案。）

## 目标

1. 根 `pom.xml` 增加 `<modules>`，把 `agent-core` 纳入聚合构建
2. CI 的 `mvn -B -ntp verify` 自动构建并测试 `agent-core`（**无需改 CI 文件**）
3. 两套测试都保持全绿，且**没有测试被减掉**

## 只许写这些文件

- `pom.xml`（根，仅新增 `<modules>` 与必要的必要 `<properties>`）
- `agent-core/pom.xml`（仅在为配合 reactor 所需时改；**不得改依赖集合**）
- `docs/W1-2-REACTOR.md`（新建，记录改动与验收）

**禁止**：改 `src/main/**`、`src/test/**`（业务）、`agent-core/src/**`（一律不动）、
`.github/workflows/ci.yml`（**不需要改 CI**，反正要验证的就是「CI 不改也能覆盖 agent-core」）、
`nginx-1.18.0/**`；不得删改任何测试或断言；不得 commit / push。

## 具体要做的事

### 1. 根 pom 增加 modules

```xml
<modules>
    <module>agent-core</module>
</modules>
```

注意点（自己核实并记录）：
- 根 pom 的 `<parent>` 是 `spring-boot-starter-parent:4.1.1`；`agent-core` 现在的 parent 是它自己
  （`agent-core/pom.xml` 无 `<parent>` 或父为独立坐标）。**聚合（modules）不等于继承（parent）**，
  两者可以并存：agent-core 作为 module 被聚合构建，同时保持自己的依赖集合。
- 若 `mvn verify` 因 parent/继承关系报错，选择**最小改动**方案解决，并在
  `docs/W1-2-REACTOR.md` 写清你改了什么、为什么，以及是否影响了 agent-core 的依赖集合。

### 2. 验证 CI 会自动覆盖（不改 CI 文件）

CI 跑的是根目录 `mvn -B -ntp verify`。并入 modules 后，该命令应会**同时**构建根项目与 `agent-core`。

请用与 CI 一致的方式验证：

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"
mvn -B -ntp verify          # 应同时出现 hm-dianping 与 agent-core 两个模块的测试统计
```

并**明确给出证据**：输出中出现了 `dev.lifeagent.mcp.*` 的测试类统计（证明 agent-core 真的被 CI 命令覆盖）。

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"

# 1. 聚合构建：根项目 + agent-core 都要跑
mvn -B -ntp verify
# 期望：BUILD SUCCESS，且输出中出现两类测试统计：
#   - com.hmdp.*  （27 个真实用例通过；Skipped 3 为 Surefire 计数语义，见 W1-UPGRADE-NOTES §D.1）
#   - dev.lifeagent.mcp.*（28 条全绿）

# 2. agent-core 独立构建仍可用（保留独立构建能力）
mvn -f agent-core/pom.xml -B -ntp test

# 3. 根项目单模块构建仍可用
mvn -B -ntp -pl . --also-make verify 2>/dev/null || echo "（该命令可选，失败不影响验收）"

# 4. CI 同款附加检查
node tests/common.test.cjs
mkdir -p nginx-1.18.0/logs && nginx -t -p "$PWD/nginx-1.18.0/" -c conf/nginx.conf
```

## 当前测试基线（必须保持）

| 构建 | 基线 |
|---|---|
| 根项目 | 27 个真实用例通过（`Tests run: 30, Failures: 0, Errors: 0, Skipped: 3`） |
| `agent-core` | `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0` |

并入后**聚合**构建的期望值 = 两者之和（Failures/Errors 必须为 0）。
**不许出现测试数减少**；若某个测试在聚合模式下失败而单独跑通过，说明隔离被破坏，
必须修好隔离（例如测试对工作目录的依赖），**不许删测试或放宽断言**。

### 特别提醒：测试对工作目录的敏感点

`agent-core` 的测试会用 `ProcessBuilder` 拉起 `FakeMcpServer` 子进程，并依赖
`surefire.test.class.path`。聚合构建时工作目录与 classpath 的组织方式可能变化。
**若出现此类失败，属于必须修好的隔离问题**，请如实记录在 `docs/W1-2-REACTOR.md` 里。

`HttpTransportTest` 还依赖 JDK 行为（202 的 `Content-Length` 由 JDK 写入、
`bodyLength=0` 触发 chunked）。JDK 版本未变（仍 21），预期不受影响；若有变化请如实报告。

## 交付前必须做的三遍审查（见 `docs/REVIEW-PROTOCOL.md`）

1. **自审**：写 `docs/W1-2-REACTOR.md`，逐条记录
   「要求 → 实现位置（文件:行）→ 是否达成 → 证据」，并**单列一节**给出
   「聚合构建输出中确实包含 `dev.lifeagent.mcp.*` 测试统计」的原始输出片段。
2. 之后由**异源模型（Codex）只读复核**（本卡 DSH 写 → Codex 审）。
3. 最后由管理者独立重跑验收命令。

**诚实要求**：没真跑的命令标 `未执行`，不许写成"通过"。

## 明确不做

- 不改 CI 文件（本卡要证的就是「CI 不改也能覆盖 agent-core」）
- 不接真实 LAYA、不写模型调用层（W2）
- 不改任何业务代码或测试断言
- 不 commit、不 push
