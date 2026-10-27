// zsb-api —— 学习进度同步 + 英语闯关排行榜
//
// 20 人小圈子，一个文件搞定，不拆微服务。
//
// 设计取舍（都写在代码里了，改之前先读一遍）：
//   · 密码用 PBKDF2-SHA256，10 万轮 + 每用户独立 salt。服务端不存明文。
//   · 排行榜显示真实用户名 —— 用户明确说了「又不是外人」。
//   · 防作弊只留一条最便宜的：单轮题数上限 + 每题最短用时。
//     20 个同学，谁一天涨 5000 分一眼就假，不需要那套种子校验。

const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8' };

function json(obj, status) {
  return new Response(JSON.stringify(obj), { status: status || 200, headers: JSON_HEADERS });
}

function fail(code, msg, status) {
  return json({ ok: false, error: code, message: msg }, status || 400);
}

// ── 时间 / 随机 ──────────────────────────────────────────

/** 按 Asia/Shanghai 切日。服务器在 UTC，直接用 toISOString 会差 8 小时，
 *  晚上 8 点之后做的题会被算到「明天」—— 排行榜按天统计时这是致命错。 */
function todayCn() {
  const d = new Date(Date.now() + 8 * 3600 * 1000);
  return d.toISOString().slice(0, 10);
}

function uuid() {
  return crypto.randomUUID();
}

function token() {
  const b = new Uint8Array(24);
  crypto.getRandomValues(b);
  return [...b].map(x => x.toString(16).padStart(2, '0')).join('');
}

// ── 密码 ────────────────────────────────────────────────

const ITER = 100000;

function toB64(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes)));
}

function fromB64(s) {
  return Uint8Array.from(atob(s), c => c.charCodeAt(0));
}

async function pbkdf2(password, saltB64) {
  const key = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', salt: fromB64(saltB64), iterations: ITER, hash: 'SHA-256' },
    key, 256);
  return toB64(bits);
}

/** 定长比较，避免按字节提前返回泄露信息（本地网络里意义不大，但顺手写上）。 */
function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// ── 鉴权 ────────────────────────────────────────────────

async function authUser(request, env) {
  const h = request.headers.get('authorization') || '';
  const m = h.match(/^Bearer\s+(.+)$/i);
  if (!m) return null;
  const row = await env.DB.prepare(
    'SELECT uid, username FROM users WHERE token = ?').bind(m[1].trim()).first();
  if (!row) return null;
  await env.DB.prepare('UPDATE users SET last_seen = ? WHERE uid = ?')
    .bind(Date.now(), row.uid).run();
  return row;
}

// ── 接口 ────────────────────────────────────────────────

/**
 * POST /v1/register  或  /v1/login
 * body: { username, password }
 *
 * 用户名 + 密码就是身份。没有邮箱、没有验证码、没有找回流程。
 * 忘记密码 = 找管理员（也就是你）在 D1 里重置，20 个人这么干最省事。
 */
async function handleLogin(request, env, isRegister) {
  let body;
  try { body = await request.json(); } catch { return fail('bad_json', '请求格式不对'); }

  const username = String(body.username || '').trim();
  const password = String(body.password || '');

  if (username.length < 1 || username.length > 20) {
    return fail('bad_name', '用户名要 1-20 个字');
  }
  if (password.length < 4) {
    return fail('weak_password', '密码至少 4 位');
  }

  const existing = await env.DB.prepare(
    'SELECT uid, username, salt, hash, token FROM users WHERE username = ?')
    .bind(username).first();

  if (isRegister) {
    if (existing) return fail('name_taken', '这个名字已经有人用了，换一个');
    const salt = toB64(crypto.getRandomValues(new Uint8Array(16)));
    const hash = await pbkdf2(password, salt);
    const uid = uuid();
    const tk = token();
    const now = Date.now();
    await env.DB.prepare(
      'INSERT INTO users (uid, username, salt, hash, token, created_at, last_seen) VALUES (?,?,?,?,?,?,?)')
      .bind(uid, username, salt, hash, tk, now, now).run();
    return json({ ok: true, uid, username, token: tk, isNew: true });
  }

  // 登录
  if (!existing) return fail('no_user', '没有这个用户名', 404);
  const h = await pbkdf2(password, existing.salt);
  if (!safeEqual(h, existing.hash)) return fail('bad_password', '密码不对', 401);

  // 登录成功换一个新 token：旧手机的 token 立刻失效，
  // 这既是「换手机」也是「改密码后踢掉别的设备」
  const tk = token();
  await env.DB.prepare('UPDATE users SET token = ?, last_seen = ? WHERE uid = ?')
    .bind(tk, Date.now(), existing.uid).run();
  return json({ ok: true, uid: existing.uid, username: existing.username, token: tk, isNew: false });
}

/**
 * POST /v1/sync
 * body: { data, updatedAt, planVer }
 *
 * 覆盖式同步，但带冲突检测：
 *   · 客户端 updatedAt 比库里新 → 覆盖，旧值进历史
 *   · 比库里旧 → 不覆盖，返回服务端版本，让用户自己决定
 * 20 个人一年也用不了几次冲突，弹一次窗口比写自动合并靠谱。
 */
async function handleSync(request, env, user) {
  let body;
  try { body = await request.json(); } catch { return fail('bad_json', '请求格式不对'); }

  const data = String(body.data || '');
  const updatedAt = Number(body.updatedAt || 0);
  const planVer = Number(body.planVer || 0);

  if (data.length < 2) return fail('empty', '没有内容');
  if (data.length > 500000) return fail('too_big', '进度太大了（超过 500KB）');

  const cur = await env.DB.prepare(
    'SELECT data, updated_at FROM progress WHERE uid = ?').bind(user.uid).first();

  if (!cur) {
    await env.DB.prepare(
      'INSERT INTO progress (uid, data, plan_ver, updated_at) VALUES (?,?,?,?)')
      .bind(user.uid, data, planVer, updatedAt || Date.now()).run();
    return json({ ok: true, action: 'created', serverUpdatedAt: updatedAt || Date.now() });
  }

  if (updatedAt < cur.updated_at) {
    // 云端更新 → 不覆盖，把云端版本带回去
    return json({
      ok: true, action: 'conflict',
      serverUpdatedAt: cur.updated_at,
      serverData: cur.data,
      message: '云端进度比这台手机新，要用哪一份？'
    });
  }

  // 覆盖前留一份历史
  await env.DB.prepare(
    'INSERT INTO progress_history (uid, data, saved_at) VALUES (?,?,?)')
    .bind(user.uid, cur.data, Date.now()).run();
  await env.DB.prepare(
    'DELETE FROM progress_history WHERE uid = ? AND id NOT IN '
    + '(SELECT id FROM progress_history WHERE uid = ? ORDER BY id DESC LIMIT 10)')
    .bind(user.uid, user.uid).run();

  await env.DB.prepare(
    'UPDATE progress SET data = ?, plan_ver = ?, updated_at = ? WHERE uid = ?')
    .bind(data, planVer, updatedAt, user.uid).run();

  return json({ ok: true, action: 'updated', serverUpdatedAt: updatedAt });
}

/** GET /v1/sync —— 拉取云端进度（换手机时用） */
async function handlePull(env, user) {
  const cur = await env.DB.prepare(
    'SELECT data, updated_at, plan_ver FROM progress WHERE uid = ?').bind(user.uid).first();
  if (!cur) return json({ ok: true, exists: false });
  return json({ ok: true, exists: true, data: cur.data,
                updatedAt: cur.updated_at, planVer: cur.plan_ver });
}

// ── 英语成绩 ────────────────────────────────────────────

const MAX_PER_ROUND = 50;      // 单轮最多 50 题
const MIN_MS_PER_Q = 800;      // 每题至少 0.8 秒
const MAX_ROUNDS_PER_DAY = 30; // 单日最多 30 轮

/**
 * POST /v1/eng/round
 * body: { right, wrong, ms, startedAt, clientRev }
 *
 * 防作弊只留这三条。挡掉手滑和明显离谱的数字就够了 ——
 * 20 个同学的榜，谁突然一天涨 5000 分，一眼就假。
 */
async function handleEngRound(request, env, user) {
  let body;
  try { body = await request.json(); } catch { return fail('bad_json', '请求格式不对'); }

  const right = Math.max(0, Math.min(999, Number(body.right || 0)));
  const wrong = Math.max(0, Math.min(999, Number(body.wrong || 0)));
  const ms = Math.max(0, Number(body.ms || 0));
  const startedAt = Number(body.startedAt || Date.now());
  const clientRev = String(body.clientRev || '').slice(0, 64);

  if (!clientRev) return fail('no_rev', '缺少幂等键');
  const total = right + wrong;
  if (total === 0) return fail('empty_round', '这一轮没有题目');
  if (total > MAX_PER_ROUND) {
    return fail('too_many', '单轮最多 ' + MAX_PER_ROUND + ' 题');
  }
  if (ms < total * MIN_MS_PER_Q) {
    // 不报错，只记下来但不入榜 —— 报错会让用户以为 App 坏了
    return json({ ok: false, error: 'too_fast', ignored: true,
                  message: '这一轮用时明显不对，已忽略' });
  }

  const day = todayCn();
  const cnt = await env.DB.prepare(
    'SELECT COUNT(*) AS n FROM eng_round WHERE uid = ? AND day = ?')
    .bind(user.uid, day).first();
  if (cnt && cnt.n >= MAX_ROUNDS_PER_DAY) {
    return json({ ok: false, error: 'daily_cap', ignored: true,
                  message: '今天已经做了 ' + MAX_ROUNDS_PER_DAY + ' 轮，明天再来' });
  }

  await env.DB.prepare(
    'INSERT OR IGNORE INTO eng_round (uid, day, right_cnt, wrong_cnt, ms, started_at, client_rev, created_at)'
    + ' VALUES (?,?,?,?,?,?,?,?)')
    .bind(user.uid, day, right, wrong, ms, startedAt, clientRev, Date.now()).run();

  return json({ ok: true });
}

/** GET /v1/eng/board?range=day|week */
async function handleBoard(url, env) {
  const range = url.searchParams.get('range') === 'week' ? 'week' : 'day';
  const today = todayCn();
  let where, binds;
  if (range === 'day') {
    where = 'r.day = ?'; binds = [today];
  } else {
    // 最近 7 天（含今天）
    const d = new Date(Date.now() + 8 * 3600 * 1000);
    d.setUTCDate(d.getUTCDate() - 6);
    where = 'r.day >= ? AND r.day <= ?';
    binds = [d.toISOString().slice(0, 10), today];
  }

  const stmt = env.DB.prepare(
    'SELECT u.username, SUM(r.right_cnt) AS score,'
    + ' SUM(r.right_cnt + r.wrong_cnt) AS total, COUNT(*) AS rounds'
    + ' FROM eng_round r JOIN users u ON u.uid = r.uid'
    + ' WHERE ' + where
    + ' GROUP BY r.uid ORDER BY score DESC, total ASC LIMIT 50').bind(...binds);

  const { results } = await stmt.all();
  return json({ ok: true, range, day: today, board: results || [] });
}

/** GET /v1/eng/me —— 我的战绩（今天 + 最近 7 天 + 历史累计） */
async function handleMe(env, user) {
  const today = todayCn();
  const one = async (sql, ...b) => {
    const r = await env.DB.prepare(sql).bind(...b).first();
    return r || { rounds: 0, right: 0, total: 0, ms: 0 };
  };
  const todayStat = await one(
    'SELECT COUNT(*) AS rounds, IFNULL(SUM(right_cnt),0) AS right,'
    + ' IFNULL(SUM(right_cnt+wrong_cnt),0) AS total, IFNULL(SUM(ms),0) AS ms'
    + ' FROM eng_round WHERE uid = ? AND day = ?', user.uid, today);
  const allStat = await one(
    'SELECT COUNT(*) AS rounds, IFNULL(SUM(right_cnt),0) AS right,'
    + ' IFNULL(SUM(right_cnt+wrong_cnt),0) AS total, IFNULL(SUM(ms),0) AS ms'
    + ' FROM eng_round WHERE uid = ?', user.uid);
  return json({ ok: true, username: user.username, today: todayStat, all: allStat });
}

// ── 路由 ────────────────────────────────────────────────

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const p = url.pathname.replace(/\/+$/, '') || '/';

    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: {
        'access-control-allow-origin': '*',
        'access-control-allow-headers': 'authorization,content-type',
        'access-control-allow-methods': 'GET,POST,OPTIONS' } });
    }

    try {
      if (p === '/' || p === '/health') {
        return json({ ok: true, service: 'zsb-api', day: todayCn() });
      }

      if (p === '/v1/register' && request.method === 'POST') {
        return await handleLogin(request, env, true);
      }
      if (p === '/v1/login' && request.method === 'POST') {
        return await handleLogin(request, env, false);
      }

      // 排行榜不需要登录（同学之间看看谁在学）
      if (p === '/v1/eng/board' && request.method === 'GET') {
        return await handleBoard(url, env);
      }

      // 以下需要登录
      const user = await authUser(request, env);
      if (!user) return fail('unauthorized', '请先登录', 401);

      if (p === '/v1/sync' && request.method === 'POST') {
        return await handleSync(request, env, user);
      }
      if (p === '/v1/sync' && request.method === 'GET') {
        return await handlePull(env, user);
      }
      if (p === '/v1/eng/round' && request.method === 'POST') {
        return await handleEngRound(request, env, user);
      }
      if (p === '/v1/eng/me' && request.method === 'GET') {
        return await handleMe(env, user);
      }

      return fail('not_found', '没有这个接口：' + p, 404);
    } catch (e) {
      // 不把堆栈吐给客户端，但留一条日志方便排查
      console.error('zsb-api error', p, e && e.stack || e);
      return fail('server_error', '服务器出错了', 500);
    }
  }
};
