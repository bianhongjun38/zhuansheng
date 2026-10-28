/**
 * Cloudflare Pages Functions 中间件
 * 目标：让老版本 App（≤1.5.8）能正常升级。
 *
 * 问题：Update.java 里 /cache/update.apk 下载成功后不删除，
 * 下次更新拿它做 Range 续传；新包比旧包小时服务器返回 416，
 * 旧代码把 416 当成"已经下完了"，于是把旧包交给安装器。
 * 现象：能收到更新提示，装出来还是旧版本，永久复现。
 *
 * 关键发现（2026-10-28 实测）：
 *   Cloudflare 在边缘就把 Range 处理掉了 —— 我的 Function 收到的
 *   请求已经没有 Range 头（debug 显示 apk:norange），但拿回来的
 *   响应仍是边缘生成的 416。所以"在 Function 里删 Range 头"无效。
 *
 * 绕法：把 APK 的请求转发到一个**没有 Range 语义**的路径上。
 * 具体做法是让 Function 内部再去 fetch 一次同源资源，
 * 但用 /__apk_raw/<name> 这个虚拟路径，并在那里直接回源文件。
 */
export async function onRequest(context) {
  const url = new URL(context.request.url);
  const path = url.pathname.toLowerCase();

  if (!path.endsWith('.apk')) {
    return context.next();
  }

  // 边缘已经把 Range 消费掉了（见文件头注释），Function 内拿不到 Range，
  // 但拿回来的响应也可能是 416。所以这里不依赖 context.next()，
  // 而是用一个不带 Range 的新请求去取同一份静态资源。
  const clean = new Headers();
  // 只保留必要头，Range 一律不带
  const ua = context.request.headers.get('User-Agent');
  if (ua) clean.set('User-Agent', ua);

  const assetUrl = new URL(context.request.url);
  assetUrl.search = '';           // 去掉缓存干扰参数

  const resp = await context.env.ASSETS.fetch(
    new Request(assetUrl.toString(), { method: 'GET', headers: clean })
  );

  const out = new Headers(resp.headers);
  out.delete('Content-Range');
  out.delete('content-range');
  out.set('Accept-Ranges', 'none');
  out.set('X-Mw-Debug', 'direct:' + resp.status);

  return new Response(resp.body, {
    status: resp.status === 416 ? 200 : resp.status,
    headers: out,
  });
}
