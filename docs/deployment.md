# 启动与验收

本项目目前是演示系统。真实短信、支付、退款和管理员功能尚未接入；不要把演示验证码账号用于真实用户。公开仓库历史中曾出现的数据库及 Redis 凭据如仍有效，必须完成轮换才能将系统视为可公开部署。

## 1. 环境

- JDK 8、Maven、MySQL、Redis 5 或更新版本（秒杀使用 Stream）、Nginx。
- 后端监听 `8081`，示例 Nginx 监听 `8080` 并将 `/api` 转发给后端。部署时让 MySQL、Redis 和后端只在受控网络中可达。
- 在运行环境中设置 `HMDP_DB_URL`、`HMDP_DB_USERNAME`、`HMDP_DB_PASSWORD`；Redis 相关变量及示例见根目录 `.env.example`。Spring Boot 不会自动读取 `.env`。不要将真实密码写入仓库、命令行参数或终端历史。
- 演示登录需单独设置 `HMDP_DEMO_PHONE`，且该号码不能属于真实用户。未设置时无法取得登录验证码。
- `HMDP_IMAGE_UPLOAD_DIR` 应指向 Nginx 以 `/imgs/` 提供的同一目录，并授予后端写入权限。默认目录是 `./nginx-1.18.0/html/hmdp/imgs`，相对路径以启动后端时的工作目录为准。

## 2. 数据初始化与升级

**仅在新建的空数据库**中导入 `src/main/resources/db/hmdp.sql`；该脚本含 `DROP TABLE` 和演示数据，不能用于现有数据库升级。先创建 UTF-8 数据库，再以交互式密码提示导入，例如在仓库根目录运行：

```sh
mysql -u "$DB_USER" -p hmdp < src/main/resources/db/hmdp.sql
```

升级现有数据库时，先停止旧版写入并备份 MySQL 与 Redis。执行 `src/main/resources/db/migrations/20261013_voucher_order_unique.sql` 前，检查其中的重复订单查询；有结果就先人工核对。然后执行 `20261013_blog_likes.sql`，保留旧的 `blog:liked:*` Redis 数据供新版启动导入。迁移文件不会自动执行。具体订单对账和点赞数据限制见根目录 README。

Redis 应启用持久化。新秒杀活动启用前，按数据库订单、待处理 Stream 消息与已受理请求对账后初始化 `seckill:stock:<券ID>` 和 `seckill:order:<券ID>`；不要在旧订单或 pending 消息尚存时直接用数据库库存覆盖 Redis 库存。当前没有公开的活动管理接口，此步骤需要受控运维操作。

## 3. 启动

从仓库根目录运行：

```sh
mvn -B -ntp verify
mvn -B -ntp -DskipTests package
java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
```

Linux/macOS 使用系统安装的 Nginx。仓库中的 `nginx-1.18.0/conf/nginx.conf` 以该目录为前缀、以仓库提供的静态页为根目录；在另一个终端从仓库根目录运行：

```sh
mkdir -p nginx-1.18.0/logs
nginx -t -p "$PWD/nginx-1.18.0/" -c conf/nginx.conf
nginx -p "$PWD/nginx-1.18.0/" -c conf/nginx.conf
```

Windows 可使用仓库附带的 Nginx 可执行文件，并先检查配置中的端口与前后端路径。跨平台系统请安装对应平台的 Nginx；仓库内的 `.exe` 不能在 Linux/macOS 上运行。打开 `http://127.0.0.1:8080/`；`/api` 请求应由 Nginx 代理到后端。

## 4. 自动测试与人工验收

CI 在不注入数据库、Redis 或演示手机号凭据的环境下执行前端请求错误用例、Nginx 配置检查和 `mvn verify`。Redis 集成测试会启动临时本机 Redis；依赖真实数据库的 `HmDianPingApplicationTests` 默认跳过，只能在**隔离的测试环境**中通过 `-Dhmdp.manualTests=true -Dtest=HmDianPingApplicationTests` 手工运行，不能连接生产数据。

在隔离的 MySQL、Redis 与演示手机号上，逐项记录结果和 `git rev-parse HEAD`：

1. 首页能加载分类与热门笔记；断网、请求超时和后端错误有稳定提示。
2. 演示手机号可获取验证码并登录；重复索取受 60 秒限制，验证码使用一次后失效。
3. 分类商铺列表在允许和拒绝定位时均可加载；距离、人气、评分切换后分页重置。
4. 商铺详情可查看优惠券；秒杀先显示受理，再通过状态接口确认落库，未落库时不提示成功。
5. 登录用户能发布笔记、上传图片、点赞、关注、查看 Feed 并编辑自己的资料；刷新后结果仍在。
6. 评论、真实支付、退款、地图、消息和商铺搜索没有伪造的可操作入口。
7. 重启后检查 Redis GEO、点赞关系和订单 pending 恢复；按 README 对账，确认数据库迁移已应用。

上述浏览器和真实数据库验收需要实际执行并记录，CI 绿灯不能替代这些结果。
