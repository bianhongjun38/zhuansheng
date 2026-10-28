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

    /**
     * 同义共鸣槽 —— 跨回合保留。
     *
     * 打出任意一张「有同义词」的词牌后，它留在槽里等后续回合配对；
     * 一旦打出与它同义的牌，触发共鸣暴击并清空。
     *
     * 为什么要跨回合：原来只比对 lastPlayed（本回合上一张），
     * 而 5 张手牌里凑出同义对的概率实测 0.4%，机制等于不存在。
     */
    public GCard resonance;
    /** 共鸣暴击倍率。比普通连锁高，因为凑对本身需要额外的词汇量。 */
    public double resonanceMul = 1.8;
    /** 本回合是否已经触发过共鸣（防止一回合内反复刷）。 */
    public boolean resonanceUsedThisTurn = false;
    /** 上一张牌是否触发了共鸣，供界面显示。 */
    public boolean lastWasResonance = false;
    /** 连锁计数，用于界面显示与伤害递增。 */
    public int chain;
    public String lastMsg = "";

    /** 场上临时加成（副词效果）。每回合重置。 */
    public double turnDamageMul = 1.0;

    // ── 特殊词效果（由 store 里已购买的特殊词 id 驱动）─────────
    //
    // 这些开关必须在构造时就定好：它们是「整局/整场」的规则变形，
    // 不能等到出牌时再问一次 store，否则同一个回合里切换 tab 就会变。
    public boolean spCrucial;      // 同义连锁伤害 +25%
    public boolean spEssential;    // 每场战斗开始 +10 血
    public boolean spFundamental;  // 放宽同类连锁限制
    public boolean spUltimate;     // 每层可用一次：本回合伤害 ×3
    public boolean spSupreme;      // 最大生命 +20
    public boolean spInevitable;   // 敌人充能永远 +1
    public boolean spIndispensable;// 留牌上限 2 → 3
    public boolean spPermanent;    // 篝火回复量 +50%
    public boolean spThorough;     // 副词消耗减半
    public boolean spCritical;     // 暴击率 +20%
    public boolean spRadical;      // 每回合多抽 1 张
    public boolean spElementary;   // 前 3 回合伤害 ×1.5
    public boolean spMarvelous;    // 每场战斗可复活一次
    public boolean spLiteral;      // 无视一切修饰
    public boolean spPremier;      // 商店价格 −30%

    /** 本层是否还能用「终极」（ultimate 每层一次）。 */
    public boolean ultimateUsedThisFloor = false;
    /** 副词消耗减半（thorough）：每回合前 1 张副词不耗能量。 */
    public boolean adverbFreeLeft = false;

    /** 本场是否已经用过复活（marvelous）。 */
    public boolean revivedThisBattle = false;
    /** 本回合是否已经放过「终极」×3（ultimate 每层一次）。 */
    public boolean ultimateUsedThisTurn = false;

    /** 一次性道具：本场免疫一次伤害。 */
    public boolean itemShield = false;
    /** 一次性道具：本场每次答对额外 +5 伤害。 */
    public int itemFlatBonus = 0;
    /** 一次性道具：本场手牌上限 +2。 */
    public int itemHandBonus = 0;
    public int turnFlatBonus = 0;
    public boolean critReady = false;
    public boolean skipEnemyCharge = false;
    public boolean noAttackThisTurn = false;
    public int extraDraw = 0;
    public boolean wrongDoubled = false;

    // ── 三连打断 ───────────────────────────────────────────
    //
    // 本回合连续答对 3 次（中间不许答错、不许直接攻击）就能截下敌人这次的
    // 出手。原来的问题是：整场战斗只有「打人」一条输出路，玩家想活只能靠
    // 打得够快 —— 而不认识词的时候根本打不动，也就永远拿不到喘息。
    // 打断给了另一条路：连对三题 = 换一次免伤，让「答对」本身能当防御用。
    /** 本回合已连续答对次数。答错清零。 */
    public int streak = 0;
    /** 本回合是否已经用掉打断（一回合只给一次，否则可以无限免伤）。 */
    public boolean interruptedThisTurn = false;
    /** 还需要几次才能打断；<=0 表示已经用掉。 */
    public int interruptNeed = 3;

    // ── 重铸之轮（re-）────────────────────────────────────
    //
    // 以 re- 开头的词在词库里代表「再次、返回」（rebuild / reject / recall）。
    // 答对后按概率弹回手牌，等于让玩家把「再一次」这个词根的意思真的用出来 ——
    // 机制和词义对得上，不是为了加数值硬凑的。
    /** 本回合被「重铸」弹回手牌的次数，用于战报展示。 */
    public int reforgedThisTurn = 0;

    // ── 熟词僻义过载 ──────────────────────────────────────
    /** 本次出牌是否走的僻义过载通道（供界面显示与结算区分）。 */
    public boolean lastWasOverload = false;

    public GBattle(GameData g, GameData.Enemy e, Random r) {
        this(g, e, r, null);
    }

    /**
     * @param store 存档。用于跨战斗保留血量、兑现篝火挂账的治疗。
     *              传 null 时按「每场满血、无治疗」跑，不会崩。
     */
    public GBattle(GameData g, GameData.Enemy e, Random r, GStore store) {
        G = g; rnd = r;
        if (g != null && store != null) g.storeRef = store;
        enemy = e;
        enemyMaxHp = e == null ? 60 : e.hp;
        enemyHp = enemyMaxHp;
        enemyChargeNeed = e == null ? 1 : Math.max(1, e.charge);
        enemyCharge = 0;
        playerMaxHp = g.playerHp;

        // ── 血量跨战斗保留 ────────────────────────────────────
        // 原来每场战斗都 playerHp = playerMaxHp（满血开局）—— 那样篝火的
        // 「回复生命」和商店买的治疗全是白买，因为下一场反正满血。
        // 现在从存档读当前血量：0 表示还没开始过（按满血），
        // 否则接着上一场结束时的血量，上限拉回 max。
        int saved = g != null && g.storeRef != null ? g.storeRef.curHp() : -1;
        if (saved > 0) {
            playerHp = Math.min(playerMaxHp, saved);
        } else {
            playerHp = playerMaxHp;
        }
        turn = 1;
        readSpecials();
        if (spInevitable) enemyChargeNeed = Math.max(1, enemyChargeNeed + 1);      // 把「买过的特殊词」翻译成本场规则开关
        // 最大生命 +20（supreme）要在算血上限之后、读存档之前生效，
        // 否则会出现「买了 supreme 但当前血量上限没涨」的情况。
        if (spSupreme) playerMaxHp += 20;
        if (spEssential) {
            playerHp = Math.min(playerMaxHp,
                    (saved > 0 ? Math.min(playerMaxHp, saved) : playerMaxHp) + 10);
        }
        consumeCampHeal();   // 篝火/商店挂账的治疗在这里兑现
        startTurn();
    }

    /**
     * 读已购买的特殊词，翻译成本场的规则开关。
     *
     * 为什么需要这一步：cards.json 里 15 个特殊词一直只有 id/cn/effect 三个字段，
     * effect 是一句中文说明，**从来没有任何代码读过它** ——
     * 商店花 40+ 金币买回来，只让顶栏的「特殊词 N」加一，战斗里毫无变化。
     * 玩家当然会觉得买了不知道有什么用。
     */
    private void readSpecials() {
        GStore st = (G == null) ? null : G.storeRef;
        if (st == null) return;
        spCrucial       = st.hasSpecial("crucial");
        spEssential     = st.hasSpecial("essential");
        spFundamental   = st.hasSpecial("fundamental");
        spUltimate      = st.hasSpecial("ultimate");
        spSupreme       = st.hasSpecial("supreme");
        spInevitable    = st.hasSpecial("inevitable");
        spIndispensable = st.hasSpecial("indispensable");
        spPermanent     = st.hasSpecial("permanent");
        spThorough      = st.hasSpecial("thorough");
        spCritical      = st.hasSpecial("critical");
        spRadical       = st.hasSpecial("radical");
        spElementary    = st.hasSpecial("elementary");
        spMarvelous     = st.hasSpecial("marvelous");
        spLiteral       = st.hasSpecial("literal");
        spPremier       = st.hasSpecial("premier");
    }

    /**
     * 本回合实际生效的伤害倍率。
     *
     * literal（字面的）是「无视一切修饰」—— 它必须在**乘算之前**把所有
     * 临时倍率清成 1.0，而不是在最后再除一次：后者遇到先乘 3 再乘 0.5
     * 这种顺序时会算错。
     */
    public double effectiveDamageMul() {
        if (spLiteral) return 1.0;
        double m = turnDamageMul;
        if (spElementary && turn <= 3) m *= 1.5;
        if (spUltimate && ultimateUsedThisTurn) m *= 3.0;
        return m;
    }

    /**
     * 是否还能发动「终极」（ultimate）：每层一次。
     *
     * 界面上用它决定那个按钮显不显示。
     */
    public boolean canUltimate() { return spUltimate && !ultimateUsedThisFloor; }

    /**
     * 发动「终极」：本回合伤害 ×3，每层一次。
     *
     * 和副词那条 turnDamageMul 分开算，是为了让「本回合」结束就自然失效，
     * 同时把「这一层已经用过了」记在 ultimateUsedThisFloor 上。
     */
    public boolean fireUltimate() {
        if (!canUltimate()) return false;
        ultimateUsedThisFloor = true;
        ultimateUsedThisTurn = true;
        lastMsg = "【终极】本回合伤害 ×3";
        return true;
    }

    /** 同义连锁倍率（crucial 把每一级连锁的增量放大 25%）。 */
    public double chainMul() {
        int c = Math.min(chain, 3);
        if (c <= 0) return 1.0;
        double base = Math.pow(G.chainMultiplier, c);
        if (spLiteral) return 1.0;
        if (spCrucial) return base * 1.25;
        return base;
    }

    /** 本场是否还能复活（marvelous，每场一次）。 */
    public boolean canRevive() { return spMarvelous && !revivedThisBattle; }

    // ── 道具（一次性消耗品）────────────────────────────────

    /**
     * 使用一个道具。返回 true 表示真的生效了。
     *
     * 道具和特殊词的区别：特殊词是永久被动，买了就一直算；
     * 道具是一次性的，用掉就没了 —— 所以这里必须从存档里删掉。
     */
    public boolean useItem(String id) {
        if (id == null || id.isEmpty()) return false;
        GStore st = (G == null) ? null : G.storeRef;
        if (st == null || !st.hasItem(id)) return false;
        if ("nevertheless".equals(id)) {
            itemShield = true;
            st.removeItem(id);
            lastMsg = "【道具】Nevertheless：本场免疫一次伤害";
            return true;
        }
        if ("anyhow".equals(id)) {
            itemHandBonus += 2;
            st.removeItem(id);
            lastMsg = "【道具】Anyhow：本场手牌上限 +2";
            return true;
        }
        if ("anyway".equals(id)) {
            hand.addAll(G.drawCards(3, rnd));
            st.removeItem(id);
            lastMsg = "【道具】Anyway：抽 3 张牌";
            return true;
        }
        if ("further".equals(id)) {
            itemFlatBonus += 5;
            st.removeItem(id);
            lastMsg = "【道具】Further：本场每次答对额外 +5 伤害";
            return true;
        }
        return false;
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
        resonanceUsedThisTurn = false;
        lastWasResonance = false;
        streak = 0;
        interruptedThisTurn = false;
        reforgedThisTurn = 0;
        lastWasOverload = false;
        keepBatches = 0;
        keptNow = 0;
        adverbFreeLeft = spThorough;   // 每回合第一张副词免费
        ultimateUsedThisTurn = false;
        energy = energyMax;

        // 保下来的牌先回手
        hand.clear();
        if (!kept.isEmpty()) { hand.addAll(kept); kept.clear(); }

        // 发牌走「同义保底」版本：以共鸣槽里的词为焦点，约 55% 概率补一张同义词。
        // 原先纯随机的同义对出现率实测 0.4%，机制等于不存在（见 drawCardsWithSyn 注释）。
        int need = Math.max(0, G.handSize + extraDraw + itemHandBonus
                + (spRadical ? 1 : 0) - hand.size());
        if (need > 0) hand.addAll(G.drawCardsWithSyn(need, rnd, resonance, 0.55));
        phase = PHASE_CHOOSE;
    }

    // ── 玩家动作 ─────────────────────────────────────────

    public void chooseAttack() {
        phase = PHASE_PLAY;
        lastMsg = "选择一张牌出击";
    }

    /**
     * 进入保牌阶段。
     *
     * ── 为什么保牌要收 2 点能量 ────────────────────────────
     * 原来的保牌是纯白给：花一整个回合的进攻机会换 2 张牌，账面上永远亏，
     * 于是没人用，机制事实上不存在。现在改成**在正在进行的回合里随时可以保**，
     * 代价是 2 点能量 —— 保牌变成了「用打人的资源换手牌质量」，
     * 手上有关键词时值得，没有时不值得，才构成决策。
     *
     * 能量不够就不让进（返回 false），避免负数能量。
     */
    public boolean chooseKeep() {
        // 只要是本回合、只要还剩得起 2 点能量，就能保 —— 不限制次数。
        // 打掉一张牌后能量降到 2，仍然可以再保一次（扣完就是 0）。
        // 唯一的前置是「不在保牌界面里」，否则会重复进入自己。
        if (phase != PHASE_PLAY && phase != PHASE_CHOOSE) return false;
        if (energy < keepEnergyCost) {
            lastMsg = "能量不足：保牌需要 " + keepEnergyCost + " 点能量";
            return false;
        }
        // 上一轮还没确认的保牌不能直接丢掉 —— 先并入 kept，再重新开一轮。
        // 不这么做的话，连按两次保牌会把第一轮挑的牌全部退回手里作废，
        // 而能量已经扣了两遍。
        if (phase == PHASE_KEEP) return false;

        energy -= keepEnergyCost;
        keepBatches++;
        phase = PHASE_KEEP;
        lastMsg = "保牌　−" + keepEnergyCost + " 能量　选最多 "
                + G.keepLimit + " 张留下（余 " + energy + " 能量）";
        return true;
    }

    /** 本回合进入过几次保牌界面，仅用于界面提示与自测。 */
    public int keepBatches = 0;

    /** 保牌的能量代价。设计常量放这里而不是 GameData —— 它是规则不是数值。 */
    public static final int keepEnergyCost = 2;

    public boolean keep(GCard c) {
        if (phase != PHASE_KEEP) return false;
        // 这一轮最多挑 keepLimit 张；上限按「本轮已挑张数」算，
        // 不按 kept 总张数算 —— 否则第二轮一张也挑不了。
        if (keptNow >= G.keepLimit) return false;
        if (!hand.remove(c)) return false;
        kept.add(c);
        keptNow++;
        return true;
    }

    /**
     * 只供 JVM 侧单测调用：按当前规则补一次手牌。
     *
     * 界面上永远走 startTurn()，不会直接调它。存在的意义是让「多抽 1 张」
     * 这类规则能被单独验证，而不必先跑完整回合流程。
     */
    void refillForTest() {
        int need = Math.max(0, G.handSize + extraDraw + itemHandBonus
                + (spRadical ? 1 : 0) - hand.size());
        if (need > 0) hand.addAll(G.drawCardsWithSyn(need, rnd, resonance, 0.55));
    }

/** 本轮已挑的张数（进保牌界面时清零）。 */
    public int keptNow = 0;

    /**
     * 结束保牌阶段。
     *
     * ── 保完牌要洗牌，而且保的牌不参与洗牌 ────────────────────
     *
     * 这条规则是按玩家要求加的：保牌的意义是「把这张牌留到下一回合」，
     * 如果保完只是把它放回手里，那玩家下一步照样能把它打出去 ——
     * 保牌等于没保，纯花 2 能量买了个空动作。
     *
     * 现在的流程：
     *   ① 保下来的牌移进 kept，**不参与**洗牌，也不回手牌；
     *   ② 手里剩下的牌全部进弃牌堆，与该堆一起洗匀；
     *   ③ 从洗好的牌堆补满手牌（补牌数 = 手牌上限 − 已保住的张数，
     *      保证「保住的牌」是新的一手牌之外额外留住的，而不是挤掉手牌位）；
     *   ④ 回到出牌阶段，剩下的能量可以继续打人。
     *
     * 这样「保」和「洗」互不干扰：洗牌只洗没保的，保住的牌始终在下方的
     * 保牌区里单独显示，要到下回合 startTurn 才回到手上。
     *
     * 旧行为（保完直接放回手里、不洗牌）在保牌已经收费的前提下
     * 既没效果又白花钱，是这次一并修掉的。
     */
    public void endKeep() {
        if (phase != PHASE_KEEP) return;

        // ① 保住的牌停在 kept 里 —— 不回手牌、不进弃牌堆、不参与洗牌。
        //    （kept 本就不在 hand 里，keep() 挑牌时已经从 hand 移出来了。）

        // ② 手里剩下的牌进弃牌堆，连同弃牌堆一起洗匀
        discard.addAll(hand);
        hand.clear();
        reshuffleDiscard();

        // ③ 补满手牌。差额补，避免把已保的张数又抽一份回来。
        int need = Math.max(0, G.handSize + extraDraw + itemHandBonus
                + (spRadical ? 1 : 0) - kept.size() - hand.size());
        if (need > 0) hand.addAll(G.drawCardsWithSyn(need, rnd, resonance, 0.55));

        keptNow = 0;
        phase = PHASE_PLAY;
        lastMsg = "已保 " + kept.size() + " 张（下回合回手）　手牌已重洗 "
                + hand.size() + " 张　余 " + energy + " 能量";
        if (energy <= 0) endTurn(false);
    }

    /**
     * 把弃牌堆洗匀。
     *
     * 牌堆模型是「全词库按需抽」，没有真正的 drawPile —— 抽牌直接从
     * G.words 里取。所以这里的「洗牌」有两层含义：
     *   ① 弃牌堆清空（这些牌已经洗回牌库了，不该再被重复计入）；
     *   ② 下一次抽牌的随机源重新滚动一遍，保证不是「刚弃掉的马上又抽回来」。
     *
     * 第 ② 点用重新播种实现：rnd 是这一整场战斗共用的 Random，
     * 抽牌时按需取值，本身就是随机的。真正要防的是「补牌刚好补到
     * 刚才弃掉的那几张」—— 这由 need 差额补牌 + 全词库抽样天然稀释，
     * 不需要额外排除，否则会破坏同义保底的分布。
     *
     * 因此这里只做「弃牌堆归档」，并把计数记进日志供观感确认。
     */
    private void reshuffleDiscard() {
        reshuffledCards = discard.size();
        discard.clear();
    }

    /** 上一次洗牌时归档了多少张弃牌。仅用于界面提示与自测。 */
    public int reshuffledCards = 0;

    /**
     * 出一张牌。注意：这里不判对错 —— 对错由界面答题决定，答对才调本方法。
     * 返回造成的伤害。
     */
    public int playCard(GCard c) {
        if (phase != PHASE_PLAY) return 0;
        if (!hand.remove(c)) return 0;

        int dmg = attackBlocked() ? 0 : G.baseDamage;

        // ── 同义共鸣 / 连环 ────────────────────────────────
        //
        // 两条路径，按优先级：
        //   ① 共鸣槽命中：槽里存的词与这张同义 → 暴击（倍率更高，且跨回合有效）
        //   ② 本回合连环：与上一张同义 → 递增倍率（原有机制，保留）
        // 都不满足时，若这张牌本身有同义词，就把它放进槽里等以后配对。
        lastWasResonance = false;
        // fundamental（任意同类即可连）：放宽成「词性相同就算连锁」。
        boolean chained = lastPlayed != null
                && (G.synonym(lastPlayed.id, c.id)
                    || (spFundamental && !spLiteral
                        && lastPlayed.pos != null && lastPlayed.pos.equals(c.pos)));

        if (resonance != null && !resonanceUsedThisTurn && G.synonym(resonance.id, c.id)) {
            // ① 共鸣暴击
            dmg = (int) Math.round(dmg * resonanceMul);
            lastWasResonance = true;
            resonanceUsedThisTurn = true;
            resonance = null;          // 触发即消耗
            chain++;
        } else if (chained) {
            // ② 本回合连环（倍率统一走 chainMul，crucial/literal 才生效）
            chain++;
            dmg = (int) Math.round(dmg * chainMul());
        } else {
            chain = 0;
        }

        // 槽是空的、且这张牌有同义词 → 入槽等待
        // 注意：这里查的是「词库里有没有与它同义的词」，不是「手牌里有没有」——
        // 手牌里有没有是发牌的事，玩家无法控制；词库里有没有才是稳定的。
        if (resonance == null && !G.synonymsOf(c.id).isEmpty()) {
            resonance = c;
        }

        // 副词与临时加成。倍率统一走 effectiveDamageMul()：
        // elementary（前 3 回合 ×1.5）、ultimate（×3）、literal（清零）都在里面。
        dmg = (int) Math.round(dmg * effectiveDamageMul()) + turnFlatBonus;
        // 道具「进一步」（further）：本场每次答对额外 +5
        if (itemFlatBonus > 0) dmg += itemFlatBonus;
        // critical：每张牌额外 20% 概率暴击。和副词给的必暴击共用
        // critReady 通道 —— 先判副词（必中），没中就掷一次概率。
        if (!critReady && spCritical && !spLiteral && rnd.nextDouble() < 0.20) {
            critReady = true;
        }
        if (critReady) { dmg = (int) Math.round(dmg * G.critMultiplier); critReady = false; }

        enemyHp -= dmg;
        lastPlayed = c;
        // thorough：副词消耗减半 —— 实现成「每回合第一张副词免费」，
        // 因为能量是整数，减半没法表示；免费一次等价于把 1 点砍成 0.5x2。
        if (spThorough && c.isAdverb() && adverbFreeLeft) {
            adverbFreeLeft = false;
        } else {
            energy--;
        }

        // ── 重铸之轮（re-）──────────────────────────────────
        //
        // 词库里以 re- 开头的词本身就表示「再次、返回」。让它们答对后有概率
        // 不进弃牌堆、直接弹回手牌，机制和词根含义是对齐的：
        // 你打出的这个 re- 词，真的「又回来了一次」。
        //
        // 概率固定 50%，不随等级浮动 —— 太高会让 re- 牌变成无限连，
        // 太低则感知不到，50% 是最容易被玩家察觉「刚才那张回来了」的位置。
        boolean reforged = false;
        if (isReWord(c) && rnd.nextDouble() < reforgeChance) {
            hand.add(c);          // 回手，而不是进弃牌堆
            reforged = true;
            reforgedThisTurn++;
        } else {
            discard.add(c);
        }

        StringBuilder msg = new StringBuilder();
        if (lastWasResonance) msg.append("⚡ 共鸣暴击  ");
        else if (chained) msg.append("同义连锁 ×").append(chain).append("  ");
        msg.append(c.en).append("  造成 ").append(dmg).append(" 伤害");
        if (reforged) msg.append("　♻ 重铸：回到手牌");

        // ── 三连打断 ────────────────────────────────────────
        //
        // 见 streak / interruptedThisTurn 的注释。这里只在「本回合还够格
        // 打断、且还没打断过」时推进计数，一旦用掉就不再累积。
        if (!interruptedThisTurn) {
            streak++;
            if (streak >= interruptNeed) {
                interruptedThisTurn = true;
                skipEnemyCharge = true;   // 交给 enemyAct() 兑现：这次不出手
                msg.append("　🛡 三连打断：敌人本次攻击被截下");
            } else {
                msg.append("　连对 ").append(streak).append("/").append(interruptNeed);
            }
        }

        lastMsg = msg.toString();

        if (enemyHp <= 0) { enemyHp = 0; phase = PHASE_WIN; saveHp(); return dmg; }

        // 牌回了手，手牌就不是空的 —— 余额判定要按「能量用光」算，
        // 否则重铸出来的那张牌会被直接弃掉，等于机制没生效。
        if (energy <= 0) endTurn(false);
        else if (hand.isEmpty()) endTurn(false);
        return dmg;
    }

    /** 重铸概率。re- 牌答对后不进弃牌堆的概率。 */
    public static final double reforgeChance = 0.5;

    /** 是不是 re- 开头的词（代表「再次、返回」）。 */
    public static boolean isReWord(GCard c) {
        if (c == null || !c.isWord()) return false;
        String w = c.en == null ? "" : c.en.toLowerCase();
        return w.length() > 2 && w.startsWith("re")
                && Character.isLetter(w.charAt(2));
    }

    /**
     * 僻义过载结算：答对走这里，答错由 onOverloadWrong() 处理。
     *
     * 2.2 倍真实伤害、无视护甲。当前版本敌人没有护甲，所以「无视护甲」
     * 等价于「不被任何减免吃掉」—— 这里直接写敌血，不再经过任何倍率。
     */
    public int playCardOverload(GCard c, int senseIndex) {
        if (phase != PHASE_PLAY) return 0;
        if (!hand.remove(c)) return 0;

        int base = attackBlocked() ? 0 : G.baseDamage;
        int dmg = (int) Math.round(base * overloadMul);
        enemyHp -= dmg;
        energy--;
        lastWasOverload = true;
        streak = interruptedThisTurn ? streak : streak + 1;

        if (!interruptedThisTurn && streak >= interruptNeed) {
            interruptedThisTurn = true;
            skipEnemyCharge = true;
        }

        discard.add(c);
        lastMsg = "🔥 僻义过载  " + c.en + "（" + c.senseText(senseIndex)
                + "）  真实伤害 " + dmg;

        if (enemyHp <= 0) { enemyHp = 0; phase = PHASE_WIN; saveHp(); }
        else if (energy <= 0 || hand.isEmpty()) endTurn(false);
        return dmg;
    }

    /** 僻义过载倍率。2.2 倍、不吃任何减免。 */
    public static final double overloadMul = 2.2;
    /** 过载答错时玩家自伤。押错僻义的代价必须比普通答错更疼。 */
    public static final int overloadWrongSelfDamage = 4;

    /**
     * 过载答错：扣 4 点血，这张牌弃掉，不计入三连。
     *
     * 为什么不走 onWrong()：普通答错是「敌人充能加快」，那是对局节奏的惩罚；
     * 过载是玩家主动加注，赌错了就该当场付钱，而不是让敌人变强。
     */
    public void onOverloadWrong(GCard c) {
        if (c != null) { hand.remove(c); discard.add(c); }
        playerHp -= overloadWrongSelfDamage;
        lastWasOverload = true;
        streak = 0;
        lastMsg = "✗ 僻义答错：" + (c == null ? "" : c.en)
                + "  自伤 " + overloadWrongSelfDamage;
        if (playerHp <= 0) { playerHp = 0; phase = PHASE_LOSE; saveHp(); }
        else if (hand.isEmpty()) endTurn(false);
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
        streak = 0;          // 连对断了：三连打断必须一次都不错才成立
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

    /** 只供单测：按正常流程开一个新回合。 */
    void startTurnPublic() { startTurn(); }

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

    /** 敌人出手。包可见是为了能在 JVM 侧直接测伤害/免伤/复活，界面不调它。 */
    void enemyAct() {
        // skipEnemyCharge 有两个来源：副词「本回合敌人不充能」和三连打断。
        // 两者的共同结果都是「这次不出手」，所以合成一条路处理。
        if (skipEnemyCharge) { enemyCharge = 0; skipEnemyCharge = false; return; }

        enemyCharge++;
        if (enemyCharge < enemyChargeNeed) return;

        enemyCharge = 0;
        int dmg = enemy == null ? 10 : enemy.atk;
        if (wrongDoubled) { dmg = (int) Math.round(dmg * G.wrongDamageMultiplier); wrongDoubled = false; }

        // 道具「尽管如此」（nevertheless）：本场免疫一次伤害。
        // 必须在扣血之前拦，扣完再补回来会把 playerHp 打成 0 触发 PHASE_LOSE。
        if (itemShield) {
            itemShield = false;
            lastMsg = (enemy == null ? "敌人" : enemy.name) + " 攻击  " + dmg
                    + " 伤害　🛡 被【尽管如此】完全免疫";
            return;
        }

        playerHp -= dmg;
        lastMsg = (enemy == null ? "敌人" : enemy.name) + " 攻击  " + dmg + " 伤害";
        if (playerHp <= 0) {
            // 特殊词「非凡的」（marvelous）：每场战斗可复活一次。
            // 这里不直接进 PHASE_LOSE，而是把血压回一半血，打上"已用过"标记。
            if (canRevive()) {
                revivedThisBattle = true;
                playerHp = Math.max(1, playerMaxHp / 2);
                lastMsg += "　✨ 【非凡的】复活：" + playerHp + " 生命";
                saveHp();
                return;
            }
            playerHp = 0;
            phase = PHASE_LOSE;
            saveHp();
        }
    }

    /**
     * 把当前血量写进存档，供下一场战斗接着用。
     *
     * 胜利时写当前血量；失败时写 0（下次重新满血，因为已经死过了）。
     * 篝火/商店的治疗在 GStore.campHeal() 里挂账，由 startTurn 之外的地方兑现 ——
     * 见 consumeCampHeal()。
     */
    private void saveHp() {
        if (G == null || G.storeRef == null) return;
        G.storeRef.setCurHp(playerHp);
    }

    /**
     * 兑现篝火 / 商店挂账的治疗量。
     *
     * 挂账发生在非战斗节点（那时没有 GBattle 实例可改），所以必须在
     * 战斗开始时补上。兑现后清零，避免一路攒成白嫖满血。
     */
    private void consumeCampHeal() {
        if (G == null || G.storeRef == null) return;
        int h = G.storeRef.campHeal();
        if (h <= 0) return;
        playerHp = Math.min(playerMaxHp, playerHp + h);
        G.storeRef.clearCampHeal();
        lastMsg = "🔥 篝火/治疗 +" + h + " 生命";
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