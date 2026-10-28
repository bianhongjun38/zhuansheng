package com.zsb.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一张牌 = 一个词条。
 *
 * 设计要点：卡面本身不区分「同义牌」和「普通牌」，同义关系由 GameData
 * 在出牌时实时查表判断 —— 因为同一个词可能属于多个同义组（regulate、rough
 * 各属 4 组），提前写死成字段一定会漏。
 */
public class GCard {
    public static final int KIND_WORD   = 0;   // 普通词牌
    public static final int KIND_ADVERB = 1;   // 副词机制牌
    public static final int KIND_SPECIAL= 2;   // 精英掉落特殊词

    public final String id;        // 唯一标识（词形 / 副词 id）
    public final String en;        // 英文面
    public final String cn;        // 中文面（首个义项，卡面上显示的就是这个）
    public final String pos;       // 词性
    public final int kind;
    public final String desc;      // 副词/特殊词的机制说明
    /** 例句与译文。答错时在提示里给出，让错误当场变成一次学习。 */
    public final String ex, exCn;

    /**
     * 例句整句的中文译文（只取第一句，与 ex 一一对应）。
     *
     * 为什么要单独切一份：words.json 的 exCn 是「多句挤在一个字段里」的
     * 拼接串（实测 a 的 exCn 是「我需要一个解释。 I have a dog. 我有一只狗。」），
     * 而 ex 同样是拼接的。直接在界面里拼整串去念，怪物会一口气说完三句
     * 毫不相干的翻译，既不像台词，也不对应题面那一句。
     *
     * exCnFirst 只在构造时算一次，之后只读；空串表示数据里没有译文。
     */
    public final String exCnFirst;

    /**
     * 取「第一句」：遇到中英文句末标点就截断，再切掉尾部残留的英文片段。
     *
     * 两步的原因是数据实测：
     *   · 965 条有译文的词里，条条可能挤着 2~3 句（「我需要一个解释。 I have a dog. …」），
     *     不切第一句，怪物会把三句不相干的翻译一口气念完；
     *   · 光切句还不够 —— 有些条目的中英混排不在句号处分开，而是在
     *     **中文逗号**后直接接半句英文（实测 chain：「生活是一串串的快乐时光， He started a fast food chain」）。
     *     这种行按句号切完，尾部仍挂着一截英文，念出来像机器在报错。
     *     所以第二步专门砍掉「尾巴上那段连续的英文」。
     *
     * 注意不能反过来「有英文就整句丢掉」：proof 的译文是
     * 「…科学证据证明iPad 对教育有好处」，丢掉就把整句学习价值一起扔了。
     * 只砍**尾部**连续英文串，句中的专有名词（iPad）原样保留。
     */
    private static String firstSentence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty()) return "";

        // ① 切第一句
        String head = t;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\u3002'
                    || ch == '\uFF01' || ch == '\uFF1F' || ch == '\uFF0E') {
                String cut = t.substring(0, i).trim();
                // 纯英文句号可能是缩写/小数点，前面没内容就别乱切
                if (cut.isEmpty()) continue;
                head = cut;
                break;
            }
        }

        // ② 砍掉尾部残留的英文串。
        //    从末尾往前扫，遇到中文就停；扫到的英文（含空格、撇号、连字符）
        //    整段切掉，再把尾巴上的中文标点和空格收拾干净。
        int end = head.length();
        int i = end;
        while (i > 0) {
            char ch = head.charAt(i - 1);
            boolean eng = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || ch == ' ' || ch == '\'' || ch == '-' || ch == '\u2019';
            if (!eng) break;
            i--;
        }
        if (i < end) {
            // 只剩英文/空白 → 整句没有中文，说明数据不是译文，宁可返回空串
            String rest = head.substring(0, i).trim();
            // 尾部那截如果不含至少 2 个英文字母，就不是"英文串"，别误删（如结尾的 & 空格）
            int letters = 0;
            for (int k = i; k < end; k++) {
                char ch = head.charAt(k);
                if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) letters++;
            }
            if (letters >= 2) {
                head = trimTailPunct(rest);
            }
        }
        // ③ 太短的不要。
        //    实测 965 条译文里，47 条按上述规则切完只剩 1~5 个字，
        //    而且都是残缺片段：「雅」「务」「上」「裤的」「的过去式是」
        //    「算机的恐惧」。这类东西念出来比不念更难懂，等于用一句
        //    噪音替换掉本来有用的单词义。所以不足 6 字直接判为无效，
        //    由调用方退回单词义。
        //    6~7 字是完好的短句（「他们必遭失败」「她生性很敏感」），保留。
        String out = head.trim();
        if (out.length() < 6) return "";
        return out;
    }

    /** 去掉尾部的中文标点与空白。 */
    private static String trimTailPunct(String s) {
        String t = s;
        while (!t.isEmpty()) {
            char ch = t.charAt(t.length() - 1);
            if (ch == ' ' || ch == ',' || ch == '\uFF0C' || ch == '\u3001'
                    || ch == ';' || ch == '\uFF1B' || ch == ':' || ch == '\uFF1A') {
                t = t.substring(0, t.length() - 1);
            } else break;
        }
        return t;
    }

    /**
     * 全部义项，形如 {"n|危险", "v|使…危险"}。
     *
     * 只有 vocab.json 里 senses.size() >= 2 的词才会填这个（实测 324 / 1654）。
     * 空列表 = 单义词，没有「僻义」，也就没有过载模式。
     *
     * 为什么存成 "pos|中文" 的扁平串而不是嵌套对象：这个类要能脱离 org.json
     * 在纯 Java 测试里跑，而且卡面只需要「第几个义项的中文是什么」。
     */
    public final List<String> senses;

    public GCard(String id, String en, String cn, String pos, int kind, String desc) {
        this(id, en, cn, pos, kind, desc, "", "", null);
    }

    public GCard(String id, String en, String cn, String pos, int kind,
                 String desc, String ex, String exCn) {
        this(id, en, cn, pos, kind, desc, ex, exCn, null);
    }

    public GCard(String id, String en, String cn, String pos, int kind,
                 String desc, String ex, String exCn, List<String> senses) {
        this.id = id; this.en = en; this.cn = cn;
        this.pos = pos == null ? "" : pos;
        this.kind = kind;
        this.desc = desc == null ? "" : desc;
        this.ex = ex == null ? "" : ex;
        this.exCn = exCn == null ? "" : exCn;
        this.exCnFirst = firstSentence(this.exCn);
        this.senses = senses == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(senses));
    }

    public boolean isWord()   { return kind == KIND_WORD; }
    public boolean isAdverb() { return kind == KIND_ADVERB; }
    public boolean isSpecial(){ return kind == KIND_SPECIAL; }

    /** 连锁用的分组键：词性 + 中文义。空词性不算同类。 */
    public String groupKey() {
        if (pos.isEmpty() || cn.isEmpty()) return null;
        return pos + "|" + cn;
    }

    // ── 熟词僻义（过载模式）──────────────────────────────

    /** 义项数量。1 = 单义，够不上过载。 */
    public int senseCount() {
        return senses.size();
    }

    /** 是否可以过载：至少两个义项，且第一个义项之外还有实义。 */
    public boolean canOverload() {
        if (senses.size() < 2) return false;
        for (int i = 1; i < senses.size(); i++) {
            if (senseText(i).length() > 0) return true;
        }
        return false;
    }

    /**
     * 取第 i 个义项的「中文部分」。i 越界返回空串。
     *
     * 存的格式是 "pos|中文"，这里切开后半段。历史数据里没有竖线时
     * 整串当中文用 —— 宁可显示得糙一点，也不能因为格式不一致就抛异常。
     */
    public String senseText(int i) {
        if (i < 0 || i >= senses.size()) return "";
        String s = senses.get(i);
        int bar = s.indexOf('|');
        return bar < 0 ? s.trim() : s.substring(bar + 1).trim();
    }

    /** 取第 i 个义项的词性。没写就返回空串。 */
    public String sensePos(int i) {
        if (i < 0 || i >= senses.size()) return "";
        String s = senses.get(i);
        int bar = s.indexOf('|');
        return bar < 0 ? "" : s.substring(0, bar).trim();
    }
}
