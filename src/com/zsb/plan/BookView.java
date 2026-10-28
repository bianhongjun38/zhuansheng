package com.zsb.plan;

import android.app.Activity;
import java.net.HttpURLConnection;
import java.net.URL;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置教材阅读器 —— 文字优先，原图对照。
 *
 * 为什么这样设计：
 *  · 原书 PDF 字小、需要反复缩放拖动，手机上很难读
 *  · PDF 文字层直接提取是碎片（公式每个字符是独立 span）
 *  · 按坐标重排后能还原成可读正文（见 scripts/extract_text_v2.py）
 *
 * 所以：默认显示重排后的文字（可调字号、深浅），
 * 一键切「原图」看版式和图例，两者同页同步。
 */
public class BookView {

    private static final int C_BG = 0xFF14161C;
    private static final int C_BG2 = 0xFF1C1F27;
    private static final int C_BG3 = 0xFF262A34;
    private static final int C_FG = 0xFFE8EAF0;
    private static final int C_FG2 = 0xFFC3C9D6;
    private static final int C_FG3 = 0xFF6B7282;
    private static final int C_PURPLE = 0xFF7C6CF0;
    private static final int C_SEPIA = 0xFFF2E9D8;
    private static final int C_SEPIA_FG = 0xFF2A2418;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // 文字数据
    private Map<String, List<String>> textCache = new HashMap<>();
    private List<String> curPages = null;
    private String curBookId = "";

    // PDF 渲染（只在切到原图时用）
    private PdfRenderer renderer;
    private PdfRenderer.Page pgObj;
    private ParcelFileDescriptor pfd;

    private Dialog dlg;
    private TextView body;
    private TextView pageLabel;
    private ImageView img;
    private ScrollView scroller;
    private FrameLayout stage;
    private TextView modeBtn;
    private TextView sizeBtn;

    private int pageCount = 0;
    private int pageNow = 0;
    private boolean imageMode = false;
    private float fontSize = 16f;
    private boolean sepia = false;
    private Bitmap bmp;
    private boolean rendering = false;
    private int pendingRender = -1;
    private String bookTitle = "";

    public BookView(Activity a) {
        this.act = a;
    }

    /** 打开某本书某页（PDF 物理页，从 1 开始）。 */
    public void open(final String bookId, final String title, final int pdfPage) {
        this.bookTitle = title;
        this.curBookId = bookId;
        // 每本书从文字模式开始。不重置的话，上一本停在「原图」时
        // imageMode 仍是 true，而 buildDialog 把 img 设成 GONE、
        // 按钮写着「原图」—— 两处对不上，gotoPage 会走 renderPdf
        // 去画一个隐藏的 ImageView，界面一片空白。
        this.imageMode = false;

        buildDialog(title);
        pageLabel.setText("正在打开…");
        dlg.show();

        new Thread(new Runnable() {
            public void run() {
                String err = loadText(bookId);
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (fe != null) {
                            pageLabel.setText(fe);
                            return;
                        }
                        pageCount = curPages.size();
                        if (pageCount <= 0) {
                            pageLabel.setText("这本书没有内容");
                            return;
                        }
                        seekBar.setMax(Math.max(0, pageCount - 1));
                        gotoPage(pdfPage, false);
                    }
                });
            }
        }).start();
    }

    private SeekBar seekBar;

    // ── 载入文字 ────────────────────────────────────────────
    private String loadText(String id) {
        List<String> cached = textCache.get(id);
        if (cached != null) {
            curPages = cached;
            return null;
        }
        try {
            InputStream in = act.getAssets().open("text/" + id + ".json");
            byte[] buf = new byte[64 * 1024];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            in.close();
            JSONObject o = new JSONObject(sb.toString());
            JSONArray arr = o.getJSONArray("pages");
            List<String> list = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getJSONObject(i).optString("t", ""));
            }
            textCache.put(id, list);
            curPages = list;
            return null;
        } catch (Exception e) {
            return "文字载入失败：" + e.getClass().getSimpleName();
        }
    }

    // ── 界面 ───────────────────────────────────────────────
    private void buildDialog(String title) {
        dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // 顶栏
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(7), dp(8), dp(7));
        top.setBackgroundColor(C_BG2);

        TextView close = new TextView(act);
        close.setText("✕");
        close.setTextSize(17);
        close.setTextColor(C_FG2);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(11), dp(5), dp(11), dp(5));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dismiss(); }
        });
        top.addView(close);

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextColor(C_FG);
        t.setTextSize(13.5f);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(tlp);
        top.addView(t);

        modeBtn = miniBtn("原图", new Runnable() {
            public void run() { switchMode(); }
        });
        top.addView(modeBtn);

        sizeBtn = miniBtn("A", new Runnable() {
            public void run() { cycleFont(); }
        });
        top.addView(sizeBtn);

        root.addView(top);

        // 页码
        pageLabel = new TextView(act);
        pageLabel.setTextColor(C_FG3);
        pageLabel.setTextSize(11.5f);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setPadding(0, dp(5), 0, dp(5));
        pageLabel.setBackgroundColor(C_BG2);
        root.addView(pageLabel);

        // 内容区
        stage = new FrameLayout(act);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        stage.setLayoutParams(slp);

        scroller = new ScrollView(act);
        scroller.setBackgroundColor(C_BG);
        scroller.setFillViewport(true);

        body = new TextView(act);
        body.setTextColor(C_FG2);
        body.setTextSize(fontSize);
        body.setLineSpacing(dp(6), 1f);
        body.setPadding(dp(16), dp(14), dp(16), dp(40));
        scroller.addView(body);
        stage.addView(scroller, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        img = new ImageView(act);
        img.setAdjustViewBounds(true);
        img.setScaleType(ImageView.ScaleType.FIT_START);
        img.setVisibility(View.GONE);
        stage.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 左右热区翻页
        View left = new View(act);
        left.setLayoutParams(new FrameLayout.LayoutParams(dp(58),
                ViewGroup.LayoutParams.MATCH_PARENT));
        left.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { step(-1); }
        });
        stage.addView(left);

        View right = new View(act);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(58),
                ViewGroup.LayoutParams.MATCH_PARENT);
        rlp.gravity = Gravity.END;
        right.setLayoutParams(rlp);
        right.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { step(1); }
        });
        stage.addView(right);

        root.addView(stage);

        // 底部
        LinearLayout bottom = new LinearLayout(act);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(8), dp(5), dp(8), dp(5));
        bottom.setBackgroundColor(C_BG2);

        bottom.addView(miniBtn("‹", new Runnable() {
            public void run() { step(-1); }
        }));

        seekBar = new SeekBar(act);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = dp(6);
        blp.rightMargin = dp(6);
        seekBar.setLayoutParams(blp);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) gotoPage(p + 1, true);
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        bottom.addView(seekBar);

        bottom.addView(miniBtn("›", new Runnable() {
            public void run() { step(1); }
        }));

        root.addView(bottom);

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            public void onDismiss(android.content.DialogInterface d) { close(); }
        });
    }

    private TextView miniBtn(String s, final Runnable r) {
        TextView b = new TextView(act);
        b.setText(s);
        b.setTextSize(12.5f);
        b.setTextColor(C_FG2);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(7));
        g.setColor(C_BG3);
        b.setBackground(g);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    // ── 交互 ───────────────────────────────────────────────
    private void cycleFont() {
        fontSize += 2;
        if (fontSize > 24) fontSize = 14;
        body.setTextSize(fontSize);
        sizeBtn.setText(fontSize >= 22 ? "A+" : (fontSize <= 14 ? "A-" : "A"));
    }

    private void switchMode() {
        imageMode = !imageMode;
        modeBtn.setText(imageMode ? "文字" : "原图");
        if (imageMode) {
            scroller.setVisibility(View.GONE);
            img.setVisibility(View.VISIBLE);
            renderPdf(pageNow);
        } else {
            // 切回文字模式必须**隐藏** img。img 加在 scroller 之后，
            // 留着 VISIBLE 会整块盖在文字上面，用户看到的是原图压着文字。
            img.setVisibility(View.GONE);
            scroller.setVisibility(View.VISIBLE);
            showText(pageNow);
        }
    }

    private void step(int d) {
        gotoPage(pageNow + d, true);
    }

    private void gotoPage(int p, boolean scrollTop) {
        if (pageCount <= 0) return;
        p = Math.max(1, Math.min(pageCount, p));
        pageNow = p;
        if (seekBar != null) seekBar.setProgress(p - 1);
        if (scrollTop && scroller != null) scroller.scrollTo(0, 0);
        if (imageMode) renderPdf(p);
        else showText(p);
    }

    private void showText(int p) {
        if (curPages == null || p < 1 || p > curPages.size()) return;
        String t = curPages.get(p - 1);
        if (t == null || t.trim().isEmpty()) {
            body.setText("（本页无可提取文字，点右上「原图」查看）");
            body.setTextColor(C_FG3);
        } else {
            body.setTextColor(sepia ? C_SEPIA_FG : C_FG2);
            // 标题行（很短且不带标点）加粗放大，方便扫读
            body.setText(buildStyled(t));
        }
        pageLabel.setText(bookTitle + "　·　第 " + pageNow + " / " + pageCount + " 页");
    }

    /** 把明显是标题的行加粗，提升扫读性。 */
    private CharSequence buildStyled(String raw) {
        String[] lines = raw.split("\n");
        SpannableString ss = new SpannableString(raw);
        return raw;  // 先保持纯文本，避免 span 偏移算错
    }

    // ── PDF 原图渲染 ───────────────────────────────────────
    private void renderPdf(final int page) {
        if (rendering) { pendingRender = page; return; }
        rendering = true;
        new Thread(new Runnable() {
            public void run() {
                Bitmap out = null;
                String err = null;
                try {
                    ensureRenderer();
                    synchronized (BookView.this) {
                        if (pgObj != null) { pgObj.close(); pgObj = null; }
                        pgObj = renderer.openPage(Math.max(0, page - 1));
                        int w = act.getResources().getDisplayMetrics().widthPixels;
                        float ratio = pgObj.getHeight() / (float) pgObj.getWidth();
                        int h = Math.max(1, Math.round(w * ratio));
                        out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        out.eraseColor(Color.WHITE);
                        pgObj.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                } catch (Exception e) {
                    err = e.getClass().getSimpleName();
                }
                final Bitmap fb = out;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        rendering = false;
                        if (fe != null) {
                            pageLabel.setText("原图渲染失败：" + fe);
                        } else if (fb != null) {
                            Bitmap old = bmp;
                            bmp = fb;
                            img.setImageBitmap(fb);
                            if (old != null && !old.isRecycled()) old.recycle();
                            pageLabel.setText(bookTitle + "　·　第 " + pageNow
                                    + " / " + pageCount + " 页（原图）");
                        }
                        if (pendingRender > 0 && pendingRender != pageNow) {
                            int nx = pendingRender;
                            pendingRender = -1;
                            renderPdf(nx);
                        }
                    }
                });
            }
        }).start();
    }

    private void ensureRenderer() throws Exception {
        if (renderer != null) return;
        File cache = new File(act.getCacheDir(), "books");
        if (!cache.exists()) cache.mkdirs();
        File f = new File(cache, curBookId + ".pdf");
        // 不能只看"文件存在且非空"。
        // 2026-10-28 审计：Cloudflare Pages 对不存在的路径返回
        // HTTP 200 + index.html（1108 字节 text/html），不是 404。
        // fetchBook() 只判 code != 200，于是那个 HTML 被当成 PDF 落盘，
        // 之后 PdfRenderer 必然抛 IOException；而 ensureRenderer 又因为
        // f.length() == 1108 > 0 永远跳过重新下载 —— 除非用户手动清缓存，
        // 这本教材的「原图」永久坏掉。
        if (!looksLikePdf(f)) {
            if (f.exists()) f.delete();
            // 三本教材 PDF 共 11.7 MB，占安装包 60%。为了不顶到
            // Cloudflare 两条 25 MiB 单文件上限（Pages 和 Workers
            // Static Assets 都是 25 MiB，实测文档确认），改成按需下载：
            // 装包时只带代码，第一次点「原图」再从 dl 域名拉这一本。
            if (!fetchBook(f)) {
                throw new java.io.FileNotFoundException(
                        "教材尚未下载，请连网后重试");
            }
        }
        try {
            pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
        } catch (Exception e) {
            // 打不开就说明这份缓存是坏的（多半是上面那类 HTML 假包，
            // 或下载被截断）。立刻删掉，否则下次还会拿它初始化失败。
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) { }
                pfd = null;
            }
            try { f.delete(); } catch (Exception ignored) { }
            throw e;
        }
    }

    /** 是不是一份真 PDF：%PDF- 魔数 + 至少 1 KB。 */
    private static boolean looksLikePdf(File f) {
        if (f == null || !f.exists() || f.length() < 1024) return false;
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] h = new byte[5];
            int n = in.read(h);
            return n == 5 && h[0] == 0x25 && h[1] == 0x50
                    && h[2] == 0x44 && h[3] == 0x46 && h[4] == 0x2D;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 下载一本教材到缓存。成功返回 true。
     *
     * 顺序：先看安装包里有没有（老版本兼容 + 离线可用），
     * 没有再走 Update.BOOK_BASE（Cloudflare Pages 上的 app 域名）拉。
     * 下载写 .part 再 rename —— 中途断网不会留下半份文件，
     * 否则下次 PdfRenderer 打开会直接崩。
     */
    private boolean fetchBook(File dst) {
        // ① 安装包里带的话直接用（有人覆盖安装旧包时仍能离线看）
        try {
            InputStream in = act.getAssets().open("books/" + curBookId + ".pdf");
            copyTo(in, dst);
            return true;
        } catch (Exception notInApk) {
            // 正常情况：新版本安装包里已经没有 PDF 了
        }

        // ② 从更新通道下载
        File tmp = new File(dst.getAbsolutePath() + ".part");
        HttpURLConnection c = null;
        try {
            URL u = new URL(Update.BOOK_BASE + curBookId + ".pdf");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) return false;
            copyTo(c.getInputStream(), tmp);
            // 长度对得上 + 真的是 PDF，才算下载成功。
            // Pages 对缺失文件回的是 200 + index.html，不校验内容的话
            // 那个 HTML 会被当教材存下来，而且因为有 .part 中转，
            // rename 之后看起来"下载成功了"，坏得很隐蔽。
            int expect = c.getContentLength();
            if ((expect > 0 && tmp.length() != expect) || !looksLikePdf(tmp)) {
                tmp.delete();
                return false;
            }
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                return false;
            }
            return true;
        } catch (Exception e) {
            tmp.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void copyTo(InputStream in, File dst) throws Exception {
        OutputStream o = new FileOutputStream(dst);
        try {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        } finally {
            try { o.close(); } catch (Exception ignored) { }
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    private void close() {
        try {
            synchronized (this) {
                if (pgObj != null) { pgObj.close(); pgObj = null; }
                if (renderer != null) { renderer.close(); renderer = null; }
                if (pfd != null) { pfd.close(); pfd = null; }
            }
        } catch (Exception ignored) {
        }
        if (bmp != null && !bmp.isRecycled()) bmp.recycle();
        bmp = null;
    }

    private void dismiss() {
        if (dlg != null && dlg.isShowing()) dlg.dismiss();
        dlg = null;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }
}