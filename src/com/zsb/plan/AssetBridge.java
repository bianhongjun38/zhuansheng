package com.zsb.plan;

import android.app.Activity;
import android.content.res.AssetManager;
import android.webkit.WebResourceResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 APK 里的 assets/atlas/ 当作一个 HTTP 站点来服务。
 *
 * <h3>为什么不直接用 file:///android_asset/</h3>
 * 词图是纯前端应用，它做了三件在 file:// 下会坏掉的事：
 *
 *   1. <b>fetch() 读 JSON</b> —— 词库是 index.json + entries/N.json 共 74 个文件，
 *      运行时按需 fetch。file:// 源下 Chromium 一律按跨域拒绝，
 *      词库读不出来，整个图鉴是空的。
 *   2. <b>localStorage</b> —— 所有学习进度存在这里。file:// 是不透明源，
 *      部分 WebView 版本把 localStorage 关掉或按会话丢弃。
 *   3. <b>WebGL2 纹理</b> —— 图片被视为跨域，texImage2D 抛 SecurityError，
 *      星图黑屏。
 *
 * 所以这里造一个虚拟域名，在 shouldInterceptRequest 里全部拦下改从 assets 读。
 * 对网页来说它就是一个正常的同源 https 站点：fetch 能用、localStorage 稳定、
 * 纹理不跨域。网页代码一行都不用改。
 *
 * <h3>站点根在哪 —— 这里有个容易踩的坑</h3>
 * Vite 构建出来的 index.html 用的是<b>根绝对路径</b>：
 *     &lt;script src="/assets/index-xxx.js"&gt;
 *     &lt;link rel="preload" href="/atlas/index.json"&gt;
 * 也就是说网页认为自己在域名根 `/` 上。所以虚拟源也必须以主机根作为站点根，
 * 再把根映射到 APK 里的 assets/atlas/ 目录：
 *
 *     https://host/index.html            -> assets/atlas/index.html
 *     https://host/assets/index-xxx.js   -> assets/atlas/assets/index-xxx.js
 *     https://host/atlas/index.json      -> assets/atlas/atlas/index.json
 *
 * 如果反过来把站点根设成 /atlas/，那么 /assets/... 就匹配不上，
 * JS 和 CSS 全部 404，打开就是白屏 —— 这个坑实际踩过一次。
 *
 * <h3>为什么不用真的 http 服务</h3>
 * 起本地 ServerSocket 要多一个线程、一个端口，还要处理 Android 9+ 的明文流量
 * 限制，装完 App 第一次打开还得等端口就绪。拦截器是同步的，零线程零端口。
 *
 * 这个手法与 androidx 的 WebViewAssetLoader 同一思路（也是它默认用的域名），
 * 只是本项目不引依赖，按自己的 assets 布局重写。
 */
final class AssetBridge {

    /** 虚拟源。不是真实域名，不会产生任何网络请求。 */
    static final String HOST = "appassets.androidplatform.net";
    static final String SCHEME = "https";

    /** 站点根（对网页而言）。注意这里没有 /atlas/。 */
    static final String ORIGIN = SCHEME + "://" + HOST;

    /** 站点文件在 APK assets 里的实际目录。 */
    static final String ASSET_ROOT = "atlas/";

    /** 给 WebView 的入口地址。 */
    static final String ENTRY = ORIGIN + "/index.html";

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put("html", "text/html");
        MIME.put("htm", "text/html");
        MIME.put("js", "application/javascript");
        MIME.put("mjs", "application/javascript");
        MIME.put("css", "text/css");
        MIME.put("json", "application/json");
        MIME.put("webmanifest", "application/manifest+json");
        MIME.put("map", "application/json");
        MIME.put("txt", "text/plain");
        MIME.put("xml", "text/xml");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("webp", "image/webp");
        MIME.put("gif", "image/gif");
        MIME.put("ico", "image/x-icon");
        MIME.put("woff2", "font/woff2");
        MIME.put("woff", "font/woff");
        MIME.put("ttf", "font/ttf");
        MIME.put("otf", "font/otf");
        MIME.put("mp3", "audio/mpeg");
        MIME.put("m4a", "audio/mp4");
        MIME.put("ogg", "audio/ogg");
        MIME.put("wav", "audio/wav");
        MIME.put("mp4", "video/mp4");
    }

    private AssetBridge() {
    }

    /** 这个 URL 是否属于本站点。 */
    static boolean owns(String url) {
        return url != null && (url.equals(ORIGIN) || url.startsWith(ORIGIN + "/"));
    }

    /**
     * 把站点 URL 转成 assets 路径。
     * `https://host/assets/a.js` -> `atlas/assets/a.js`
     * 不属于本站点时返回 null。
     */
    static String toAssetPath(String url) {
        if (!owns(url)) return null;
        String rest = url.substring(ORIGIN.length());
        while (rest.startsWith("/")) rest = rest.substring(1);
        int q = rest.indexOf('?');
        if (q >= 0) rest = rest.substring(0, q);
        int h = rest.indexOf('#');
        if (h >= 0) rest = rest.substring(0, h);
        // 目录请求 → index.html
        if (rest.isEmpty() || rest.endsWith("/")) rest = rest + "index.html";
        return ASSET_ROOT + rest;
    }

    /** 站点是否已经被打进 APK。构建时没跑 --atlas 就是 false。 */
    static boolean hasSite(Activity act) {
        try {
            InputStream in = act.getAssets().open(ASSET_ROOT + "index.html");
            in.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 处理一个对本站点的请求，从 assets 读出内容。
     *
     * @param url 完整的站点 URL。
     * @return 找不到时返回 404 响应，让网页自己走错误分支，
     *         而不是让 WebView 去撞真实网络。
     */
    static WebResourceResponse serve(Activity act, String url) {
        AssetManager am = act.getAssets();
        String path = toAssetPath(url);
        // 防目录穿越：assets 路径里不允许出现 ..
        if (path == null || path.contains("..")) return notFound();

        String mime = guessMime(path);
        boolean isText = mime.startsWith("text/") || mime.contains("json")
                || mime.contains("javascript") || mime.contains("xml");

        try {
            InputStream in = am.open(path, AssetManager.ACCESS_STREAMING);
            WebResourceResponse r = new WebResourceResponse(mime, isText ? "utf-8" : null, in);
            Map<String, String> hdrs = new HashMap<>();
            // 带 hash 的静态资源可以长缓存；词库和 html 要 no-cache，
            // 否则更新 APK 后可能读到旧的词条。
            boolean hashed = path.matches(".*[-.][A-Za-z0-9_-]{8,}\\.(js|css|woff2?)$");
            hdrs.put("Cache-Control", hashed ? "public, max-age=604800"
                    : (isText ? "no-cache" : "public, max-age=86400"));
            hdrs.put("Access-Control-Allow-Origin", "*");
            r.setResponseHeaders(hdrs);
            return r;
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8",
                new java.io.ByteArrayInputStream("404 Not Found".getBytes()));
    }

    private static String guessMime(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = path.substring(dot + 1).toLowerCase();
        String m = MIME.get(ext);
        return m == null ? "application/octet-stream" : m;
    }
}
