package com.zsb.plan;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * 云同步界面 —— 一个全屏 Dialog：填 token、填 gist id、两个按钮、一行状态。
 *
 * 为什么不直接吃 Store：
 *   同步界面只需要「要一份本地 JSON」和「把一份 JSON 写回去」这两件事。
 *   定义 LocalStore 两个方法让主界面注入，SyncView 就完全不认识 Store 的内部结构；
 *   以后进度表结构再改，这里一行都不用动。
 *
 * 用法（主界面里加这几行即可）：
 *   new SyncView(this, new SyncView.LocalStore() {
 *       @Override public String exportJson() { return store.exportSnapshot(); }
 *       @Override public void importJson(String json) {
 *           try { store.importJson(json); } catch (Exception ignored) { }
 *           store.flush();
 *           render();
 *       }
 *   }).show();
 *
 * 安全约定：
 *   · token 只存 SharedPreferences("zsb_sync")，不进 SQLite、不进日志、不上报
 *   · 输入框是密码型，屏幕上不回显
 */
public class SyncView {

    // ── 本地进度的两个注入点 ──────────────────────────────
    public interface LocalStore {
        /** 导出当前本地进度为 JSON 字符串（与 StateLogic.exportJson 同构）。 */
        String exportJson();
        /** 用给定 JSON 覆盖本地进度。实现方负责异常兜底。 */
        void importJson(String json);
    }

    /** 同步结束后的可选回调，主界面要刷新时可以接。 */
    public interface OnDone {
        void onDone(boolean changedLocally);
    }

    private static final int C_BG = 0xFF14161C;
    private static final int C_BG2 = 0xFF1C1F27;
    private static final int C_BG3 = 0xFF262A34;
    private static final int C_FG = 0xFFE8EAF0;
    private static final int C_FG2 = 0xFFC3C9D6;
    private static final int C_FG3 = 0xFF6B7282;
    private static final int C_TEAL = 0xFF2FA8A0;
    private static final int C_RED = 0xFFE05A54;
    private static final int C_AMBER = 0xFFE0A94A;

    private static final String PREF = "zsb_sync";
    private static final String K_TOKEN = "token";
    private static final String K_GIST = "gist_id";

    private final Activity act;
    private final LocalStore store;
    private final SharedPreferences sp;

    private Dialog dlg;
    private EditText etToken;
    private EditText etGist;
    private TextView tvStatus;
    private TextView btnPush;
    private TextView btnPull;
    private OnDone onDone;

    private boolean busy;
    /** 本次操作是否真的改写了本地进度，用于回调主界面刷新。 */
    private boolean localChanged;

    public SyncView(Activity a, LocalStore store) {
        this.act = a;
        this.store = store;
        this.sp = a.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 需要主界面在同步写回本地后刷新 UI 时接这个。 */
    public void setOnDone(OnDone cb) {
        this.onDone = cb;
    }

    // ── 界面 ──────────────────────────────────────────────
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }

    private GradientDrawable round(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(12));
        return g;
    }

    private GradientDrawable round(int fill, int strokeColor, float stroke) {
        GradientDrawable g = round(fill);
        g.setStroke(dp(stroke), strokeColor);
        return g;
    }

    /** 打开同步面板。重复调用只会把已有面板提到前面。 */
    public void show() {
        if (dlg != null && dlg.isShowing()) {
            dlg.show();
            return;
        }
        build();
        loadPrefs();
        dlg.show();
    }

    public boolean isShowing() {
        return dlg != null && dlg.isShowing();
    }

    private void build() {
        dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // 顶栏：关闭 ✕ + 标题
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(12), dp(14), dp(8));

        TextView close = new TextView(act);
        close.setText("✕");
        close.setTextSize(22);
        close.setTextColor(C_FG2);
        close.setPadding(dp(6), dp(4), dp(14), dp(4));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        top.addView(close);

        TextView title = new TextView(act);
        title.setText("云同步");
        title.setTextSize(17);
        title.setTextColor(C_FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(top);

        // 内容区（可滚动，键盘弹起时不被挤掉）
        ScrollView scroller = new ScrollView(act);
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(4), dp(18), dp(18));

        TextView hint = new TextView(act);
        hint.setText("进度存进你自己 GitHub 账号的一个私有 Gist。"
                + "Token 只保存在本机，不会上传到任何第三方服务器。");
        hint.setTextSize(13);
        hint.setTextColor(C_FG3);
        hint.setLineSpacing(dp(5), 1f);
        body.addView(hint);

        // Token
        body.addView(label("GitHub Token"));
        etToken = field();
        etToken.setHint("粘贴 GitHub Token（需 gist 权限）");
        // 密码型文本：屏幕上不回显 token。
        // 注意 Android 里没有 TYPE_TEXT_VARIABLE_PASSWORD，正确常量是 VARIATION。
        etToken.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etToken.setTypeface(Typeface.MONOSPACE);
        body.addView(etToken);

        // Gist ID
        body.addView(label("Gist ID"));
        etGist = field();
        etGist.setHint("首次留空，自动创建");
        etGist.setInputType(InputType.TYPE_CLASS_TEXT);
        etGist.setTypeface(Typeface.MONOSPACE);
        body.addView(etGist);

        // 按钮行
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(20), 0, 0);

        btnPush = button("上传到云端", C_TEAL, 0xFF10151A);
        btnPush.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doPush(); }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                0, dp(48), 1f);
        plp.setMargins(0, 0, dp(6), 0);
        row.addView(btnPush, plp);

        btnPull = button("从云端恢复", C_BG3, C_FG);
        btnPull.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doPull(); }
        });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                0, dp(48), 1f);
        qlp.setMargins(dp(6), 0, 0, 0);
        row.addView(btnPull, qlp);
        body.addView(row);

        // 状态
        tvStatus = new TextView(act);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(C_FG2);
        tvStatus.setLineSpacing(dp(5), 1f);
        tvStatus.setPadding(dp(2), dp(16), dp(2), dp(4));
        tvStatus.setText("填好 Token 后点「上传到云端」。");
        body.addView(tvStatus);

        // 底部关闭
        TextView btnClose = button("关闭", C_BG3, C_FG);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        clp.setMargins(0, dp(22), 0, 0);
        body.addView(btnClose, clp);

        scroller.addView(body);
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dlg.setContentView(root);
    }

    private TextView label(String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(C_FG3);
        t.setPadding(dp(2), dp(16), dp(2), dp(6));
        return t;
    }

    private EditText field() {
        EditText e = new EditText(act);
        e.setTextSize(14);
        e.setTextColor(C_FG);
        e.setHintTextColor(C_FG3);
        e.setSingleLine(true);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackground(round(C_BG2, C_BG3, 1));
        e.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private TextView button(String text, int fill, int fg) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextSize(15);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(fill));
        return b;
    }

    // ── 偏好存取 ──────────────────────────────────────────
    private void loadPrefs() {
        etToken.setText(sp.getString(K_TOKEN, ""));
        etGist.setText(sp.getString(K_GIST, ""));
    }

    private void savePrefs() {
        sp.edit()
                .putString(K_TOKEN, text(etToken).trim())
                .putString(K_GIST, text(etGist).trim())
                .apply();
    }

    private static String text(EditText e) {
        return e == null || e.getText() == null ? "" : e.getText().toString();
    }

    // ── 上传：拉 → 合并 → 推 → 写回本地 ───────────────────
    private void doPush() {
        if (busy) return;
        final String token = text(etToken).trim();
        final String gistId = text(etGist).trim();
        if (token.isEmpty()) {
            status(C_RED, "先填 GitHub Token");
            etToken.requestFocus();
            return;
        }
        if (store == null) {
            status(C_RED, "本地进度不可用，无法导出");
            return;
        }

        String localJson;
        try {
            localJson = store.exportJson();
        } catch (Throwable t) {
            status(C_RED, "导出本地进度失败：" + t.getClass().getSimpleName());
            return;
        }
        if (localJson == null || localJson.trim().isEmpty()) localJson = "{}";

        savePrefs();
        start("正在读取云端…");

        final GistSync gs = new GistSync(token, gistId);
        final String local = localJson;

        // 先拉云端：否则上传会直接盖掉另一台设备刚记的进度
        gs.pullAsync(new GistSync.Cb() {
            @Override public void ok(String remote) {
                if (remote != null && !remote.trim().isEmpty()) {
                    String merged = GistSync.merge(local, remote);
                    applyLocal(merged);
                    status(C_FG2, "云端已有进度，已合并后上传…");
                    pushMerged(gs, merged);
                } else {
                    status(C_FG2, gistId.isEmpty()
                            ? "首次同步，正在创建 Gist…" : "云端还没有进度文件，直接上传…");
                    pushMerged(gs, local);
                }
            }

            @Override public void fail(String msg) {
                // 读不到云端（比如 gist 被删 / 首次创建）时，退化成直接推本地
                status(C_AMBER, msg + "；改为直接上传本机进度…");
                pushMerged(gs, local);
            }
        });
    }

    private void pushMerged(GistSync gs, final String json) {
        gs.pushAsync(json, new GistSync.Cb() {
            @Override public void ok(String id) {
                if (id != null && !id.isEmpty()) {
                    etGist.setText(id);
                    sp.edit().putString(K_GIST, id).apply();
                }
                done(C_TEAL, "已上传到云端。Gist ID：" + (id == null ? "" : id));
            }

            @Override public void fail(String msg) {
                done(C_RED, "上传失败：" + msg);
            }
        });
    }

    // ── 恢复：拉 → 合并 → 写回本地 → 再推一次 ──────────────
    private void doPull() {
        if (busy) return;
        final String token = text(etToken).trim();
        final String gistId = text(etGist).trim();
        if (token.isEmpty()) {
            status(C_RED, "先填 GitHub Token");
            etToken.requestFocus();
            return;
        }
        if (gistId.isEmpty()) {
            status(C_RED, "先填 Gist ID，或用「上传到云端」自动创建一个");
            etGist.requestFocus();
            return;
        }

        savePrefs();
        start("正在从云端读取…");

        final GistSync gs = new GistSync(token, gistId);
        gs.pullAsync(new GistSync.Cb() {
            @Override public void ok(String remote) {
                if (remote == null || remote.trim().isEmpty()) {
                    done(C_AMBER, "云端还没有这个文件，先点「上传到云端」");
                    return;
                }
                String localJson;
                try {
                    localJson = store == null ? "{}" : store.exportJson();
                } catch (Throwable t) {
                    localJson = "{}";
                }
                // 恢复不是覆盖：两边的完成记录都保留，避免恢复把本机进度冲掉
                String merged = GistSync.merge(localJson, remote);
                boolean changed = applyLocal(merged);
                String sum = summary(merged);
                if (changed) {
                    status(C_FG2, "已写入本机，正在把合并结果回传云端…");
                    gs.pushAsync(merged, new GistSync.Cb() {
                        @Override public void ok(String id) {
                            if (id != null && !id.isEmpty()) {
                                etGist.setText(id);
                                sp.edit().putString(K_GIST, id).apply();
                            }
                            done(C_TEAL, "恢复完成。" + sum);
                        }

                        @Override public void fail(String msg) {
                            done(C_AMBER, "本机已恢复，但回传云端失败：" + msg);
                        }
                    });
                } else {
                    done(C_TEAL, "已恢复。" + sum);
                }
            }

            @Override public void fail(String msg) {
                done(C_RED, "读取失败：" + msg);
            }
        });
    }

    /**
     * 把 JSON 写回本地。返回 true 表示确实动了本地数据。
     * 实现方（主模型那侧）负责 flush 和刷新界面。
     */
    private boolean applyLocal(String json) {
        if (store == null || json == null || json.isEmpty()) return false;
        try {
            store.importJson(json);
            localChanged = true;
            return true;
        } catch (Throwable t) {
            status(C_RED, "写入本机失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 合并结果的人话摘要：完成几天、勾了几项。 */
    private static String summary(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject st = root.optJSONObject("state");
            if (st == null) st = root;
            int done = sizeOf(st.optJSONObject("done"));
            int days = sizeOf(st.optJSONObject("completed"));
            return "已勾选 " + done + " 项，已推进 " + days + " 天。";
        } catch (Throwable t) {
            return "";
        }
    }

    private static int sizeOf(JSONObject o) {
        if (o == null) return 0;
        int n = 0;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            it.next();
            n++;
        }
        return n;
    }

    // ── 状态与忙闲 ────────────────────────────────────────
    private void start(String msg) {
        busy = true;
        localChanged = false;
        setButtons(false);
        status(C_FG2, msg);
    }

    private void done(int color, String msg) {
        busy = false;
        setButtons(true);
        status(color, msg);
        if (onDone != null) {
            boolean changed = localChanged;
            try {
                onDone.onDone(changed);
            } catch (Throwable ignored) {
                // 主界面刷新失败不影响同步结果
            }
        }
    }

    private void setButtons(boolean enabled) {
        if (btnPush != null) {
            btnPush.setEnabled(enabled);
            btnPush.setAlpha(enabled ? 1f : 0.45f);
        }
        if (btnPull != null) {
            btnPull.setEnabled(enabled);
            btnPull.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private void status(int color, String msg) {
        if (tvStatus == null) return;
        tvStatus.setTextColor(color);
        tvStatus.setText(msg == null ? "" : msg);
    }

    private void dismiss() {
        savePrefs();
        if (dlg != null && dlg.isShowing()) dlg.dismiss();
        dlg = null;
    }
}
