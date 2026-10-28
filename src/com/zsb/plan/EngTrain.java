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
        // 相邻字母颠倒也算「差一点」。
        // accusatoin / recieve / thier / freind 这类是拼写题里最高频的错误，
        // 原先 edit1() 只认插入-删除-替换，交换被判成彻底 WRONG，
        // 学生明明只错在顺序上，却得到「不会写」的反馈。那是最误导人的一种判错。
        if (a.length() == b.length() && swap1(a, b)) return J_NEAR;
        return J_WRONG;
    }

    /**
     * b 是不是 a 交换了某一对相邻字母。
     *
     * 只比长度相等的情况（交换不改变长度），且只允许**恰好一对**：
     * 两处以上交换就是真的不会写，不能再给「差一点」。
     */
    static boolean swap1(String a, String b) {
        // 必须自己挡长度：judgeText 那侧有 Math.abs(...) <= 1 兜着，
        // 但 nearHint 是公开方法、任何调用方都可能直接传不等长的一对。
        // 少了这一句，swap1("abc","ab") 会在 a.charAt(i+1) 上抛 StringIndexOutOfBounds，
        // 整个反馈 render 直接崩 —— 本来就答错了，还看不到答案。
        if (a == null || b == null || a.length() != b.length()) return false;
        int i = 0, n = a.length();
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        if (i >= n - 1) return false;                 // 完全相等或多出来的都在末尾，不算交换
        // 命中第一处不同：必须正好是 a[i]b[i+1] == b[i]a[i+1]
        if (a.charAt(i) != b.charAt(i + 1) || a.charAt(i + 1) != b.charAt(i)) return false;
        // 交换之后剩余部分必须完全一致
        for (int j = i + 2; j < n; j++) {
            if (a.charAt(j) != b.charAt(j)) return false;
        }
        return true;
    }

    /**
     * 归一化：全角转半角、大小写拉平、去首尾与多余空格、去标点、去零宽字符。
     *
     * ── 为什么必须做全角转换 ──────────────────────────────
     * 用户报「拼写正确后还显示错误」。实测根因就在这里：
     * 手机中文输入法开着的时候，敲出来的是全角字母（ａｂａｎｄｏｎ，U+FF41 起），
     * 屏幕上和半角几乎没有区别，但 char 值差 0xFEE0，字符串比较必然不等。
     * 全角空格 U+3000、不换行空格 U+00A0 同理 —— 它们不是 ' '，
     * 既不会被当作空格跳过，也不会被 trim() 去掉。
     * 之前只处理半角 ' ' 和 A-Z，这几种输入全部被判错，学生看着自己明明打对了。
     *
     * 另外顺手处理两类看不见的东西：
     *   · 零宽字符（U+200B/200C/200D/FEFF）—— 输入法或复制粘贴会带进来
     *   · 各种引号（直/弯单引号、双引号、反引号）—— 统一去掉
     * 连字符要保留：well-known、e-mail 这类词本身带连字符。
     */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        boolean lastSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            // ① 零宽 / 格式字符：直接丢
            if (c == '\u200B' || c == '\u200C' || c == '\u200D'
                    || c == '\uFEFF' || c == '\u00AD') continue;

            // ② 全角 ASCII（U+FF01..U+FF5E）→ 半角
            if (c >= '\uFF01' && c <= '\uFF5E') c = (char) (c - 0xFEE0);
            // 全角空格
            else if (c == '\u3000') c = ' ';
            // 不换行空格 / 窄空格 等
            else if (c == '\u00A0' || c == '\u202F' || c == '\u2007') c = ' ';

            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);

            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (!lastSpace && b.length() > 0) b.append(' ');
                lastSpace = true;
                continue;
            }
            // ③ 标点：直/弯引号、反引号、常见英文标点都去掉
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '!'
                    || c == '?' || c == '"' || c == 39 || c == 96
                    || c == '\u2018' || c == '\u2019' || c == '\u201C' || c == '\u201D') continue;
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

        // 先判是不是相邻字母写反了 —— 这种要给「顺序」提示，
        // 不能说「第 N 个字母不对」，否则学生盯着那个字母看不出错在哪。
        if (a.length() == b.length() && swap1(a, b)) {
            int i = 0, n = a.length();
            while (i < n && a.charAt(i) == b.charAt(i)) i++;
            if (i < n - 1) {
                return "字母写反了：第 " + (i + 1) + " 和第 " + (i + 2)
                        + " 个字母顺序颠倒（共 " + n + " 个字母）";
            }
        }

        // 长度不同就是多写/少写了一个字母。必须先判这个再逐位比 ——
        // neccessary 多了一个 c，逐位比会指向「第 4 个字母不对」，
        // 但学生看到第 4 位是 c、答案第 4 位也是 c，会以为自己没写错。
        // 直接说「多了一个字母」才对得上他手里的东西。
        if (a.length() != b.length()) {
            return (b.length() > a.length() ? "多" : "少") + "了一个字母（共 " + a.length() + " 个字母）";
        }

        int n = a.length();
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return "差一个字母：第 " + (i + 1) + " 个字母不对（共 " + n + " 个字母）";
            }
        }
        return "少/多了一个字母（共 " + n + " 个字母）";
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
