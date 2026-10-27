package com.zsb.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 出题器 —— 复用 EngTrain 的词条，但干扰项从「同义组」里取，制造真正的辨析题。
 *
 * 为什么要用同义组做干扰项：
 *   随便拿三个不相干的词当干扰项，不认识也能靠猜排除；
 *   而 enormous / immense / tremendous / vast 四个都表「巨大」，
 *   必须真知道细微差别才能选对 —— 这才是四级阅读理解的实际难点。
 *
 * 反向题：给中文选英文。中文取「唯一义」的词，多义词会同时出现两个
 * 看起来都对的选项，题目直接作废。
 */
public class GQuiz {

    public static class Q {
        public String prompt = "";
        public String sub = "";
        public String[] options;
        public int answer = -1;
        public String explain = "";
        public GCard card;
    }

    private final GameData G;
    private final Random rnd;

    public GQuiz(GameData g, Random r) { G = g; rnd = r; }

    /**
     * 释义净化。
     *
     * vocab.json 里的 cn 是多义串（「讨论;辩论」「有经验的;熟练的」），
     * 直接拿去当选项会有两个问题：一是选项框被撑成两三行、四个选项看不全；
     * 二是干扰项和正确答案可能共享同一个义项字符串，看起来像重复。
     * 所以选项只取第一个义项（中文分号/逗号/顿号切分），题面才是完整释义。
     */
    private static String firstSense(String cn) {
        if (cn == null) return "";
        String s = cn.trim();
        for (String sep : new String[]{"；", ";", "，", ",", "、", "/"}) {
            int i = s.indexOf(sep);
            if (i > 0) s = s.substring(0, i).trim();
        }
        return s;
    }

    /** 判重键：按第一个义项比较，避免「讨论;辩论」和「讨论」被当成两个不同选项。 */
    private static String key(String cn) { return firstSense(cn); }

    /**
     * 当前手牌里的英文词 —— 这些词不能出现在「中译英」的选项里。
     *
     * 为什么必须屏蔽：题面是中文释义，正确答案是这个词本身。
     * 而手牌在答题时仍然显示在屏幕上半部分，上面就印着英文单词。
     * 于是「题目问『明智的』，牌面上正好有一张 wise」—— 答案直接写在脸上了。
     * 出题器看不到 UI，所以由界面把当前手牌塞进来。
     */
    private java.util.Set<String> banned = java.util.Collections.emptySet();

    /** 由 GBattleView 在出题前调用，传当前手牌的全部英文词（小写）。 */
    public void setHandWords(java.util.Collection<String> words) {
        java.util.Set<String> s = new java.util.HashSet<>();
        if (words != null) {
            for (String w : words) {
                if (w != null && !w.trim().isEmpty()) s.add(w.trim().toLowerCase());
            }
        }
        banned = s;
    }

    private boolean isBanned(String en) {
        return en != null && banned.contains(en.trim().toLowerCase());
    }


    /**
     * 出题。mode: 0=英译中  1=中译英  2=同义辨析
     * 答完后卡牌效果才生效，所以题目必须用「这张牌的词」当考点。
     */
    public Q make(GCard c, int mode) {
        if (c == null || G.words.isEmpty()) return null;
        switch (mode) {
            case 0: return enToCn(c);
            case 1: return cnToEn(c);
            default: return synonymPick(c);
        }
    }

    /** 英译中：给英文选中文。干扰项优先取同词性、不同义的词。 */
    private Q enToCn(GCard c) {
        Q q = new Q();
        q.card = c;
        q.prompt = c.en;
        q.sub = c.pos.isEmpty() ? "" : c.pos + ".";

        String right = firstSense(c.cn);
        if (right.isEmpty()) return null;

        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(key(c.cn));
        List<String> pool = new ArrayList<>();
        for (GCard o : G.words) {
            if (o.id.equals(c.id)) continue;
            String cand = firstSense(o.cn);
            if (cand.isEmpty()) continue;
            if (!seen.add(key(o.cn))) continue;
            pool.add(cand);
        }
        Collections.shuffle(pool, rnd);
        List<String> opts = new ArrayList<>();
        opts.add(right);
        for (String s : pool) {
            if (opts.size() >= 4) break;
            if (!opts.contains(s)) opts.add(s);
        }
        Collections.shuffle(opts, rnd);
        q.options = opts.toArray(new String[0]);
        q.answer = opts.indexOf(right);
        q.explain = c.en + " = " + c.cn + (c.ex.isEmpty() ? "" : "  例：" + c.ex);
        return q;
    }

    /**
     * 中译英：给中文选英文。只取中文义唯一的词，避免「两个都对」。
     *
     * 关键约束：正确答案 c.en 如果正好是玩家手上的一张牌，这题等于送分 ——
     * 因为手牌一直显示在屏幕上，英文单词就印在牌面上。
     * 这种情况不能「换掉答案」（答案只能是这张牌本身的词），
     * 所以整题退化成英译中，那个方向手牌里印的英文恰好是题面，不构成泄漏。
     */
    private Q cnToEn(GCard c) {
        if (isBanned(c.en)) return enToCn(c);

        Q q = new Q();
        q.card = c;
        q.prompt = firstSense(c.cn);
        q.sub = c.pos.isEmpty() ? "" : c.pos + ".";

        List<String> opts = new ArrayList<>();
        opts.add(c.en);
        List<GCard> pool = new ArrayList<>();
        for (GCard o : G.words) {
            if (o.id.equals(c.id)) continue;
            if (key(o.cn).equals(key(c.cn))) continue;   // 同义不能当干扰项
            if (!o.pos.equals(c.pos)) continue; // 词性不同白送分
            if (isBanned(o.en)) continue;      // 手牌上的词不能当干扰项
            pool.add(o);
        }
        Collections.shuffle(pool, rnd);
        for (GCard o : pool) {
            if (opts.size() >= 4) break;
            if (!opts.contains(o.en)) opts.add(o.en);
        }
        if (opts.size() < 2) return enToCn(c);   // 凑不出干扰项就退回去
        Collections.shuffle(opts, rnd);
        q.options = opts.toArray(new String[0]);
        q.answer = opts.indexOf(c.en);
        q.explain = c.cn + " = " + c.en;
        return q;
    }

    /**
     * 同义辨析：四个词义都相近，必须找出「和题干同义」的那个。
     *
     * 这里也受手牌泄漏影响：题干是 c.en（明写在牌上，没关系，那本来就是题），
     * 但正确答案 partner.en 如果也在手牌里，玩家扫一眼牌面就能挑出来。
     * 遇到这种情况换个搭档；换不到就退回英译中。
     */
    private Q synonymPick(GCard c) {
        List<String> syn = G.synonymsOf(c.id);
        if (syn.isEmpty()) return enToCn(c);
        if (isBanned(c.en)) return enToCn(c);

        Q q = new Q();
        q.card = c;
        // 找一个不在手牌里的同义词当正确答案；全都碰上手牌就放弃这种题型
        GCard partner = null;
        java.util.List<String> cand = new java.util.ArrayList<>(syn);
        Collections.shuffle(cand, rnd);
        for (String sid : cand) {
            GCard g = G.byId(sid);
            if (g != null && !isBanned(g.en)) { partner = g; break; }
        }
        if (partner == null) return enToCn(c);

        q.prompt = c.en;
        q.sub = "选出与它同义的词";
        List<String> opts = new ArrayList<>();
        opts.add(partner.en);

        // 干扰项：同词性但不同义
        List<GCard> pool = new ArrayList<>();
        for (GCard o : G.words) {
            if (o.id.equals(c.id) || o.id.equals(partner.id)) continue;
            if (G.synonym(c.id, o.id)) continue;
            if (!o.pos.equals(c.pos)) continue;
            if (isBanned(o.en)) continue;      // 手牌上的词不能当干扰项
            pool.add(o);
        }
        Collections.shuffle(pool, rnd);
        for (GCard o : pool) {
            if (opts.size() >= 4) break;
            if (!opts.contains(o.en)) opts.add(o.en);
        }
        if (opts.size() < 2) return enToCn(c);
        Collections.shuffle(opts, rnd);
        q.options = opts.toArray(new String[0]);
        q.answer = opts.indexOf(partner.en);
        q.explain = c.en + " ≈ " + partner.en;
        return q;
    }

    /** 随机挑一种题型，避免长期只有一种。 */
    public int randomMode() {
        int r = rnd.nextInt(100);
        if (r < 45) return 0;
        if (r < 80) return 1;
        return 2;
    }
}