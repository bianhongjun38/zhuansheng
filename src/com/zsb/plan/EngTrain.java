package com.zsb.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 英语训练引擎 —— 纯逻辑，不碰 Android，所以能在电脑上被审计。
 *
 * 为什么不只做"翻卡背单词"：
 *   翻卡只测「见过没」，测不出「会不会用」。真正的考点有五个独立能力：
 *     ① 看英文认中文（读）      ② 看中文选英文（写的前提）
 *     ③ 拼写（写）              ④ 在句子里认出来（语境）
 *     ⑤ 词性/搭配（语法位）
 *   只做①的话，一个词认识却拼不出、放进句子里认不出，考场上照样错。
 *
 * 每个题型最容易做坏的地方（这里都做了处理，EngTrainTest 会验证）：
 *   · 干扰项和答案意思相近 → 两个都对，题目作废
 *   · 干扰项和答案词性不同 → 不用认识也能排除，白送分
 *   · 例句挖空时把单词的一部分也挖掉（如 art 挖成 st）→ 必须按词边界匹配
 *   · 大小写敏感的判分 → 句首大写的词被判错
 */
public class EngTrain {

    // ── 题型 ──────────────────────────────────────────────
    public static final int M_EC = 1;      // 英译中：给英文选中文
    public static final int M_CE = 2;      // 中译英：给中文选英文
    public static final int M_SPELL = 3;   // 拼写：给中文+音标+首字母，敲出单词
    public static final int M_CLOZE = 4;   // 例句填空
    public static final int M_POS = 5;     // 词性判断

    public static final int J_RIGHT = 0;
    public static final int J_NEAR = 1;    // 差一个字母
    public static final int J_WRONG = 2;

    /** 一个词条。字段名和 assets/words.json 对齐。 */
    public static class W {
        public final int id;
        public final String w;      // 单词
        public final String ph;     // 音标
        public final String pos;    // 词性
        public final String cn;     // 中文释义
        public final String ex;     // 例句
        public final String exCn;   // 例句翻译

        public W(int id, String w, String ph, String pos, String cn, String ex, String exCn) {
            this.id = id;
            this.w = nz(w); this.ph = nz(ph); this.pos = nz(pos);
            this.cn = nz(cn); this.ex = nz(ex); this.exCn = nz(exCn);
        }

        static String nz(String s) { return s == null ? "" : s; }

        /** 只有纯字母单字才适合拼写题（含空格/连字符的短语不考拼写）。 */
        public boolean spellable() {
            if (w.length() < 3 || w.length() > 16) return false;
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) return false;
            }
            return true;
        }
    }

    /** 一道题。 */
    public static class Item {
        public int mode;
        public String prompt = "";      // 主题干
        public String sub = "";         // 副信息：音标 / 词性 / 提示
        public String[] options;        // 选择题选项；拼写/填空为 null
        public int answer = -1;         // 正确选项下标；非选择题为 -1
        public String answerText = "";  // 标准答案文本
        public String tail = "";        // 答完后附带的补充信息
        public W word;

        public boolean isChoice() { return options != null && options.length > 0; }
    }

    // ── 出题 ──────────────────────────────────────────────
    public static Item make(int mode, W target, List<W> pool, List<W> all, Random rnd) {
        if (target == null) return null;
        switch (mode) {
            case M_EC: return ec(target, pool, all, rnd);
            case M_CE: return ce(target, pool, all, rnd);
            case M_SPELL: return spell(target);
            case M_CLOZE: return cloze(target);
            case M_POS: return posQ(target, pool, all, rnd);
            default: return null;
        }
    }

    // 英译中
    static Item ec(W t, List<W> pool, List<W> all, Random rnd) {
        if (t.cn.isEmpty()) return null;
        List<W> ds = distractors(t, pool, all, rnd, 3, true);
        if (ds.size() < 3) return null;
        Item it = new Item();
        it.mode = M_EC;
        it.word = t;
        it.prompt = t.w;
        it.sub = t.ph.isEmpty() ? "" : "/" + t.ph + "/";
        it.options = new String[4];
        it.answer = rnd.nextInt(4);
        it.options[it.answer] = t.cn;
        it.answerText = t.cn;
        int k = 0;
        for (int i = 0; i < 4; i++) {
            if (i == it.answer) continue;
            it.options[i] = ds.get(k++).cn;
        }
        return it;
    }

    // 中译英
    static Item ce(W t, List<W> pool, List<W> all, Random rnd) {
        if (t.cn.isEmpty() || t.w.isEmpty()) return null;
        List<W> ds = distractors(t, pool, all, rnd, 3, false);
        if (ds.size() < 3) return null;
        Item it = new Item();
        it.mode = M_CE;
        it.word = t;
        it.prompt = t.cn;
        it.sub = t.pos.isEmpty() ? "" : "（" + t.pos + "）";
        it.options = new String[4];
        it.answer = rnd.nextInt(4);
        it.options[it.answer] = t.w;
        it.answerText = t.w;
        int k = 0;
        for (int i = 0; i < 4; i++) {
            if (i == it.answer) continue;
            it.options[i] = ds.get(k++).w;
        }
        return it;
    }

    // 拼写
    static Item spell(W t) {
        if (!t.spellable() || t.cn.isEmpty()) return null;
        Item it = new Item();
        it.mode = M_SPELL;
        it.word = t;
        it.prompt = t.cn;
        StringBuilder b = new StringBuilder();
        b.append(t.w.charAt(0));
        for (int i = 1; i < t.w.length(); i++) b.append('·');
        it.sub = (t.ph.isEmpty() ? "" : "/" + t.ph + "/   ")
                + t.w.length() + " 个字母   " + b;
        it.answerText = t.w;
        return it;
    }

    // 例句填空
    static Item cloze(W t) {
        if (t.ex.isEmpty() || t.w.isEmpty()) return null;
        String blanked = blankOut(t.ex, t.w);
        if (blanked == null) return null;
        Item it = new Item();
        it.mode = M_CLOZE;
        it.word = t;
        it.prompt = blanked;
        it.sub = t.cn;
        it.answerText = t.w;
        it.tail = t.exCn;
        return it;
    }

    /**
     * 词性缩写 → 中文名。
     *
     * 用户原话："下面的选项也应该弄个中文释义，不然 art 是什么都不知道"。
     * 光给 "art." 等于在考缩写；给 "art.（冠词）" 才是考词性本身。
     */
    public static String posName(String p) {
        if (p == null) return "";
        String k = p.trim().replace(".", "");
        if (k.equals("n")) return "名词";
        if (k.equals("v")) return "动词";
        if (k.equals("vt")) return "及物动词";
        if (k.equals("vi")) return "不及物动词";
        if (k.equals("adj")) return "形容词";
        if (k.equals("adv")) return "副词";
        if (k.equals("prep")) return "介词";
        if (k.equals("conj")) return "连词";
        if (k.equals("pron")) return "代词";
        if (k.equals("num")) return "数词";
        if (k.equals("art")) return "冠词";
        if (k.equals("int")) return "感叹词";
        if (k.equals("aux")) return "助动词";
        if (k.equals("abbr")) return "缩写";
        return "";
    }

    // 词性判断
    static Item posQ(W t, List<W> pool, List<W> all, Random rnd) {
        if (t.pos.isEmpty()) return null;
        String[] allPos = {"n.", "v.", "adj.", "adv.", "prep.", "conj.", "pron.", "num."};
        List<String> ds = new ArrayList<String>();
        List<String> seen = new ArrayList<String>();
        List<W> src = pool.size() >= 20 ? pool : all;
        for (W x : src) if (!x.pos.isEmpty() && !seen.contains(x.pos)) seen.add(x.pos);
        for (String p : seen) if (!p.equals(t.pos) && ds.size() < 3) ds.add(p);
        for (String p : allPos) if (!p.equals(t.pos) && ds.size() < 3 && !ds.contains(p)) ds.add(p);
        if (ds.size() < 3) return null;
        Item it = new Item();
        it.mode = M_POS;
        it.word = t;
        // 英文词才是题目主体，中文只是提示。
        // 原来把 "apple  苹果" 整串塞进 prompt，用的是 22pt 大字，
        // 结果中文跟英文一样大，一眼看过去中文抢了主角。
        it.prompt = t.w;
        it.sub = t.cn;
        it.options = new String[4];
        it.answer = rnd.nextInt(4);
        it.options[it.answer] = t.pos;
        it.answerText = t.pos;
        int k = 0;
        for (int i = 0; i < 4; i++) {
            if (i == it.answer) continue;
            it.options[i] = ds.get(k++);
        }
        return it;
    }

    /**
     * 挑干扰项。三条硬规则：
     *   ① 中文释义不能相同、不能互相包含 → 否则两个选项都对
     *   ② 中译英时，干扰词词性要和答案一致 → 否则能靠词性排除
     *   ③ 中译英时，语义不能重叠
     */
    static List<W> distractors(W t, List<W> pool, List<W> all, Random rnd, int need, boolean forCn) {
        List<W> out = new ArrayList<W>();
        List<W> cand = new ArrayList<W>(pool);
        if (cand.size() < need * 4) cand.addAll(all);
        int[] order = shuffled(cand.size(), rnd);
        for (int idx : order) {
            if (out.size() >= need) break;
            W x = cand.get(idx);
            if (x.id == t.id) continue;
            if (x.w.equalsIgnoreCase(t.w)) continue;
            if (forCn) {
                if (x.cn.isEmpty()) continue;
                if (x.cn.equals(t.cn)) continue;
                if (x.cn.contains(t.cn) || t.cn.contains(x.cn)) continue;
                if (containsCn(out, x.cn)) continue;
            } else {
                if (x.w.isEmpty()) continue;
                if (samePos(t.pos, x.pos)) {
                    if (!x.cn.isEmpty() && !t.cn.isEmpty()
                            && (x.cn.contains(t.cn) || t.cn.contains(x.cn))) continue;
                } else {
                    if (out.size() < need - 1) continue;
                }
                if (containsW(out, x.w)) continue;
            }
            out.add(x);
        }
        return out;
    }

    static boolean samePos(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return true;
        String pa = a.split("[,;/]")[0].trim();
        String pb = b.split("[,;/]")[0].trim();
        return pa.equals(pb);
    }

    static boolean containsCn(List<W> l, String cn) {
        for (W x : l) if (x.cn.equals(cn)) return true;
        return false;
    }

    static boolean containsW(List<W> l, String w) {
        for (W x : l) if (x.w.equalsIgnoreCase(w)) return true;
        return false;
    }

    static int[] shuffled(int n, Random rnd) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = a[i]; a[i] = a[j]; a[j] = t;
        }
        return a;
    }

    // ── 例句挖空（词边界匹配）───────────────────────────
    /**
     * 把例句里的目标词挖成 ______。
     * 必须按词边界：不能用 String.replace 直接替换，
     * 否则 "art" 会把 "start" 里的 art 也挖掉。
     */
    public static String blankOut(String sentence, String word) {
        if (sentence == null || word == null || word.isEmpty()) return null;
        int hit = findWord(sentence, word);
        if (hit < 0) return null;
        return sentence.substring(0, hit) + "______" + sentence.substring(hit + word.length());
    }

    static int findWord(String s, String w) {
        String ls = s.toLowerCase(), lw = w.toLowerCase();
        int from = 0;
        while (true) {
            int p = ls.indexOf(lw, from);
            if (p < 0) return -1;
            boolean leftOk = p == 0 || !isLetter(s.charAt(p - 1));
            int e = p + w.length();
            boolean rightOk = e >= s.length() || !isLetter(s.charAt(e));
            if (leftOk && rightOk) return p;
            from = p + 1;
        }
    }

    static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    // ── 判分 ──────────────────────────────────────────────
    public static int judgeChoice(Item it, int picked) {
        if (it == null || !it.isChoice()) return J_WRONG;
        return picked == it.answer ? J_RIGHT : J_WRONG;
    }

    /**
     * 文本作答判分。归一化：去首尾空白、合并内部空格、转小写、去标点。
     * 差一个字母不算对，但单独告诉学生"就差一个字母"——
     * 直接判错不给线索，学生下次还是错在同一个字母上。
     */
    public static int judgeText(Item it, String input) {
        if (it == null) return J_WRONG;
        String a = norm(it.answerText);
        String b = norm(input);
        if (a.isEmpty()) return J_WRONG;
        if (a.equals(b)) return J_RIGHT;
        if (Math.abs(a.length() - b.length()) <= 1 && edit1(a, b)) return J_NEAR;
        return J_WRONG;
    }

    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        boolean lastSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            if (c == ' ' || c == '\t' || c == '\n') {
                if (!lastSpace && b.length() > 0) b.append(' ');
                lastSpace = true;
                continue;
            }
            // 去英文标点，保留连字符（well-known 这类词本身带连字符）
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!'
                    || c == '?' || c == '"' || c == 39 || c == 96) continue;
            b.append(c);
            lastSpace = false;
        }
        return b.toString().trim();
    }

    static boolean edit1(String a, String b) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 1) return false;
        if (la == lb) {
            int diff = 0;
            for (int i = 0; i < la; i++) if (a.charAt(i) != b.charAt(i)) { diff++; if (diff > 1) return false; }
            return diff == 1;
        }
        String s = la < lb ? a : b, l = la < lb ? b : a;
        int i = 0, j = 0, skipped = 0;
        while (i < s.length() && j < l.length()) {
            if (s.charAt(i) == l.charAt(j)) { i++; j++; continue; }
            skipped++;
            if (skipped > 1) return false;
            j++;
        }
        return true;
    }

    public static String nearHint(String answer, String input) {
        String a = norm(answer), b = norm(input);
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return "差一个字母：第 " + (i + 1) + " 个字母不对（共 " + a.length() + " 个字母）";
            }
        }
        return "少/多了一个字母（共 " + a.length() + " 个字母）";
    }

    // ── 组卷 ──────────────────────────────────────────────
    /**
     * 按配比组一卷。每个词不一定每种题型都出得来（没例句就出不了填空），
     * 所以"造不出就跳过"，绝不为了凑数造一道错题。
     */
    public static List<Item> build(List<W> words, List<W> all, int[] modes, Random rnd) {
        List<Item> out = new ArrayList<Item>();
        if (words == null || words.isEmpty()) return out;
        for (W w : words) {
            List<Integer> avail = new ArrayList<Integer>();
            for (int m : modes) {
                Item probe = make(m, w, words, all, new Random(1));
                if (probe != null) avail.add(m);
            }
            if (avail.isEmpty()) continue;
            int m = avail.get(rnd.nextInt(avail.size()));
            Item it = make(m, w, words, all, rnd);
            if (it != null) out.add(it);
        }
        return out;
    }

    public static String modeName(int m) {
        switch (m) {
            case M_EC: return "英译中";
            case M_CE: return "中译英";
            case M_SPELL: return "拼写";
            case M_CLOZE: return "例句填空";
            case M_POS: return "词性";
            default: return "混合";
        }
    }

    public static String modeHint(int m) {
        switch (m) {
            case M_EC: return "选正确的意思";
            case M_CE: return "选正确的单词";
            case M_SPELL: return "按中文和首字母敲出单词";
            case M_CLOZE: return "把例句里缺的词填上";
            case M_POS: return "这个词是什么词性";
            default: return "五种题型混着来";
        }
    }
}
