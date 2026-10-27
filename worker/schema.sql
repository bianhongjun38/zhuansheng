-- 学习进度同步 + 英语闯关排行榜
-- 20 人小圈子，不做微服务，三张表够了。

CREATE TABLE IF NOT EXISTS users (
  uid        TEXT PRIMARY KEY,
  username   TEXT NOT NULL,
  salt       TEXT NOT NULL,
  hash       TEXT NOT NULL,        -- PBKDF2(密码, salt)，服务端不存明文
  token      TEXT NOT NULL UNIQUE,
  created_at INTEGER NOT NULL,
  last_seen  INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_name ON users(username);

-- 进度整包 JSON。
-- 上一版方案考虑过拆成 337 行，但 20 人根本不需要那种 SQL 分析，
-- blob 让同步从「一堆 UPSERT」变成「一次 UPDATE」，少一半 bug。
CREATE TABLE IF NOT EXISTS progress (
  uid         TEXT PRIMARY KEY,
  data        TEXT NOT NULL,
  plan_ver    INTEGER NOT NULL DEFAULT 0,
  updated_at  INTEGER NOT NULL
);

-- 每次覆盖前把旧值留一份，保留最近 10 版。
-- 这是「同学误操作清空进度」的第一道保险，D1 的 7 天 Time Travel 是第二道。
CREATE TABLE IF NOT EXISTS progress_history (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  uid        TEXT NOT NULL,
  data       TEXT NOT NULL,
  saved_at   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_hist_uid ON progress_history(uid, id DESC);

CREATE TABLE IF NOT EXISTS eng_round (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  uid        TEXT NOT NULL,
  day        TEXT NOT NULL,
  right_cnt  INTEGER NOT NULL,
  wrong_cnt  INTEGER NOT NULL,
  ms         INTEGER NOT NULL,
  started_at INTEGER NOT NULL,
  client_rev TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_eng_idem ON eng_round(uid, client_rev);
CREATE INDEX IF NOT EXISTS idx_eng_day ON eng_round(day, right_cnt DESC);
