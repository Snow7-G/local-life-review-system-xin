# AGENTS.md — 探店规划 Agent 改造纪律

基础项目：local-life-review-system-xin（黑马点评二次开发，Spring Boot + Redis + MySQL）。
目标：在其上实现《探店规划 Agent 计划手册》v2026-10 的快慢双系统 Agent。

## 铁律（违反即回滚）

1. **不改坏原功能。** 秒杀（Lua + Stream）、登录、关注流、点赞、签到、GEO 搜索必须始终可用。
   每周收尾必须跑一遍 `mvn -B -ntp verify` 且全绿。
2. **任何明文凭据不得进仓库。** 一律走环境变量（见 `.env.example`）。提交前自查
   `git diff --cached | grep -iE 'password|secret|token'`。
3. **不新增未授权写接口。** 管理类写路由保持关闭状态，`ManagementRoutesTest` 是回归红线，不许改断言放行。
4. **不伪造功能。** 支付、退款、真实短信、评论业务接口没有实现就不得在页面暴露可操作入口。
   文档已明确承诺这一点，保持诚实。
5. **迁移脚本必须幂等或前置检查。** 不得出现无条件 `ALTER TABLE ... ADD UNIQUE KEY`
   （当前 `hmdp.sql` 基线已含该索引，重复执行会 1061）。
6. **一次一件事。** 一个 commit 一个可验收目标，commit 格式 `[P{阶段}-{序号}] 说明`。

## 已知技术债（动手前必读）

- `pom.xml` 把 `spring-data-redis`(2.6.2) 和 `lettuce-core`(6.1.6.RELEASE) 从 starter 中**排除后手工钉版本**，
  绕过了 Boot BOM。升 Boot 3 前必须先拆掉这两组 exclusion + 硬钉。
- MySQL 驱动 `5.1.47` 与驱动类 `com.mysql.jdbc.Driver` 必须换 `8.x` / `com.mysql.cj.jdbc.Driver`。
- `nginx-1.18.0/nginx.exe`(3.54MB) 及运行时上传目录 `nginx-1.18.0/html/hmdp/imgs/blogs/` 被误提交，
  属体积与脏工作区问题，清理需单独评估（改历史需授权）。
- 明文凭据 `1234` / `123456` 仍在 `ff16a74` 历史中（本地开发值，非生产）。
- 本机（阿里云 4G ECS）内存是全局瓶颈：Maven 构建必须 `MAVEN_OPTS="-Xmx640m"`，重活串行。

## 执行器分工

| 活 | 派给 |
|---|---|
| 模块脚手架、适配器、POJO、文档、已定位 bug | DSH（deepseek-v4.1-flash） |
| 主循环、流式解析、MCP 客户端、并发/权限、升级改造 | Codex（`-m gpt-5.6-sol`） |

**异源交叉复核，无例外**：DSH 产物 → Codex 只读审查；Codex 产物 → DSH 只读审查。
审查清单：①安全 ②逻辑正确性（对照任务卡逐条）③边界条件 ④与需求一致性。
复核者只读不改。两次重做仍不过 → 升级人工决策。

## 验证命令

```bash
# 全量回归（每周收尾、每次合并前）
export MAVEN_OPTS="-Xmx640m"
mvn -B -ntp verify

# 前端请求错误用例（CI 同款）
node tests/common.test.cjs

# nginx 配置检查（CI 同款）
mkdir -p nginx-1.18.0/logs
nginx -t -p "$PWD/nginx-1.18.0/" -c conf/nginx.conf
```

CI（`.github/workflows/ci.yml`）跑的就是这三条 + JDK 8 构建。
本地是 JDK 21，编译 `-source 8` 通过但运行时行为不等价，**最终判定以 CI 为准**。

## 禁止事项

- 不 push、不 force-push、不提交到上游（`xinqi123321` 无 push 权限，只有 PR 通道）。
- 删库、生产部署、花钱、暴露凭据 → 必须先请示。
- 不许用「改动测试断言」的方式让测试变绿。
