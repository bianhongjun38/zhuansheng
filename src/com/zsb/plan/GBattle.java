package com.zsb.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 战斗引擎 —— 纯逻辑，不碰 Android，能在电脑上直接跑测试。
 *
 * 与 Slay the Spire 的差异（都是为了让「答对」成为唯一输出手段）：
 *   1. 出牌必须先答对题。答错不消耗能量、不给伤害，而是敌人充能 +2
 *      且下次伤害翻倍 —— 用惩罚错误代替奖励运气。
 *   2. 同义连锁：连续打出的两张牌若同义，后一张 ×1.5。这条是核心，
 *      因为它逼着玩家在「手上有什么」和「词义关系」之间做取舍。
 *   3. 每回合要在【攻击】和【保牌】之间选一个：攻击就是出牌打人，
 *      保牌是留 2 张到下回合（保的牌也占手牌上限）。
 *
 * 为什么留牌上限是 2 而不是更多：模拟下来 3 张以上会让高手直接锁死
 * 连锁链，战斗退化成无脑循环；2 张保住紧张感又不至于无解。
 */
public class GBattle {

    public static final int PHASE_CHOOSE = 0;   // 选【攻击】/【保牌】
    public static final int PHASE_PLAY   = 1;   // 攻击阶段：出牌
    public static final int PHASE_KEEP   = 2;   // 保牌阶段：挑 2 张留下
    public static final int PHASE_QUIZ   = 3;   // 答题中
    public static final int PHASE_WIN    = 4;
    public static final int PHASE_LOSE   = 5;

    private final GameData G;
    private final Random rnd;

    public final List<GCard> hand = new ArrayList<>();
    public final List<GCard> kept = new ArrayList<>();   // 上回合保下来的
    public final List<GCard> discard = new ArrayList<>();

    public GameData.Enemy enemy;
    public int enemyHp, enemyMaxHp;
    public int enemyCharge, enemyChargeNeed;
    public int playerHp, playerMaxHp;

    public int turn;
    public int phase = PHASE_CHOOSE;
    public int energy, energyMax = 3;

    /** 本回合已出过牌的最后一张，用于同义连锁判定。 */
    public GCard lastPlayed;
    /** 连锁计数，用于界面显示与伤害递增。 */
    public int chain;
    public String lastMsg = "";

    /** 场上临时加成（副词效果）。每回合重置。 */
    public double turnDamageMul = 1.0;
    public int turnFlatBonus = 0;
    public boolean critReady = false;
    public boolean skipEnemyCharge = false;
    public boolean noAttackThisTurn = false;
    public int extraDraw = 0;
    public boolean wrongDoubled = false;

    public GBattle(GameData g, GameData.Enemy e, Random r) {
        G = g; rnd = r;
        enemy = e;
        enemyMaxHp = e == null ? 60 : e.hp;
        enemyHp = enemyMaxHp;
        enemyChargeNeed = e == null ? 1 : Math.max(1, e.charge);
        enemyCharge = 0;
        playerMaxHp = g.playerHp;
        playerHp = playerMaxHp;
        turn = 1;
        startTurn();
    }

    // ── 回合开始 ─────────────────────────────────────────

    private void startTurn() {
        turnDamageMul = 1.0;
        turnFlatBonus = 0;
        critReady = false;
        skipEnemyCharge = false;
        noAttackThisTurn = false;
        lastPlayed = null;
        chain = 0;
        energy = energyMax;

        // 保下来的牌先回手
        hand.clear();
        if (!kept.isEmpty()) { hand.addAll(kept); kept.clear(); }

        int need = Math.max(0, G.handSize + extraDraw - hand.size());
        if (need > 0) hand.addAll(G.drawCards(need, rnd));
        phase = PHASE_CHOOSE;
    }

    // ── 玩家动作 ─────────────────────────────────────────

    public void chooseAttack() {
        phase = PHASE_PLAY;
        lastMsg = "选择一张牌出击";
    }

    public void chooseKeep() {
        phase = PHASE_KEEP;
        lastMsg = "选择最多 " + G.keepLimit + " 张牌保留到下回合";
    }

    public boolean keep(GCard c) {
        if (phase != PHASE_KEEP) return false;
        if (kept.size() >= G.keepLimit) return false;
        if (!hand.remove(c)) return false;
        kept.add(c);
        return true;
    }

    public void endKeep() {
        // 没保的牌全部弃掉
        discard.addAll(hand);
        hand.clear();
        endTurn(false);
    }

    /**
     * 出一张牌。注意：这里不判对错 —— 对错由界面答题决定，答对才调本方法。
     * 返回造成的伤害。
     */
    public int playCard(GCard c) {
        if (phase != PHASE_PLAY) return 0;
        if (!hand.remove(c)) return 0;

        int dmg = attackBlocked() ? 0 : G.baseDamage;

        // 同义连锁
        boolean chained = lastPlayed != null && G.synonym(lastPlayed.id, c.id);
        if (chained) {
            chain++;
            dmg = (int) Math.round(dmg * Math.pow(G.chainMultiplier, Math.min(chain, 3)));
        } else {
            chain = 0;
        }

        // 副词与临时加成
        dmg = (int) Math.round(dmg * turnDamageMul) + turnFlatBonus;
        if (critReady) { dmg = (int) Math.round(dmg * G.critMultiplier); critReady = false; }

        enemyHp -= dmg;
        lastPlayed = c;
        discard.add(c);
        energy--;

        lastMsg = (chained ? "同义连锁 ×" + chain + "  " : "") + c.en + "  造成 " + dmg + " 伤害";

        if (enemyHp <= 0) { enemyHp = 0; phase = PHASE_WIN; }
        else if (energy <= 0 || hand.isEmpty()) endTurn(false);
        return dmg;
    }

    /**
     * 答错：不给伤害，敌人充能 +2、下次伤害翻倍，并且这张牌作废。
     *
     * 三个数值都是反复试出来的：
     *   · 不扣能量 —— 扣能量会让一次失误直接报废整回合，没有翻身余地；
     *   · 但牌必须弃掉 —— 否则玩家可以对着同一个词无限重试，
     *     等于把「不认识」变成「多试几次就过」，学习目的直接失效；
     *   · 充能 +2 且伤害翻倍 —— 惩罚落在「敌人更快打你」上，
     *     压力真实存在但不至于一刀毙命。
     * 手牌上限 5，所以一回合最多错 5 次，错满就只能直接攻击或结束回合。
     */
    public void onWrong(GCard c) {
        enemyCharge += G.wrongChargeUp;
        wrongDoubled = true;
        if (c != null) { hand.remove(c); discard.add(c); }
        lastMsg = "答错：" + (c == null ? "" : c.en) + "  敌人充能 +"
                + G.wrongChargeUp + "，下次伤害翻倍";
        if (hand.isEmpty()) endTurn(false);
    }

    /** 直接攻击（不出牌、跳过答题），造成小量固定伤害。 */
    public int attackDirect() {
        int dmg = Math.max(4, G.baseDamage / 3);
        enemyHp -= dmg;
        lastMsg = "直接攻击 " + dmg + " 伤害";
        if (enemyHp <= 0) { enemyHp = 0; phase = PHASE_WIN; }
        else endTurn(false);
        return dmg;
    }


    /**
     * 应用副词效果。词表在 assets/game/cards.json 的 adverbs 段，
     * 每个副词带一个 fx 标识，这里按标识分派。
     *
     * 为什么副词不做成「加减伤害」而要做成「改写规则」：
     * 单纯加数值的副词玩家无脑出就行，不构成决策；
     * 而「本回合只能出一张牌但伤害 ×3」逼着玩家判断现在是不是打爆发的时候。
     */
    public void applyAdverb(String fx) {
        if (fx == null) return;
        switch (fx) {
            case "nextCardX2_single":
                turnDamageMul *= 2.0;
                break;
            case "firstX2_5_restHalf":
                turnDamageMul *= 2.5;
                break;
            case "ramp30_hp5":
                turnFlatBonus += 4;
                playerHp = Math.max(1, playerHp - 5);
                break;
            case "singleX3":
                turnDamageMul *= 3.0;
                break;
            case "allFixed12":
                turnDamageMul = 1.0;
                turnFlatBonus = 0;
                break;
            case "guaranteedCrit_chargeUp1":
                critReady = true;
                enemyCharge += 1;
                break;
            case "discardAll_plus8":
                turnFlatBonus += hand.size() * 8;
                discard.addAll(hand);
                hand.clear();
                break;
            case "chargeDown1_dmgDown30":
                enemyCharge = Math.max(0, enemyCharge - 1);
                turnDamageMul *= 0.7;
                break;
            case "noCharge_noAttack":
                skipEnemyCharge = true;
                break;
            case "true20_discard1":
                enemyHp -= 20;
                if (!hand.isEmpty()) discard.add(hand.remove(0));
                if (enemyHp <= 0) { enemyHp = 0; phase = PHASE_WIN; }
                break;
            case "skipCharge_chargeUp1":
                skipEnemyCharge = true;
                enemyCharge = Math.max(0, enemyCharge - 1);
                break;
            case "rampPerTurn2":
                turnFlatBonus += turn * 2;
                break;
            case "skipTurn_nextX2":
                turnDamageMul *= 2.0;
                break;
            case "noConsume_halfDmg":
                turnDamageMul *= 0.5;
                break;
            case "redrawHand_chargeUp1":
                discard.addAll(hand);
                hand.clear();
                hand.addAll(G.drawCards(G.handSize, rnd));
                enemyCharge += 1;
                break;
            case "draw2_dmgHalf":
                hand.addAll(G.drawCards(2, rnd));
                turnDamageMul *= 0.5;
                break;
            case "chargeDownPerCard_dmg40":
                enemyCharge = Math.max(0, enemyCharge - 1);
                turnDamageMul *= 0.6;
                break;
            case "enemyNextHalf_noAttack":
                noAttackThisTurn = true;
                wrongDoubled = false;
                break;
            case "handPlus3_unusable":
                hand.addAll(G.drawCards(3, rnd));
                break;
            case "heal15orClearCharge":
                if (rnd.nextBoolean()) playerHp = Math.min(playerMaxHp, playerHp + 15);
                else enemyCharge = 0;
                break;
            case "randomRareCard":
                hand.addAll(G.drawCards(1, rnd));
                break;
            case "enemyFullChargeNext":
                enemyCharge = Math.max(0, enemyChargeNeed - 1);
                break;
            default:
                break;
        }
    }

    /** 本回合是否被禁止攻击（hence 副词）。 */
    public boolean attackBlocked() { return noAttackThisTurn; }

    // ── 回合结束 / 敌人行动 ──────────────────────────────

    public void endTurn(boolean fromKeep) {
        if (phase == PHASE_WIN || phase == PHASE_LOSE) return;
        if (!fromKeep) discard.addAll(hand);
        hand.clear();

        enemyAct();
        if (phase == PHASE_LOSE) return;

        turn++;
        startTurn();
    }

    private void enemyAct() {
        if (skipEnemyCharge) { enemyCharge = 0; skipEnemyCharge = false; return; }

        enemyCharge++;
        if (enemyCharge < enemyChargeNeed) return;

        enemyCharge = 0;
        int dmg = enemy == null ? 10 : enemy.atk;
        if (wrongDoubled) { dmg = (int) Math.round(dmg * G.wrongDamageMultiplier); wrongDoubled = false; }

        playerHp -= dmg;
        lastMsg = (enemy == null ? "敌人" : enemy.name) + " 攻击  " + dmg + " 伤害";
        if (playerHp <= 0) {
            playerHp = 0;
            phase = PHASE_LOSE;
        }
    }

    // ── 查询 ─────────────────────────────────────────────

    public boolean over() { return phase == PHASE_WIN || phase == PHASE_LOSE; }
    public boolean won()  { return phase == PHASE_WIN; }

    public int goldReward() {
        if (enemy == null) return G.battleGoldMin;
        if (enemy.tier.startsWith("elite")) return G.eliteGold;
        return G.battleGoldMin + rnd.nextInt(Math.max(1, G.battleGoldMax - G.battleGoldMin + 1));
    }
}