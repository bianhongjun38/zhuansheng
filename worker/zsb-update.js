/**
 * zsb-update —— 专升本 App 的更新分发入口
 *
 * 为什么要有这一层：
 *   手机直连 github.com / api.github.com 在国内经常超时（实测 git push 连续
 *   4 次 443 超时 21s）。GitHub 一抽风，检查更新就报「网络不通」。
 *   Cloudflare 边缘节点国内可达性好得多，所以让手机只认这个域名：
 *
 *     GET /update.json  → 版本信息（从 GitHub API 抓，边缘缓存 5 分钟）
 *     GET /apk          → 302 到最新 APK 的 GitHub 下载地址
 *     GET /             → 人看的说明页
 *
 * 版本信息仍然以 GitHub Release 为唯一真相，这一层只是「换个更稳的入口」，
 * 不做二次维护，避免两边版本号打架。
 * ── 2026-10-27 22:27 实测：本 Worker 在边缘持续 500 / error code 1101 ──
 * 已用「全新 3 行 pong Worker」对照确认是**账号级 Workers 运行时故障**，
 * 与本文件代码无关（详见 wrangler.toml 底部记录）。
 * 不要在这里改 fetch / cache 逻辑来试图修 500 —— 改不好的。
 * App 目前靠 Pages + GitHub API 两条通道正常工作。
 */

const REPO = 'bianhongjun38/zhuansheng';
const API = `https://api.github.com/repos/${REPO}/releases/latest`;

// 缓存时长：5 分钟。发版后最迟 5 分钟手机能看到，足够。
const TTL = 300;

function json(data, status = 200, extra = {}) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': `public, max-age=${TTL}`,
      ...extra,
    },
  });
}

/** 从 GitHub Release 抽出 App 需要的字段。字段名与 Update.java 对齐。 */
function normalize(rel) {
  const tag = (rel.tag_name || '').trim();
  const vname = tag.replace(/^[vV]/, '');
  const apk = (rel.assets || []).find((a) => /\.apk$/i.test(a.name));
  return {
    ok: true,
    versionName: vname,
    // 与 Update.parseVer 同一套换算：1.2.1 → 10201
    versionInt: (() => {
      const p = vname.split('.').map((x) => parseInt(x, 10) || 0);
      return (p[0] || 0) * 10000 + (p[1] || 0) * 100 + (p[2] || 0);
    })(),
    notes: rel.body || '',
    publishedAt: rel.published_at || null,
    apk: apk
      ? {
          name: apk.name,
          size: apk.size,
          url: apk.browser_download_url,
        }
      : null,
    source: 'github-release',
  };
}

async function fetchLatest() {
  // 不再传 cf:{cacheTtl,cacheEverything}。
  // 实测线上报 error code 1101（Worker 抛未捕获异常），而本地 dev 正常 ——
  // 差别就在这个 cf 选项：它要求请求走 Cloudflare Cache API，
  // 在某些 zone 配置下会直接抛错。缓存改由**响应头** Cache-Control 承担，
  // 由边缘自动缓存 GET 响应，效果一样但不碰那套内部 API。
  const r = await fetch(API, {
    headers: {
      accept: 'application/vnd.github+json',
      'user-agent': 'zsb-update-worker',
    },
  });
  if (!r.ok) {
    throw new Error(`GitHub API ${r.status}`);
  }
  return await r.json();
}

export default {
  async fetch(req) {
    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    try {
      if (path === '/update.json') {
        const rel = await fetchLatest();
        return json(normalize(rel));
      }

      if (path === '/apk') {
        const rel = await fetchLatest();
        const apk = (rel.assets || []).find((a) => /\.apk$/i.test(a.name));
        if (!apk) {
          return json({ ok: false, error: '最新版没有 APK 资产' }, 404);
        }
        // 302 交给 GitHub 的 CDN 下载；这一跳不经过本 Worker 传字节
        return Response.redirect(apk.browser_download_url, 302);
      }

      if (path === '/') {
        const rel = await fetchLatest().catch(() => null);
        const info = rel ? normalize(rel) : null;
        const body = info && info.ok
          ? `最新版本：v${info.versionName}\n` +
            `安装包：${info.apk ? info.apk.name : '（无）'}\n` +
            `大小：${info.apk ? (info.apk.size / 1048576).toFixed(2) + ' MB' : '-'}\n\n` +
            `机器可读：/update.json\n下载跳转：/apk\n`
          : '暂时取不到版本信息，请稍后重试。\n';
        return new Response(body, {
          headers: { 'content-type': 'text/plain; charset=utf-8' },
        });
      }

      return json({ ok: false, error: 'not found' }, 404);
    } catch (e) {
      // 取不到时明确报错，不返回假版本号 —— 否则手机可能误判「已是最新」
      return json({ ok: false, error: String(e && e.message || e) }, 502);
    }
  },
};
