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
    private TextView tabDay, tabWeek, tvStatus, uploadBtn;
    private String range = "day";
    private boolean busy;
    private boolean uploading;

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

        // ── 主动上传 ──
        //
        // 排行榜不再自动上报，改由玩家自己点这里。
        // 按钮常驻而不是藏在菜单里：它是这个弹窗唯一能给榜上添数的入口，
        // 藏起来等于没人会用。
        uploadBtn = new TextView(act);
        uploadBtn.setText("↑ 上传我的进度");
        uploadBtn.setTextSize(14);
        uploadBtn.setTypeface(Typeface.DEFAULT_BOLD);
        uploadBtn.setTextColor(0xFF10131A);
        uploadBtn.setGravity(Gravity.CENTER);
        uploadBtn.setPadding(dp(10), dp(11), dp(10), dp(11));
        uploadBtn.setBackground(round(C_AMBER, 10));
        LinearLayout.LayoutParams ubLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ubLp.leftMargin = dp(18);
        ubLp.rightMargin = dp(18);
        ubLp.bottomMargin = dp(6);
        uploadBtn.setLayoutParams(ubLp);
        uploadBtn.setOnClickListener(v -> doUpload());
        root.addView(uploadBtn);

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

    // ── 主动上传 ──────────────────────────────────────────

    /**
     * 把这个弹窗背后那个存档的当前进度传给排行榜。
     *
     * 为什么不放在战斗界面里：排行榜是独立弹窗，可能在
     * 根本没在战斗的时候打开（比如刚进游戏就想传一下）。把上传逻辑
     * 放在这里，它只依赖 GStore，不依赖任何战斗界面还活着。
     *
     * 可以反复上传：服务端 /v1/game/snapshot 是「每人每天一条、覆盖更新」，
     * 传第二次只会把榜上的数字换成最新进度，不会累加。
     * 每次用新 rev —— 老 rev 会被服务端判 dup 静默丢弃（那是防断网重放的机制，
     * 不是「只能传一次」的限制）。
     */
    private void doUpload() {
        if (uploading) return;
        final Account acc = Account.load(act);
        if (!acc.loggedIn()) {
            tvStatus.setTextColor(C_RED);
            tvStatus.setText("请先登录账号，再上传进度");
            return;
        }
        final GStore st = new GStore(act);
        if (st.floorIndex() <= 0) {
            tvStatus.setTextColor(C_AMBER);
            tvStatus.setText("还没开始爬塔，没有可上传的进度");
            return;
        }

        uploading = true;
        uploadBtn.setText("正在上传…");
        uploadBtn.setAlpha(0.6f);
        tvStatus.setTextColor(C_FG3);
        tvStatus.setText("");

        final String rev = st.newUploadRev();
        final int floor = st.floorIndex();
        final int mapNo = st.layer();
        final int gold = st.gold();
        final int right = st.right();
        final int wrong = st.wrong();

        new Thread(() -> {
            Api.R r = Api.postSnapshot(acc.token, floor, mapNo, gold, right, wrong, rev);
            act.runOnUiThread(() -> {
                uploading = false;
                uploadBtn.setText("↑ 上传我的进度");
                uploadBtn.setAlpha(1f);
                if (r.ok) {
                    st.markUploaded();
                    tvStatus.setTextColor(C_TEAL);
                    tvStatus.setText("✓ 已上传：第 " + (floor + 1) + " 层 · 第 "
                            + (mapNo + 1) + " 张图 · ✦" + gold);
                    load();       // 立刻刷新，让玩家看到自己出现在榜上
                } else if (r.body != null && r.body.optBoolean("ignored")) {
                    st.markUploaded();
                    tvStatus.setTextColor(C_AMBER);
                    tvStatus.setText("服务端没有收录："
                            + r.body.optString("message", "条件不满足"));
                } else if (r.code == 401) {
                    // token 对不上。光弹一句「登录过期」没用 —— 玩家不知道
                    // 该去哪儿、点了之后这一下又要重来。
                    // 直接清掉本地作废的凭证并拉起登录框，登完自动继续上传。
                    tvStatus.setTextColor(C_RED);
                    tvStatus.setText("登录状态失效，正在重新登录…");
                    askRelogin();
                } else if (r.code == 0) {
                    tvStatus.setTextColor(C_RED);
                    tvStatus.setText("连不上服务器，稍后再试");
                } else {
                    tvStatus.setTextColor(C_RED);
                    tvStatus.setText("上传失败：" + (r.msg.isEmpty()
                            ? ("HTTP " + r.code) : r.msg));
                }
            });
        }).start();
    }

    /**
     * 凭证失效时：清掉本地作废的 token，拉起登录框，登完自动接着上传。
     *
     * 为什么必须自动续上：玩家点「上传」的意图是「把我现在的进度传上去」，
     * 不是「我要登录」。让他登完再自己找一遍按钮，等于这一下白点。
     */
    private void askRelogin() {
        Account.clear(act);          // 旧的已经作废，留着只会一直 401
        LoginView lv = new LoginView(act);
        lv.setOnLoggedIn(a -> {
            tvStatus.setTextColor(C_TEAL);
            tvStatus.setText("已重新登录：" + a.username + "，正在上传…");
            doUpload();
        });
        lv.show();
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
            empty.setText("点上面的「↑ 上传我的进度」就会出现在这里。\n"
                    + "排行榜改成玩家主动上传，不会再自动记录。\n"
                    + "还没有记录说明大家都还没传，不是出错了。");
            empty.setTextSize(13);
            empty.setTextColor(C_FG3);
            empty.setLineSpacing(dp(4), 1f);
            empty.setPadding(dp(2), dp(16), dp(2), dp(4));
            listBox.addView(empty);
            return;
        }

        tvStatus.setTextColor(C_FG3);
        // 顺手告诉玩家「榜上这份是不是我现在的进度」——
        // 排行榜改成主动上传之后，最容易出现的困惑就是
        // 「我又爬了两层，榜上怎么还是旧的」。答案永远是：需要再点一次上传。
        String when = "";
        long lastAt = new GStore(act).lastUploadAt();
        if (lastAt > 0) {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA);
            when = "　· 我上次上传 " + f.format(new java.util.Date(lastAt));
        }
        tvStatus.setText(("day".equals(range) ? day : "最近 7 天")
                + "　按到达层数排" + when);

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
        // 层数是 floorIndex 口径（第 1 层起），node 现在存的是「第几张地图」。
        //
        // 四段全写（层·图·金·正确率）实测 356px，而名字列只有约 206px，
        // 真机上会被省略号吃掉一半，反而什么都看不清。
        // 所以地图序号只在超过第 1 张时才出现 —— 那是信息量真正增加的时刻，
        // 前 8 层的人不会因为「第1图」这三个字挤掉正确率。
        //
        // 我一度以为四段放不下（356px > 206px），于是按需隐藏地图序号。
        // 后来发现那条测量用错了字号：明细行是 11sp，审计却拿 22pt 去量。
        // 换成真实字号后四段最坏情况只有 137px，余量充足 ——
        // 那就没必要藏信息，地图序号始终显示。
        // 新快照接口返回 map（第几张地图，0 基），不再有 node。
        // 兼容旧字段 node 是没意义的 —— 旧接口已废弃，没有历史数据要读。
        int mapNo = o.optInt("map", 0) + 1;
        // 排行榜上不写 Lv.，直接写层数 —— 等级和层数是同一个数，
        // 榜单要传达的是「爬到哪了」，用层数最直白。
        sub.setText("第" + (floor + 1) + "层 · 图" + mapNo
                + " · ✦" + gold + " · " + rate + "%");
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