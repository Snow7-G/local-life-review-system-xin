# W1-2 Reactor 并入：结论、证据与待批准替代方案

- 任务卡：`docs/tasks/W1-2-reactor.md`
- 基线：HEAD `93bc851`（`[W1-2-0] 出 W1-2 任务卡：agent-core 并入根 reactor`）
- 执行者：DSH；日期：2026-10-14
- **本轮对版本控制内文件的净改动：零。** `git status --porcelain` 输出为空（详见 §7）

---

## 0. 结论摘要（先看这里）

1. **卡的目标 1 在"根 pom 保持 jar 打包 + 不改 CI + 不动 `src/**`"这三条约束下不可能达成。**
   Maven 上游把"聚合项目（有 `<modules>`）必须是 `packaging=pom`"做成了**模型校验的 `Severity.ERROR`**
   （`DefaultModelValidator`），本仓库根 `pom.xml` **没有显式 `<packaging>`，因此默认 `jar`**，
   一旦加 `<modules>`，Maven 在**读取模型阶段**就报错退出——根本走不到编译与测试。
   已在本机 **Maven 3.8.7 与 Maven 3.9.16 上各自复现**（CI 用的是 3.9.x，见 §2.3），
   并在"**无 Boot parent、无任何依赖**"的裸 jar pom 上同样复现 → **与 Spring Boot parent、BOM、依赖集合都无关**。
2. **这不是"parent/继承关系"报错，也不是本仓库的特例**，所以卡的兜底条款（"选择最小改动方案解决"）没有解：
   我评估过的每一种"最小改动"都会违反卡片自己列的至少一条硬边界（§2.4、§3）。
3. **我没有留下任何改动**：`src/**`、`agent-core/src/**`、`agent-core/pom.xml`（依赖集合）、`.github/workflows/ci.yml`、
   `nginx-1.18.0/**` 全部未动；所有尝试都已还原；未 commit、未 push。
4. 为了让决策有落地选项，我在 **`target/` 下的隔离原型**里把可行布局跑通了（§4，**不在版本控制内**）：
   根 pom 当聚合器（`packaging=pom`）+ 新增 `app/` 模块承载现有 `src/**`，
   单条 `mvn -B -ntp verify` 同时产出 **`dev.lifeagent.mcp.*` 28 条**与 **`com.hmdp.*` 30 条（Skipped 3）**，
   **且 Boot fat jar 照旧产出**（60,411,049 字节，含 `BOOT-INF/classes`、103 个依赖 jar、`Main-Class: …JarLauncher`）。
5. 落地该布局**超出本卡写白名单**（要新增 `app/pom.xml`、根 pom 要加 `<packaging>pom</packaging>`），
   因此**标"待批准"，本轮未落地**（§8）。

---

## 1. 卡片逐条对照（要求 → 实现位置 → 是否达成 → 证据）

| # | 卡片要求 | 实现位置（文件:行） | 是否达成 | 证据 |
|---|---|---|---|---|
| 1 | 根 `pom.xml` 增加 `<modules>`，把 `agent-core` 纳入聚合构建 | **无实现**（未落地；插入点在 `pom.xml:15` 之后、`<properties>` 之前） | ❌ **不可达成**（Maven 上游硬规则，见 §2） | `target/w12-evidence/03-aggregate-root-verify.log`（`EXIT=1`，`'packaging' with value 'jar' is invalid. Aggregator projects require 'pom' as packaging. @ line 3, column 110`）；`target/w12-evidence/scratch/repro-root-maven-3.8.7.log`、`…-3.9.16.log` |
| 2 | CI 的 `mvn -B -ntp verify` 自动构建并测试 `agent-core`（无需改 CI） | `.github/workflows/ci.yml:32`（现状：只有一条 `mvn -B -ntp verify`）；JDK 由 `:15-16` 指定 `temurin/21` | ❌ 未达成（依赖第 1 条；第 1 条不成立，CI 就跑不到 agent-core） | `ci.yml:32`；CI 本身**未执行**（本地无 CI 环境，见 §5、§8） |
| 3 | 两套测试都保持全绿，且没有测试被减掉 | 未改动（维持现状） | ✅ 达成（现状即全绿，未减任何测试） | §6 原始统计：root `Tests run: 30, Failures: 0, Errors: 0, Skipped: 3`；agent-core `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0` |

### 卡片验收命令

| 命令 | 真跑？ | 退出码 | 关键输出 | 日志 |
|---|---|---|---|---|
| ① `mvn -B -ntp verify`（**原样**，未加 `<modules>`） | 真跑 | **0** | `Tests run: 30, Failures: 0, Errors: 0, Skipped: 3` + `BUILD SUCCESS`（41.467 s） | `target/w12-evidence/10-root-verify-reverted.log` / `.exit` |
| ① 同一命令**加上 `<modules>` 后** | 真跑 | **1** | 模型校验错误，无任何测试执行 | `target/w12-evidence/03-aggregate-root-verify.log` |
| ② `mvn -f agent-core/pom.xml -B -ntp test` | 真跑 | **0** | `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS` | `target/w12-evidence/11-agentcore-standalone.log` |
| ③ `mvn -B -ntp -pl . --also-make verify` | 真跑 | **0** | `Tests run: 30, Failures: 0, Errors: 0, Skipped: 3` + `BUILD SUCCESS`（26.715 s） | `target/w12-evidence/14-root-single-module.log` |
| ④ `node tests/common.test.cjs` | 真跑 | **0** | `Frontend request error cases passed` | `target/w12-evidence/12-node-common-test.log` |
| ④ `mkdir -p nginx-1.18.0/logs && nginx -t -p … -c conf/nginx.conf` | 真跑 | **1** | `syntax is ok` 之后因**只读运行时路径**失败（环境限制，非配置错误，见 §8.2） | `target/w12-evidence/13-nginx-t.log`、`15-nginx-t-pidoverride.log`、`16-nginx-t-patched-copy.log`（`EXIT=0`） |
| ⑤ GitHub CI（`mvn -B -ntp verify` 在 CI 上） | **未执行** | — | 本地无 CI，未跑 | — |

---

## 2. 为什么"根 pom 加 `<modules>`"必然失败（三重证据）

### 2.1 实测（本仓库真实 `pom.xml` 的逐字节副本）

把真实根 pom 复制一份、只插入 `<modules><module>agent-core</module></modules>`，在两种 Maven 上跑 `verify`：

```
[ERROR] 'packaging' with value 'jar' is invalid. Aggregator projects require 'pom' as packaging. @ line 3, column 110
EXIT=1
```

- Maven 3.8.7（`/usr/share/maven`）：`target/w12-evidence/scratch/repro-root-maven-3.8.7.log`
- Maven 3.9.16（`~/.m2/wrapper/dists/apache-maven-3.9.16/…`）：`target/w12-evidence/scratch/repro-root-maven-3.9.16.log`

两次**都**在模型读取阶段失败、**都没有执行任何测试**。注意 `pom.xml:4-15`：根 pom 只有 `<modelVersion>/<parent>/<groupId>/<artifactId>/<version>/<name>/<description>`，
**没有 `<packaging>`**（`grep -n packaging pom.xml` 无匹配）→ 默认就是 `jar`，这正是报错里 `'jar'` 的来源。

### 2.2 最小复现：与 Spring Boot parent、BOM、依赖集合无关

另做两个"最小 pom"对照实验（`target/w12-evidence/probe-*.log`）：

| 实验 | pom 特征 | 结果 |
|---|---|---|
| `probe-jar-direct` | **无 `<parent>`、无任何依赖、无插件** 的裸 jar pom，直接写 `<modules>` | 同一错误 `@ line 1, column 52`，`EXIT=1` |
| `probe-jar-profile` | 裸 jar pom，`<modules>` 只写在**file 激活的 profile** 里 | 同一错误，`EXIT=1` |

结论：① 抛错与 `spring-boot-starter-parent` 或任何依赖无关；② **用 profile 装 `<modules>` 也绕不过去**（校验看的是生效模型）。

### 2.3 上游源码（这条规则是 Maven 自己的，且是 `ERROR`）

`maven-model-builder` 源码（从 Central 取 sources jar，落在 `target/w12-evidence/mmb-*-src.jar`）：

`org/apache/maven/model/validation/DefaultModelValidator.java`，**3.8.7 第 349-355 行**：

```java
if ( !m.getModules().isEmpty() )
{
    if ( !"pom".equals( m.getPackaging() ) )
    {
        addViolation( problems, Severity.ERROR, Version.BASE, "packaging", null, "with value '"
            + m.getPackaging() + "' is invalid. Aggregator projects " + "require 'pom' as packaging.", m );
    }
```

同一文件 **3.9.16 第 451-462 行**，语义完全相同（`Severity.ERROR`、`Version.BASE`）——
说明这是**长期存在的上游规则**，不是某个发行版补丁，也就没有"换 Maven 版本"这条路。

再看 CI：`.github/workflows/ci.yml:15-16` 用 `actions/setup-java@v4` 指定 `distribution: temurin`、`java-version: '21'`，
**没有钉 Maven 版本** → CI 实际用的是 setup-java 自带的 Maven 3.9.x，**和 3.9.16 的复现结果一致**，所以"本地能过、CI 过不了"或反过来的可能性都不存在：**两边都会失败**。

### 2.4 被排除的"最小改动"绕法

| 绕法 | 为什么不行 |
|---|---|
| 把 `<modules>` 放进 profile | 已实测同样报错（§2.2） |
| 只聚合、不给根 pom 加 `<packaging>` | 就是 §2.1，报错 |
| 给 agent-core 加 `<parent>` 指向根 pom 来代替聚合 | **聚合 ≠ 继承**（卡片自己也这么写）。加 parent 只是让 agent-core 继承坐标/依赖管理，**并不会让它进入聚合构建**，CI 的 `mvn verify` 依旧只构建根模块 → 目标 1、2 仍未达成，而且会给 agent-core 引入它现在**刻意没有**的父 POM 继承链（违反"不得改依赖集合"的精神） |
| 用 `exec-maven-plugin`/`maven-invoker-plugin` 在 `verify` 里**嵌套调起** `mvn -f agent-core/pom.xml test` | 这只改 pom、能打印两套统计，但：① 它**不是聚合**，与目标 1 的字面要求不符；② 嵌套 Maven（一次构建两个 JVM）在 4G ECS 上是不必要的风险，且依赖 PATH 上的 `mvn`；③ 子构建失败时的传播/日志可读性都更差。属于"看起来达标、实际偷换概念"，不建议 |

---

## 3. 为什么不能直接把根 `<packaging>` 改成 `pom`

这是唯一能让 `<modules>` 合法化的改法，但代价是**根模块不再是 jar 工程**：

1. **Boot 的 repackage 会被静默跳过。** `spring-boot-maven-plugin:4.1.1` 的 `RepackageMojo` 里有字面量
   `repackage goal could not be applied to pom project` —— 对 `pom` 项目它不干活也**不报错**，
   于是 `target/*.jar` 不再是可执行 fat jar。
2. **`pom` 打包不绑定资源/编译/测试/打包生命周期。** 要恢复就得手工重绑
   `resources`、`compiler`、`testResources`、`testCompile`、`surefire`、`jar`、`repackage`
   这一整条链（等于把 Boot parent 给的 jar 生命周期自己重写一遍），改动面远超"最小改动"，
   而且极易出现"某个 phase 忘了绑"的隐性回归。
3. **它会破坏已有文档承诺的部署方式。** `docs/deployment.md:30-32` 明确写：
   ```sh
   mvn -B -ntp verify
   mvn -B -ntp -DskipTests package
   java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
   ```
   改成 `pom` 后第 3 行直接失效（没有 fat jar）。这违反铁律 1（不改坏原功能）。
4. **`mvn install` 会把 jar 安装成 `.pom` 扩展名**，污染本地仓库/下游使用方式。

---

## 4. 已验证可行的替代布局（原型，**待批准后才能落地**）

原型位置：`target/w12-evidence/proto/`（由 `git archive HEAD` 展开，**不在版本控制内**，
故对仓库零影响；验证方式见 §4.3 可复现）。

### 4.1 布局与改动清单

| 改动 | 内容 |
|---|---|
| 根 `pom.xml` | 加 `<packaging>pom</packaging>`（加在 `pom.xml:13` 之后）+ `<modules>`：`agent-core`、`app`；现有 `<dependencies>`/`<build>`（Boot 插件）保留，作为**父 POM** 供 `app` 继承 |
| **新增** `app/pom.xml` | 唯一承载现有 `src/**` 的模块（内容见下）；`artifactId` 用 **`hm-dianping-app`**，避免与聚合器同 GAV |
| `agent-core/**` | **完全不动**（无 parent、依赖集合不动） |

`app/pom.xml`（原型实测所用，全文）：

```xml
<project …>
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.hmdp</groupId>
        <artifactId>hm-dianping</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>hm-dianping-app</artifactId>
    <name>hm-dianping-app</name>
    <build>
        <finalName>hm-dianping-0.0.1-SNAPSHOT</finalName>
        <directory>${project.basedir}/../target</directory>
        <sourceDirectory>${project.basedir}/../src/main/java</sourceDirectory>
        <testSourceDirectory>${project.basedir}/../src/test/java</testSourceDirectory>
        <resources>
            <resource><directory>${project.basedir}/../src/main/resources</directory></resource>
        </resources>
        <testResources>
            <testResource><directory>${project.basedir}/../src/test/resources</directory></testResource>
        </testResources>
    </build>
</project>
```

要点：
- `src/**` **物理位置不动**（`../src/...`），满足"不改业务代码"；`<directory>../target</directory>` + `<finalName>` 让产物
  **仍然落在 `target/hm-dianping-0.0.1-SNAPSHOT.jar`**，`docs/deployment.md:32` 的 `java -jar` 命令原样有效。
- agent-core 作为**被聚合模块**（无 parent，用 Maven 默认插件版本）——它在 CI 上会多下载一个 `maven-jar-plugin:2.4`
  （父无 parent 的默认绑定，本沙箱因 `/root/.m2` 只读而无法下载，见 §8.1；**CI 有网络，会正常下载**）。

### 4.2 原型实测输出（单条 `mvn -B -ntp verify`）

```
[INFO] Reactor Build Order:
[INFO] agent-core                                                         [jar]
[INFO] hm-dianping                                                        [pom]
[INFO] hm-dianping-app                                                    [jar]
…
[INFO] agent-core 0.1.0-SNAPSHOT .......................... SUCCESS [ 34.018 s]
[INFO] hm-dianping 0.0.1-SNAPSHOT ......................... SUCCESS [ 17.588 s]
[INFO] hm-dianping-app 0.0.1-SNAPSHOT ..................... SUCCESS [ 36.031 s]
[INFO] BUILD SUCCESS
```

同一份日志里两类测试统计**同时存在**（原始行）：

```
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 12.33 s -- in dev.lifeagent.mcp.McpClientTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.078 s -- in dev.lifeagent.mcp.JsonRpcCodecTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.915 s -- in dev.lifeagent.mcp.HttpTransportTest
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
…
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 3
```

fat jar 完整性校验（`python3 zipfile`）：

```
exists: True size: 60411049
entries: 334
has BOOT-INF/classes/: True
has BOOT-INF/lib/: True
libs: 103
Main-Class: org.springframework.boot.loader.launch.JarLauncher
```

对照：**未改动**的现状树 `target/hm-dianping-0.0.1-SNAPSHOT.jar` = 60,411,583 字节（差异仅来自构建时间戳）→ **产物体积/结构等价，没有丢功能**。

另外验证：`mvn -f <proto>/agent-core/pom.xml -B -ntp test` 仍然 `EXIT=0`、`Tests run: 28 … Skipped: 0`（验收 ② 在新布局下依然成立）。

### 4.3 复现方式（不影响仓库）

```bash
mkdir -p target/w12-evidence/proto && git archive HEAD | tar -x -C target/w12-evidence/proto
# 按 §4.1 改 proto/pom.xml（加 <packaging>pom</packaging> 与 <modules>），并写入 proto/app/pom.xml
mvn -f target/w12-evidence/proto/pom.xml -Dmaven.repo.local="$PWD/target/w12-evidence/m2repo" -B -ntp verify
```

### 4.4 未实测项（诚实标注）

- **未实测**：同一 reactor 内 `app` 与根模块使用**相同 GAV** 会有什么报错——我出于规避风险直接用了不同的 `artifactId`（`hm-dianping-app`）。若批准落地，这点建议由 Codex 复核确认。
- **未执行**：`mvn -pl app spring-boot:run` 这类新工作流命令（需要 MySQL/Redis 且会常驻，不适合在验收里跑）。
- **未执行**：CI 上的真实构建。

---

## 5. 验收命令结果汇总

见 §1 的验收表。补充说明：

- ① 在**原样**树上 `EXIT=0`（30 条、Skipped 3、BUILD SUCCESS，41.467 s）；加 `<modules>` 后 `EXIT=1`（模型校验错误，零测试执行）。
- ② `EXIT=0`，28 条全绿（12 + 2 + 14）。
- ③ `EXIT=0`，30 条、Skipped 3、BUILD SUCCESS（26.715 s）。
- ④ `node tests/common.test.cjs` `EXIT=0`；`nginx -t` 因沙箱只读路径 `EXIT=1`（配置本身有效，见 §8.2）。
- ⑤ **未执行**：GitHub CI。

---

## 6. 两套测试的原始统计（供复核）

**A. 根工程（`mvn -B -ntp verify`，现状）** — `target/w12-evidence/10-root-verify-reverted.log`

```
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 3
[INFO] BUILD SUCCESS
```

**B. agent-core（`mvn -f agent-core/pom.xml -B -ntp test`）** — `target/w12-evidence/11-agentcore-standalone.log`

```
[INFO] Running dev.lifeagent.mcp.McpClientTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 12.70 s -- in dev.lifeagent.mcp.McpClientTest
[INFO] Running dev.lifeagent.mcp.JsonRpcCodecTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.054 s -- in dev.lifeagent.mcp.JsonRpcCodecTest
[INFO] Running dev.lifeagent.mcp.HttpTransportTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.601 s -- in dev.lifeagent.mcp.HttpTransportTest
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**C. 原型聚合（§4）** — 单条 `mvn -B -ntp verify` 内**同时**出现 `dev.lifeagent.mcp.*`（28，Skipped 0）与 `com.hmdp.*`（30，Skipped 3），见 §4.2。
`Skipped 3` 的语义见 `docs/W1-UPGRADE-NOTES.md` §D.1（Surefire 计数语义，非测试缺失）。

三条口径都能对上基线的"27 真实用例 + 3 skipped = 30"与"28 条全绿"，**没有任何测试被减掉或放宽**。

---

## 7. 我改了什么、没改什么

**净改动：零。**

| 对象 | 结论 |
|---|---|
| 根 `pom.xml` | **未改动**（尝试性插入 `<modules>` 后已 `git checkout -- pom.xml` 还原；`00-tree-before-revert.txt`/`01-tree-after-revert.txt` 记录还原前后均为 `93bc851`，最终 `git status --porcelain` 为空） |
| `agent-core/pom.xml` | **未改动** → **依赖集合未被触碰**（仍是 `jackson-databind 2.19.2` + `junit-jupiter 5.13.4`，见 `agent-core/pom.xml:18-30`） |
| `agent-core/src/**` | **未改动** |
| `src/main/**`、`src/test/**` | **未改动**；没有任何测试或断言被删除/放宽 |
| `.github/workflows/ci.yml` | **未改动**（按要求"无需改 CI"） |
| `nginx-1.18.0/**` | **未改动**（`git status --porcelain nginx-1.18.0/` 为空；`nginx -t` 只用了一次性副本，见 §8.2） |
| `docs/W1-2-REACTOR.md` | 本文件（**新建**，卡允许） |
| commit / push | **未做** |
| 其它产物 | 全部在 `target/w12-evidence/`（`.gitignore:42` 的 `target/` 内，不入库） |

---

## 8. 环境限制与诚实标注

### 8.1 本机 `/root/.m2` 只读

`mkdir /root/.m2/repository/zz-write-test` → `Read-only file system`。后果：Maven **无法下载本地仓库里缺的构件**。
原型第一次跑 `verify` 时因此失败在 `maven-jar-plugin:2.4`（agent-core 无 parent → 用 Maven 默认绑定）：
`Failed to create parent directories for tracking file …/maven-jar-plugin-2.4.pom.lastUpdated`。
改为把本地仓库放到工作区内（`cp -al /root/.m2/repository` → `target/w12-evidence/m2repo`，因跨设备硬链接失败只拷到 134M，但所需构件齐全）后即通过：
那次成功的 `verify` 日志里 `Downloading` 次数为 **0**，说明全部构件都取自该副本，没有依赖网络。
**这不影响 CI**（CI 有网络、可写 `~/.m2`），也**不影响任何一条验收结论**（现状树的所有构件都已缓存）。

### 8.2 `nginx -t` 在本沙箱无法零退出（配置本身有效）

`nginx-1.18.0/conf/nginx.conf` **没有** `pid`/`error_log`/`access_log` 指令（`grep -nE "log|pid" …` 仅匹配到 `worker_processes`/`http {`/`include`），
于是走 Debian 编译期默认路径 `/run/nginx.pid`、`/var/log/nginx/*.log`，这些**在本沙箱只读**：

```
nginx: the configuration file …/nginx.conf syntax is ok
[emerg] open() "/run/nginx.pid" failed (30: Read-only file system)
nginx: configuration file …/nginx.conf test failed          # EXIT=1
```

证据链（原配置文件始终未改）：① 用 `-g "pid <工作区>/logs/nginx.pid;"` 覆盖 pid → 只剩 `/var/log/nginx/access.log` 只读报错；
② 用**一次性副本**（`target/w12-evidence/nginx-check/nginx.conf`，只在副本里写 `pid`/`error_log`/`access_log` 指向工作区）→

```
nginx: the configuration file …/nginx-check/nginx.conf syntax is ok
nginx: configuration file …/nginx-check/nginx.conf test is successful   # EXIT=0
```

→ **配置内容是有效的，CI（`sudo`、路径可写）会通过**；本沙箱的 `EXIT=1` 纯属运行环境限制。

### 8.3 未执行项

GitHub CI（⑤）、`mvn -pl app spring-boot:run`（§4.4）、以及"同 GAV 会不会报错"的对照实验（§4.4）——**均未执行**，上文未把它们写成"通过"。

---

## 9. 待批准事项（建议）

**建议：批准"方案 C：真多模块布局"（§4）落地**，理由：它是唯一同时满足
①`agent-core` 真正进聚合 ②CI 无需改动即自动覆盖 ③两套测试全绿不减少 ④**不丢 Boot fat jar / 不破坏 `docs/deployment.md:32`** 的方案。
代价是需要**超出本卡白名单**：根 pom 加 `<packaging>pom</packaging>` + 新增 `app/pom.xml`（`src/**` 与 `agent-core/**` 仍不动）。

若不同意，退路是**保持现状**（`agent-core` 继续用 `mvn -f agent-core/pom.xml test` 单独构建，
即 `docs/APPROVALS-PENDING.md` AP-003 的原始决定）——但那样卡的目标 1、2 就只能**判为不可达成**，
请在 `docs/APPROVALS-PENDING.md` 里把 W1-2 的结论登记清楚。

**回滚指令**（若批准落地后需要撤销）：

```bash
git checkout pom.xml && rm -rf app
```

**请求复核**：请按 `docs/REVIEW-PROTOCOL.md` 走异源只读复核（Codex），重点看 §2 的三重证据是否足以支撑"不可达成"的结论、以及 §4 布局是否有我漏掉的回归面（例如 `mvn install`、IDE 导入、`-pl` 用法变化）。

---

# 管理者裁定（Hermes）— W1-2

> 本段由管理者追加。**采纳 W1-2 的事实结论，否决其 §4 的布局方案。**

## 1. 事实结论：采纳（并已独立复现）

W1-2 关于「根 pom 加 `<modules>` 必然失败」的三重证据**成立**，管理者已独立复现：

```
# 纯 jar pom + <modules>（无 parent、无依赖）
[ERROR] 'packaging' with value 'jar' is invalid. Aggregator projects require 'pom' as packaging.
[ERROR] The build could not read 1 project

# 同一 pom 加 <packaging>pom</packaging> 后
[INFO] BUILD SUCCESS
```

结论：**这与 Spring Boot parent / BOM / 依赖集合无关，是 Maven 模型校验的硬规则。**
任务卡 W1-2 的目标 1（"根 pom 保持 jar 打包 + 不改 CI + 不动 src 下并入 reactor"）**前提有误，责任在管理者**。

## 2. 布局方案：否决，理由如下

W1-2 提出的可行原型（根 pom 改 `packaging=pom` + 新增 `app/` 模块用 `../src` 反向引用源码）**技术上验证通过**，
但管理者**否决**，理由：

1. **收益/代价不成比例**：该方案的收益仅是"CI 里 agent-core 与根项目在**同一个** `mvn` 调用中构建"。
   而同一目标可用**一条 CI 步骤**达成（见 §3），代价近乎为零。
2. **布局反直觉，损害项目可读性**：`app/` 模块把源码目录指到 `../src`（`<sourceDirectory>${project.basedir}/../src/main/java</sourceDirectory>`）、
   把输出指到 `../target`——这是为迁就聚合器约束而造的非标准结构。
   本项目是**求职作品**，代码结构与面试讲解价值成正比；这种布局会在面试中被追问"为什么源码不在模块里"，
   而理由（"为了让 fat jar 项目当聚合器"）不是加分项。
3. **改动面大、回归面广**：要动根 pom 打包类型、新增模块、改产物路径映射，而收益只是"少一条 CI 步骤"。
4. W1-2 自己也标注其为**反模式（nested build）**并列为"待批准"——**管理者的裁定是不批准**。

## 3. 实际采用的方案：CI 增加一条 agent-core 构建步骤

`.github/workflows/ci.yml` 新增（**仅此 4 行**，其余 CI 步骤未动）：

```yaml
      # agent-core 是独立模块（不并入根 reactor：Maven 要求聚合项目 packaging=pom，
      # 而根打包为 Boot fat jar，改布局代价大）。用 -f 直接构建，同样在 CI 中覆盖它。
      - name: Build and run agent-core tests
        run: mvn -f agent-core/pom.xml -B -ntp verify
```

**已本地验证两条命令**（与 CI 将执行的完全一致）：

| CI 步骤 | 命令 | 结果 |
|---|---|---|
| `Build and run tests` | `mvn -B -ntp verify` | `Tests run: 30, Failures: 0, Errors: 0, Skipped: 3` / BUILD SUCCESS |
| `Build and run agent-core tests`（新增） | `mvn -f agent-core/pom.xml -B -ntp verify` | `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS |

## 4. 目标达成情况

| W1-2 原目标 | 状态 |
|---|---|
| 根 pom 加 `<modules>` 纳入聚合 | ❌ **不可达成**（Maven 硬规则，已复现），已由本裁定替代 |
| CI 自动构建并测试 agent-core | ✅ **达成**（改为 CI 独立步骤，覆盖效果等同） |
| 两套测试全绿、无测试减少 | ✅ 达成（根 27 真实用例 / agent-core 28 条，全部通过且未减） |

**`docs/reviews/P1-3-review.md` 的必上报项「agent-core 无 CI 回归守护」由此关闭**：
此后每次 push/PR，CI 都会跑 `agent-core` 的 28 条测试。

## 5. 致谢与记录

W1-2 在发现自己无法完成卡内目标时**没有硬套、没有伪造通过**，而是：
停止改动、还原全部试探、在 `target/` 隔离原型中把可行布局跑通、带回可复现证据与两个选项请管理者裁定。
这是**正确行为**，已在本次裁定的方案选择中被采纳其事实部分。
另外它顺带发现 `AGENTS.md` 中「CI 跑 JDK 8」一句在 W1-1 后已过时（实为 21）——该文件受保护，
需嘉批准后才能修改，已登记待办。
