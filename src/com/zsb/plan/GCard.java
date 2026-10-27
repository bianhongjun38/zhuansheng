package com.zsb.plan;

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
    public final String cn;        // 中文面
    public final String pos;       // 词性
    public final int kind;
    public final String desc;      // 副词/特殊词的机制说明
    /** 例句与译文。答错时在提示里给出，让错误当场变成一次学习。 */
    public final String ex, exCn;

    public GCard(String id, String en, String cn, String pos, int kind, String desc) {
        this(id, en, cn, pos, kind, desc, "", "");
    }

    public GCard(String id, String en, String cn, String pos, int kind,
                 String desc, String ex, String exCn) {
        this.id = id; this.en = en; this.cn = cn;
        this.pos = pos == null ? "" : pos;
        this.kind = kind;
        this.desc = desc == null ? "" : desc;
        this.ex = ex == null ? "" : ex;
        this.exCn = exCn == null ? "" : exCn;
    }

    public boolean isWord()   { return kind == KIND_WORD; }
    public boolean isAdverb() { return kind == KIND_ADVERB; }
    public boolean isSpecial(){ return kind == KIND_SPECIAL; }

    /** 连锁用的分组键：词性 + 中文义。空词性不算同类。 */
    public String groupKey() {
        if (pos.isEmpty() || cn.isEmpty()) return null;
        return pos + "|" + cn;
    }
}