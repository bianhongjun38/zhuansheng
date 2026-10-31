package com.zsb.plan;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 数学工具 —— 用现成开源库替代自研的 Expr / PlotGeom。
 *
 * <h3>为什么换掉自研实现</h3>
 * 原来的 MathView 是手写的：一个只会多项式与初等函数的表达式解析器（Expr）、
 * 一个 Canvas 画图器（PlotGeom）、一张 40 条的求导表。能画 sin(x)，
 * 但 <b>算不出 d/dx(x^2·sin x)</b> —— 求导练习只能比对预存的答案串，
 * 学生写成 2*x*sin(x)+x^2*cos(x) 换个顺序就判错。
 *
 * 现在换成三个成熟开源库（都是 MIT / Apache-2.0，全部离线打包）：
 *   nerdamer      符号运算：求导、积分、化简、展开、因式分解、解方程
 *   function-plot SVG 绘图：值域自动适配、可拖拽缩放、支持多条曲线
 *   mathjs        数值计算（备用）
 *
 * 关键改进：<b>求导判等价而不是判字符串</b>。
 * 学生写 3x² 还是 3*x^2 还是 x*3*x，都能判对 —— 因为是真算过。
 *
 * <h3>为什么用 WebView</h3>
 * 这三个库都是 JavaScript 的。要在原生 Java 里复刻同样的能力，
 * 等于重写一个计算机代数系统。WebView 已在词图那边验证过（AssetBridge），
 * 资源从 APK 里读，零网络请求，离线可用。
 */
public class MathToolView {

    private static final int C_BG = 0xFF14161C;

    private final Activity act;
    private Dialog dlg;
    private WebView web;
    private View loading;

    public MathToolView(Activity a) { this.act = a; }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }

    /** 打开数学工具。tab 参数保留兼容旧调用（0 图像 / 1 公式 / 2 练习），已不区分。 */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public void open(int tab) {
        if (!AssetBridge.hasMath(act)) {
            android.widget.Toast.makeText(act,
                    "数学工具没有打进这个安装包（构建时缺 assets/math）",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }

        dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // 顶栏只留一个关闭按钮。页签、输入框都在网页里，
        // 原生层再画一遍会和网页重复，也难保持两边一致。
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(10), dp(4));
        TextView close = new TextView(act);
        close.setText("✕");
        close.setTextSize(20);
        close.setTextColor(0xFFC3C9D6);
        close.setPadding(dp(10), dp(6), dp(12), dp(6));
        close.setOnClickListener(v -> dlg.dismiss());
        bar.addView(close);
        root.addView(bar);

        android.widget.FrameLayout box = new android.widget.FrameLayout(act);
        root.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        web = new WebView(act);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);            // 整个页面都是 JS 驱动的
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);             // 不需要 file://
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        // 关掉双击缩放：手机上双击是「放大」手势，会误触。
        // 但 pinch 缩放留着 —— 看函数图像时学生可能需要放大看细节。
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.setBackgroundColor(C_BG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // 给网页一个关闭入口（页面左上角的 ✕）
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void close() {
                act.runOnUiThread(() -> {
                    if (dlg != null && dlg.isShowing()) dlg.dismiss();
                });
            }
        }, "MathHost");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (!AssetBridge.owns(u)) return null;
                return AssetBridge.serve(act, u);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (AssetBridge.owns(u) || u.startsWith("about:")) return false;
                // 页面里没有任何外链，真出现了也按外链处理，
                // 不让 WebView 变成浏览器（也是安全底线）
                try {
                    act.startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, Uri.parse(u)));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                if (loading != null) loading.setVisibility(View.GONE);
                web.setVisibility(View.VISIBLE);
            }
        });

        web.setVisibility(View.INVISIBLE);
        box.addView(web, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loading = new android.widget.ProgressBar(act);
        box.addView(loading, new android.widget.FrameLayout.LayoutParams(
                dp(36), dp(36), Gravity.CENTER));

        web.loadUrl(AssetBridge.MATH_ENTRY);
        dlg.setContentView(root);
        dlg.show();
    }
}
