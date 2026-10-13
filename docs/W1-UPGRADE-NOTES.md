# W1-1 JDK 21 / Spring Boot 4.1.1 升级记录

日期：2026-10-14

状态：阻塞，尚未完成验收。本文只记录实际改动与实际执行结果，不代表任务已通过。

## 依赖与构建版本改动

| 项目 | 旧值 | 新值 | 动机 |
|---|---|---|---|
| Spring Boot parent | `2.3.12.RELEASE` | `4.1.1` | 使用任务卡指定的当前受支持线 |
| Java target | `1.8` | `21` | 升级运行与编译基线，启用 JDK 21 |
| Spring Data Redis | 显式依赖 `2.6.2`，并从 starter 排除 | 删除显式依赖与 exclusion，由 Boot 4.1.1 BOM 管理 | 消除绕过 Boot BOM 的手工降级 |
| Lettuce | 显式依赖 `6.1.6.RELEASE`，并从 starter 排除 | 删除显式依赖与 exclusion，由 Boot 4.1.1 BOM 管理 | 让 Redis 依赖保持同一兼容版本族 |
| MySQL Connector/J | `mysql:mysql-connector-java:5.1.47` | `com.mysql:mysql-connector-j`，不显式写版本 | 使用新坐标，并交给 Boot BOM 管理 |
| MyBatis-Plus starter | `mybatis-plus-boot-starter:3.4.3` | `mybatis-plus-spring-boot4-starter:3.5.17` | 使用任务卡指定的 Boot 4 适配 starter |
| Redisson core | `org.redisson:redisson:3.13.6` | `org.redisson:redisson:4.8.0` | 升到任务卡指定版本；仍使用 core 与手写 `Redisson.create`，未引入 Spring starter |
| Lombok | `1.18.30` | `1.18.30`（未改） | 已达到任务卡的 JDK 21 最低兼容要求；构建尚未进入编译，未发现需升级的注解处理器错误 |

`hutool-all`、`guava`、`owasp-java-html-sanitizer` 的显式版本未改；`commons-pool2`、`aspectjweaver` 仍不显式写版本。由于依赖无法下载，尚未得到 Boot 4.1.1 BOM 实际解析出的 Redis、Lettuce、MySQL、Commons Pool 和 AspectJ 精确版本。

## 迁移改动

- 将主代码的 18 个 `javax.annotation.Resource`、2 个 `javax.annotation.PostConstruct`、1 个 `javax.annotation.PreDestroy` 导入迁移为 `jakarta.annotation.*`。
- 将主代码的 8 个 `javax.servlet.http.*` 导入迁移为 `jakarta.servlet.http.*`。
- 保留 JDK 自带的 `javax.sql.DataSource` 和 `javax.imageio.*`，未迁移。
- 将 JDBC 驱动类从 `com.mysql.jdbc.Driver` 改为 `com.mysql.cj.jdbc.Driver`。
- 将 `application.yaml` 的 `spring.redis.*` 整体迁移到 `spring.data.redis.*`，环境变量名保持不变。
- 同步将 `RedissonConfig` 的三个 `@Value` 占位符迁移到 `spring.data.redis.host`、`spring.data.redis.port`、`spring.data.redis.password`。
- CI 只把 `java-version` 从 `8` 改为 `21`，其余步骤未改。

## 非机械性检查与处理

- `MybatisConfig` 现有 `MybatisPlusInterceptor` 与 `PaginationInnerInterceptor(DbType.MYSQL)` 写法未改。尚未完成依赖解析和编译，不能确认最终二进制兼容性。
- `MvcConfig` 的 `WebMvcConfigurer` / `InterceptorRegistry` 包名未变，只迁移了注入注解。
- `LoginIntercepor` / `ReFreshIntecepor` 的 `HandlerInterceptor` 方法签名只迁移 Servlet 参数类型，没有改变拦截逻辑。
- `StringRedisTemplate`、`DefaultRedisScript`、Lua 路径和秒杀处理逻辑均未改。
- 未发现 `javax.validation` 或 `spring-boot-starter-validation` 用法。

## 遇到的问题

1. 默认 Maven 本地仓库位于只读范围，首次执行 `mvn -B -ntp verify` 在创建 `/root/.m2/.../4.1.1` 缓存目录时失败，未进入编译。
2. 改用 `/tmp/llrs-audit-m2` 独立本地仓库后再次执行，环境 DNS 禁止访问 Maven Central，报 `Unknown host repo.maven.apache.org`。本机缓存没有 Boot 4.1.1、MyBatis-Plus 3.5.17 和 Redisson 4.8.0，仍未进入编译。
3. 验收 grep 在 `src/test/java/com/hmdp/HmDianPingApplicationTests.java` 发现一个既有 `javax.annotation.Resource`。任务卡写入白名单只包含 `src/main/java/**`，因此未越界修改测试源码；这使“整个 `src/` 无残留”的验收当前无法通过。
4. 原样执行 nginx 验收命令时，配置语法检查为 `syntax is ok`，但沙箱中的 `/run` 为只读，nginx 无法打开默认 `/run/nginx.pid`，所以命令最终失败。
5. 根构建未成功产出新 class；`javap` 读到的是升级前遗留的 `target/classes`，其 major version 为 52，不能作为本卡结果。

## 验收实录

| 验收项 | 状态 | 实际结果 |
|---|---|---|
| `mvn -B -ntp verify` | 已执行，失败 | 第一次被只读 Maven 缓存阻塞；使用 `/tmp` 本地仓库重跑后被 DNS 阻塞。均未进入编译，无法统计测试数 |
| `node tests/common.test.cjs` | 通过 | `Frontend request error cases passed` |
| `mkdir -p nginx-1.18.0/logs && nginx -t ...` | 已执行，失败 | 配置语法正确，但无法写 `/run/nginx.pid` |
| `grep -rn "javax\.annotation\.\|javax\.servlet\." src/ ...` | 已执行，失败 | 测试源码仍有 1 个白名单外的 `javax.annotation.Resource` |
| `javap ... | grep -i "major version"` | 已执行，不通过 | 输出 52，来自升级前旧产物；期望的新产物 65 尚未生成 |
| `mvn -f agent-core/pom.xml -B -ntp test` | 未执行 | 根项目验收尚未进入编译，且本卡禁止修改 `agent-core/**` |

## 未完成、降级与明确未做

- 没有依赖或功能降级处理。
- 根项目尚未编译，28 条以上测试全绿尚未证实，major version 65 尚未证实。
- 未验证 Boot 4.1.1 下的应用启动、MyBatis-Plus 分页插件、Redis/Lua、Redisson、秒杀、登录、关注流、点赞、签到、GEO 和上传运行行为。
- 未修改任何测试断言，也未修改白名单外的测试 import。
- 未修改或并入 `agent-core`，未修改 nginx 配置、数据库脚本或 CI 的其他步骤。
- 未 commit，未 push。

## 自审结论

当前结论为不通过、阻塞。需要可写且已预热的 Maven 本地仓库或可访问 Maven Central 的构建环境，并需管理者决定是否允许对测试源码做一次纯 import 迁移。完成这两项后才能继续编译修复和全量验收。

---

# 管理者续记（Hermes）— W1-1 最终验收 ✅

> 本段由管理者追加，**不修改**上文 Codex 的原始记录（保留审计链）。
> Codex 报告的两个阻塞点均由管理者在本机解除：本机可访问 Maven Central，且
> 「测试源码 import 迁移」属任务卡白名单疏漏，由管理者授权并执行。

## A. 状态更正

Codex 结论为「不通过、阻塞」的原因是**其沙箱环境**（Maven 仓库只读 + DNS 禁网），
**不是代码问题**。在具备网络的真实环境中继续后，全部验收项已通过：

```
$ MAVEN_OPTS="-Xmx640m" mvn -B -ntp verify
[INFO] Compiling 78 source files with javac [debug parameters release 21] to target/classes
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 3
[INFO] BUILD SUCCESS   (Total time: 38.068 s)
```

## B. 管理者解除的第一个阻塞：测试源码 import 迁移

Codex 指出正确（并在卡内白名单之外，故未越界）：`src/test/java/com/hmdp/HmDianPingApplicationTests.java`
有 1 个 `javax.annotation.Resource`。**这是任务卡写入白名单的疏漏**（只写了 `src/main/java/**`，
未含 `src/test/**`），责任在管理者。已授权并执行迁移 → `jakarta.annotation.Resource`。
迁移后 `grep -rn "javax\.annotation\.\|javax\.servlet\." src/` **无输出**。

## C. 管理者在真实构建中发现并修复的 5 处编译错误

以下全部是 Codex 沙箱**从未进入编译阶段**因而无法发现的问题，由管理者在本机实跑构建时逐个暴露并修复：

| # | 错误现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | `com.baomidou.mybatisplus.extension.service.IService` 不存在（10 处） | MyBatis-Plus 3.5.x 将 `IService` 从 `extension` 移到 `spring` 子模块 | 改为 `com.baomidou.mybatisplus.spring.service.IService` |
| 2 | `...extension.service.impl.ServiceImpl` 不存在（10 处） | 同上 | 改为 `com.baomidou.mybatisplus.spring.service.impl.ServiceImpl` |
| 3 | `PaginationInnerInterceptor` 找不到 | 3.5.x 起分页插件被拆为**独立 artifact**（因它依赖 JSqlParser） | 新增依赖 `com.baomidou:mybatis-plus-jsqlparser:3.5.17`（包路径不变） |
| 4 | `FollowServiceImpl:59` `java.lang.Long cannot be converted to java.lang.Integer` | MyBatis-Plus 3.5.x `count()` 返回类型由 `Integer` 改为 `Long` | 局部变量改为 `Long count` |
| 5 | `org.springframework.format.datetime.joda.LocalDateTimeParser` 不存在 | 该 import **从未被使用**，且 Boot 3+ 已移除 joda 支持 | 删除该无用 import |

另有 1 处测试侧同源迁移：`extension.spring.MybatisSqlSessionFactoryBean`
→ `spring.MybatisSqlSessionFactoryBean`（3.5.17 的 `spring` 子模块）。

`MybatisConfig` 的 `MybatisPlusInterceptor` 保持原写法不动（包路径未变，仍在 `extension.plugins`）。

## D. 全部验收项（管理者独立执行）

| 验收项 | 结果 |
|---|---|
| `mvn -B -ntp verify` | `Tests run: 30, Failures: 0, Errors: 0, Skipped: 3` / **BUILD SUCCESS** ✅ |
| `node tests/common.test.cjs` | `Frontend request error cases passed` ✅ |
| `nginx -t -p "$PWD/nginx-1.18.0/" -c conf/nginx.conf` | `syntax is ok` / `test is successful` ✅ |
| `grep -rn "javax\.annotation\.\|javax\.servlet\." src/` | 无输出 ✅ |
| `javap -v -cp target/classes com.hmdp.HmDianPingApplication` | **`major version: 65`**（JDK 21）✅ |
| `mvn -f agent-core/pom.xml -B -ntp test` | `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0` ✅ |
| 根 `pom.xml` 含 `<modules>` | 否（未并入 agent-core，留 W1-2）✅ |

### D.1 `Skipped 1 → 3` 的查证（确认非回归）

| 类 | 升级前（基线日志） | 升级后（Surefire XML） |
|---|---|---|
| `HmDianPingApplicationTests` | `run=1, skipped=1` | `run=3, skipped=3` |
| 其余 16 个测试类 | — | **逐类 run/skipped 与基线完全一致** |

该类带类级 `@EnabledIfSystemProperty(named="hmdp.manualTests", matches="true")`（需手动开启）。
旧 Surefire 把整类计为 1 个 skip，新 Surefire 按方法计数（3 个）。
**实际通过用例前后均为 27 个**，无测试丢失、无断言放宽。

## E. 启动冒烟（最强证据：装配真的成立）

本机无 MySQL 服务端，故以**报错类型**判定迁移是否成功：

```
$ HMDP_DB_URL="jdbc:mysql://127.0.0.1:3306/hmdp?..." HMDP_DB_USERNAME=probe HMDP_DB_PASSWORD=probe \
  timeout 60 java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
Caused by: com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure
Caused by: java.net.ConnectException: Connection refused
```

启动推进到**数据源 TCP 连接**阶段才失败 —— 证明 Spring Boot 4.1.1 上下文装配、
`jakarta.*` 迁移、`spring.data.redis.*` 配置键绑定、MyBatis-Plus 自动配置、
`RedissonConfig` 的三个 `@Value` 占位符**全部生效**。
（若任一项迁移有误，会在更早阶段抛配置/装配异常，而非走到 TCP 连接。）

不带环境变量启动时的报错为
`Driver com.mysql.cj.jdbc.Driver claims to not accept jdbcUrl, ${HMDP_DB_URL}`
—— 同样说明驱动类名迁移正确、只是 URL 未注入。

## F. 未验证项（诚实标注，遗留风险）

**真实 MySQL + Redis 上的端到端功能回归未执行**（本机无 MySQL 服务端）：
秒杀链路（Lua + Stream）、登录、关注流、点赞、签到、GEO、上传的真实运行行为**未验证**。

已完成的验证层次：编译（78 个源文件在 JDK 21 下）→ 单元/集成测试（27 个真实用例）→
Spring 上下文装配（启动冒烟）。**建议**：在具备隔离 MySQL 的环境补做一次端到端回归，
或在 W2 联调时一并覆盖。

## G. 遗留

1. 端到端功能回归未做（见 F）。
2. `RedissonConfig` 编译时有 deprecation 警告（Redisson 4.8.0 API 演进），不阻塞，可在 W2 顺手清理。
3. `VoucherOrderServiceImpl` 的 unchecked 警告为既有问题，非本次引入。
4. CI 将在推送后以 JDK 21 真实运行；本地三条 CI 命令已逐条通过。

## H. 结论

**W1-1 验收通过。** 三遍审查：Codex 自审（诚实报告环境阻塞）→ 管理者修复 5 处编译错误并独立重跑全部验收 →
上文即为管理者验收记录。Codex 提出的两个阻塞点根因已明确（沙箱环境 + 任务卡白名单疏漏），均非代码缺陷。
