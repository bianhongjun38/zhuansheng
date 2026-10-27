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

    /** 中译英：给中文选英文。只取中文义唯一的词，避免「两个都对」。 */
    private Q cnToEn(GCard c) {
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

    /** 同义辨析：四个词义都相近，必须找出「和题干同义」的那个。 */
    private Q synonymPick(GCard c) {
        List<String> syn = G.synonymsOf(c.id);
        if (syn.isEmpty()) return enToCn(c);

        Q q = new Q();
        q.card = c;
        GCard partner = G.byId(syn.get(rnd.nextInt(syn.size())));
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