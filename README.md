# 本地生活服务系统

后端使用 MySQL 和 Redis。启动前在运行环境或 IDE 的运行配置中设置
`HMDP_DB_URL`、`HMDP_DB_USERNAME`、`HMDP_DB_PASSWORD`。
Redis 使用 `HMDP_REDIS_HOST`、`HMDP_REDIS_PORT` 和 `HMDP_REDIS_PASSWORD`；
前两项默认指向本机 6379 端口，Redis 无密码时可不设置最后一项。

`.env.example` 只展示变量名和假值。Spring Boot 不会自动加载 `.env` 文件，
请通过运行环境注入实际值，不要将实际凭据提交到仓库。

此前提交过的数据库和 Redis 凭据即使从当前代码删除，仍可能留在 Git 历史中；
如果这些凭据仍有效，需要另行轮换。

登录目前没有接入短信平台。仅在配置 `HMDP_DEMO_PHONE` 后，该指定手机号
可从 `/user/code` 的响应取得演示验证码；其他手机号会被拒绝。请只使用专用的
演示账号，不要把真实用户手机号配置为演示账号。验证码请求间隔为 60 秒，
登录成功后立即失效，连续输错 5 次需等待验证码过期。

## 秒杀订单部署与对账

部署本阶段代码前，先停止旧版秒杀写入，备份数据库，执行
`src/main/resources/db/migrations/20261013_voucher_order_unique.sql`。
其中第一条查询若返回重复的 `(user_id, voucher_id)`，须先人工核对并处理，
再执行唯一索引变更。仓库内的 SQL 文件不会自动迁移现有数据库。

秒杀依赖 Redis 的 `seckill:stock:<券ID>`、`seckill:order:<券ID>` 和
`stream.orders`。Redis 应启用持久化；首次启用或数据丢失后，先暂停秒杀入口，
检查数据库订单、Stream 待处理消息和已接受的请求，再人工恢复库存与用户集合，
不能直接用数据库库存覆盖 Redis 库存。当前公开的优惠券写接口已关闭，
新活动的库存初始化需由受控运维流程完成。

`POST /voucher-order/seckill/{id}` 返回字符串请求 ID，代表请求已进入队列；
`GET /voucher-order/status/{请求ID}` 返回 `ACCEPTED`、`DELAYED` 或
`CONFIRMED`。只有 `CONFIRMED` 表示订单已落库，尚未支付。消费者启动时创建
消费组，定期重试待处理消息；数据库失败后消息保留在 pending 中，供恢复。
排障时先看 `XPENDING stream.orders g1`、对应 `seckill:request:<请求ID>`，
再核对 `tb_voucher_order` 与 `tb_seckill_voucher.stock`。修复依赖后重启服务
可触发恢复；不要手工 ACK 尚未落库的消息。请求状态保存 7 天，过期后需从
数据库和 Stream 查证。此流程未实现支付，也不保证活动结束后自动退款或补货。

## 资料、商铺位置与点赞数据

`PUT /user/me` 仅保存当前登录用户的昵称、头像和个人介绍；头像先通过
`POST /upload/blog` 上传。上传目录由 `HMDP_IMAGE_UPLOAD_DIR` 指定，默认是
`./nginx-1.18.0/html/hmdp/imgs`。部署时应改成后端可写、Nginx 以 `/imgs/`
提供的同一目录；相对路径以服务启动目录为基准。密码登录入口已移除，当前
只提供上文所述的演示验证码登录。

现有数据库部署阶段三前，先停止旧版写入并备份数据库，执行
`src/main/resources/db/migrations/20261013_blog_likes.sql`。新版启动时会把旧的
`blog:liked:*` Redis 集合逐项写入 `tb_blog_like`，成功后删除对应旧集合；
再次启动不会重新导入已取消的点赞。切换前须保留旧 Redis 数据并停止旧版
点赞写入。如果旧集合此前已丢失，数据库中只有点赞总数，无法推断点赞用户，
需从可信备份恢复，不能把总数当作用户关系。新点赞关系以数据库为准。

共同关注从 `tb_follow` 查询。Feed 键丢失后，按当前关注关系和关注时间
从 `tb_blog` 重建；取消关注后又重新关注等历史操作无法仅凭现存两张表
完整还原原 Feed，这部分需以备份为准。启动时会把数据库商铺坐标加入
Redis GEO 索引；受控修改商铺数据后应重启服务重建索引。浏览器拒绝定位时
展示普通分类列表；人气和评分排序由数据库字段执行。评价、支付和退款
仍未提供真实业务接口，页面不再展示样例评价或可操作的付款入口。
