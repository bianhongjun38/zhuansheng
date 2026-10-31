package com.zsb.plan;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 雅思词图（IELTS Word Atlas）—— 嵌在 App 里的独立学习模块。
 *
 * <h3>为什么是 WebView 而不是原生 View</h3>
 * 词图是一套已经跑通的 React 应用：7,298 个雅思词条、7,298 张插画、
 * 22,905 段英美发音与例句朗读、FSRS 间隔复习、WebGL2 星图。
 * 把它逐屏翻译成 Android View 要重写几千行，而且图鉴的插画瀑布流和
 * 星图的 GPU 渲染，在原生侧重做只会更慢更丑。
 *
 * 所以主 App 这边只做<b>宿主</b>：负责把网页装进来、接住返回键、
 * 出事时给出能看懂的提示。学习逻辑、数据、进度全在网页里。
 *
 * <h3>为什么不用 file:///android_asset</h3>
 * 词图有三处能力在 file:// 下会直接坏掉，详见 {@link AssetBridge} 的类注释：
 * fetch 读不了词库、localStorage 不稳定、WebGL 纹理跨域。
 * 所以走虚拟 https 源，所有请求由 AssetBridge 从 assets 读出。
 * 对网页而言它就是一个正常的同源 https 站点，零改造成本。
 *
 * <h3>与主 App 的隔离</h3>
 * 完全独立：自己的 localStorage 命名空间（word-atlas:*）、自己的路由、
 * 自己的返回栈。主 App 的 Store / plan.json / 词汇塔进度一行都不碰，
 * 双向不会互相污染。想清词图进度只需要清它的域名数据。
 */
public class AtlasView {

    private static final int C_BG = 0xFF14161C;
    private static final int C_BG2 = 0xFF1C1F27;
    private static final int C_FG = 0xFFE8EAF0;
    private static final int C_FG2 = 0xFFC3C9D6;
    private static final int C_FG3 = 0xFF6B7282;
    private static final int C_TEAL = 0xFF3ECFAF;

    private final Activity act;
    private Dialog dlg;
    private WebView web;
    private View errorView;
    private Runnable onExit;

    public AtlasView(Activity a) {
        this.act = a;
    }

    /** 从今日页 / 错题库打开词图首页。 */
    public void open() {
        open(null);
    }

    /**
     * @param deep 可选 hash 路由，例如 "#/atlas"、"#/practice"、"#/me"。
     *             传 null 或空串就是首页（今日）。
     */
    public void open(String deep) {
        if (dlg != null && dlg.isShowing()) return;
        dlg = new Dialog(act);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(C_BG);

        // ── 顶部条 ────────────────────────────────────────────
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        bar.setBackgroundColor(C_BG2);

        TextView close = new TextView(act);
        close.setText("✕");
        close.setTextSize(18);
        close.setTextColor(C_FG2);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), dp(6), dp(10), dp(6));
        close.setOnClickListener(v -> close());
        bar.addView(close);

        TextView title = new TextView(act);
        title.setText("雅思词图");
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_FG);
        LinearLayout.LayoutParams tlp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(4);
        bar.addView(title, tlp);

        TextView reload = new TextView(act);
        reload.setText("↻");
        reload.setTextSize(17);
        reload.setTextColor(C_FG3);
        reload.setGravity(Gravity.CENTER);
        reload.setPadding(dp(10), dp(6), dp(10), dp(6));
        reload.setOnClickListener(v -> {
            if (web != null) web.reload();
        });
        bar.addView(reload);

        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // ── 加载指示 ──────────────────────────────────────────
        final ProgressBar spin = new ProgressBar(act);
        root.addView(spin, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));

        // ── WebView ───────────────────────────────────────────
        web = new WebView(act);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // 词图全部进度靠 localStorage
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);           // 不需要 file://，关掉更安全
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false); // 发音要能自动播
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        web.setBackgroundColor(C_BG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLongClickable(false);           // 不是阅读器，关掉长按选词
        web.setOnLongClickListener(v -> true);

        // deep 是 hash 路由：ENTRY 已经是 .../index.html，
        // 直接拼 "#/atlas" 即可，WebView 不会把 hash 发给拦截器。
        final String url = (deep == null || deep.isEmpty())
                ? AssetBridge.ENTRY
                : AssetBridge.ENTRY + deep;

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                String u = req.getUrl().toString();
                // 只接管自己的虚拟源，其它一律放走（不会有真实网络请求）
                if (!AssetBridge.owns(u)) return null;
                return AssetBridge.serve(act, u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (AssetBridge.owns(u)) return false;
                if (u.startsWith("about:")) return false;
                // 外链交给系统浏览器，别把 WebView 变成浏览器
                try {
                    act.startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, req.getUrl()));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                spin.setVisibility(View.GONE);
                web.setVisibility(View.VISIBLE);
            }
        });

        web.setVisibility(View.INVISIBLE);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── 兜底：assets 没打包时给明确指引，而不是白屏 ──────
        errorView = buildError();
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (!AssetBridge.hasSite(act)) {
            spin.setVisibility(View.GONE);
            errorView.setVisibility(View.VISIBLE);
            web.setVisibility(View.GONE);
        } else {
            web.loadUrl(url);
        }

        dlg.setContentView(root);
        dlg.setOnDismissListener(d -> {
            if (web != null) {
                web.stopLoading();
                web.loadUrl("about:blank");
                web.destroy();
                web = null;
            }
            if (onExit != null) onExit.run();
        });
        dlg.show();
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawableResource(android.R.color.black);
        }
    }

    /**
     * 返回键。先让网页自己处理（关词卡 / 退出学习 / 回上一页），
     * 网页到头了才关整个面板 —— 这样在词图里按返回不会一下子退出。
     */
    public boolean onBack() {
        if (dlg == null || !dlg.isShowing()) return false;
        if (web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        close();
        return true;
    }

    public boolean isShowing() {
        return dlg != null && dlg.isShowing();
    }

    public void close() {
        if (dlg != null && dlg.isShowing()) dlg.dismiss();
    }

    public void setOnExit(Runnable r) {
        this.onExit = r;
    }

    private View buildError() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(28), dp(28), dp(28), dp(28));
        box.setBackgroundColor(C_BG);

        TextView h = new TextView(act);
        h.setText("词图资源没有打包进来");
        h.setTextSize(17);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(C_FG);
        h.setGravity(Gravity.CENTER);
        box.addView(h);

        TextView p = new TextView(act);
        p.setText("\nAPK 里缺少 assets/atlas/ 目录。\n\n"
                + "重新构建即可：\n"
                + "  cd mobile\n"
                + "  python build_apk.py --atlas\n\n"
                + "构建脚本会把 ielts-atlas/dist 拷进 assets/atlas/。");
        p.setTextSize(13);
        p.setTextColor(C_FG2);
        p.setGravity(Gravity.CENTER);
        p.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(12);
        box.addView(p, plp);

        TextView btn = new TextView(act);
        btn.setText("关闭");
        btn.setTextSize(14);
        btn.setTextColor(C_TEAL);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(20), dp(12), dp(20), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_BG2);
        bg.setCornerRadius(dp(10));
        btn.setBackground(bg);
        btn.setOnClickListener(v -> close());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(24);
        box.addView(btn, blp);

        return box;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }
}
