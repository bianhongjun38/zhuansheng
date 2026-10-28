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
