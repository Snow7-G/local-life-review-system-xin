# 本地生活服务系统

后端使用 MySQL 和 Redis。启动前在运行环境或 IDE 的运行配置中设置
`HMDP_DB_URL`、`HMDP_DB_USERNAME`、`HMDP_DB_PASSWORD`。
Redis 使用 `HMDP_REDIS_HOST`、`HMDP_REDIS_PORT` 和 `HMDP_REDIS_PASSWORD`；
前两项默认指向本机 6379 端口，Redis 无密码时可不设置最后一项。

`.env.example` 只展示变量名和假值。Spring Boot 不会自动加载 `.env` 文件，
请通过运行环境注入实际值，不要将实际凭据提交到仓库。

此前提交过的数据库和 Redis 凭据即使从当前代码删除，仍可能留在 Git 历史中；
如果这些凭据仍有效，需要另行轮换。
