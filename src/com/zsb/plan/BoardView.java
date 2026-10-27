package com.zsb.plan;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 英语闯关排行榜。今日 / 近 7 天两个口径。
 *
 * 显示真实用户名 —— 20 个同学一个班，本来就是互相看得见的。
 * 不做匿名化、不做脱敏：那套是为陌生人准备的，这里只会让人找不到自己。
 *
 * 排行榜不需要登录也能看（路过瞄一眼谁在学），但自己的名次要高亮。
 */
public class BoardView {

    private static final int C_BG = 0xFF14161C;
    private static final int C_BG2 = 0xFF1C1F27;
    private static final int C_FG = 0xFFE8EAF0;
    private static final int C_FG2 = 0xFFC3C9D6;
    private static final int C_FG3 = 0xFF6B7282;
    private static final int C_TEAL = 0xFF3ECFAD;
    private static final int C_AMBER = 0xFFE0A94A;
    private static final int C_RED = 0xFFE05A54;

    private final Activity act;
    private Dialog dlg;
    private LinearLayout listBox;
    private TextView tabDay, tabWeek, tvStatus;
    private String range = "day";
    private boolean busy;

    public BoardView(Activity a) {
        this.act = a;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }

    private GradientDrawable round(int fill, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public void show() {
        if (dlg != null && dlg.isShowing()) { dlg.show(); return; }
        build();
        dlg.show();
        load();
    }

    private void build() {
        dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(12), dp(14), dp(8));

        TextView close = new TextView(act);
        close.setText("✕");
        close.setTextSize(22);
        close.setTextColor(C_FG2);
        close.setPadding(dp(6), dp(4), dp(14), dp(4));
        close.setOnClickListener(v -> dlg.dismiss());
        top.addView(close);

        TextView title = new TextView(act);
        title.setText("英语闯关榜");
        title.setTextSize(17);
        title.setTextColor(C_FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top);

        // 两个口径的切换
        LinearLayout tabs = new LinearLayout(act);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(18), dp(2), dp(18), dp(6));

        tabDay = tab("今日");
        tabDay.setOnClickListener(v -> { range = "day"; paintTabs(); load(); });
        tabs.addView(tabDay, tabLp(0));

        tabWeek = tab("近 7 天");
        tabWeek.setOnClickListener(v -> { range = "week"; paintTabs(); load(); });
        tabs.addView(tabWeek, tabLp(8));
        root.addView(tabs);
        paintTabs();

        tvStatus = new TextView(act);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(C_FG3);
        tvStatus.setPadding(dp(18), dp(6), dp(18), dp(6));
        root.addView(tvStatus);

        ScrollView sc = new ScrollView(act);
        listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(18), dp(4), dp(18), dp(24));
        sc.addView(listBox);
        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dlg.setContentView(root);
    }

    private LinearLayout.LayoutParams tabLp(int leftDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.leftMargin = dp(leftDp);
        return p;
    }

    private TextView tab(String s) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(10), dp(10), dp(10));
        return t;
    }

    private void paintTabs() {
        boolean d = "day".equals(range);
        tabDay.setTextColor(d ? 0xFF10131A : C_FG2);
        tabDay.setTypeface(d ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tabDay.setBackground(d ? round(C_TEAL, 10) : round(C_BG2, 10));
        tabWeek.setTextColor(d ? C_FG2 : 0xFF10131A);
        tabWeek.setTypeface(d ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        tabWeek.setBackground(d ? round(C_BG2, 10) : round(C_TEAL, 10));
    }

    // ── 拉数据 ────────────────────────────────────────────

    private void load() {
        if (busy) return;
        busy = true;
        tvStatus.setTextColor(C_FG3);
        tvStatus.setText("正在加载…");
        final String want = range;
        new Thread(() -> {
            Api.R r = Api.board(want);
            act.runOnUiThread(() -> {
                busy = false;
                if (!want.equals(range)) return;   // 用户切走了，别用旧数据覆盖
                if (!r.ok) {
                    tvStatus.setTextColor(C_RED);
                    tvStatus.setText(r.msg.isEmpty() ? "加载失败" : r.msg);
                    listBox.removeAllViews();
                    return;
                }
                render(r.body);
            });
        }).start();
    }

    private void render(JSONObject body) {
        listBox.removeAllViews();
        JSONArray arr = body.optJSONArray("board");
        String day = body.optString("day", "");

        if (arr == null || arr.length() == 0) {
            tvStatus.setTextColor(C_FG3);
            tvStatus.setText("今天还没有人交卷");
            TextView empty = new TextView(act);
            empty.setText("做完一轮英语训练就会出现在这里。\n"
                    + "还没有记录说明大家都还没开始，不是出错了。");
            empty.setTextSize(13);
            empty.setTextColor(C_FG3);
            empty.setLineSpacing(dp(4), 1f);
            empty.setPadding(dp(2), dp(16), dp(2), dp(4));
            listBox.addView(empty);
            return;
        }

        tvStatus.setTextColor(C_FG3);
        tvStatus.setText("day".equals(range) ? (day + "　按答对题数排") : "最近 7 天　按答对题数排");

        Account me = Account.load(act);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("username", "?");
            int score = o.optInt("score", 0);
            int total = o.optInt("total", 0);
            int rounds = o.optInt("rounds", 0);
            boolean isMe = me.loggedIn() && me.username.equals(name);
            listBox.addView(row(i + 1, name, score, total, rounds, isMe));
        }
    }

    private View row(int rank, String name, int score, int total, int rounds, boolean isMe) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(round(isMe ? 0xFF20323A : C_BG2, 12));
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        row.setLayoutParams(rp);

        // 名次。前三名给颜色，其余用灰。
        TextView rk = new TextView(act);
        rk.setText(String.valueOf(rank));
        rk.setTextSize(15);
        rk.setGravity(Gravity.CENTER);
        rk.setTypeface(Typeface.DEFAULT_BOLD);
        rk.setTextColor(rank == 1 ? C_AMBER : rank == 2 ? C_FG2 : rank == 3 ? 0xFFB08968 : C_FG3);
        row.addView(rk, new LinearLayout.LayoutParams(dp(26),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 名字 + 明细
        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);

        TextView nm = new TextView(act);
        nm.setText(isMe ? (name + "（我）") : name);
        nm.setTextSize(14);
        // 用户名最长 20 字，实机 360dp 屏上加上分数列放不下。
        // 用 TextView 原生截断（省略号）而不是自己量宽度：
        // Android 的字宽和 AWT 不一致，手算的阈值在真机上一定偏。
        nm.setSingleLine(true);
         nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        
        nm.setTextColor(isMe ? C_TEAL : C_FG);
        nm.setTypeface(isMe ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        mid.addView(nm);

        int rate = total > 0 ? score * 100 / total : 0;
        TextView sub = new TextView(act);
        // 原本写「N 轮 · 共 M 题 · 正确率 X%」，实测这一行比名字列宽出 9px，
        // 会把右边的分数挤掉。改成短写法，语义没丢。
        sub.setText(rounds + " 轮 · " + total + " 题 · " + rate + "%");
        sub.setTextSize(11);
        sub.setTextColor(C_FG3);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setPadding(0, dp(2), 0, 0);
        mid.addView(sub);

        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 分数
        TextView sc = new TextView(act);
        sc.setText(String.valueOf(score));
        sc.setTextSize(17);
        sc.setTypeface(Typeface.DEFAULT_BOLD);
        sc.setTextColor(isMe ? C_TEAL : C_FG);
        row.addView(sc);

        return row;
    }
}