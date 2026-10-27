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

        // 提示
        tvMsg = tv("", 12.5f, C_FG3);
        tvMsg.setPadding(dp(14), 0, dp(14), dp(6));
        addView(tvMsg);

        // 手牌区
        ScrollView hs = new ScrollView(getContext());
        hs.setHorizontalScrollBarEnabled(false);
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

        tvMsg.setText(battle.lastMsg);

        renderHand();
        renderOptions();

        if (battle.phase == GBattle.PHASE_WIN) {
            sprite.play("death");
            showEnd(true);
        } else if (battle.phase == GBattle.PHASE_LOSE) {
            sprite.play("death");
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

    /** 卡面。showPlay=true 时可点出牌。 */
    private View cardView(final GCard c, boolean clickable) {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setPadding(dp(9), dp(9), dp(9), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(11));
        g.setColor(c.isAdverb() ? 0xFF2A2440 : C_BG3);
        g.setStroke(dp(1), c.isAdverb() ? C_PURPLE : C_LINE);
        box.setBackground(g);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(104),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        box.setLayoutParams(lp);

        TextView en = tv(c.en, 13.5f, C_FG);
        en.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(en);

        TextView sub = tv(c.isAdverb() ? "副词·机制" : c.pos, 10, C_FG3);
        sub.setPadding(0, dp(1), 0, dp(3));
        box.addView(sub);

        TextView cn = tv(c.isAdverb() ? c.desc : c.cn, 11.5f, C_FG2);
        box.addView(cn);

        // 同义提示：和上一张同义则标出来
        if (battle != null && battle.lastPlayed != null
                && data.synonym(battle.lastPlayed.id, c.id)) {
            TextView tip = tv("◆ 连锁 ×" + (battle.chain + 1), 10, C_TEAL);
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
                    battle.attackDirect(); render();
                }));
                break;

            case GBattle.PHASE_PLAY:
                optRow.addView(smallBtn("结束回合", () -> {
                    battle.endTurn(false); render();
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

    private void answer(int pick) {
        boolean right = pick == pendingQ.answer;
        GCard c = pending;
        String explain = pendingQ.explain;
        pending = null;
        pendingQ = null;

        if (right) {
            store.markRight();
            if (c == null) {
                // 不该发生，防御性处理
            } else if (battle.phase == GBattle.PHASE_PLAY && c.isWord()) {
                int dmg = battle.playCard(c);
                sprite.play("attack");
                postDelayed(() -> { if (!battle.over()) sprite.play("idle"); }, 700);
            } else if (c.isAdverb()) {
                // 副词：消耗它并按 fx 改写本回合规则
                battle.hand.remove(c);
                battle.discard.add(c);
                battle.energy--;
                battle.applyAdverb(adverbFx(c.id));
                battle.lastMsg = "【" + c.en + "】" + c.desc;
                sprite.play("cast");
                postDelayed(() -> { if (!battle.over()) sprite.play("idle"); }, 700);
                if (battle.energy <= 0 || battle.hand.isEmpty()) battle.endTurn(false);
            }
        } else {
            store.markWrong();
            battle.onWrong(c);
            sprite.play("cast");
            postDelayed(() -> { if (!battle.over()) sprite.play("idle"); }, 700);
        }

        render();
        toast((right ? "✓ " : "✗ ") + explain);
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

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}