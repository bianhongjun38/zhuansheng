package com.zsb.plan;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Random;

/**
 * 战斗界面。全部代码构建布局，和项目其它模块保持一致（不依赖 XML）。
 *
 * 界面顺序刻意如此：敌人 → 血条 → 意图 → 手牌 → 阶段按钮。
 * 因为玩家每回合要做的第一个决策是「这回合打不打」，所以阶段按钮放在
 * 手牌之后、视线落点上，而不是塞在角落里。
 */
public class GBattleView extends LinearLayout {

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
    private final GQuiz quiz;
    private final Random rnd = new Random();

    private GBattle battle;
    private GSprite sprite;
    private LinearLayout stage, handRow, optRow;
    private TextView tvMsg, tvEnemyHp, tvPlayerHp, tvIntent, tvResonance;

    /** 战报：每回合发生的事都记在这，点战报条可看全部。 */
    private final java.util.ArrayList<String> log = new java.util.ArrayList<>();
    /** 本回合/上一张牌触发了什么，显示在战报条上。 */
    private String flash = "";
    /** 战报/状态的浮层；null 表示没打开。 */
    private android.widget.FrameLayout overlay;
    private android.app.Dialog dialog;

    // ── 受击表现 ─────────────────────────────────────────
    // 这套动画是 2026-10-28 补的。此前敌人攻击只有一行战报文字，
    // 血条瞬间跳变，玩家反馈"不知道是否攻击了也没有攻击动作"。
    /** 血条红色残影的当前值，比真实血量滞后，用来表现"刚掉了多少"。 */
    private float hpTrail = -1f;
    /** 残影开始追赶的时间点（uptimeMillis）；0 表示不追赶。 */
    private long hpTrailStart = 0;
    /** 血条外层容器，受击时抖动它。 */
    private LinearLayout midRow;
    /** 残影条与真实条，宽度按血量比例设置。 */
    private View hpTrailBar, hpFillBar;
    /** 玩家血量条总宽（像素），布局时算一次。 */
    private int hpBarMaxW = 1;
    /** 屏幕闪红层。 */
    private View flashView;
    /** 抖动/残影动画的帧循环。 */
    private final Runnable fxTick = new Runnable() {
        @Override public void run() { stepFx(); }
    };
    private boolean fxRunning = false;

    /** 当前正在答题的牌；null 表示没在答题。 */
    private GCard pending;
    private GQuiz.Q pendingQ;

    /**
     * 敌人台词气泡。
     *
     * 为什么单独做一个：玩家答对之后什么反馈都没有 —— 只多了一行战报，
     * 怪物毫无反应，「用这个词打它」这件事感觉不到。
     * 现在答对时它会用这个词的中文说一句话，把「打中了」和「这个词什么意思」
     * 一起显示出来：既给了反馈，又顺手复述了一遍词义。
     */
    private TextView tvSpeak;

    /**
     * 本次作答是否走僻义过载通道，值为所考的义项下标；-1 表示普通作答。
     *
     * 单独存一个字段而不是只读 pendingQ.mode：resolve() 开头就把 pendingQ
     * 置空了，那时已经读不到 mode。这个标记必须在清空之前落到一个活下来的变量里。
     */
    private int overloadIndex = -1;

    public GBattleView(Context c, GStore s) {
        super(c);
        store = s;
        data = GameData.get(c);
        quiz = new GQuiz(data, rnd);
        setOrientation(VERTICAL);
        setBackgroundColor(C_BG);
        build();
    }

    // ── 布局 ─────────────────────────────────────────────

    private void build() {
        removeAllViews();

        // 敌人区
        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setBackgroundColor(C_BG2);
        top.setPadding(dp(14), dp(10), dp(14), dp(8));

        sprite = new GSprite(getContext());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(150), dp(150));
        sprite.setLayoutParams(sp);
        top.addView(sprite);

        tvEnemyHp = tv("", 13, C_FG2);
        tvEnemyHp.setGravity(Gravity.CENTER);
        top.addView(tvEnemyHp);

        // 台词气泡：答对时怪物用这个词的中文说一句
        tvSpeak = tv("", 12.5f, C_FG);
        tvSpeak.setGravity(Gravity.CENTER);
        tvSpeak.setPadding(dp(10), dp(5), dp(10), dp(5));
        tvSpeak.setBackground(round(0xFF2B303B, 10, 0xFF3A4152));
        LinearLayout.LayoutParams skp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        skp.topMargin = dp(5);
        tvSpeak.setLayoutParams(skp);
        tvSpeak.setVisibility(GONE);
        top.addView(tvSpeak);

        tvIntent = tv("", 12, C_AMBER);
        tvIntent.setGravity(Gravity.CENTER);
        tvIntent.setPadding(0, dp(3), 0, 0);
        top.addView(tvIntent);

        // 共鸣槽：只在这一槽有词时出现
        tvResonance = tv("", 12, C_PURPLE);
        tvResonance.setGravity(Gravity.CENTER);
        tvResonance.setPadding(dp(8), dp(4), dp(8), dp(4));
        tvResonance.setBackground(round(0xFF2A2440, 8, C_PURPLE));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(5);
        tvResonance.setLayoutParams(rp);
        tvResonance.setVisibility(GONE);
        top.addView(tvResonance);

        addView(top);

        // 玩家血条 + 受击表现
        //
        // 三层结构（原方案只有一层纯文字，受击时只是数字跳变，看不出打了多少）：
        //   底槽（深色）→ 红色残影条（滞后追赶）→ 真实血量条（绿色）
        // 残影留在原位、真实条立刻缩短，两者之间的红块就是"这一下掉了多少"。
        midRow = new LinearLayout(getContext());
        midRow.setOrientation(VERTICAL);
        midRow.setPadding(dp(14), dp(8), dp(14), dp(8));

        LinearLayout hpLine = new LinearLayout(getContext());
        hpLine.setOrientation(HORIZONTAL);
        hpLine.setGravity(Gravity.CENTER_VERTICAL);
        tvPlayerHp = tv("", 13, C_FG);
        hpLine.addView(tvPlayerHp);
        midRow.addView(hpLine);

        android.widget.FrameLayout bar = new android.widget.FrameLayout(getContext());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(9));
        bp.topMargin = dp(5);
        bar.setLayoutParams(bp);
        bar.setBackground(round(0xFF22252E, 5, 0));

        // 残影条（红）—— 先加，画在底层
        hpTrailBar = new View(getContext());
        hpTrailBar.setBackground(round(0xFFE05B5B, 5, 0));
        bar.addView(hpTrailBar, new android.widget.FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));

        // 真实血量条（绿）—— 后加，盖在残影之上
        hpFillBar = new View(getContext());
        hpFillBar.setBackground(round(C_GREEN, 5, 0));
        bar.addView(hpFillBar, new android.widget.FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));

        midRow.addView(bar);
        addView(midRow);

        // 血条宽度要等布局完成才知道，post 一次补同步
        bar.post(new Runnable() {
            @Override public void run() {
                hpBarMaxW = Math.max(1, bar.getWidth());
                syncHpBar();
            }
        });

        // 战报条：点开看完整日志（答对/答错/触发效果/增减益都在这）
        tvMsg = tv("", 12.5f, C_FG2);
        tvMsg.setPadding(dp(10), dp(6), dp(10), dp(6));
        tvMsg.setBackground(round(C_BG3, 9, 0));
        tvMsg.setOnClickListener(v -> showLog());
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.leftMargin = dp(12); mp.rightMargin = dp(12); mp.bottomMargin = dp(4);
        tvMsg.setLayoutParams(mp);
        addView(tvMsg);

        // 手牌区：横向滑动。5 张卡共 560dp 远超屏宽（本机 1264px/560dpi≈361dp），
        // 原来用纵向 ScrollView 装横向排列的卡 → 横向溢出且滑不动，
        // 第 4 张起被裁在屏幕外。改成 HorizontalScrollView 才能滑完全部。
        android.widget.HorizontalScrollView hs =
                new android.widget.HorizontalScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(false);
        handRow = new LinearLayout(getContext());
        handRow.setOrientation(HORIZONTAL);
        handRow.setPadding(dp(10), dp(4), dp(10), dp(8));
        hs.addView(handRow);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        hs.setLayoutParams(hp);
        addView(hs);

        // 选项区（答题 / 阶段按钮都放这）
        optRow = new LinearLayout(getContext());
        optRow.setOrientation(VERTICAL);
        optRow.setPadding(dp(10), dp(6), dp(10), dp(12));
        optRow.setBackgroundColor(C_BG2);
        addView(optRow);

        stage = new LinearLayout(getContext());
        stage.setOrientation(VERTICAL);
        stage.setPadding(dp(14), dp(8), dp(14), dp(4));
        addView(stage);

        // 受击闪红层：盖在最上层，平时不可见、不接收触摸。
        //
        // GBattleView 本身是 LinearLayout，没法"叠"东西；这里把一个
        // FrameLayout 作为最后一层加进来，闪红层放在其中，靠 match_parent
        // 铺满整个战斗区，就能实现全屏闪红。
        android.widget.FrameLayout flashHost = new android.widget.FrameLayout(getContext());
        flashHost.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));   // 高度 0，只做覆盖宿主
        flashView = new View(getContext());
        flashView.setBackgroundColor(0xFFFF3B3B);
        flashView.setAlpha(0f);
        flashView.setClickable(false);
        flashView.setFocusable(false);
        flashView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        flashView.setVisibility(GONE);
        flashHost.addView(flashView);
        addView(flashHost);
    }

    // ── 排行榜埋点 ────────────────────────────────────────
    /** 本场战斗的起点快照。结束时算增量，不能直接读累计值 —— 
     *  否则一台手机玩了一整月的累计数会一次性算到今天头上。 */
    /** 本场是否已经结算过。render() 每帧都会调 showEnd，没有它就会反复发金币。 */
    private boolean endShown;
    private long bpT0;
    private int bpRight0, bpWrong0, bpGold0;
    /** 本场的幂等键：start() 生成一次，补传时一直用它。
     *  这样「上报成功但没收到响应、用户重进又打完一场」不会在榜上算两场。 */
    private String bpRev = "";

    // ── 开始战斗 ─────────────────────────────────────────

    public void start(GameData.Enemy e) {
        battle = new GBattle(data, e, rnd, store);
        sprite.load(e == null ? "mists" : e.sprite);
        sprite.play("idle");
        endShown = false;               // 新一场，允许再结算一次
        hideOverlay();                  // 上一场可能留着战报浮层，别带到这一场
        bpT0 = System.currentTimeMillis();
        bpRight0 = store.right();
        bpWrong0 = store.wrong();
        bpGold0 = store.gold();
        bpRev = Long.toString(bpT0, 36) + "-" + Integer.toString(rnd.nextInt(1 << 20), 36);
        render();
    }

    // ── 渲染 ─────────────────────────────────────────────

    private void render() {
        if (battle == null) return;

        tvEnemyHp.setText(battle.enemy == null ? "敌人"
                : battle.enemy.name + "  " + battle.enemyHp + " / " + battle.enemyMaxHp);
        tvPlayerHp.setText("生命 " + battle.playerHp + " / " + battle.playerMaxHp
                + "    能量 " + battle.energy + " / " + battle.energyMax
                + "    回合 " + battle.turn);
        syncHpBar();

        // 敌人意图：必须说真话。
        //
        // 引擎的实际节奏是 endTurn() 里先 enemyCharge++ 再比较，
        // 所以 chargeNeed=1 的怪每回合结束必打，chargeNeed=2 的隔回合打。
        // 原来这里写死"下回合攻击（还需 N 回合）"：普通怪永远显示还需 1 回合，
        // 玩家以为还能安全过一回合，结果结束回合立刻挨打 —— 预告和实际不符。
        //
        // left = 距离下次攻击还差几次 endTurn。left==1 就是【本回合结束时】打，
        // 必须明确写"本回合"，让玩家知道现在就该做防御决策。
        if (battle.enemy != null) {
            int left = battle.enemyChargeNeed - battle.enemyCharge;
            if (left < 1) left = 1;
            int atk = enemyAtk();
            if (left <= 1) {
                tvIntent.setText("⚔ 本回合结束时攻击 " + atk + " 伤害");
                tvIntent.setTextColor(C_RED);
            } else {
                tvIntent.setText("⏳ 蓄力中 · 还需 " + (left - 1) + " 回合"
                        + "（届时攻击 " + atk + "）");
                tvIntent.setTextColor(C_AMBER);
            }
        }

        // 共鸣槽提示：跨回合保留，告诉玩家"再打一张同义词就暴击"
        renderResonance();

        // 战报条优先显示最近一次操作结果，其次才是引擎消息
        String line = flash != null && !flash.isEmpty() ? flash : battle.lastMsg;
        if (line == null) line = "";
        tvMsg.setText(line + "\n▸ 点这里看完整战报（" + log.size() + " 条）");

        // ── 不再一进战斗就自动出题 ─────────────────────────
        //
        // 旧流程是 renderOptions() 里无条件 ensureQuestion()，于是「攻击阶段」
        // 永远挂着 pendingQ，玩家只能答题、不能保牌 —— 而保牌是花 2 能量的
        // 战术动作，本来应该和攻击交替使用。结果保牌按钮被 renderQuiz() 的
        // 提前 return 整个吃掉，一回合内选定攻击之后就再也点不到保牌。
        // 现在出题改成**玩家点「攻击」才触发**，攻击与保牌才能一来一回。
        renderHand();
        renderOptions();

        if (battle.phase == GBattle.PHASE_WIN) {
            // 胜利时怪物死亡，定格在最后一帧（fallback=null 不回待机）
            sprite.play("death", null);
            showEnd(true);
        } else if (battle.phase == GBattle.PHASE_LOSE) {
            // 玩家战败，怪物还活着，播个攻击动作收尾
            sprite.play("attack");
            showEnd(false);
        }
    }

    private void renderHand() {
        handRow.removeAllViews();
        for (final GCard c : battle.hand) {
            handRow.addView(cardView(c, true));
        }
        if (battle.hand.isEmpty()) {
            TextView t = tv("手牌空了", 12, C_FG3);
            t.setPadding(dp(8), dp(20), 0, 0);
            handRow.addView(t);
        }
    }

    /**
     * 卡面。**故意不显示中文释义** —— 那等于把考题的答案印在题面上，
     * 正确答案看到「marsh / n / 沼泽」时已经知道该选什么了。
     * 释义改在答完之后由战报条给出（见 flashResult / showLog）。
     */
    private View cardView(final GCard c, boolean clickable) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setPadding(dp(9), dp(9), dp(9), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(11));
        g.setColor(c.isAdverb() ? 0xFF2A2440 : C_BG3);
        // 多义项词（熟词僻义候选）用紫色粗描边标出来。这是「这张牌可以过载」
        // 的唯一视觉线索 —— 不标的话玩家永远不知道哪张牌值得赌，
        // 机制等于不存在。描边宽 2dp 而不是 1dp，才在深色底上看得出来。
        boolean overloadable = c.canOverload();
        if (overloadable) {
            g.setStroke(dp(2), C_PURPLE);
        } else {
            g.setStroke(dp(1), c.isAdverb() ? C_PURPLE : C_LINE);
        }
        box.setBackground(g);

        // 卡宽 = 屏宽的三分之一，保证一屏恰好看到 3 张、左右露边提示可滑
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.max(dp(92), (screenW - dp(40)) / 3);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        box.setLayoutParams(lp);

        TextView en = tv(c.en, 13.5f, C_FG);
        en.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(en);

        TextView sub = tv(c.isAdverb() ? "副词·机制" : c.pos, 10, C_FG3);
        sub.setPadding(0, dp(1), 0, dp(3));
        box.addView(sub);

        // 僻义过载标记 + 重铸标记。两个都是「这张牌有额外玩法」的提示，
        // 挤在同一行显示，避免卡面被撑高后一屏放不下。
        StringBuilder tag = new StringBuilder();
        if (overloadable) tag.append("◆僻义").append(c.senseCount());
        if (GBattle.isReWord(c)) {
            if (tag.length() > 0) tag.append(" ");
            tag.append("♻re-");
        }
        if (tag.length() > 0) {
            TextView badge = tv(tag.toString(), 9.5f, C_PURPLE);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setPadding(0, 0, 0, dp(2));
            box.addView(badge);
        }

        // 副词是机制牌，按名字选就行，不考释义，所以保留效果说明
        if (c.isAdverb() && c.desc != null && !c.desc.isEmpty()) {
            TextView cn = tv(c.desc, 11, C_FG2);
            cn.setMaxLines(3);
            box.addView(cn);
        }

        // 同义提示：和上一张同义则标出来
        if (battle != null && battle.lastPlayed != null
                && data.synonym(battle.lastPlayed.id, c.id)) {
            TextView tip = tv("连锁 ×" + (battle.chain + 1), 10, C_TEAL);
            tip.setPadding(0, dp(4), 0, 0);
            box.addView(tip);
        }

        if (clickable && pendingQ != null) {
            // 敌人已经出题：点牌即作答。答对打出这张，答错弃掉这张。
            box.setOnClickListener(v -> answerCard(c));
        } else if (clickable && battle.phase == GBattle.PHASE_PLAY && c.isAdverb()) {
            // 副词是机制牌，不参与词义考核。手牌里全是副词时出不了题（pendingQ
            // 为 null），这时副词必须还能直接打出去，否则整手牌是点不动的死牌。
            box.setOnClickListener(v -> resolveAdverb(c));
        } else if (clickable && battle.phase == GBattle.PHASE_KEEP) {
            box.setOnClickListener(v -> {
                if (battle.keep(c)) {
                    battle.lastMsg = "已保：" + c.en;
                    render();
                }
            });
        }
        return box;
    }

    // ── 阶段按钮 ─────────────────────────────────────────

    /**
     * 战斗中的道具条。
     *
     * 道具（nevertheless / anyhow / anyway / further）在 cards.json 里
     * 早就定义好了，但从来没有出现在任何界面 —— 商店不卖、战斗里不能用，
     * 等于这四个道具不存在。这里按「持有才显示」列出手牌上方的一行。
     *
     * 文案必须把效果写出来：玩家背包里放着一个「尽管如此」，
     * 光看词根本猜不到它是「免疫一次伤害」。
     */
    private void addItemBar(LinearLayout parent) {
        org.json.JSONArray ids = store.items();
        if (ids.length() == 0) return;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < ids.length(); i++) {
            final String id = ids.optString(i);
            if (id == null || id.isEmpty() || !seen.add(id)) continue;
            GameData.Item found = null;
            for (GameData.Item it : data.items) {
                if (it.id.equals(id)) { found = it; break; }
            }
            if (found == null) continue;
            final GameData.Item item = found;
            parent.addView(smallBtn("🎒 " + item.nameEn + " — " + item.desc, () -> {
                if (battle.useItem(item.id)) {
                    flash = "🎒 已使用【" + item.nameEn + "】" + item.desc;
                    addLog(flash, C_AMBER);
                } else {
                    flash = "这个道具现在用不了";
                    addLog(flash, C_FG3);
                }
                render();
            }));
        }
    }

    /**
     * 「Ultimate」按钮：每层只能用一次，本回合伤害 ×3。
     *
     * 特殊词 ultimate 的说明是「每层可用一次：本回合伤害 ×3」，
     * 没有按钮就没法发动 —— 效果写了却点不到，等于没实现。
     * 只有买过 ultimate 且这一层还没用过时才显示。
     */
    private void addUltimateButton(LinearLayout parent) {
        if (battle == null || !battle.canUltimate()) return;
        parent.addView(smallBtn("✨ 终极 — 本回合伤害 ×3（每层一次）", () -> {
            if (battle.fireUltimate()) {
                flash = "✨ 终极发动：本回合伤害 ×3";
                addLog(flash, C_AMBER);
            }
            render();
        }));
    }

    private void renderOptions() {
        optRow.removeAllViews();
        if (pendingQ != null) { renderQuiz(); return; }

        switch (battle.phase) {
            case GBattle.PHASE_CHOOSE:
                // 一点「攻击」就直接出题，不要再让玩家点第二下。
                // 每回合都从 PHASE_CHOOSE 开始，如果这里只切阶段不出题，
                // 玩家会觉得第一下点击没反应 —— 白白多一次交互。
                optRow.addView(bigBtn("⚔ 攻击 · 出牌打人", C_PURPLE, () -> {
                    battle.chooseAttack();
                    askQuestion();
                    render();
                }));
                optRow.addView(bigBtn("保牌 · 花 " + GBattle.keepEnergyCost
                        + " 能量留 2 张（余 " + battle.energy + "）", C_TEAL, () -> {
                    if (!battle.chooseKeep()) {
                        flash = "能量不足，保牌需要 " + GBattle.keepEnergyCost + " 点";
                        addLog(flash, C_AMBER);
                    }
                    render();
                }));
                addItemBar(optRow);
                optRow.addView(smallBtn("直接攻击（不答题）", () -> {
                    // attackDirect() 内部会 endTurn()，也就是敌人同样会打你一下。
                    // 原来直接调它再 render()，敌人这一下的动作完全看不见。
                    int hp0 = battle.enemyHp;
                    int myHp0 = battle.playerHp;
                    int dealt = battle.attackDirect();
                    flash = "【直接攻击】造成 " + dealt + " 伤害";
                    addLog(flash, C_TEAL);
                    if (battle.over()) { render(); return; }
                    // 敌人反击
                    if (battle.playerHp < myHp0) {
                        int took = myHp0 - battle.playerHp;
                        addLog("▶ " + (battle.enemy == null ? "敌人" : battle.enemy.name)
                                + " 反击　你受到 " + took + " 伤害", C_RED);
                        sprite.play("attack");
                    } else {
                        sprite.play("hurt");
                    }
                    render();
                }));
                break;

            case GBattle.PHASE_PLAY:
                // 正在答题：只画题面，不画阶段按钮。
                if (pendingQ != null) { renderQuiz(); return; }

                // 手牌空了就一个按钮都不渲染会让界面死掉 —— 这正是「结束回合」
                // 最需要出现的时候。
                if (battle.hand.isEmpty()) {
                    optRow.addView(tv("手牌打空了 —— 结束回合继续", 12.5f, C_FG3));
                } else if (!hasQuizableCard()) {
                    optRow.addView(tv("手里只剩副词牌，直接打出去或结束回合",
                            12.5f, C_FG3));
                }

                // ── 攻击与保牌交替 ──────────────────────────
                //
                // 现在这两件事是并列的两个按钮，一回合内可以来回切：
                //   打一张（−1 能量，答对才扣）→ 回来还能再选保牌
                //   保一轮（−2 能量）→ 回来还能再攻击
                // 唯一的限制是能量，不再是「先选哪个就锁死哪个」。
                if (canAttackByEnergy() && hasQuizableCard()) {
                    optRow.addView(bigBtn("⚔ 攻击 · 出牌打人（−1 能量）", C_PURPLE,
                            () -> {
                        askQuestion();
                        render();
                    }));
                }

                // 保牌：花 2 能量挑牌留住。能量够就随时可以进来，
                // 不设「每回合一次」，也不受「这回合打没打过」限制。
                if (battle.energy >= GBattle.keepEnergyCost) {
                    optRow.addView(bigBtn("🛡 保牌 · 花 " + GBattle.keepEnergyCost
                            + " 能量留 " + data.keepLimit + " 张（余 " + battle.energy
                            + "）", C_TEAL, () -> {
                        if (!battle.chooseKeep()) {
                            flash = "能量不足，保牌需要 " + GBattle.keepEnergyCost + " 点";
                            addLog(flash, C_AMBER);
                        }
                        render();
                    }));
                } else {
                    optRow.addView(tv("保牌需 " + GBattle.keepEnergyCost
                            + " 能量，当前只剩 " + battle.energy + " 点", 11.5f, C_FG3));
                }

                addItemBar(optRow);
                addUltimateButton(optRow);
                optRow.addView(smallBtn("结束回合", () -> {
                    advanceTurn();
                }));
                break;

            case GBattle.PHASE_KEEP:
                optRow.addView(smallBtn("确定（本轮已保 " + battle.keptNow
                        + " / " + data.keepLimit + " 张）", () -> {
                    battle.endKeep(); render();
                }));
                break;

            default:
                break;
        }
    }

    // ── 答题 ─────────────────────────────────────────────

    /**
     * 保证当前处于「有题待答」的状态。
     *
     * 由 render() 在渲染手牌之前调用 —— 顺序很关键，见 render() 里的注释。
     */
    private void ensureQuestion() {
        if (battle == null) return;
        if (battle.phase != GBattle.PHASE_PLAY) return;
        if (pendingQ != null) return;
        if (battle.hand.isEmpty()) return;
        askQuestion();
    }

    /** 手牌里还有没有能拿来出题的单词牌（副词不算）。 */
    private boolean hasQuizableCard() {
        if (battle == null) return false;
        for (GCard c : battle.hand) {
            if (c != null && c.isWord() && c.cn != null && !c.cn.isEmpty()) return true;
        }
        return false;
    }

    /** 能量够不够出一次手。 */
    private boolean canAttackByEnergy() {
        return battle != null && battle.energy > 0;
    }

    /**
     * 让怪物用玩家刚打出的这个词的中文说一句话。
     *
     * 设计意图：答对之后原来只有一行绿色战报，怪物毫无反应，
     * 「我在用它打它」这件事没有落点。现在让怪物把词义复述一遍 ——
     * 既是打击反馈，又顺手又把释义过了一次眼（答完就忘是背单词最大的漏斗）。
     *
     * 注意：只在**答对之后**调用。答题期间任何位置都不能出现词义，
     * 否则就是上一版「答案写在明面上」的同类错误。
     */
    private void enemySpeak(String line) {
        if (tvSpeak == null) return;
        if (line == null || line.isEmpty()) { tvSpeak.setVisibility(GONE); return; }
        tvSpeak.setText(line);
        tvSpeak.setVisibility(VISIBLE);
        tvSpeak.setAlpha(0f);
        tvSpeak.animate().alpha(1f).setDuration(180).start();
    }

    /** 按题型挑一句合适的中文台词。c 是这轮打出去的牌。 */
    private void enemySpeakFor(GCard c, String explain) {
        if (c == null) { enemySpeak(null); return; }
        String sense = c.isWord() ? GQuiz.firstSensePublic(c.cn) : "";
        if (sense == null) sense = "";
        sense = sense.trim();
        if (sense.isEmpty()) { enemySpeak(null); return; }

        // 例句题额外把整句的中文意思带出来 —— 这是它比「词=义」多给的东西
        String said = "「" + sense + "」……你居然真懂这个词。";
        if (c.ex != null && !c.ex.trim().isEmpty() && pendingQLastMode == 1) {
            said = "「" + sense + "」……这一句我本来没打算让你读懂。";
        }
        enemySpeak(said);
    }

    /** 刚结算掉的那道题的题型。仅用于挑台词，不影响判定。 */
    private int pendingQLastMode = -1;

    /**
     * 敌人出题。
     *
     * 旧流程是玩家先点一张牌、系统再考这张牌 —— 等于自己给自己出题，
     * 只挑会的就永远不会被考到不会的。现在反过来：敌人先出题，
     * 题目围绕手牌里的某一张，玩家必须读懂题面才能从手里挑对。
     *
     * 挑错就把那张错牌弃掉（认错了自己承担），挑对才算打出这张牌。
     */
    private void askQuestion() {
        if (battle == null || battle.phase != GBattle.PHASE_PLAY) return;
        if (pendingQ != null) return;
        if (battle.hand.isEmpty()) return;

        pendingQ = quiz.make(battle.hand, quiz.randomMode());
        if (pendingQ == null) return;   // 手牌全是副词，出不了题

        // 敌人「施法」出题，让玩家看到是它在发难，而不是系统弹窗
        sprite.play("cast");
        pending = pendingQ.card;
    }

    private void renderQuiz() {
        optRow.removeAllViews();

        // 例句题句子长，字号要小一点，否则一个屏幕放不下
        boolean isExample = pendingQ.mode == 1;
        TextView q = tv(pendingQ.prompt, isExample ? 15 : 20, C_FG);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        q.setGravity(Gravity.CENTER);
        q.setPadding(dp(4), dp(2), dp(4), 0);
        if (isExample) q.setLineSpacing(dp(3), 1f);
        optRow.addView(q);

        if (!pendingQ.sub.isEmpty()) {
            TextView s = tv(pendingQ.sub, 11.5f, C_FG3);
            s.setGravity(Gravity.CENTER);
            s.setPadding(dp(4), dp(3), dp(4), dp(8));
            optRow.addView(s);
        }

        if (pendingQ.options != null) {
            // 释义辨析题：选项是屏幕上的按钮（干扰项都不是手牌）
            for (int i = 0; i < pendingQ.options.length; i++) {
                final int k = i;
                optRow.addView(optBtn(pendingQ.options[i], () -> answer(k)));
            }
        } else if (pendingQ.mode == 4) {
            // 僻义过载走的是选项制，答案不在手上 —— 提示语不能写「点牌」，
            // 否则玩家会去点手牌，而那正是这题要避免的作弊路径。
            TextView tip = tv("▾ 选出这个词的生僻义项", 12, C_PURPLE);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(6), 0, dp(2));
            optRow.addView(tip);
        } else {
            // 其余题型：答案在手上，点牌作答
            TextView tip = tv("▾ 点你手上对应的那张牌", 12, C_TEAL);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(6), 0, dp(2));
            optRow.addView(tip);
        }

        // ── 僻义过载入口 ──────────────────────────────────
        //
        // 只有当前题目的目标牌有第二个义项时才出现。点它会把题面换成
        // 生僻义项重新出题，答对 2.2 倍真实伤害、答错自伤 4 点。
        // 放在最底下、用醒目色，是因为它是个「加注」操作，
        // 不该和正常作答混在一起被误触。
        if (pendingQ.card != null && pendingQ.card.canOverload()
                && pendingQ.mode != 4) {
            // 【绝不能写 pendingQ.card.en】。
            // 这一题问的是「中文 → 从手牌里挑出那个词」，答案就是这张牌的单词。
            // 之前按钮写成「🔥 僻义过载 · <单词>」，等于把答案直接印在题面上，
            // 玩家不用辨识，照着按钮上的拼写去点牌就行 —— 和手牌上
            //「◆僻义2」标记泄露答案是同一种错误。
            // 所以这里只说加注规则，不透露是哪张牌。
            optRow.addView(smallBtn("🔥 僻义过载（答对 2.2×，答错自伤 "
                    + GBattle.overloadWrongSelfDamage + "）",
                    this::startOverload));
        }
    }

    /**
     * 把当前题目换成僻义题。
     *
     * 目标牌仍然是玩家按过载时选中的那张（他手上有它、答对就打它），
     * 但**题面改成给单词选释义**：四选一挑它的僻义。
     *
     * 为什么不能沿用「给中文选牌」：手牌上印着「◆僻义2」，
     * 玩家点哪张都看得见那个标记 —— 题面问「哪个是它的僻义」时，
     * 带 ◆ 的牌本身就把答案指出来了。改成选释义之后，标记不再是提示，
     * 因为这时要答的是「哪个意思」，而不是「哪张牌」。
     */
    private void startOverload() {
        if (battle == null || pendingQ == null) return;
        GCard target = pendingQ.card;
        GQuiz.Q oq = quiz.makeOverload(target);
        if (oq == null) {
            addLog("这张牌没有可考的僻义", C_AMBER);
            return;
        }
        pendingQ = oq;
        // 这里同样不能带 target.en —— flash/addLog 会立刻把答案显示出来。
        flash = "🔥 僻义过载：改考生僻义项";
        addLog(flash, C_PURPLE);
        render();
    }

    /**
     * 推进一回合，先把敌人这次的攻击"演"出来再刷界面。
     *
     * 原实现直接 battle.endTurn(false); render()，敌人的攻击只体现在
     * 血量数字瞬间变小上，怪物一个动作都没有 —— 玩家看不出是挨打了。
     * 这里先算一次 endTurn，比较前后血量：掉血了就播 attack，
     * 让动作和伤害数字对得上。
     */
    private void advanceTurn() {
        if (battle == null || battle.over()) return;
        // 结束回合时若还有题没答，必须先清掉：否则 pendingQ 会带进下一回合，
        // 新回合的牌对不上旧题目，玩家怎么点都是错的。
        if (pendingQ != null) {
            pendingQ = null;
            pending = null;
        }
        int hpBefore = battle.playerHp;
        battle.endTurn(false);

        boolean hurt = battle.playerHp < hpBefore;
        if (battle.phase == GBattle.PHASE_LOSE) {
            render();
            return;
        }
        if (hurt) {
            final int dmg = hpBefore - battle.playerHp;
            flash = "⚔ " + (battle.enemy == null ? "敌人" : battle.enemy.name)
                    + " 攻击　你受到 " + dmg + " 伤害";
            addLog(flash, C_RED);
            // 演出交给 enemyStrike：后撤→前冲→命中(闪红+抖动+残影+飘字)→回位
            enemyStrike(new Runnable() {
                @Override public void run() { playerHurtFx(dmg); }
            });
        } else {
            addLog("── 你结束了回合（敌人还在蓄力）", C_FG3);
            sprite.play("move");
        }
        render();
    }

    /**
     * 选项制题（题型 3 释义辨析）：点按钮作答。
     *
     * 答错时把**正确答案那张牌**弃掉 —— 因为选项不是实体牌，
     * 没法「弃掉你点错的那个词」。题考的是这张牌，认不出来就该失去它。
     */
    private void answer(int pick) {
        if (pendingQ == null || pendingQ.options == null) return;
        GCard target = pendingQ.card;

        // 僻义过载（mode 4）也是选项题，但判对错的方式不同：
        // 它选的是「哪个释义」，不是「哪个单词」，所以直接比 answer 下标，
        // 不能走下面那个和 target.en 比字符串的分支。
        if (pendingQ.mode == 4) {
            // 不论对错都要记住义项下标：答对要用它结算 2.2 倍伤害，
            // 答错要用它打出「你答的是什么、正确答案是什么」的说明。
            overloadIndex = pendingQ.senseIndex;
            resolve(target, pick == pendingQ.answer);
            return;
        }

        String chose = (pick >= 0 && pick < pendingQ.options.length)
                ? pendingQ.options[pick] : "";
        boolean right = chose.equalsIgnoreCase(target == null ? "" : target.en);
        resolve(target, right);
    }

    /**
     * 点手牌作答。
     *
     * 这是新流程的核心：敌人已经出题，答案就在手上，玩家点自己认为是答案的那张牌。
     *   · 点对 → 出这张牌打人（走 playCard）
     *   · 点错 → **弃掉点错的这张**（不是正确答案那张）。认错了自己承担 ——
     *     否则玩家可以把 5 张牌挨个试一遍，等于把「不认识」变成「多试几次就过」。
     */
    private void answerCard(GCard picked) {
        if (pendingQ == null) return;
        GCard target = pendingQ.card;

        // 副词是机制牌，不参与词义考核：直接打出去，不判对错
        if (picked != null && picked.isAdverb()) {
            pending = null;
            pendingQ = null;
            resolveAdverb(picked);
            return;
        }

        boolean right = target != null && picked != null && picked.id.equals(target.id);
        // style="pendingQ.mode == 4" 时记住考的是第几个义项，供 resolve() 结算用
        overloadIndex = (pendingQ.mode == 4) ? pendingQ.senseIndex : -1;
        resolve(picked, right);
    }

    /** 统一的答题结算。c 是玩家实际点的那张牌（答错时被弃掉的就是它）。 */
    private void resolve(GCard c, boolean right) {
        if (pendingQ == null) return;
        String explain = pendingQ.explain;
        pendingQLastMode = pendingQ.mode;
        pending = null;
        pendingQ = null;
        // 先收掉上一条台词：答错时如果还挂着上一句「你居然真懂这个词」，
        // 等于在骂人，而且会让人误以为这题答对了。
        enemySpeak(null);


        // 记下出手前的血量，用来判断这一下有没有引出「引擎内部自动结束回合」。
        int myHpBefore = battle.playerHp;

        // 僻义过载走单独的结算：倍率不同，答错也不走敌人的充能逻辑。
        // 这里必须在常规分支之前拦下来，否则过载会被当成普通答对处理，
        // 2.2 倍伤害就白写了。
        if (overloadIndex >= 0) {
            int oi = overloadIndex;
            overloadIndex = -1;
            if (right && c != null) {
                store.markRight();
                int hpBefore = battle.enemyHp;
                int dmg = battle.playCardOverload(c, oi);
                int real = hpBefore - battle.enemyHp;
                flash = "🔥 僻义过载　" + c.en + "（" + c.senseText(oi)
                        + "）　真实伤害 " + real;
                addLog(flash, C_PURPLE);
                enemySpeak("「" + c.senseText(oi) + "」……这个义项你都知道？");
                sprite.play("hurt");
                if (battle.playerHp < myHpBefore) {
                    final int took = myHpBefore - battle.playerHp;
                    addLog("⚔ " + (battle.enemy == null ? "敌人" : battle.enemy.name)
                            + " 攻击　你受到 " + took + " 伤害", C_RED);
                    if (!battle.over()) {
                        enemyStrike(new Runnable() {
                            @Override public void run() { playerHurtFx(took); }
                        });
                    } else {
                        playerHurtFx(took);
                    }
                }
            } else {
                store.markWrong();
                battle.onOverloadWrong(c);
                flash = "✗ 僻义答错：" + explain + "　自伤 "
                        + GBattle.overloadWrongSelfDamage;
                addLog(flash, C_RED);
                playerHurtFx(GBattle.overloadWrongSelfDamage);
            }
            render();
            return;
        }

        if (right) {
            store.markRight();
            addLog("✓ 答对：" + (c == null ? "" : c.en + " = " + c.cn), C_GREEN);
            if (c == null) {
                // 不该发生，防御性处理
            } else if (battle.phase == GBattle.PHASE_PLAY && c.isWord()) {
                int hpBefore = battle.enemyHp;
                int dmg = battle.playCard(c);
                int real = hpBefore - battle.enemyHp;
                flash = "【攻击】" + c.en + "  造成 " + real + " 伤害"
                        + (battle.chain > 1 ? "（连锁 ×" + battle.chain + "）" : "");
                addLog(flash, C_TEAL);
                if (battle.critReady && dmg > 0) addLog("★ 暴击生效", C_AMBER);
                // 怪物用这个词的中文说一句 —— 打击反馈 + 顺手复述词义
                enemySpeakFor(c, explain);
                // 玩家出牌打人，怪物是"挨打"的一方 —— 播 hurt 不是 attack
                sprite.play("hurt");
                // playCard() 在「能量打空 / 手牌打空」时会自己调 endTurn(false)，
                // 也就是敌人已经打完你了。原来这里直接 render()，那一下完全静默：
                // 不掉血条动画、不写战报，玩家只看到血量数字莫名少了一截。
                if (battle.playerHp < myHpBefore) {
                    final int took = myHpBefore - battle.playerHp;
                    addLog("⚔ " + (battle.enemy == null ? "敌人" : battle.enemy.name)
                            + " 攻击　你受到 " + took + " 伤害", C_RED);
                    if (!battle.over()) {
                        enemyStrike(new Runnable() {
                            @Override public void run() { playerHurtFx(took); }
                        });
                    } else {
                        playerHurtFx(took);
                    }
                }
            } else if (c.isAdverb()) {
                resolveAdverb(c);
                return;
            }
        } else {
            store.markWrong();
            battle.onWrong(c);
            flash = "✗ 答错：" + explain + "　敌人充能 +2，下次伤害翻倍";
            addLog(flash, C_RED);
            // 答错是玩家失误，怪物只是"蓄力"，播 cast 表现蓄力
            sprite.play("cast");
            // onWrong() 在手牌因此变空时也会内部 endTurn(false)，敌人会真的打你一下。
            // 这种情况要按「攻击」演，不能还停在蓄力动作上。
            if (battle.playerHp < myHpBefore) {
                final int took = myHpBefore - battle.playerHp;
                addLog("⚔ " + (battle.enemy == null ? "敌人" : battle.enemy.name)
                        + " 攻击　你受到 " + took + " 伤害", C_RED);
                if (!battle.over()) {
                    enemyStrike(new Runnable() {
                        @Override public void run() { playerHurtFx(took); }
                    });
                } else {
                    playerHurtFx(took);
                }
            }
        }

        render();
    }


    /**
     * 打出一张副词牌：消耗它，按 fx 改写本回合规则。
     *
     * 副词是机制牌，考它「是什么意思」没有意义（exceedingly 就是「极其」），
     * 所以它不参与词义考核，玩家点它就直接生效。
     */
    private void resolveAdverb(GCard c) {
        if (c == null) return;
        int myHpBefore = battle.playerHp;
        battle.hand.remove(c);
        battle.discard.add(c);
        battle.energy--;
        battle.applyAdverb(adverbFx(c.id));
        battle.lastMsg = "【" + c.en + "】" + c.desc;
        flash = "【副词】" + c.en + " — " + c.desc;
        addLog(flash, C_PURPLE);
        // 副词是玩家改写规则，怪物侧只轻微受创反馈
        sprite.play("hurt");
        if (battle.playerHp < myHpBefore) {
            int took = myHpBefore - battle.playerHp;
            addLog("▶ " + (battle.enemy == null ? "敌人" : battle.enemy.name)
                    + " 攻击　你受到 " + took + " 伤害", C_RED);
            if (!battle.over()) sprite.play("attack");
        }
        if (battle.energy <= 0 || battle.hand.isEmpty()) advanceTurn();
        else render();
    }

    /** 副词 id → fx 标识。与 assets/game/cards.json 的 adverbs 段一一对应。 */
    private static String adverbFx(String id) {
        switch (id) {
            case "exceedingly":    return "nextCardX2_single";
            case "particularly":   return "firstX2_5_restHalf";
            case "increasingly":   return "ramp30_hp5";
            case "practically":    return "singleX3";
            case "exclusively":    return "singleX3";
            case "basically":      return "allFixed12";
            case "indeed":         return "guaranteedCrit_chargeUp1";
            case "altogether":     return "discardAll_plus8";
            case "quickly":        return "chargeDown1_dmgDown30";
            case "slowly":         return "noCharge_noAttack";
            case "suddenly":       return "true20_discard1";
            case "immediately":    return "skipCharge_chargeUp1";
            case "gradually":      return "rampPerTurn2";
            case "deliberately":   return "skipTurn_nextX2";
            case "naturally":      return "noConsume_halfDmg";
            case "normally":       return "redrawHand_chargeUp1";
            case "consequently":   return "draw2_dmgHalf";
            case "accordingly":    return "chargeDownPerCard_dmg40";
            case "hence":          return "enemyNextHalf_noAttack";
            case "moreover":       return "handPlus3_unusable";
            case "mercifully":     return "heal15orClearCharge";
            case "fortunately":    return "randomRareCard";
            case "presently":      return "enemyFullChargeNext";
            default:               return "";
        }
    }

    // ── 战报 / 状态 ──────────────────────────────────────

    private void addLog(String s, int color) {
        if (s == null || s.isEmpty()) return;
        log.add(s);
        // 只留最近 120 条，防止长时间对局内存无限增长
        while (log.size() > 120) log.remove(0);
    }

    /** 完整战报：在一层可滚动的浮层里显示，带颜色。 */
    private void showLog() {
        if (log.isEmpty()) { toast("还没有战报"); return; }
        android.widget.ScrollView sv = new android.widget.ScrollView(getContext());
        sv.setBackgroundColor(0x00000000);
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(12));
        sv.addView(col);

        TextView h = tv("战报（" + log.size() + " 条）", 15, C_FG);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, 0, 0, dp(8));
        col.addView(h);

        for (String s : log) {
            TextView r = tv(s, 12, s.startsWith("✓") ? C_GREEN
                    : s.startsWith("✗") ? C_RED
                    : s.startsWith("【攻击】") ? C_TEAL
                    : s.startsWith("【副词】") ? C_PURPLE
                    : s.startsWith("★") ? C_AMBER
                    : C_FG2);
            r.setPadding(0, dp(2), 0, dp(2));
            col.addView(r);
        }

        col.addView(smallBtn("关闭", this::hideOverlay));

        showOverlay(sv);
    }

    /** 当前状态：血量/能量/回合/敌人充能/所有增益减益。 */
    private void showStatus() {
        android.widget.ScrollView sv = new android.widget.ScrollView(getContext());
        sv.setBackgroundColor(0x00000000);
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(VERTICAL);
        col.setPadding(dp(14), dp(12), dp(14), dp(12));
        sv.addView(col);

        TextView h = tv("当前状态", 15, C_FG);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, 0, 0, dp(8));
        col.addView(h);

        col.addView(statLine("回合", String.valueOf(battle.turn), null));
        col.addView(statLine("本轮答对", store.right() + " 次", C_GREEN));
        col.addView(statLine("本轮答错", store.wrong() + " 次", C_RED));
        col.addView(statLine("生命", battle.playerHp + " / " + battle.playerMaxHp, null));
        col.addView(statLine("能量", battle.energy + " / " + battle.energyMax, null));
        col.addView(statLine("手牌", battle.hand.size() + " 张", null));
        col.addView(statLine("已保牌", battle.keptNow + " / " + data.keepLimit + " 张", null));
        if (!battle.kept.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (GCard k : battle.kept) sb.append(k.en).append("  ");
            col.addView(statLine("保牌内容", sb.toString().trim(), C_TEAL));
        }
        if (battle.enemy != null) {
            int left = battle.enemyChargeNeed - battle.enemyCharge;
            if (left <= 0) left = 1;
            col.addView(statLine("敌人", battle.enemy.name, null));
            col.addView(statLine("敌人血量",
                    battle.enemyHp + " / " + battle.enemyMaxHp, null));
            col.addView(statLine("下次攻击",
                    enemyAtk() + " 伤害"
                            + (left <= 1 ? "（本回合结束时）"
                                         : "（还需 " + (left - 1) + " 回合）"),
                    left <= 1 ? C_RED : C_AMBER));
        }

        // ── 增减益 ──
        TextView bh = tv("当前的增益 / 减益", 13, C_FG);
        bh.setTypeface(Typeface.DEFAULT_BOLD);
        bh.setPadding(0, dp(12), 0, dp(4));
        col.addView(bh);

        boolean any = false;
        if (battle.turnDamageMul != 1.0) {
            col.addView(buff("伤害倍率 ×" + fmt(battle.turnDamageMul),
                    battle.turnDamageMul > 1 ? C_GREEN : C_RED));
            any = true;
        }
        if (battle.turnFlatBonus != 0) {
            col.addView(buff("固定加成 " + (battle.turnFlatBonus > 0 ? "+" : "")
                    + battle.turnFlatBonus, battle.turnFlatBonus > 0 ? C_GREEN : C_RED));
            any = true;
        }
        if (battle.critReady) { col.addView(buff("必暴击（下一张牌）", C_AMBER)); any = true; }
        if (battle.skipEnemyCharge) { col.addView(buff("冻结敌人充能", C_GREEN)); any = true; }
        if (battle.noAttackThisTurn) { col.addView(buff("本回合无法攻击", C_RED)); any = true; }
        if (battle.wrongDoubled) { col.addView(buff("敌人下次伤害翻倍", C_RED)); any = true; }
        if (battle.chain > 1) { col.addView(buff("同义连锁 ×" + battle.chain, C_TEAL)); any = true; }
        if (battle.extraDraw > 0) { col.addView(buff("额外抽牌 +" + battle.extraDraw, C_GREEN)); any = true; }
        if (!any) {
            TextView none = tv("无（本回合状态正常）", 12, C_FG3);
            none.setPadding(0, dp(2), 0, dp(2));
            col.addView(none);
        }

        col.addView(smallBtn("关闭", this::hideOverlay));
        showOverlay(sv);
    }

    private TextView statLine(String k, String v, Integer vColor) {
        TextView t = tv(k + "：" + v, 12.5f, vColor == null ? C_FG2 : vColor);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private TextView buff(String s, int color) {
        TextView t = tv("· " + s, 12.5f, color);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private static String fmt(double d) {
        if (Math.abs(d - Math.round(d)) < 0.01) return String.valueOf(Math.round(d));
        return String.format(java.util.Locale.US, "%.2f", d);
    }

    /**
     * 浮层用 Dialog 实现，不挂在 GBattleView 上。
     *
     * 踩过的坑：GBattleView 是纵向 LinearLayout，把浮层 addView 到它末尾时，
     * 就算给浮层设 MATCH_PARENT 也会被其它子 view 抢走高度，
     * 面板被顶到屏幕外（实测只露出最上面几行）。Dialog 有独立 window，
     * 不受父布局分配影响。
     */
    private void showOverlay(View content) {
        hideOverlay();
        int screenH = getResources().getDisplayMetrics().heightPixels;

        dialog = new android.app.Dialog(getContext(), android.R.style.Theme_Translucent_NoTitleBar);
        android.widget.FrameLayout root = new android.widget.FrameLayout(getContext());
        root.setOnClickListener(v -> hideOverlay());

        LinearLayout wrap = new LinearLayout(getContext());
        wrap.setOrientation(VERTICAL);
        wrap.setBackground(round(C_BG2, 14, C_LINE));
        wrap.setPadding(dp(6), dp(6), dp(6), dp(6));
        wrap.setOnClickListener(v -> { });
        android.widget.FrameLayout.LayoutParams wp =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (int) (screenH * 0.62f));
        wp.gravity = Gravity.CENTER;
        wp.setMargins(dp(16), 0, dp(16), 0);
        wrap.setLayoutParams(wp);
        wrap.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(wrap);

        dialog.setContentView(root);
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setDimAmount(0.75f);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.setCancelable(true);   // 显式声明：BACK 一定能关掉，别把人困在浮层里
        dialog.setOnCancelListener(d -> { dialog = null; });
        dialog.show();
    }

    private void hideOverlay() {
        if (dialog != null) {
            try { dialog.dismiss(); } catch (Exception ignore) { }
            dialog = null;
        }
        if (overlay != null) { removeView(overlay); overlay = null; }
    }

    // ── 结束 ─────────────────────────────────────────────

    private void showEnd(boolean win) {
        // 原来用 optRow.setTag("end") 当守卫，但那个 tag 只设不清：
        // 打第二场时 start() 没复位它，这里就直接 return，
        // 「继续」按钮永远加不出来 → onFinish 不触发 → 地图不推进。
        // 玩家看到的是「打完了但界面没反应」。
        // 换成每场开头复位的字段，既挡住重复发金币，也不会跨局残留。
        if (endShown) return;
        endShown = true;
        optRow.removeAllViews();

        TextView t = tv(win ? "胜利" : "失败", 26, win ? C_GREEN : C_RED);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(10));
        optRow.addView(t);

        if (win) {
            int gold = battle.goldReward();
            store.addGold(gold);
            TextView g = tv("+" + gold + " 金币", 14, C_AMBER);
            g.setGravity(Gravity.CENTER);
            optRow.addView(g);
        }

        recordBattle(win);

        if (win) {
            optRow.addView(bigBtn("继续", C_PURPLE, () -> {
                if (onFinish != null) onFinish.run();
            }));
        } else {
            // 战败必须重开这一局，不能走 onFinish。
            // onFinish 挂的是 advanceAfterBattle()，它无条件 node+1（到层末还翻层），
            // 也就是说「输了也能白拿一个节点」—— 这一局永远输不掉，
            // 塔就没有任何失败压力。而且 gold/层数还会继续累加。
            optRow.addView(tv("这一局结束了。金币与特殊词清零，从第 1 层重新开始。",
                    12.5f, C_FG3));
            optRow.addView(bigBtn("重新开始", C_PURPLE, () -> {
                store.resetRun();
                if (onFinish != null) onFinish.run();
            }));
        }
    }

    /**
     * 把这一场记进本地并按天聚合，然后后台补传。
     *
     * 为什么用「增量」而不是直接读累计值：right()/wrong()/gold() 都是
     * 这台手机的累计数（一个月的量）。直接上报会让今天凭空涨几千分，
     * 排行榜立刻变成「谁玩得久谁第一」，和当天表现无关。
     *
     * 失败静默：成绩存本地就够了，上传失败不该弹窗打断玩家。
     */
    private void recordBattle(boolean win) {
        try {
            if (bpRev == null || bpRev.isEmpty()) return;
            GStore.Battle b = new GStore.Battle();
            b.day = com.zsb.plan.StateLogic.todayIso();
            b.floor = store.layer();
            b.node = store.curRow();   // 分叉地图下「节点」就是第几层（供排行榜展示用）
            b.win = win;
            b.gold = Math.max(0, store.gold() - bpGold0);
            b.right = Math.max(0, store.right() - bpRight0);
            b.wrong = Math.max(0, store.wrong() - bpWrong0);
            b.ms = Math.max(0, System.currentTimeMillis() - bpT0);
            b.started = bpT0;
            b.rev = bpRev;
            // 一题都没答就结束的战斗（全程「直接攻击」）不记：
            // 服务端 empty_battle 会拒收，留着它只会永远堵在队头。
            if (b.total() > 0) store.addBattle(b);
        } catch (Exception ignored) {
            // 记不上不能影响玩家继续玩
        }
        uploadPendingBattles();
    }

    /**
     * 补传积压的战斗记录。
     *
     * 和 EngView.uploadPending 一个套路：同学玩的地方经常没网，
     * 当场传必失败，所以先落本地，每次战斗结束时顺手试一遍。
     * 服务器明确回了 ignored=true（用时太短/超上限/越界）也算已处理 ——
     * 再传一次结果一样，不该无限重试。
     */
    private void uploadPendingBattles() {
        final com.zsb.plan.Account acc = com.zsb.plan.Account.load(getContext());
        if (!acc.loggedIn()) return;
        final org.json.JSONArray pend = store.pendingBattles();
        if (pend.length() == 0) return;

        final java.util.List<org.json.JSONObject> items = new java.util.ArrayList<>();
        for (int i = 0; i < pend.length(); i++) {
            org.json.JSONObject o = pend.optJSONObject(i);
            if (o != null) items.add(o);
        }
        if (items.isEmpty()) return;

        new Thread(() -> {
            for (org.json.JSONObject o : items) {
                Api.R res = Api.postBattle(acc.token,
                        o.optInt("floor"), o.optInt("node"), o.optInt("win") == 1,
                        o.optInt("gold"), o.optInt("r"), o.optInt("w"),
                        o.optLong("ms"), o.optLong("started"), o.optString("rev"));
                if (res.ok || (res.body != null && res.body.optBoolean("ignored"))) {
                    store.markBattleSent(o.optString("rev"));
                } else if (res.code == 401) {
                    // 登录过期。这条记录本身是好的，重新登录后还要传 ——
                    // 所以既不能丢，也不能硬刷，停下来等下次机会。
                    break;
                } else if (res.code >= 400 && res.code < 500) {
                    // 其它 4xx = 服务端明确说「这条我不收」（字段不合法之类）。
                    // 必须当成已处理扔掉：如果这里 break，队头这条会永远堵住
                    // 后面所有记录，越积越多、再也传不上去。
                    store.markBattleSent(o.optString("rev"));
                } else {
                    // code==0 是连不上，5xx 是服务器抖了 —— 这两种才值得下次重试
                    break;
                }
            }
        }).start();
    }

    public Runnable onFinish;
    public void setOnFinish(Runnable r) { onFinish = r; }

    // ── 控件工厂 ─────────────────────────────────────────

    private TextView tv(String s, float size, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private TextView bigBtn(String s, int color, Runnable r) {
        TextView t = tv(s, 15, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(13), 0, dp(13));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(color);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private TextView smallBtn(String s, Runnable r) {
        TextView t = tv(s, 13, C_FG2);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(10), 0, dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(C_BG3);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private TextView optBtn(String s, Runnable r) {
        TextView t = tv(s, 15, C_FG);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(13), dp(12), dp(13));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(11));
        g.setColor(C_BG3);
        g.setStroke(dp(1), C_LINE);
        t.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(7);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> r.run());
        return t;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(getContext(), s,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private GradientDrawable round(int fill, float radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /**
     * 敌人这一下实际会打多少。
     *
     * 不能直接显示 enemy.atk —— 答错会让伤害翻倍（wrongDamageMultiplier），
     * 意图写着 8 结果打 16，玩家会以为是 bug。预告必须预报真实数值。
     */
    private int enemyAtk() {
        if (battle == null || battle.enemy == null) return 0;
        int dmg = battle.enemy.atk;
        if (battle.wrongDoubled) {
            dmg = (int) Math.round(dmg * data.wrongDamageMultiplier);
        }
        return dmg;
    }

    // ── 受击表现实现 ─────────────────────────────────────

    /**
     * 把血量百分比刷到两条血条上。
     *
     * hpTrail 是残影：正常情况等于真实血量；掉血时它先留在原处，
     * 由 stepFx() 逐帧追上来，中间露出的红色就是这次掉了多少。
     */
    private void syncHpBar() {
        if (battle == null || hpFillBar == null) return;
        int max = Math.max(1, battle.playerMaxHp);
        int w = hpBarMaxW > 1 ? hpBarMaxW : Math.max(1, midRow.getWidth() - dp(28));
        if (w > 1) hpBarMaxW = w;

        float cur = Math.max(0f, Math.min(1f, battle.playerHp / (float) max));
        if (hpTrail < 0f) hpTrail = cur;

        setBarW(hpFillBar, w, cur);
        setBarW(hpTrailBar, w, Math.max(cur, hpTrail));
    }

    private void setBarW(View v, int total, float ratio) {
        if (v == null) return;
        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) return;
        lp.width = Math.max(0, Math.round(total * ratio));
        v.setLayoutParams(lp);
    }

    /**
     * 敌人攻击表现：后撤蓄力 → 前冲 → 命中反馈 → 回位。
     *
     * 用 View 的 translationX/scaleX/scaleY，不需要 Canvas 重绘循环，
     * 也不用引入动画库 —— 这套属性从 API 11 就有。
     * 全程 420ms，命中点在第 190ms。
     */
    private void enemyStrike(final Runnable onHit) {
        if (sprite == null) { if (onHit != null) onHit.run(); return; }

        float back = -dp(16);    // 后撤（向右，因为敌人在中上）
        float hit  = dp(46);     // 前冲

        // ① 蓄力后撤 0~110ms
        sprite.animate().translationX(back).scaleX(1.10f).scaleY(0.92f)
                .setDuration(110).withEndAction(new Runnable() {
            @Override public void run() {
                // ② 前冲 110~190ms
                sprite.animate().translationX(hit).scaleX(1.18f).scaleY(0.86f)
                        .setDuration(80).withEndAction(new Runnable() {
                    @Override public void run() {
                        // ③ 命中：扣血已由引擎完成，这里只负责"演"
                        if (onHit != null) onHit.run();
                        // ④ 回位 190~420ms
                        sprite.animate().translationX(0f).scaleX(1f).scaleY(1f)
                                .setDuration(230).start();
                    }
                }).start();
            }
        }).start();

        // 精灵动作同步：攻击帧
        sprite.play("attack");
    }

    /**
     * 玩家受击的完整表现：闪红 + 抖动 + 残影 + 飘字。
     * 所有子效果都是独立的 View 属性，互不阻塞。
     */
    private void playerHurtFx(int dmg) {
        if (dmg <= 0) return;

        // ① 屏幕闪红：淡入 60ms，淡出 260ms
        if (flashView != null && flashView.getParent() != null) {
            flashView.setVisibility(VISIBLE);
            flashView.setAlpha(0f);
            flashView.animate().alpha(0.32f).setDuration(60)
                    .withEndAction(new Runnable() {
                @Override public void run() {
                    flashView.animate().alpha(0f).setDuration(260)
                            .withEndAction(new Runnable() {
                        @Override public void run() {
                            flashView.setVisibility(GONE);
                        }
                    }).start();
                }
            }).start();
        }

        // ② 血条抖动（左右各 6dp，衰减）
        shakeRow();

        // ③ 残影从当前值开始追赶真实血量
        if (battle != null) {
            int max = Math.max(1, battle.playerMaxHp);
            hpTrail = Math.max(hpTrail, (battle.playerHp + dmg) / (float) max);
            hpTrailStart = android.os.SystemClock.uptimeMillis() + 220;  // 停 220ms 再追
        }
        startFx();

        // ④ 伤害飘字
        floatDamage(dmg);
    }

    /** 血条容器左右抖动，衰减到 0。 */
    private void shakeRow() {
        if (midRow == null) return;
        final float[] seq = { -6f, 6f, -5f, 5f, -3f, 3f, -1.5f, 1.5f, 0f };
        for (int i = 0; i < seq.length; i++) {
            final float dx = dp(seq[i]);
            midRow.postDelayed(new Runnable() {
                @Override public void run() { midRow.setTranslationX(dx); }
            }, i * 32L);
        }
    }

    /** 伤害数字从血条处飘起并淡出。 */
    private void floatDamage(int dmg) {
        if (midRow == null || midRow.getParent() == null) return;
        final TextView ft = tv("-" + dmg, 22, C_RED);
        ft.setTypeface(Typeface.DEFAULT_BOLD);
        ft.setAlpha(1f);
        midRow.post(new Runnable() {
            @Override public void run() {
                try {
                    ((ViewGroup) midRow.getParent()).addView(ft,
                            new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT));
                } catch (Throwable ignored) { return; }
                ft.animate().translationY(-dp(40)).alpha(0f)
                        .setDuration(760).withEndAction(new Runnable() {
                    @Override public void run() {
                        ViewGroup p = (ViewGroup) ft.getParent();
                        if (p != null) p.removeView(ft);
                    }
                }).start();
            }
        });
    }

    private void startFx() {
        if (fxRunning) return;
        fxRunning = true;
        postDelayed(fxTick, 16L);
    }

    /** 残影追赶：每帧把 hpTrail 往真实血量收，收齐就停。 */
    private void stepFx() {
        if (battle == null) { fxRunning = false; return; }
        int max = Math.max(1, battle.playerMaxHp);
        float cur = Math.max(0f, Math.min(1f, battle.playerHp / (float) max));
        boolean busy = false;

        if (hpTrail > cur + 0.001f
                && android.os.SystemClock.uptimeMillis() >= hpTrailStart) {
            hpTrail -= Math.max(0.004f, (hpTrail - cur) * 0.16f);
            if (hpTrail < cur) hpTrail = cur;
            busy = true;
        } else if (hpTrail < cur) {
            hpTrail = cur;   // 回血（篝火/道具）直接跟上，不做残影
        }

        setBarW(hpFillBar, hpBarMaxW, cur);
        setBarW(hpTrailBar, hpBarMaxW, Math.max(cur, hpTrail));

        if (busy) postDelayed(fxTick, 16L);
        else fxRunning = false;
    }

    /**
     * 共鸣槽。
     *
     * 玩家需要知道"槽里现在是什么词、打它的同义词就能暴击"，
     * 否则跨回合机制完全不可见（这是原版连锁最大的问题：触发了才显示，
     * 玩家不知道还差什么）。
     * 槽里只显示英文 —— 显示中文就等于把答案印出来了。
     */
    private void renderResonance() {
        if (tvResonance == null) return;
        if (battle == null) { tvResonance.setVisibility(GONE); return; }

        // 三连打断的进度优先显示：它是「这回合还能不能免伤」的直接答案，
        // 比共鸣提示更影响当下的决策。打断用掉之后才轮到共鸣条。
        if (!battle.interruptedThisTurn && battle.streak > 0) {
            tvResonance.setVisibility(VISIBLE);
            tvResonance.setText("🛡 连对 " + battle.streak + " / " + battle.interruptNeed
                    + "　—　再连对 " + Math.max(0, battle.interruptNeed - battle.streak)
                    + " 次可截下敌人这次攻击");
            return;
        }

        if (battle.resonance == null) {
            tvResonance.setVisibility(GONE);
            return;
        }
        tvResonance.setVisibility(VISIBLE);
        tvResonance.setText("⚡ 共鸣待发：" + battle.resonance.en
                + "　—　打出与它同义的词可暴击 ×" + (int) Math.round(battle.resonanceMul * 100) / 100.0);
    }

    /**
     * 视图离开窗口时必须停掉自循环。
     *
     * stepFx() 会一直 postDelayed 自己直到血条追上真实血量，
     * 如果玩家在中途退出战斗，这个 Runnable 会继续持有 View 引用 ——
     * 典型的动画泄漏。这里统一收尾。
     */
    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(fxTick);
        fxRunning = false;
        if (sprite != null) {
            sprite.animate().cancel();
            sprite.setTranslationX(0f);
            sprite.setScaleX(1f);
            sprite.setScaleY(1f);
        }
        if (midRow != null) midRow.setTranslationX(0f);
        super.onDetachedFromWindow();
    }
}