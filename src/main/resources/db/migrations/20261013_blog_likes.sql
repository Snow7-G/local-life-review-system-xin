-- Apply before deploying stage three. Backfill existing Redis blog:liked:* members first.
CREATE TABLE IF NOT EXISTS tb_blog_like (
  blog_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (blog_id, user_id),
  KEY idx_blog_like_time (blog_id, create_time, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
