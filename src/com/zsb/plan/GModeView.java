package com.zsb.plan;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 词汇塔模式主界面：地图 + 状态 + 进入战斗。
 *
 * 地图做成「一条路线一张卡」而不是画节点图，原因很实际：
 * 手机上画带分支的节点图要么太挤点不准，要么得引第三方控件；
 * 而 4 条路线、每条 10~12 个节点，用列表反而一眼能看完全局。
 */
public class GModeView extends LinearLayout {

    private static final int C_BG     = 0xFF14161C;
    private static final int C_BG2    = 0xFF1C1F27;
    private static final int C_BG3    = 0xFF262A34;
    private static final int C_LINE   = 0xFF2C313C;
    private static final int C_FG     = 0xFFE8EAF0;
    private static final int C_FG2    = 0xFFA8AEBC;
    private static final int C_FG3    = 0xFF6B7282;
    private static final int C_PURPLE = 0xFF7C6CF0;
    private static final int C_TEAL   = 0xFF2FA8A0;
    private static final int C_ORANGE = 0xFFE0873F;
    private static final int C_GREEN  = 0xFF4BBF8A;
    private static final int C_RED    = 0xFFE05B5B;
    private static final int C_AMBER  = 0xFFE0A94A;

    private final GStore store;
    private final GameData data;
    private final Random rnd = new Random();

    private LinearLayout body;
    private android.widget.ScrollView scroller;
    private GBattleView battleView;
    private Runnable onExit;

    public GModeView(Context c, GStore s) {
        super(c);
        store = s;
        data = GameData.get(c);
        setOrientation(VERTICAL);
        setBackgroundColor(C_BG);
        build();
    }

    public void setOnExit(Runnable r) { onExit = r; }

    private void build() {
        removeAllViews();

        // 顶栏
        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(12), dp(14), dp(8));

        TextView back = tv("← 返回", 13, C_FG3);
        back.setPadding(0, dp(4), dp(12), dp(4));
        back.setOnClickListener(v -> { if (onExit != null) onExit.run(); });
        head.addView(back);

        TextView title = tv("词汇塔", 17, C_FG);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);

        TextView info = tv("", 12, C_AMBER);
        info.setId(android.R.id.text1);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        info.setGravity(Gravity.RIGHT);
        info.setLayoutParams(ip);
        head.addView(info);
        addView(head);

        View line = new View(getContext());
        line.setBackgroundColor(C_LINE);
        addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        body = new LinearLayout(getContext());
        body.setOrientation(VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(24));
        scroller = new android.widget.ScrollView(getContext());
        scroller.setFillViewport(true);
        scroller.addView(body);
        scroller.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(scroller);
    }

    /** 从外部进入时调用，决定显示地图还是直接战斗。 */
    public void show() {
        if (battleView != null) { removeView(battleView); battleView = null; }
        if (scroller != null) scroller.setVisibility(View.VISIBLE);
        renderMap();
    }

    private void updateHead() {
        TextView info = findViewById(android.R.id.text1);
        if (info != null) info.setText("✦ " + store.gold() + "    特殊词 "
                + store.specialCount() + "    Lv." + (store.bestFloor() + 1));
    }

    // ── 地图 ─────────────────────────────────────────────

    private void renderMap() {
        body.removeAllViews();
        updateHead();

        JSONArray route = store.route();
        if (route.length() == 0) {
            // 首次进入：提示 + 开始
            LinearLayout intro = box();
            intro.addView(title("开始一局"));
            intro.addView(sub("4 条路线，每条 10~12 个节点。至少一条路线没有精英，" +
                    "但商店也更少 —— 想稳就拿少点资源，想赌就去打精英。"));
            intro.addView(sub("每层至少 10 场战斗。精英会掉永久特殊词，死了就没了。"));
            intro.addView(bigBtn("生成地图", C_PURPLE, this::newMap));
            body.addView(intro);
            return;
        }

        int node = store.node();
        int layer = store.layer();

        LinearLayout hdr = box();
        hdr.addView(title("第 " + (layer + 1) + " 层 · 节点 " + (node + 1)));
        hdr.addView(sub("当前金币 " + store.gold() + "，累计答对 " + store.right()
                + " / 答错 " + store.wrong()));
        body.addView(hdr);

        for (int i = 0; i < route.length(); i++) {
            JSONArray r = route.optJSONArray(i);
            if (r == null) continue;
            body.addView(routeCard(i, r, node));
        }

        LinearLayout foot = box();
        foot.addView(smallBtn("重新生成地图（进度清零）", () -> {
            store.resetRun();
            renderMap();
        }));
        body.addView(foot);
    }

    private View routeCard(int ri, JSONArray r, int curNode) {
        LinearLayout box = box();

        LinearLayout h = new LinearLayout(getContext());
        h.setOrientation(HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = title("路线 " + (ri + 1));
        h.addView(t);
        TextView c = sub(r.length() + " 节点");
        c.setPadding(dp(8), 0, 0, 0);
        h.addView(c);
        box.addView(h);

        // 节点串
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < r.length(); i++) {
            final int idx = i;
            String raw = r.optString(i, "0");
            int type = parseInt(raw);
            TextView n = nodeChip(type, i == curNode);
            if (i == curNode) {
                n.setOnClickListener(v -> enterNode(ri, idx, type));
            }
            row.addView(n);
        }
        android.widget.HorizontalScrollView hsv = new android.widget.HorizontalScrollView(getContext());
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(row);
        box.addView(hsv);
        return box;
    }

    private TextView nodeChip(int type, boolean cur) {
        String icon;
        int color;
        switch (type) {
            case GameData.N_SHOP:  icon = "店"; color = C_AMBER; break;
            case GameData.N_CAMP:  icon = "火"; color = C_ORANGE; break;
            case GameData.N_CHEST: icon = "箱"; color = C_TEAL; break;
            case GameData.N_ELITE: icon = "精"; color = C_RED; break;
            default:               icon = "战"; color = C_FG2; break;
        }
        TextView t = tv(icon, 12, cur ? 0xFFFFFFFF : color);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(cur ? color : C_BG3);
        g.setStroke(dp(1), cur ? color : C_LINE);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private void newMap() {
        List<List<GameData.Node>> routes = GameData.makeMap(rnd);
        JSONArray arr = new JSONArray();
        for (List<GameData.Node> route : routes) {
            JSONArray one = new JSONArray();
            for (GameData.Node n : route) one.put(String.valueOf(n.type));
            arr.put(one);
        }
        store.setRoute(arr);
        store.setNode(0);
        renderMap();
    }

    // ── 进入节点 ─────────────────────────────────────────

    private void enterNode(int routeIdx, int nodeIdx, int type) {
        store.setNode(nodeIdx);

        if (type == GameData.N_BATTLE || type == GameData.N_ELITE
                || type == GameData.N_SHOP || type == GameData.N_CAMP
                || type == GameData.N_CHEST) {
            GameData.Enemy e = data.pickEnemy(type, store.layer(), rnd);
            startBattle(e);
            return;
        }
        renderMap();
    }

    private void startBattle(GameData.Enemy e) {
        body.removeAllViews();
        if (battleView != null) { removeView(battleView); battleView = null; }
        // 战斗时整块替换掉地图滚动区，否则两者抢高度、战斗界面被压扁
        if (scroller != null) scroller.setVisibility(View.GONE);
        battleView = new GBattleView(getContext(), store);
        battleView.setOnFinish(() -> {
            store.raiseFloor(store.layer());
            store.setNode(store.node() + 1);
            show();
        });
        battleView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(battleView);
        battleView.start(e);
    }

    // ── 控件 ─────────────────────────────────────────────

    private LinearLayout box() {
        LinearLayout b = new LinearLayout(getContext());
        b.setOrientation(VERTICAL);
        b.setBackground(round(C_BG2, 14, C_LINE));
        b.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(11);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView title(String s) {
        TextView t = tv(s, 15.5f, C_FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView sub(String s) {
        TextView t = tv(s, 12, C_FG3);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private TextView bigBtn(String s, int color, Runnable r) {
        TextView t = tv(s, 15, 0xFFFFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(11));
        g.setColor(color);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private TextView smallBtn(String s, Runnable r) {
        TextView t = tv(s, 12.5f, C_FG2);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(9));
        g.setColor(C_BG3);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private GradientDrawable round(int fill, float radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView tv(String s, float size, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}