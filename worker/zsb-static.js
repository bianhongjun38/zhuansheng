/**
 * zsb-update —— 专升本 App 的更新分发 Worker（静态直出）
 *
 * 与旧版的根本区别：**不再把 APK 302 到 GitHub**。
 *
 * 为什么（2026-10-28 实测，不是猜测）：
 *   · github.com 主站在国内被阻断：HTTP 000，超时 12s。
 *     旧版 302 到 github.com/.../releases/download/... 之后必然下载失败。
 *   · *.workers.dev 在国内被 DNS 污染：
 *       zsb-update.woctmdeding.workers.dev 解析出
 *       202.160.129.6 → 103.73.161.52 → 74.86.142.55（每次都变）
 *       TCP 443 全部超时。
 *     但同账号的 cloudflare.com / pages.dev / 自定义域名都通。
 *     => 不是账号故障（旧版注释里写的「账号级 1101 故障」是误判），
 *        是 workers.dev 这个后缀走不通。
 *   · Pages 单文件上限 25 MiB，APK 已 20.13 MiB，容不下下次增长。
 *
 * 所以：APK 与清单都作为 Worker 静态资源随 deployment 一起上传，
 * 由绑定在自有域名上的这个 Worker 直接从边缘出网。
 *
 *   GET /                 → 下载页
 *   GET /update.json      → 版本清单（App 主要读这个）
 *   GET /version.json     → 同上，兼容旧客户端
 *   GET /<name>.apk       → 安装包本体（21 MB，静态资源）
 */

const TTL = 60;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    let path = url.pathname.replace(/\/+$/, '');
    if (path === '') path = '/';

    // 静态资源优先：APK / update.json / index.html 都在 ./public 里
    if (path === '/') {
      const idx = await env.ASSETS.fetch(new URL('/index.html', url));
      return withCache(idx);
    }

    // /update.json 与 /version.json：给不同的缓存策略
    if (path === '/update.json' || path === '/version.json') {
      const r = await env.ASSETS.fetch(new URL(path, url));
      if (r.status === 404) {
        return json({ ok: false, error: 'manifest not found' }, 404);
      }
      return withCache(r);
    }

    // 其余 (主要是 *.apk) 直接透传静态资源
    const r = await env.ASSETS.fetch(req);
    if (r.status === 404) {
      return json({ ok: false, error: 'not found: ' + path }, 404);
    }
    // APK 允许浏览器/下载器缓存一天；换版本换文件名，不会串味
    return withCache(r, /.apk$/i.test(path) ? 86400 : TTL);
  },
};

function withCache(resp, maxAge = TTL) {
  const h = new Headers(resp.headers);
  h.set('cache-control', 'public, max-age=' + maxAge);
  // 让手机和 CDN 都知道这是可下载的二进制，别当 HTML 渲染
  if (!h.get('content-type')) h.set('content-type', 'application/octet-stream');
  return new Response(resp.body, { status: resp.status, headers: h });
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      'cache-control': 'public, max-age=' + TTL,
    },
  });
}
