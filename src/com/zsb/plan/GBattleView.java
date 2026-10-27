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
    private TextView tvMsg, tvEnemyHp, tvPlayerHp, tvIntent;

    /** 战报：每回合发生的事都记在这，点战报条可看全部。 */
    private final java.util.ArrayList<String> log = new java.util.ArrayList<>();
    /** 本回合/上一张牌触发了什么，显示在战报条上。 */
    private String flash = "";
    /** 战报/状态的浮层；null 表示没打开。 */
    private android.widget.FrameLayout overlay;
    private android.app.Dialog dialog;

    /** 当前正在答题的牌；null 表示没在答题。 */
    private GCard pending;
    private GQuiz.Q pendingQ;

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

        tvIntent = tv("", 12, C_AMBER);
        tvIntent.setGravity(Gravity.CENTER);
        tvIntent.setPadding(0, dp(3), 0, 0);
        top.addView(tvIntent);
        addView(top);

        // 玩家血条
        LinearLayout mid = new LinearLayout(getContext());
        mid.setOrientation(HORIZONTAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        mid.setPadding(dp(14), dp(8), dp(14), dp(8));
        tvPlayerHp = tv("", 13, C_FG);
        mid.addView(tvPlayerHp);
        addView(mid);

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
    }

    // ── 开始战斗 ─────────────────────────────────────────

    public void start(GameData.Enemy e) {
        battle = new GBattle(data, e, rnd);
        sprite.load(e == null ? "mists" : e.sprite);
        sprite.play("idle");
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

        // 敌人意图：还差几回合攻击
        if (battle.enemy != null) {
            int left = battle.enemyChargeNeed - battle.enemyCharge;
            if (left <= 0) left = 1;
            tvIntent.setText("意图：下回合攻击 " + battle.enemy.atk
                    + " 伤害（还需 " + left + " 回合）");
        }

        // 战报条优先显示最近一次操作结果，其次才是引擎消息
        String line = flash != null && !flash.isEmpty() ? flash : battle.lastMsg;
        if (line == null) line = "";
        tvMsg.setText(line + "\n▸ 点这里看完整战报（" + log.size() + " 条）");

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
        g.setStroke(dp(1), c.isAdverb() ? C_PURPLE : C_LINE);
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

        if (clickable && battle.phase == GBattle.PHASE_PLAY) {
            box.setOnClickListener(v -> beginQuiz(c));
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

    private void renderOptions() {
        optRow.removeAllViews();
        if (pendingQ != null) { renderQuiz(); return; }

        switch (battle.phase) {
            case GBattle.PHASE_CHOOSE:
                optRow.addView(bigBtn("攻击 · 出牌打人", C_PURPLE, () -> {
                    battle.chooseAttack(); render();
                }));
                optRow.addView(bigBtn("保牌 · 留 2 张到下回合", C_TEAL, () -> {
                    battle.chooseKeep(); render();
                }));
                optRow.addView(smallBtn("直接攻击（不答题）", () -> {
                    int hp0 = battle.enemyHp;
                battle.attackDirect();
                flash = "【直接攻击】造成 " + Math.max(0, hp0 - battle.enemyHp) + " 伤害";
                addLog(flash, C_TEAL);
                sprite.play("hurt");
                render();
                }));
                break;

            case GBattle.PHASE_PLAY:
                if (battle.hand.isEmpty()) break;
                optRow.addView(smallBtn("当前状态与战报", () -> showStatus()));
                optRow.addView(smallBtn("结束回合", () -> {
                    advanceTurn();
                }));
                break;

            case GBattle.PHASE_KEEP:
                optRow.addView(smallBtn("确定（已保 " + battle.kept.size()
                        + " / " + data.keepLimit + " 张）", () -> {
                    battle.endKeep(); render();
                }));
                break;

            default:
                break;
        }
    }

    // ── 答题 ─────────────────────────────────────────────

    private void beginQuiz(GCard c) {
        pending = c;
        pendingQ = quiz.make(c, quiz.randomMode());
        if (pendingQ == null) { pending = null; return; }
        renderOptions();
    }

    private void renderQuiz() {
        optRow.removeAllViews();

        TextView q = tv(pendingQ.prompt, 20, C_FG);
        q.setTypeface(Typeface.DEFAULT_BOLD);
        q.setGravity(Gravity.CENTER);
        optRow.addView(q);

        if (!pendingQ.sub.isEmpty()) {
            TextView s = tv(pendingQ.sub, 11.5f, C_FG3);
            s.setGravity(Gravity.CENTER);
            s.setPadding(0, dp(2), 0, dp(8));
            optRow.addView(s);
        }

        for (int i = 0; i < pendingQ.options.length; i++) {
            final int k = i;
            optRow.addView(optBtn(pendingQ.options[i], () -> answer(k)));
        }
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
        int hpBefore = battle.playerHp;
        battle.endTurn(false);

        boolean hurt = battle.playerHp < hpBefore;
        if (battle.phase == GBattle.PHASE_LOSE) {
            render();
            return;
        }
        if (hurt) {
            int dmg = hpBefore - battle.playerHp;
            flash = "▶ " + (battle.enemy == null ? "敌人" : battle.enemy.name)
                    + " 攻击　你受到 " + dmg + " 伤害";
            addLog(flash, C_RED);
            sprite.play("attack");
        } else {
            addLog("── 你结束了回合（敌人还在蓄力）", C_FG3);
            sprite.play("move");
        }
        render();
    }

    private void answer(int pick) {
        boolean right = pick == pendingQ.answer;
        GCard c = pending;
        String explain = pendingQ.explain;
        pending = null;
        pendingQ = null;

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
                // 玩家出牌打人，怪物是"挨打"的一方 —— 播 hurt 不是 attack
                sprite.play("hurt");
            } else if (c.isAdverb()) {
                // 副词：消耗它并按 fx 改写本回合规则
                battle.hand.remove(c);
                battle.discard.add(c);
                battle.energy--;
                battle.applyAdverb(adverbFx(c.id));
                battle.lastMsg = "【" + c.en + "】" + c.desc;
                flash = "【副词】" + c.en + " — " + c.desc;
                addLog(flash, C_PURPLE);
                // 副词是玩家改写规则，怪物侧只轻微受创反馈
                sprite.play("hurt");
                if (battle.energy <= 0 || battle.hand.isEmpty()) advanceTurn();
            }
        } else {
            store.markWrong();
            battle.onWrong(c);
            flash = "✗ 答错：" + explain + "　敌人充能 +2，下次伤害翻倍";
            addLog(flash, C_RED);
            // 答错是玩家失误，怪物只是"蓄力"，播 cast 表现蓄力
            sprite.play("cast");
        }

        render();
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
        col.addView(statLine("已保牌", battle.kept.size() + " / " + data.keepLimit + " 张", null));
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
                    battle.enemy.atk + " 伤害（还需 " + left + " 回合）", C_AMBER));
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
        if (optRow.getChildCount() > 0
                && optRow.getTag() != null
                && "end".equals(optRow.getTag())) return;
        optRow.setTag("end");
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

        optRow.addView(bigBtn("继续", C_PURPLE, () -> {
            if (onFinish != null) onFinish.run();
        }));
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
}