package com.zsb.plan;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

/**
 * 读雅思词图的打卡数据，给原生首页用。
 *
 * <h3>为什么要读，而不是让用户在两个页面之间来回跳</h3>
 * 雅思进度存在 WebView 的 localStorage 里（键 word-atlas:v2:word-atlas-open），
 * 原生页面拿不到。用户想在首页直接看见"今天打卡了没、连着几天了"，
 * 现在必须点进雅思页签才知道 —— 而看不见就记不住，记不住就断签。
 *
 * <h3>为什么不直接读 WebView 的 localStorage 文件</h3>
 * Android 的 WebView 数据在 /data/data/&lt;pkg&gt;/app_webview/Local Storage/leveldb/，
 * 是 LevelDB 格式。解析它要引一个 LevelDB 库，还要处理编码差异
 * （Chromium 存的是 UTF-16 带前缀的键），版本一变就可能失效。脆且重。
 *
 * <h3>这里的做法：借 WebView 自己读</h3>
 * 起一个不可见的 WebView，加载同源的一个空白页（{@link #BLANK_PATH}），
 * 用 evaluateJavascript 直接读 localStorage。
 *
 * 为什么不加载词图首页：那是整个 React 应用，一进去会跑 Splash、
 * 初始化音频、预取 10 个词的插画与发音 —— 为了读三个数字付这些代价没有道理。
 * 空白页同源、秒开，只读一次键。
 *
 * 拿到的数据由 {@link Checkin#parse} 解析，后者是纯 Java，电脑上可单测。
 */
final class AtlasStats {

    /** 打卡摘要。所有字段都是"读不到就是默认值"，绝不抛异常给界面。 */
    static final class Checkin {
        /** 今天已背的新词数。 */
        int todayNew;
        /** 今天复习过的词数。>0 也算打卡（复习了就是学了）。 */
        int todayReview;
        /** 连续打卡天数（今天还没开始则从昨天往前数）。 */
        int streak;
        /** 累计学过的词数。 */
        int learned;
        /** 最近 7 天每天学了多少，下标 0 是 6 天前，6 是今天。 */
        int[] week = new int[7];
        /** 数据是否读成功。false 时界面显示"还没开始"而不是"0 天"。 */
        boolean ok;

        /** 今天算不算打过卡。 */
        boolean checkedToday() { return todayNew > 0 || todayReview > 0; }

        /** 一周里最多的一天，用来给柱状图定高度。至少为 1，避免除零。 */
        int weekMax() {
            int m = 1;
            for (int v : week) if (v > m) m = v;
            return m;
        }

        /** 今天总共动了多少词。 */
        int todayTotal() { return todayNew + todayReview; }
    }

    private AtlasStats() {
    }

    /** 回调在 UI 线程调用。 */
    interface OnCheckin {
        void on(Checkin c);
    }

    /** 词库 id。dist/atlas/index.json 的 id 字段就是它。 */
    static final String DATASET_KEY = "word-atlas:v2:word-atlas-open";

    /**
     * 读数据用的空白页路径。
     *
     * 它必须是和词图同一个 origin，localStorage 才是同一份。
     * 用 data: / file: / about:blank 都不行 —— 不同源就看不到。
     */
    static final String BLANK_PATH = AssetBridge.BLANK_PATH;

    /**
     * 异步读打卡数据。
     *
     * @param act    宿主 Activity
     * @param parent 临时 WebView 的挂载容器（不可见，读完立刻摘掉）
     */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    static void load(Activity act, ViewGroup parent, final OnCheckin cb) {
        if (!AssetBridge.hasSite(act)) {
            cb.on(new Checkin());
            return;
        }

        final WebView w = new WebView(act);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // 关键：不打开就看不到 localStorage
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(true);      // 这个 WebView 绝不该发真实请求

        final Object bridge = new Object() {
            @JavascriptInterface
            public void post(String json) {
                final Checkin c = parse(json);
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        cb.on(c);
                        try { parent.removeView(w); w.destroy(); } catch (Exception ignored) {}
                    }
                });
            }
        };
        w.addJavascriptInterface(bridge, "__zsbStats");

        w.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (!AssetBridge.owns(u)) return null;
                return AssetBridge.serve(act, u);
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                // 页面自己也有初始化逻辑，等一拍再读
                v.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            v.evaluateJavascript(snippet(), null);
                        } catch (Exception e) {
                            act.runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    cb.on(new Checkin());
                                    try { parent.removeView(w); w.destroy(); } catch (Exception ignored) {}
                                }
                            });
                        }
                    }
                }, 250);
            }
        });

        parent.addView(w, new ViewGroup.LayoutParams(1, 1));
        w.setVisibility(android.view.View.INVISIBLE);
        w.loadUrl(AssetBridge.ORIGIN + BLANK_PATH);
    }

    /**
     * 在页面里跑的一段 JS：把 progress 里的 days / cards 交给 Java。
     *
     * 找不到数据时也回 {}，让 Java 侧统一判成"没数据"，
     * 而不是让 WebView 静默失败、界面永远停在加载中。
     */
    static String snippet() {
        return "(function(){try{"
                + "var raw=localStorage.getItem('" + DATASET_KEY + "');"
                + "if(!raw){__zsbStats.post('{}');return;}"
                + "var p=JSON.parse(raw);"
                + "__zsbStats.post(JSON.stringify({days:p.days||{},cards:p.cards||{}}));"
                + "}catch(e){__zsbStats.post('{}');}})();";
    }

    /**
     * 解析页面吐回来的 JSON。纯函数，不碰 Android，可单测。
     *
     * 输入形如 {"days":{"2026-10-31":{"n":10,"r":12}},"cards":{"abandon":{...}}}
     */
    static Checkin parse(String json) {
        return parseAt(json, new java.util.Date());
    }

    /** 同上，但把"现在"作为参数传进来 —— 测试里才能构造确定的日期。 */
    static Checkin parseAt(String json, java.util.Date now) {
        Checkin c = new Checkin();
        if (json == null) return c;
        try {
            String s = json.trim();
            // evaluateJavascript 会把字符串再包一层引号并转义
            if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
            if (s.isEmpty() || s.equals("null") || s.equals("{}")) return c;
            JSONObject root = new JSONObject(s);
            JSONObject days = root.optJSONObject("days");
            JSONObject cards = root.optJSONObject("cards");
            if (cards != null) c.learned = cards.length();
            if (days == null) {
                c.ok = cards != null;
                return c;
            }

            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTime(now);

            for (int i = 0; i < 7; i++) {
                String k = fmt.format(cal.getTime());
                JSONObject d = days.optJSONObject(k);
                int v = 0;
                if (d != null) v = d.optInt("n", 0) + d.optInt("r", 0);
                c.week[6 - i] = v;
                if (i == 0 && d != null) {
                    c.todayNew = d.optInt("n", 0);
                    c.todayReview = d.optInt("r", 0);
                }
                cal.add(java.util.Calendar.DATE, -1);
            }

            // 连续天数：今天打卡了就从今天数；今天还没开始就从昨天数 ——
            // 一天还没过完，不能算断签。
            cal.setTime(now);
            boolean today = c.todayTotal() > 0;
            if (!today) cal.add(java.util.Calendar.DATE, -1);
            int streak = 0;
            // 上限 3650 天，防御损坏数据导致死循环
            for (int i = 0; i < 3650; i++) {
                JSONObject d = days.optJSONObject(fmt.format(cal.getTime()));
                if (d == null) break;
                if (d.optInt("n", 0) + d.optInt("r", 0) <= 0) break;
                streak++;
                cal.add(java.util.Calendar.DATE, -1);
            }
            c.streak = streak;
            c.ok = true;
        } catch (Exception e) {
            // 解析失败就当没数据，不抛给界面
        }
        return c;
    }
}