# 任务卡 P1-4 — 补齐 P1-3 的两处测试覆盖缺口

难度：日常
交给：DSH（`dsh headless`）
理由：规格已被异源复核精确锁定（含已实证通过的探针写法），属「已定位缺口 + 方案定好的测试」。

**前置**：P1-3 已提交（`3c8c097`），复核判定 **有条件通过**（`docs/reviews/P1-3-review.md`）。
本卡只补测试与自审记录，**不动实现代码**。

## 背景（必须理解，否则会写错）

P1-3 有 2 处**测试覆盖缺口**。复核者已用探针（`/tmp` 内、未进仓库）实证：
**实现侧行为是正确的**，缺的是守住它的断言。

复核结论摘要（见 `docs/reviews/P1-3-review.md` 判定节）：

1. **要求 6（404 重握手）**：现有用例只断言了「不可用 + 不泄漏 404 + 快速失败」，
   **没有**断言「重握手请求不带旧 `Mcp-Session-Id`」。更要命的是夹具配的
   `restartBackoff = [5s]`（`HttpTransportTest.java:223`），用例在 5 秒内就跑完了，
   **根本观测不到任何重握手** —— 这条断言等于形同虚设。
2. **要求 7（notify 202 + 空 body）**：仓库里**没有任何针对 notify 响应的显式断言**，
   只在每次握手的 `notifications/initialized` 上被间接走过。

## 只许写这些文件

- `agent-core/src/test/java/dev/lifeagent/mcp/HttpTransportTest.java`
- `agent-core/SELF-REVIEW-P1-4.md`（新建）

**禁止**：改 `HttpTransport.java` / `McpClient.java` / `McpTransport.java` /
`StdioTransportConfig.java` / `McpClientConfig.java` / `StdioTransport.java`
（**本卡零实现改动**；若你发现实现真有问题，停手并在自审里报告，不要自己改）；
禁止改 `McpClientTest.java`（既有 14 条的红线）；禁止改范围外文件。

## 要补的两件事

### 1. 补「重握手不带旧 Mcp-Session-Id」断言

复核者给出的写法（探针 P1 已实证稳定通过，建议照此实现）：
把该用例的 `restartBackoff` 调小（如 `50ms`），等待重握手真实发生，然后断言：

- 服务端看到的**第二次 `initialize` 请求的 `Mcp-Session-Id` 为 `null`**
  （用夹具已有的 `RequestRecord.sessionId()` / `server.requests()`）
- 重握手完成后 `client.isAvailable()` 恢复为 true，且 `callTool` 成功
- 重建使用了**新的** session id（`initialize` 之后请求带的是服务端新发的 id）

可以改现有用例，也可以**新增**一条独立用例（推荐新增，保留原用例对 fail-fast 的覆盖）。
若改现有用例，**不得删除或放宽它原有的任何断言**。

### 2. 补 notify 显式用例

服务端对 `notifications/*` 方法回 **202 且无 body**，断言客户端不抛异常且 `transport` 仍在运行
（`client.isAvailable() == true` / `isRunning()`）。

要求覆盖三种变体（复核者探针 P2 三种均实证通过）：
- `202` + 无 body
- `202` + `Content-Length: 0`
- `204`

需在夹具侧新增一个能触发客户端发通知的方法或开关（夹具现有 `notifications/initialized`
只在握手时被动发出）。**实现方式由你决定**，但必须真正走到客户端的 `notify(...)` 路径，
不能只断言握手间接成功。

### 3. 修正 `SELF-REVIEW-P1-3.md` 的对照表

（这处由你一并改，属同一份交付）
- 补回被遗漏的**要求 7**（notify 202 空 body）
- 如实标注**要求 6** 的实际覆盖强度：只覆盖 fail-fast，未覆盖重握手头断言
- 说明该表使用「补丁前编号」的原因：本卡任务卡在派单后被打过补丁
  （补丁提交 `8a2466f`，晚于执行器启动 31 秒），执行器读到的是补丁前版本 —— **责任在管理者，不在执行器**

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"
mvn -f agent-core/pom.xml -B -ntp test     # 必须 BUILD SUCCESS；测试数 ≥ 25（原 23 + 本卡 ≥2）
mvn -B -ntp verify                          # 仍须 28 tests 全绿
```

## 当前测试基线

- `agent-core`：**Tests run: 23, Failures: 0, Errors: 0, Skipped: 0**
- 根回归 `mvn verify`：**Tests run: 28, Failures: 0, Errors: 0, Skipped: 1**
- 两者都必须保持全绿；`agent-core` 只能增加不能减少。
- 本卡**不碰实现代码**，所以无「实现—断言」争议空间。

## 交付前必须做的三遍审查（见 `docs/REVIEW-PROTOCOL.md`）

1. **自审**：写 `agent-core/SELF-REVIEW-P1-4.md`，逐条记录
   「要求编号 → 实现位置（文件:行）→ 是否达成 → 备注」。
   并**单列一节**说明：为什么新断言能真正观测到重握手（引用 `restartBackoff` 的实际取值），
   以及 notify 用例走的是哪条代码路径。
2. 之后由**异源模型（Codex）只读复核**你的产物（本卡 DSH 写 → Codex 审）。
3. 最后由管理者独立重跑验收命令，并做一次**变异验证**。

**变异自检（请你自己也做一次并写进自审）**：把 `HttpTransport` 里 404 路径的
`sessionId = null` 暂时去掉，你新加的断言**必须变红**；若不变红，说明断言仍然观测不到，
不能算完成。验证后务必还原，并在自审里贴出变异时的失败输出。

## 明确不做

- 不改任何实现代码（含 `HttpTransport.java`）
- 不处理复核报告里的建议 5-10（SSRF 白名单、isAvailable 副作用、notify 超时、
  字节上限、Content-Type 嗅探、404 诊断）—— 这些留待接线 LAYA 时评估
- 不动 CI 配置（那是单独的人工决策项）
- 不 commit、不 push
