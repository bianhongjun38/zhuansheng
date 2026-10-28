package com.zsb.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 出题器 —— 敌人出题，玩家从**手牌里**挑出对应的那张牌作答。
 *
 * ── 为什么要改成这个方向 ────────────────────────────────────
 *
 * 旧版是「玩家先挑牌，系统考这张牌」。这样有两个致命问题：
 *
 *   1. 挑牌本来就是选自己认识的，等于玩家自己给自己出题 ——
 *      只挑会的，永远不会被考到不会的，学习目的直接失效。
 *   2. 中译英时手牌一直显示在屏幕上，而正确答案就是刚点的那张牌上的英文，
 *      答案明写在牌面上。
 *
 * 现在改成：**敌人出题，答案藏在 5 张手牌里**。
 *   题面用中文释义或英文例句，玩家必须读懂题面才能从手里挑对那张牌。
 *   挑错就把那张牌弃掉 —— 牌是你自己选的，认错了代价自己承担。
 *
 * ── 四种题型 ────────────────────────────────────────────────
 *
 *   0 释义题   题面「abandon 放弃，抛弃」→ 选 abandon（答案就在手牌里）
 *   1 例句题   题面「The captain gave the order to ___ ship.」→ 选 abandon
 *   2 英文听辨 题面给中文释义 → 选对应的英文牌
 *   3 释义辨析 题面给中文释义 + 四个英文词作选项，其中只有一个是手牌里的正确项
 *
 * 所有题型的共同铁律：**正确答案必须是手牌里的一张**，
 * 且干扰项一律不能是手牌里的其它牌（否则会出现两个正确答案）。
 */
public class GQuiz {

    public static class Q {
        public String prompt = "";
        public String sub = "";
        public String[] options;
        public int answer = -1;
        public String explain = "";
        public GCard card;          // 正确答案那张牌
        public int mode;            // 题型，见类注释
        /** 仅 mode==4（僻义过载）有效：本次考的是第几个义项。 */
        public int senseIndex = -1;
    }

    private final GameData G;
    private final Random rnd;

    public GQuiz(GameData g, Random r) { G = g; rnd = r; }

    /** 释义净化：只取第一个义项，避免选项框被撑成三行、以及同义串重复。 */
    private static String firstSense(String cn) {
        if (cn == null) return "";
        String s = cn.trim();
        for (String sep : new String[]{"；", ";", "，", ",", "、", "/"}) {
            int i = s.indexOf(sep);
            if (i > 0) s = s.substring(0, i).trim();
        }
        return s;
    }

    private static String key(String cn) { return firstSense(cn); }

    /**
     * 供界面取「首个义项」。
     *
     * 战斗里怪物答对后的台词要用它（把词义复述一遍）。
     * 不去 GBattleView 里复制一份切分逻辑 —— 两处各写一份，
     * 迟早会一边改了另一边没改，题目和台词对不上。
     */
    public static String firstSensePublic(String cn) { return firstSense(cn); }

    /** 随机挑一种题型。例句题要求有例句，所以在出题时再校验。 */
    public int randomMode() {
        int r = rnd.nextInt(100);
        if (r < 40) return 0;   // 释义题
        if (r < 70) return 1;   // 例句题
        if (r < 85) return 2;   // 中译英
        return 3;               // 释义辨析
    }

    /**
     * 敌人出题。
     *
     * @param hand 玩家当前手牌 —— 正确答案必须从这里面挑
     * @param mode 期望题型；凑不出就自动退化
     * @return 题目；手牌全是用不了的牌时返回 null（界面据此给出提示）
     */
    public Q make(List<GCard> hand, int mode) {
        if (hand == null || hand.isEmpty()) return null;

        // 只拿「词牌」出题。副词是机制牌，考释义没有意义。
        List<GCard> pool = new ArrayList<>();
        for (GCard c : hand) {
            if (c != null && c.isWord() && !firstSense(c.cn).isEmpty()) pool.add(c);
        }
        if (pool.isEmpty()) return null;

        GCard target = pool.get(rnd.nextInt(pool.size()));

        switch (mode) {
            case 1:  {
                // 例句题最容易撞（两个词都能填空），撞了就换一个能答的目标，
                // 都不行再退到释义题。
                List<GCard> shuffled = new ArrayList<>(pool);
                Collections.shuffle(shuffled, rnd);
                for (GCard cand : shuffled) {
                    Q q = exampleQ(cand, hand);
                    if (q != null) return q;
                }
                break;
            }
            case 2:  { Q q = cnToEnQ(target);   if (q != null) return q; break; }
            case 3:  { Q q = discriminateQ(target, hand); if (q != null) return q; break; }
            default: break;
        }
        return meaningQ(target);
    }

    // ── 题型 0：释义题 ────────────────────────────────────
    // 题面是「英文 + 中文释义」，但**把英文挖掉**，只给中文，让玩家从手里找。
    // 若直接把英文写在题面上，答案就等于写在题面上了。
    private Q meaningQ(GCard c) {
        Q q = new Q();
        q.card = c;
        q.mode = 0;
        q.prompt = firstSense(c.cn);
        q.sub = "选出对应的单词" + (c.pos.isEmpty() ? "" : "（" + c.pos + "）");
        q.explain = c.en + " = " + c.cn;
        return q;   // 选项由界面用「手牌本身」渲染，不用 options
    }

    // ── 题型 1：例句题 ────────────────────────────────────
    // 例句里把这个词挖成 ___，玩家读句子猜该填哪个。这题真正考理解。
    //
    // ⚠ 必须检查「手牌里有没有第二个词也能填这个空」。实测 0.9% 的句子会撞：
    //    目标 ignore，但手牌里的 time 也能匹配进 "in a short period of time" ——
    //    这时玩家选 time 逻辑上完全正确，却被判答错并弃牌，是冤枉的。
    //    撞了就放弃这道题，由上层换题型。
    private Q exampleQ(GCard c, List<GCard> hand) {
        String ex = c.ex == null ? "" : c.ex.trim();
        if (ex.isEmpty()) return null;

        String blanked = blankOut(ex, c.en);
        if (blanked == null) return null;    // 例句里找不到这个词，出不了题

        for (GCard o : hand) {
            if (o == null || o == c || !o.isWord()) continue;
            if (blankOut(ex, o.en) != null) return null;   // 有第二个正确答案
        }

        Q q = new Q();
        q.card = c;
        q.mode = 1;
        q.prompt = blanked;
        q.sub = "选出能填进空格的单词";
        q.explain = c.en + " = " + c.cn + "　例：" + ex;
        return q;
    }

    /**
     * 把例句里的目标词替换成 ___。
     *
     * words.json 的例句有 79% 含本词，但词形会变形（abandon → abandoned、
     * ability → abilities），所以按「原形 → 去 e 加 ing/ed → 词干」的顺序试，
     * 找不到就返回 null 让上层换题型 —— 宁可换题，也不能出一个空格对不上
     * 任何手牌词的错题（那样玩家永远答不对）。
     */
    private static String blankOut(String ex, String word) {
        if (word == null || word.isEmpty()) return null;
        String lower = ex.toLowerCase();
        String w = word.toLowerCase();

        // 候选词形，长的先试，避免 "able" 先把 "abilities" 切错
        List<String> forms = new ArrayList<>();
        forms.add(w);
        forms.add(w + "s");
        forms.add(w + "es");
        forms.add(w + "ed");
        forms.add(w + "d");
        forms.add(w + "ing");
        if (w.endsWith("e")) forms.add(w.substring(0, w.length() - 1) + "ing");
        if (w.endsWith("y")) forms.add(w.substring(0, w.length() - 1) + "ies");
        if (w.length() > 3) forms.add(w.substring(0, w.length() - 1));   // 粗略词干

        String best = null;
        for (String f : forms) {
            // 整词匹配：两侧不能是字母，否则 "a" 会命中 "ability" 里的 a
            int i = indexWord(lower, f);
            if (i >= 0) { best = f; break; }
        }
        if (best == null) return null;

        int i = indexWord(lower, best);
        if (i < 0) return null;
        return ex.substring(0, i) + "＿＿＿" + ex.substring(i + best.length());
    }

    /** 找整词出现位置：前后都不是字母。 */
    private static int indexWord(String hay, String needle) {
        int from = 0;
        while (true) {
            int i = hay.indexOf(needle, from);
            if (i < 0) return -1;
            boolean leftOk = i == 0 || !Character.isLetter(hay.charAt(i - 1));
            int end = i + needle.length();
            boolean rightOk = end >= hay.length() || !Character.isLetter(hay.charAt(end));
            if (leftOk && rightOk) return i;
            from = i + 1;
        }
    }

    // ── 题型 2：中译英 ────────────────────────────────────
    // 与题型 0 的区别：题面额外给出词性，且干扰项是「其他手牌」——
    // 玩家必须在几张牌之间比对，而不是一眼认出。
    private Q cnToEnQ(GCard c) {
        Q q = new Q();
        q.card = c;
        q.mode = 2;
        q.prompt = firstSense(c.cn);
        q.sub = (c.pos.isEmpty() ? "" : c.pos + "　") + "从手牌中选出正确的词";
        q.explain = c.cn + " = " + c.en;
        return q;
    }

    // ── 题型 4：僻义过载 ──────────────────────────────────
    /**
     * 只用「第二个及以后的义项」出题。
     *
     * 为什么单独一个入口：常规出题永远考 firstSense（最常见义），
     * 玩家背熟了主义就能一路平推，而考试里真正拉分的是熟词僻义
     * （charge 考「充电」不考「收费」）。过载模式把 senses[1..] 拿出来考，
     * 答对了给 2.2 倍真实伤害，等于用「敢赌僻义」换输出。
     *
     * @param c 目标牌，必须是 canOverload() 的牌
     * @return 题目；这张牌没有僻义时返回 null
     */
    public Q makeOverload(GCard c) {
        if (c == null || !c.canOverload()) return null;

        // 从第 1 个义项开始随机挑（senses[0] 是常见义，不能用来过载）
        List<Integer> idx = new ArrayList<>();
        for (int i = 1; i < c.senseCount(); i++) {
            if (!c.senseText(i).isEmpty()) idx.add(i);
        }
        if (idx.isEmpty()) return null;
        int pick = idx.get(rnd.nextInt(idx.size()));

        // ── 题型必须是「给词选义」，不能是「给义选牌」 ──
        //
        // 手牌上已经印着「◆僻义2」，玩家一眼就知道哪张有多义项。
        // 如果题面给中文、让玩家点手牌，那张带 ◆ 的牌本身就是答案提示 ——
        // 等于答案摆在台面上。所以这里反过来出题：正面给单词，
        // 四个释义里挑它的僻义。手牌标记这时不再是提示，因为
        // 玩家已经知道考的就是这张，要选的是「哪个意思」。
        Q q = new Q();
        q.card = c;
        q.mode = 4;
        q.senseIndex = pick;
        q.prompt = c.en;

        // 干扰项：优先用同词性的其它义项，凑不够再从词库里补。
        String right = c.senseText(pick);
        List<String> opts = new ArrayList<>();
        opts.add(right);

        // ① 先把这张牌其它义项（含常见义）拿来当干扰 —— 最难也最有意义：
        //    玩家必须分清「收费/充电/控告」到底哪一个是僻义。
        for (int i = 0; i < c.senseCount() && opts.size() < 4; i++) {
            if (i == pick) continue;
            String t = c.senseText(i);
            if (!t.isEmpty() && !t.equals(right)) opts.add(t);
        }
        // ② 还不够的话，从词库里找同词性的词义补齐
        if (opts.size() < 4) {
            String wantPos = c.sensePos(pick);
            List<String> pool = new ArrayList<>();
            for (GCard o : G.words) {
                if (o == null || o == c) continue;
                for (int i = 0; i < o.senseCount(); i++) {
                    String t = o.senseText(i);
                    if (t.isEmpty() || opts.contains(t)) continue;
                    // 同词性优先，不同词性只在池子不够时才用
                    if (wantPos.isEmpty() || o.sensePos(i).equals(wantPos)) pool.add(t);
                }
            }
            java.util.Collections.shuffle(pool, rnd);
            for (String t : pool) {
                if (opts.size() >= 4) break;
                if (!opts.contains(t)) opts.add(t);
            }
        }
        java.util.Collections.shuffle(opts, rnd);

        q.options = opts.toArray(new String[0]);
        q.answer = opts.indexOf(right);
        String sp = c.sensePos(pick);
        q.sub = (sp.isEmpty() ? "" : sp + "　") + "选出生僻义 · 答对 2.2 倍真实伤害，答错自伤 "
                + GBattle.overloadWrongSelfDamage;
        q.explain = c.en + " = " + right
                + (c.cn.isEmpty() ? "" : "（常见义：" + c.cn + "）");
        return q;
    }

    // ── 题型 3：释义辨析 ──────────────────────────────────
    // 给 4 个英文词作选项：1 个是手牌里的正确项，其余从词典里取不同义的。
    // 屏幕上同时显示手牌，所以干扰项绝不能是手里别的牌，否则两个都「对」。
    private Q discriminateQ(GCard c, List<GCard> hand) {
        Set<String> handEn = new HashSet<>();
        for (GCard h : hand) {
            if (h != null && h.en != null) handEn.add(h.en.toLowerCase());
        }

        List<String> opts = new ArrayList<>();
        opts.add(c.en);

        List<GCard> cand = new ArrayList<>();
        for (GCard o : G.words) {
            if (o.id.equals(c.id)) continue;
            if (handEn.contains(o.en.toLowerCase())) continue;   // 不能是别的牌
            if (key(o.cn).equals(key(c.cn))) continue;           // 同义不作干扰
            if (!o.pos.equals(c.pos)) continue;                  // 词性不同白送分
            cand.add(o);
        }
        Collections.shuffle(cand, rnd);
        for (GCard o : cand) {
            if (opts.size() >= 4) break;
            if (!opts.contains(o.en)) opts.add(o.en);
        }
        if (opts.size() < 2) return null;

        Collections.shuffle(opts, rnd);

        Q q = new Q();
        q.card = c;
        q.mode = 3;
        q.prompt = firstSense(c.cn);
        q.sub = "选出正确的单词";
        q.options = opts.toArray(new String[0]);
        q.answer = opts.indexOf(c.en);
        q.explain = c.cn + " = " + c.en;
        return q;
    }
}
