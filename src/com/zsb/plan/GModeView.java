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

    /**
     * 从外部进入时调用。
     *
     * 关键：**战斗中切到别的 tab 再切回来，必须还在战斗里。**
     * 原来的实现无条件 removeView(battleView) + renderMap()，
     * 于是玩家去「错题库」看一眼再回来，整场战斗就没了，
     * 地图回到起点 —— 因为 renderMap() 只读 store 的 node，
     * 而战斗进度（血量/手牌/回合）只存在 GBattleView 实例里，
     * 实例被丢掉就全丢了。
     *
     * 现在：battleView 还活着且没结束，就原样保留；
     * 只有战斗真正结束（onFinish 把 battleView 置 null）才回地图。
     */
    public void show() {
        if (battleView != null) {
            // 战斗中：保持战斗界面，只把地图滚动区藏起来
            if (scroller != null) scroller.setVisibility(View.GONE);
            battleView.setVisibility(View.VISIBLE);
            updateHead();
            return;
        }
        if (scroller != null) scroller.setVisibility(View.VISIBLE);
        renderMap();
    }

    /** 战斗结束（胜/负都走这里），清掉战斗视图回到地图。 */
    private void endBattle() {
        if (battleView != null) {
            removeView(battleView);
            battleView = null;
        }
        if (scroller != null) scroller.setVisibility(View.VISIBLE);
        renderMap();
    }

    private void updateHead() {
        TextView info = findViewById(android.R.id.text1);
        if (info != null) info.setText("✦ " + store.gold() + "    特殊词 "
                + store.specialCount() + "    Lv." + (store.bestFloor() + 1));
    }

    // ── 地图 ─────────────────────────────────────────────

    /**
     * 画地图。
     *
     * 自上而下一层层画，每层横排该层所有节点。**只有从玩家当前位置能走到的
     * 节点才可点**，其余全部只展示 —— 这就是「自主选路」的来源。
     */
    private void renderMap() {
        body.removeAllViews();
        updateHead();

        GameData.RouteMap map = loadOrMakeMap();
        if (map == null) {
            LinearLayout intro = box();
            intro.addView(title("开始一局"));
            intro.addView(sub("树枝状地图：每层 1~3 个节点，走哪条由你决定。"));
            intro.addView(sub("战斗 / 商店 / 篝火 / 宝箱 / 精英，五种格子各有用途。"));
            intro.addView(bigBtn("生成地图", C_PURPLE, this::newMap));
            body.addView(intro);
            return;
        }

        int row = store.curRow();
        int col = store.curCol();
        if (row < 0 || row >= map.rows()) { row = 0; store.setPos(0, 0); col = 0; }

        List<GameData.Node> curLayer = map.row(row);

        // col == -1 表示「刚打完上一格的战斗，还没在这一层选格子」。
        // 这种状态下**整层所有非末层节点都可选** —— 这正是分叉的意义：
        // 打完一架，你站在岔路口，往左往右自己挑。
        boolean forkPending = (col < 0 || col >= curLayer.size());
        GameData.Node here = forkPending ? null : curLayer.get(col);

        // 玩家下一步能去的节点下标集合
        java.util.Set<Integer> reachable = new java.util.HashSet<>();
        if (forkPending) {
            // 岔路口：这一层全部可选
            for (int i = 0; i < curLayer.size(); i++) reachable.add(i);
        } else {
            for (int idx : here.next) reachable.add(idx);
        }

        LinearLayout hdr = box();
        hdr.addView(title("第 " + (row + 1) + " / " + map.rows() + " 层"));
        hdr.addView(sub("金币 " + store.gold() + "    答对 " + store.right()
                + " / 答错 " + store.wrong()));
        body.addView(hdr);

        // 自上而下画。倒序？不 —— 从上往下画，玩家从上面往下走，
        // 顶部是已走过的路，底部是还没去的，视线方向自然。
        for (int r = 0; r < map.rows(); r++) {
            List<GameData.Node> layer = map.row(r);
            LinearLayout line = new LinearLayout(getContext());
            line.setOrientation(HORIZONTAL);
            line.setGravity(Gravity.CENTER);
            line.setPadding(0, dp(4), 0, dp(4));

            for (int c = 0; c < layer.size(); c++) {
                final GameData.Node n = layer.get(c);
                final int myRow = r, myCol = c;

                boolean isHere = (!forkPending && r == row && c == col);
                // forkPending：当前层可点（选路）；否则只能点下一层的可达格
                boolean canGo = forkPending
                        ? (r == row)
                        : (r == row + 1 && reachable.contains(c));
                boolean passed = store.isVisited(r, c);

                TextView chip = nodeChip(n.type, isHere, canGo, passed);
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(dp(46), dp(46));
                lp.leftMargin = dp(7);
                lp.rightMargin = dp(7);
                chip.setLayoutParams(lp);

                if (canGo) {
                    chip.setOnClickListener(v -> enterNode(myRow, myCol, n.type));
                }
                line.addView(chip);
            }
            body.addView(line);

            // 层与层之间的连线提示（简单竖线，视觉上把树枝串起来）
            if (r < map.rows() - 1) {
                View conn = new View(getContext());
                conn.setBackgroundColor(C_LINE);
                LinearLayout.LayoutParams cp =
                        new LinearLayout.LayoutParams(dp(1), dp(10));
                conn.setLayoutParams(cp);
                LinearLayout wrap = new LinearLayout(getContext());
                wrap.setGravity(Gravity.CENTER);
                wrap.addView(conn);
                body.addView(wrap);
            }
        }

        // 图例
        LinearLayout legend = box();
        legend.addView(sub("战=战斗  精=精英  店=商店  火=篝火  箱=宝箱"));
        legend.addView(sub("亮色描边 = 下一步可去，暗色 = 已走过或去不了"));
        body.addView(legend);

        LinearLayout foot = box();
        foot.addView(smallBtn("重新生成地图（进度清零）", () -> {
            store.resetRun();
            store.setMap("");
            renderMap();
        }));
        body.addView(foot);
    }

    /**
     * 从存档还原地图；没有就现造一张。
     *
     * 邻接关系由 makeMap 现场重建 —— 但它依赖 Random，存档里没有种子，
     * 所以必须**把生成的邻接表一起存下来**，否则读档后连通性全变了。
     * 这里改用「按层节点数确定性重建」：只要每层节点数一致，makeMap 的
     * 连边算法就是确定的？不是 —— 它用了 rnd。所以改成存完整拓扑。
     *
     * 实际做法：存档里把每层的节点类型存成 "0,2,1|2,4|..."（| 分层，, 分列），
     * 连通性则存成 "0-1,0-2|1-0,1-2|..."（层内 h 连到下层 t）。
     */
    private GameData.RouteMap loadOrMakeMap() {
        String raw = store.mapRaw();
        if (raw == null || raw.isEmpty()) return null;

        try {
            GameData.RouteMap m = new GameData.RouteMap();
            String[] layerStrs = raw.split("\\|");
            for (int r = 0; r < layerStrs.length; r++) {
                String ls = layerStrs[r];
                List<GameData.Node> layer = new ArrayList<>();
                if (!ls.isEmpty()) {
                    String[] cols = ls.split(",");
                    for (int c = 0; c < cols.length; c++) {
                        int type = parseInt(cols[c]);
                        layer.add(new GameData.Node(type, r, c));
                    }
                }
                m.nodes.add(layer);
            }
            // 重建连通性：按 makeMap 同样的算法会不可复现，所以改用「按层节点数
            // 均匀分配」的确定性规则 —— 与 makeMap 的"按比例映射"主边一致。
            rebuildEdges(m);
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 重建层间连边。规则与 makeMap 的主干边一致：上一层的第 i 个节点
     * 按比例映射到下一层，再补齐所有下层节点的入边。
     *
     * 这样存档只需存「每层有几个节点、各是什么类型」，连通性完全确定，
     * 读档不会走样。
     */
    private static void rebuildEdges(GameData.RouteMap m) {
        for (int r = 0; r < m.rows() - 1; r++) {
            List<GameData.Node> cur = m.row(r);
            List<GameData.Node> nxt = m.row(r + 1);
            int nc = nxt.size();
            for (GameData.Node n : cur) n.next.clear();
            for (int i = 0; i < cur.size(); i++) {
                int target = cur.size() == 1 ? 0
                        : (int) Math.round((double) i * (nc - 1) / Math.max(1, cur.size() - 1));
                target = Math.max(0, Math.min(nc - 1, target));
                if (!cur.get(i).next.contains(target)) cur.get(i).next.add(target);
            }
            // 补齐入边
            for (int j = 0; j < nc; j++) {
                boolean has = false;
                for (GameData.Node n : cur) if (n.next.contains(j)) has = true;
                if (has) continue;
                int src = cur.size() == 1 ? 0
                        : Math.max(0, Math.min(cur.size() - 1,
                            (int) Math.round((double) j * (cur.size() - 1) / Math.max(1, nc - 1))));
                cur.get(src).next.add(j);
            }
            for (GameData.Node n : cur) java.util.Collections.sort(n.next);
        }
    }

    /** 把 GameData.RouteMap 序列化成存档字符串。 */
    private static String mapToRaw(GameData.RouteMap m) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < m.rows(); r++) {
            if (r > 0) sb.append("|");
            List<GameData.Node> layer = m.row(r);
            for (int c = 0; c < layer.size(); c++) {
                if (c > 0) sb.append(",");
                sb.append(layer.get(c).type);
            }
        }
        return sb.toString();
    }

    /** 节点格子。 */
    private TextView nodeChip(int type, boolean isHere, boolean canGo, boolean passed) {
        String icon;
        int color;
        switch (type) {
            case GameData.N_SHOP:  icon = "店"; color = C_AMBER; break;
            case GameData.N_CAMP:  icon = "火"; color = C_ORANGE; break;
            case GameData.N_CHEST: icon = "箱"; color = C_TEAL; break;
            case GameData.N_ELITE: icon = "精"; color = C_RED; break;
            default:               icon = "战"; color = C_FG2; break;
        }

        TextView t = tv(icon, 13, isHere || canGo ? 0xFFFFFFFF : C_FG3);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));

        if (isHere) {
            // 当前位置：实心高亮
            g.setColor(color);
            g.setStroke(dp(2), 0xFFFFFFFF);
        } else if (canGo) {
            // 可去：描边发亮
            g.setColor(C_BG3);
            g.setStroke(dp(2), color);
        } else if (passed) {
            // 走过：压暗
            g.setColor(0xFF191C22);
            g.setStroke(dp(1), C_LINE);
        } else {
            // 还没到 / 去不了的未来节点
            g.setColor(C_BG2);
            g.setStroke(dp(1), C_LINE);
        }
        t.setBackground(g);
        t.setAlpha(isHere || canGo || passed ? 1f : 0.45f);
        return t;
    }

    /**
     * 抽一张新地图。
     *
     * 起点（第 0 层）是普通战斗，但**不该让玩家先点一个「战」格子**才开始 ——
     * 那一步没有任何选择。所以直接把第 0 层标记为已走过，位置落到「岔路口」
     * 状态，玩家一进来就在第 1 层挑路。
     */
    private void newMap() {
        GameData.RouteMap m = GameData.makeMap(rnd);
        store.setMap(mapToRaw(m));
        store.setVisitedClear();
        store.markVisited(0, 0);
        // row=0 且 col=-1：站在第 0 层但还没选格子 → 第 0 层全部可选
        store.setPos(0, -1);
        renderMap();
    }

    /**
     * 进入一个节点。
     *
     * ── 这里修的是最严重的逻辑 bug ─────────────────────────
     * 旧代码把五种类型全塞进 startBattle：
     *     if (type == N_BATTLE || type == N_ELITE || type == N_SHOP
     *             || type == N_CAMP || type == N_CHEST) { startBattle(...); }
     * 所以选篝火、进商店、开宝箱，全都是打一场怪 ——
     * 玩家反馈的「选择火也是进入怪物战」就是这一行。
     *
     * 现在每种节点走各自的流程：
     *   战斗 / 精英 → 开战
     *   篝火       → 回复生命（补给点）
     *   商店       → 花金币买特殊词 / 治疗
     *   宝箱       → 直接拿金币
     */
    private void enterNode(int row, int col, int type) {
        store.setPos(row, col);
        store.markVisited(row, col);

        switch (type) {
            case GameData.N_CAMP:
                showCamp();
                return;
            case GameData.N_SHOP:
                showShop();
                return;
            case GameData.N_CHEST:
                openChest();
                return;
            default: {
                GameData.Enemy e = data.pickEnemy(type, store.layer(), rnd);
                if (e == null) { advanceAfterNode(); return; }
                startBattle(e);
            }
        }
    }

    /** 篝火：回血。是补给点，不是战斗。 */
    private void showCamp() {
        body.removeAllViews();
        updateHead();

        int maxHp = data.playerHp;
        int heal = Math.max(10, Math.round(maxHp * 0.3f));

        LinearLayout card = box();
        card.addView(title("🔥 篝火"));
        card.addView(sub("你在火堆边坐下休息。"));
        card.addView(sub("生命上限 " + maxHp + "，这次休息可回复 " + heal + " 点。"));
        body.addView(card);

        LinearLayout acts = box();
        acts.addView(bigBtn("休息 · 回复 " + heal + " 生命", C_ORANGE, () -> {
            store.addCampHeal(heal);
            flashThen("🔥 已休息：下一场战斗 +" + heal + " 生命", this::advanceAfterNode);
        }));
        acts.addView(smallBtn("不休息，直接前进", this::advanceAfterNode));
        body.addView(acts);
    }

    /** 商店：花金币买特殊词 / 治疗。是消费点，不是战斗。 */
    private void showShop() {
        body.removeAllViews();
        updateHead();

        LinearLayout card = box();
        card.addView(title("🏪 商店"));
        card.addView(sub("当前金币 " + store.gold() + "。"));
        body.addView(card);

        LinearLayout acts = box();
        int base = 30 + store.layer() * 10;

        acts.addView(buyRow("随机特殊词", base + 40, () -> {
            GameData.Special pick = data.randomUnownedSpecial(store, rnd);
            if (pick == null) { toast("特殊词已集齐，没得卖了"); return; }
            store.addSpecial(pick.id);
            flashThen("🏪 买到特殊词：" + pick.cn, this::advanceAfterNode);
        }));

        acts.addView(buyRow("回复 25 生命", base, () -> {
            store.addCampHeal(25);
            flashThen("🏪 已购买治疗：下一场战斗 +25 生命", this::advanceAfterNode);
        }));

        acts.addView(smallBtn("离开商店", this::advanceAfterNode));
        body.addView(acts);
    }

    /** 商店一行商品：名字 + 价格 + 按钮。 */
    private View buyRow(String name, int price, Runnable onBuy) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        boolean afford = store.gold() >= price;

        TextView nm = tv(name + "　" + price + " 金", 13, C_FG);
        nm.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(nm);

        TextView btn = tv(afford ? "购买" : "金币不足", 12, afford ? C_AMBER : C_FG3);
        btn.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(C_BG3);
        g.setStroke(dp(1), afford ? C_AMBER : C_LINE);
        btn.setBackground(g);
        if (afford) {
            btn.setOnClickListener(v -> {
                if (store.spend(price)) onBuy.run();
                else toast("金币不足");
            });
        }
        row.addView(btn);
        return row;
    }

    /** 宝箱：直接拿金币。是奖励点，不是战斗。 */
    private void openChest() {
        body.removeAllViews();
        updateHead();

        int gold = 20 + rnd.nextInt(30) + store.layer() * 5;

        LinearLayout card = box();
        card.addView(title("📦 宝箱"));
        card.addView(sub("闪闪发光的箱子。打开可获得 " + gold + " 金币。"));
        body.addView(card);

        LinearLayout acts = box();
        acts.addView(bigBtn("打开 · 拿 " + gold + " 金币", C_TEAL, () -> {
            store.addGold(gold);
            flashThen("📦 获得 " + gold + " 金币", this::advanceAfterNode);
        }));
        acts.addView(smallBtn("不打开，直接前进", this::advanceAfterNode));
        body.addView(acts);
    }

    /** 显示一句结果，等玩家点继续。 */
    private void flashThen(String msg, Runnable next) {
        body.removeAllViews();
        updateHead();
        LinearLayout card = box();
        card.addView(title(msg));
        body.addView(card);
        body.addView(bigBtn("继续", C_PURPLE, next));
    }

    /** 非战斗节点处理完，推进到下一步。 */
    private void advanceAfterNode() {
        advanceStep();
        renderMap();
    }

    private void startBattle(GameData.Enemy e) {
        body.removeAllViews();
        if (battleView != null) { removeView(battleView); battleView = null; }
        // 战斗时整块替换掉地图滚动区，否则两者抢高度、战斗界面被压扁
        if (scroller != null) scroller.setVisibility(View.GONE);
        battleView = new GBattleView(getContext(), store);
        battleView.setOnFinish(this::advanceAfterBattle);
        battleView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        addView(battleView);
        battleView.start(e);
    }

    /**
     * 一场战斗结束后推进地图。
     *
     * 这里修的是两个老 bug：
     *   1. 原来是 raiseFloor(store.layer()) —— 参数就是自己，恒等于 0，
     *      于是 bestFloor 永远是 0、顶栏永远显示 Lv.1。
     *   2. 原来只做 node+1，没有任何翻层逻辑。node 走到路线最后一个节点后，
     *      routeCard 只给 i == curNode 的节点绑定点击，而 i 最大就是 len-1，
     *      于是节点越界、地图上一个能点的格子都没有 —— 玩家被卡死在层末。
     */
    private void advanceAfterBattle() {
        advanceStep();
        endBattle();
    }

    /**
     * 推进到地图上的下一步。
     *
     * 分叉地图没有「路线长度」概念了 —— 玩家站在 (row,col)，下一步是他
     * 在那个节点上选的。但战斗结束后玩家还没选，所以流程是：
     *   打完 → row+1（先落到下一层），下一层具体哪个格子等他点。
     *
     * 这里只负责「层数往上走 + 刷新 Lv. + 到底了翻新地图」；
     * 具体站在下一层的哪一格，由玩家点哪个可达节点决定（enterNode 里 setPos）。
     */
    private void advanceStep() {
        GameData.RouteMap map = loadOrMakeMap();
        if (map == null) { renderMap(); return; }

        int row = store.curRow();
        int nextRow = row + 1;

        if (nextRow >= map.rows()) {
            // 走到底了：进新一层，整张地图重抽
            store.setLayer(store.layer() + 1);
            GameData.RouteMap nm = GameData.makeMap(rnd);
            store.setMap(mapToRaw(nm));
            store.setVisitedClear();
            store.markVisited(0, 0);
            store.setPos(0, -1);   // 新一层同样从岔路口开始
        } else {
            // 还没到底：位置先落在这一层，等玩家点可达格。
            // 用 -1 表示"在层间过渡，尚未选定格子"。
            store.setPos(nextRow, -1);
        }
        store.raiseFloor(store.layer());
    }

    // ── 控件 ─────────────────────────────────────────────

    private void toast(String s) {
        android.widget.Toast.makeText(getContext(), s,
                android.widget.Toast.LENGTH_SHORT).show();
    }

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