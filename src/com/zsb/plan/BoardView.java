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
 * 词汇塔排行榜。今日 / 近 7 天两个口径。
 *
 * 排序口径：今日通关节点数 → 今日金币 → 正确率 → 首次上报时间。
 * 为什么主排序是「节点数」而不是「答对题数」：塔的玩法是往上推图，
 * 推进量才是这段游戏时间里真正发生的事。答对题数会被「反复刷第 1 层
 * 的简单怪」拉高，和爬到第几层没有关系 —— 拿它当主键，榜上会是一群
 * 卡在第一层但答了几百题的人。
 *
 * 显示真实用户名 —— 20 个同学一个班，本来就是互相看得见的。
 * 不做匿名化、不做脱敏：那套是为陌生人准备的，这里只会让人找不到自己。
 *
 * ⚠️ 入口是有登录门槛的：MainActivity 那里 `if (!acc.loggedIn()) toast` 拦了一道。
 * 服务端接口本身是公开的（和 eng/board 一致），但玩家必须先登录才能打开这个
 * 弹窗 —— 因为「（我）」高亮和后续补传都依赖账号。这里原先的注释写的是
 * 「不需要登录也能看」，和实际行为不符，已按代码实际行为改正。
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
        title.setText("词汇塔 · 排行");
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
            Api.R r = Api.gameBoard(want);
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
            tvStatus.setText("今天还没有人爬塔");
            TextView empty = new TextView(act);
            empty.setText("在「词汇塔」里打赢一场战斗就会出现在这里。\n"
                    + "还没有记录说明大家都还没开始，不是出错了。");
            empty.setTextSize(13);
            empty.setTextColor(C_FG3);
            empty.setLineSpacing(dp(4), 1f);
            empty.setPadding(dp(2), dp(16), dp(2), dp(4));
            listBox.addView(empty);
            return;
        }

        tvStatus.setTextColor(C_FG3);
        tvStatus.setText(("day".equals(range) ? day : "最近 7 天") + "　按通关节点数排");

        Account me = Account.load(act);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("username", "?");
            boolean isMe = me.loggedIn() && me.username.equals(name);
            listBox.addView(row(i + 1, name, o, isMe));
        }
    }

    private View row(int rank, String name, JSONObject o, boolean isMe) {
        int nodes = o.optInt("nodes", 0);
        int gold = o.optInt("gold", 0);
        int floor = o.optInt("floor", 0);
        int rate = o.optInt("rate", 0);
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

        TextView sub = new TextView(act);
        // 宽度是实打实的约束：名字列在 360dp 屏上只分到约 190dp，
        // 原来「N 轮 · 共 M 题 · 正确率 X%」就超出过 9px 把右边挤掉。
        // 这里改成三段短写：层（爬到哪）、金币（攒了多少）、正确率（打得准不准）。
        // 场次不放进来 —— 它和节点数量级接近，信息重复，留着只会挤掉别的东西。
        sub.setText("第" + (floor + 1) + "层 · " + gold + "金 · " + rate + "%");
        sub.setTextSize(11);
        sub.setTextColor(C_FG3);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sub.setPadding(0, dp(2), 0, 0);
        mid.addView(sub);

        row.addView(mid, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 主数字 = 今日通关节点数（排序主键，得让它最醒目）
        TextView sc = new TextView(act);
        sc.setText(String.valueOf(nodes));
        sc.setTextSize(17);
        sc.setTypeface(Typeface.DEFAULT_BOLD);
        sc.setTextColor(isMe ? C_TEAL : C_FG);
        row.addView(sc);

        return row;
    }
}