# 任务卡 W1-1 — 升级 JDK 21 + Spring Boot 4.1.1，原功能全回归

难度：困难
交给：Codex（`codex exec -m gpt-5.6-sol --sandbox workspace-write`）
理由：跨全仓的依赖手术 + 版本迁移 + `javax`→`jakarta` 全量替换，属「多文件重构 / 架构调整」。

**前置**：P1-4 已提交；`agent-core` 23+ 条测试全绿；根回归 28 tests 全绿。
**依据**：手册 §14 第 1 周「升级 JDK 21 和 Boot 3，原功能回归」+ §15「宁可推迟后面的功能，也别在半升级状态上继续加代码」。
**管理者已核实的版本事实**（不要自行猜测版本）：

| 事实 | 数值 |
|---|---|
| Spring Boot 3.5.x OSS 支持 | **已于 2026-06-30 结束**（EOL） |
| 当前受支持线 | **4.1（最新 4.1.1，2026-08-20）**；4.0.8 也可 |
| Java 支持范围 | Boot 4.1 → Java 17–26（Java 21 ✅） |
| `mybatis-plus-spring-boot4-starter` | **3.5.17**（2026-07-08，Maven Central HTTP 200 已验） |
| Redisson core | **4.8.0**（2026-10-02 发布） |
| 本机 Maven Central 可达性 | 已验证，可构建 |

> **决策说明（管理者）**：手册原文写"Boot 3.x"，但 3.5 已 OSS EOL，照字面升级会立刻落在
> 无支持版本上。故按"当前受支持线"升到 **Boot 4.1.1**，其余意图（JDK 21、jakarta、虚拟线程可用）不变。

## 目标

把项目从 JDK 8 + Spring Boot 2.3.12 升到 **JDK 21 + Spring Boot 4.1.1**，
**且原功能一条不坏**（秒杀、登录、关注流、点赞、签到、GEO、上传）。

## 只许写这些文件

- `pom.xml`（仅依赖与版本，不动 artifact/groupId）
- `src/main/java/**`（仅迁移性改动：`javax`→`jakarta`、驱动类名、配置键）
- `src/main/resources/application.yaml`
- `.github/workflows/ci.yml`（**仅** JDK 版本 8 → 21）
- `docs/W1-UPGRADE-NOTES.md`（新建，记录迁移中所有非机械性改动与理由）

**禁止**：改 `agent-core/**`（它是独立模块，本卡不动，升完再评估并入 reactor）、
改 `nginx-1.18.0/**`、改任何测试断言、改数据库脚本、
把 `agent-core` 加进根 `<modules>`（**本卡不加**，并入留待 W1-2）。

## 必须做的具体改动（逐条）

### 1. `pom.xml` 版本与依赖手术

1. parent：`spring-boot-starter-parent` `2.3.12.RELEASE` → **`4.1.1`**
2. `<java.version>` `1.8` → **`21`**
3. **拆掉手工钉版本**（这正是 AGENTS.md 记录的已知技术债 Δ8）：
   移除 `spring-boot-starter-data-redis` 里对 `spring-data-redis` 与 `lettuce-core` 的 `<exclusion>`，
   并**删除** `spring-data-redis`(2.6.2) 与 `lettuce-core`(6.1.6.RELEASE) 这两个显式依赖条目 ——
   交给 Boot 4.1 BOM 统一管理。
4. MySQL 驱动：`mysql:mysql-connector-java:5.1.47` → **`com.mysql:mysql-connector-j`**
   （**不写 version**，由 Boot BOM 管理）
5. MyBatis-Plus：`com.baomidou:mybatis-plus-boot-starter:3.4.3` →
   **`com.baomidou:mybatis-plus-spring-boot4-starter:3.5.17`**
6. Redisson：`org.redisson:redisson:3.13.6` → **`4.8.0`**
   （项目用 Redisson core + 手写 `Redisson.create`，**不**引入 spring-boot-starter）
7. `lombok`：1.18.30 起**且必须**能配合 JDK 21；若构建报 `java.lang.ExceptionInInitializerError`
   或注解处理器错误，升到当前与 JDK 21 兼容的最新 1.18.x
8. `hutool-all` / `guava` / `owasp-java-html-sanitizer` / `commons-pool2` / `aspectjweaver`
   保持原版本不动，除非构建失败（失败则记入 `docs/W1-UPGRADE-NOTES.md` 并给出新版本与理由）

### 2. `javax` → `jakarta`（全量，已实测统计）

| 现有用法 | 出现次数 | 改为 |
|---|---|---|
| `javax.annotation.Resource` | 18 | `jakarta.annotation.Resource` |
| `javax.annotation.PostConstruct` | 2 | `jakarta.annotation.PostConstruct` |
| `javax.annotation.PreDestroy` | 1 | `jakarta.annotation.PreDestroy` |
| `javax.servlet.http.*` | 8 | `jakarta.servlet.http.*` |

**不要动**（这些仍是 JDK 自带，不是 jakarta）：
- `javax.sql.DataSource`（3 处）
- `javax.imageio.*`（4 处）

### 3. 驱动类名与配置键

- `application.yaml`：`driver-class-name: com.mysql.jdbc.Driver` → **`com.mysql.cj.jdbc.Driver`**
- **Redis 配置键迁移**：Boot 3/4 把 `spring.redis.*` 改为 `spring.data.redis.*`。
  - `application.yaml` 里 `spring.redis.*` 下的 `host/port/password/lettuce.pool.*` 全部迁到 `spring.data.redis.*`
  - `RedissonConfig` 里的 `@Value("${spring.redis.host}")` / `${spring.redis.port}` / `${spring.redis.password:}`
    必须同步改为 `spring.data.redis.*` —— **漏改这一处会导致启动失败**
- `application.yaml` 引用的环境变量名（`HMDP_DB_*` / `HMDP_REDIS_*`）**保持不变**，不许改名
- `logging.level.com.hmdp: debug` 保持

### 4. CI

`.github/workflows/ci.yml` 里 `java-version: '8'` → **`'21'`**。
其余步骤（node 前端用例、nginx -t、`mvn -B -ntp verify`）**不得改动**。

### 5. 代码级迁移（可能被你漏掉的）

升到 Boot 4 后，以下地方要逐一确认，凡改动必须记入 `docs/W1-UPGRADE-NOTES.md`：

- `MvcConfig` 的 `WebMvcConfigurer` / `InterceptorRegistry`（Boot 4 仍在 `spring-webmvc`，包名不变）
- `LoginIntercepor` / `ReFreshIntecepor` 的 `HandlerInterceptor` 签名
- `WebExceptionAdvice`
- `RedisIdMake` / `CacheClient` 的 `StringRedisTemplate` 用法（`opsForValue`/`opsForHash`/Lua `execute`）
- 秒杀全链路：`VoucherOrderServiceImpl`、`SeckillOrderPersistenceService`、Lua 脚本路径
- `MybatisConfig`（MyBatis-Plus 3.5.17 的分页插件配置类名可能变化）
- 任何用了 `javax.validation` / `spring-boot-starter-validation` 的地方（本项目未用，确认即可）

## 验收命令

```bash
cd /root/projects/llrs-audit
export MAVEN_OPTS="-Xmx640m"

# 1. 编译与全量测试（原功能回归）
mvn -B -ntp verify
# 期望：BUILD SUCCESS，且测试数不少于 28（原基线 28 / 0 fail / 0 error / 1 skip）

# 2. CI 同款附加检查
node tests/common.test.cjs
mkdir -p nginx-1.18.0/logs && nginx -t -p "$PWD/nginx-1.18.0/" -c conf/nginx.conf

# 3. 确认没有任何 javax.* Web/注解残留（应无输出）
grep -rn "javax\.annotation\.\|javax\.servlet\." src/ || echo "OK: no javax web/annotation left"

# 4. 确认编译产物真的是 JDK 21 target
javap -v -cp target/classes com.hmdp.HmDianPingApplication 2>/dev/null | grep -i "major version"
# 期望 major version: 65（JDK 21）
```

## 当前测试基线（必须保持）

- 根回归 `mvn verify`：**Tests run: 28, Failures: 0, Errors: 0, Skipped: 1**
- `agent-core`：23 条（本卡不改它，只需确认仍能独立构建：`mvn -f agent-core/pom.xml -B -ntp test`）
- **如果升级过程中发现某个既有测试因为框架语义变化而失败，必须停下来在自审里报告，
  不许通过修改测试断言让它变绿。**

## 交付前必须做的三遍审查（见 `docs/REVIEW-PROTOCOL.md`）

1. **自审**：写 `docs/W1-UPGRADE-NOTES.md`，含：
   - 每条依赖改动的 `旧版本 → 新版本 → 动机`
   - 所有**非机械性**代码改动（尤其是配置键迁移与 MyBatis-Plus 配置类名）
   - 升级过程中踩到的坑与解决方式
   - 明确列出你**没能**升级或降级处理的地方
2. 之后由**异源模型（DSH）只读复核**你的产物（本卡 Codex 写 → DSH 审）。
3. 最后由管理者独立重跑全部验收命令。

**诚实要求**：本卡最容易出现"看着绿其实功能坏了"。
凡是你**没有真跑**的验收命令，必须在自审里标 `未执行`，不许含糊写成"通过"。

## 明确不做

- 不把 `agent-core` 加进根 reactor（留 W1-2）
- 不接真实 LAYA、不写 agent-core 的模型调用层（W2）
- 不改业务行为、不加新功能、不动数据库脚本
- 不 commit、不 push
