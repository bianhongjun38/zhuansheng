package com.zsb.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 英语训练引擎审计。
 *
 * 英语题比数学题更容易"看起来对、其实是废题"：
 *   · 四个选项里两个意思相近 → 两个都对，题目作废
 *   · 干扰项词性不同 → 不用认识也能排除，白送分
 *   · 例句挖空挖错位置（art 把 start 挖成 st）→ 句子读不通
 *   · 大小写敏感判分 → 句首大写的词被判错
 * 每一条都在这里用真实词库跑一遍，不靠"我觉得没问题"。
 */
public class EngTrainTest {

    static int pass = 0;
    static List<String> fails = new ArrayList<String>();
    static List<String> notes = new ArrayList<String>();

    static void ok(String name, boolean cond) { ok(name, cond, ""); }

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; return; }
        fails.add(name + (detail.isEmpty() ? "" : "  →  " + detail));
    }

    public static void main(String[] args) {
        System.exit(run() == 0 ? 0 : 1);
    }

    public static int run() {
        pass = 0;
        fails.clear();
        notes.clear();

        try { blankOutTests(); } catch (Exception e) { fails.add("挖空异常：" + e); }
        try { distractorTests(); } catch (Exception e) { fails.add("干扰项异常：" + e); }
        try { judgeTests(); } catch (Exception e) { fails.add("判分异常：" + e); }
        try { allModeTests(); } catch (Exception e) { fails.add("出题异常：" + e); }
        try { realWordsTest(); } catch (Exception e) { fails.add("真实词库异常：" + e); }

        System.out.println("  英语引擎：" + pass + " 项通过，"
                + fails.size() + " 项失败");
        for (String f : fails) System.out.println("     ✗ " + f);
        for (String n : notes) System.out.println("     · " + n);
        return fails.size();
    }

    // ── ① 例句挖空必须按词边界 ───────────────────────────
    static void blankOutTests() {
        // 核心用例：art 在 start 里不能被挖
        String s1 = "We start at nine.";
        ok("art 不挖 start", EngTrain.blankOut(s1, "art") == null,
                String.valueOf(EngTrain.blankOut(s1, "art")));

        ok("整词能挖", "I need an ______.".equals(
                EngTrain.blankOut("I need an art.", "art")),
                String.valueOf(EngTrain.blankOut("I need an art.", "art")));

        // 大小写：句首 Art 也要挖
        String s2 = EngTrain.blankOut("Art is long.", "art");
        ok("句首大写也能挖", s2 != null && s2.startsWith("______"), String.valueOf(s2));

        // 词尾带标点
        String s3 = EngTrain.blankOut("I like art.", "art");
        ok("词尾句点不影响", s3 != null && s3.contains("______."), String.valueOf(s3));

        // 只挖第一个整词，不挖后面同形的部分
        String s4 = EngTrain.blankOut("An art art.", "art");
        ok("只挖一处", s4 != null && s4.indexOf("______") >= 0
                && s4.indexOf("______") == s4.lastIndexOf("______"), String.valueOf(s4));

        // 词不在句子里 → null（不能造一道没答案的题）
        ok("词不在句子里返回 null", EngTrain.blankOut("No match here.", "apple") == null);

        // 空输入
        ok("空句子返回 null", EngTrain.blankOut("", "art") == null);
        ok("空词返回 null", EngTrain.blankOut("I like art.", "") == null);

        // 连字符词
        String s5 = EngTrain.blankOut("A well-known fact.", "well-known");
        ok("连字符词能挖", s5 != null && s5.contains("______"), String.valueOf(s5));

        // 复数形式不该被当前实现挖到（词边界右端是字母，所以 s 挡住）
        ok("art 不挖 arts", EngTrain.blankOut("He likes arts.", "art") == null,
                String.valueOf(EngTrain.blankOut("He likes arts.", "art")));
    }

    // ── ② 干扰项三条硬规则 ───────────────────────────────
    static void distractorTests() {
        Random rnd = new Random(42);
        List<EngTrain.W> pool = new ArrayList<EngTrain.W>();
        pool.add(new EngTrain.W(1, "abandon", "", "v.", "放弃", "He abandoned it.", "他放弃了。"));
        pool.add(new EngTrain.W(2, "abandon", "", "v.", "抛弃", "They abandoned us.", "他们抛弃了我们。"));
        pool.add(new EngTrain.W(3, "ability", "", "n.", "能力", "He has ability.", "他有能力。"));
        pool.add(new EngTrain.W(4, "able", "", "adj.", "能够的", "He is able.", "他能。"));
        pool.add(new EngTrain.W(5, "about", "", "prep.", "关于", "A book about it.", "一本关于它的书。"));
        pool.add(new EngTrain.W(6, "above", "", "prep.", "在上面", "Above the line.", "线上面。"));
        pool.add(new EngTrain.W(7, "accept", "", "v.", "接受", "I accept it.", "我接受。"));
        pool.add(new EngTrain.W(8, "accident", "", "n.", "事故", "A car accident.", "车祸。"));
        pool.add(new EngTrain.W(9, "account", "", "n.", "账户", "My account.", "我的账户。"));
        pool.add(new EngTrain.W(10, "achieve", "", "v.", "达到", "Achieve a goal.", "达到目标。"));

        EngTrain.W target = pool.get(0);
        List<EngTrain.W> ds = EngTrain.distractors(target, pool, pool, rnd, 3, true);
        ok("英译中能凑齐 3 个干扰项", ds.size() == 3, "实际 " + ds.size());

        // 规则①：中文释义不能相同、不能互相包含
        int dupCn = 0, containCn = 0;
        for (EngTrain.W d : ds) {
            if (d.cn.equals(target.cn)) dupCn++;
            if (d.cn.contains(target.cn) || target.cn.contains(d.cn)) containCn++;
        }
        ok("干扰项释义不重复", dupCn == 0, dupCn + " 个重复");
        ok("干扰项释义不互相包含", containCn == 0, containCn + " 个包含");

        // 干扰项之间也不能互相包含
        int cross = 0;
        for (int i = 0; i < ds.size(); i++) {
            for (int j = 0; j < ds.size(); j++) {
                if (i == j) continue;
                if (ds.get(i).cn.contains(ds.get(j).cn)) cross++;
            }
        }
        ok("干扰项之间不互相包含", cross == 0, cross + " 处");

        // 规则②：中译英时词性优先一致
        List<EngTrain.W> ds2 = EngTrain.distractors(target, pool, pool, rnd, 3, false);
        ok("中译英能凑齐 3 个干扰项", ds2.size() == 3, "实际 " + ds2.size());
        int samePosCount = 0;
        for (EngTrain.W d : ds2) if (EngTrain.samePos(target.pos, d.pos)) samePosCount++;
        ok("中译英干扰项词性尽量一致（≥2）", samePosCount >= 2,
                samePosCount + "/3 同词性");

        // 干扰项不能和答案同词
        int sameWord = 0;
        for (EngTrain.W d : ds2) if (d.w.equalsIgnoreCase(target.w)) sameWord++;
        ok("干扰项不与答案同词", sameWord == 0, sameWord + " 个");

        // 干扰项互不重复
        int dupW = 0;
        for (int i = 0; i < ds2.size(); i++)
            for (int j = i + 1; j < ds2.size(); j++)
                if (ds2.get(i).w.equalsIgnoreCase(ds2.get(j).w)) dupW++;
        ok("干扰项互不重复", dupW == 0, dupW + " 对");
    }

    // ── ③ 判分归一化 ─────────────────────────────────────
    static void judgeTests() {
        EngTrain.Item it = new EngTrain.Item();
        it.mode = EngTrain.M_SPELL;
        it.answerText = "Apple";

        ok("完全一致判对", EngTrain.judgeText(it, "Apple") == EngTrain.J_RIGHT);
        ok("小写也算对", EngTrain.judgeText(it, "apple") == EngTrain.J_RIGHT);
        ok("带空格也算对", EngTrain.judgeText(it, "  apple  ") == EngTrain.J_RIGHT);
        ok("带句点也算对", EngTrain.judgeText(it, "apple.") == EngTrain.J_RIGHT);
        ok("差一个字母判近", EngTrain.judgeText(it, "aple") == EngTrain.J_NEAR,
                String.valueOf(EngTrain.judgeText(it, "aple")));
        ok("错一个字母判近", EngTrain.judgeText(it, "appla") == EngTrain.J_NEAR,
                String.valueOf(EngTrain.judgeText(it, "appla")));
        ok("差两个字母判错", EngTrain.judgeText(it, "apl") == EngTrain.J_WRONG,
                String.valueOf(EngTrain.judgeText(it, "apl")));
        ok("完全无关判错", EngTrain.judgeText(it, "banana") == EngTrain.J_WRONG);
        ok("空作答判错", EngTrain.judgeText(it, "") == EngTrain.J_WRONG);
        ok("null 判错", EngTrain.judgeText(it, null) == EngTrain.J_WRONG);

        // 带空格的短语
        EngTrain.Item it2 = new EngTrain.Item();
        it2.answerText = "in charge of";
        ok("短语多空格归一化", EngTrain.judgeText(it2, "in  charge  of") == EngTrain.J_RIGHT);
        ok("短语大小写归一化", EngTrain.judgeText(it2, "In Charge Of") == EngTrain.J_RIGHT);

        // nearHint 要指出位置
        String h = EngTrain.nearHint("apple", "aple");
        ok("nearHint 非空", h != null && h.length() > 4, h);

        // 选择题判分
        EngTrain.Item c = new EngTrain.Item();
        c.options = new String[]{"a", "b", "c", "d"};
        c.answer = 2;
        ok("选对判对", EngTrain.judgeChoice(c, 2) == EngTrain.J_RIGHT);
        ok("选错判错", EngTrain.judgeChoice(c, 0) == EngTrain.J_WRONG);
        ok("越界判错", EngTrain.judgeChoice(c, 9) == EngTrain.J_WRONG);

        // norm 本身
        ok("norm 去标点", "hello".equals(EngTrain.norm("Hello, ")));
        ok("norm 保留连字符", "well-known".equals(EngTrain.norm("well-known")));
        ok("norm 空串", "".equals(EngTrain.norm("")));
        ok("norm null", "".equals(EngTrain.norm(null)));

        // edit1 本身
        ok("edit1 同长替换", EngTrain.edit1("abc", "abd"));
        ok("edit1 同长两处差异", !EngTrain.edit1("abc", "aed"));
        ok("edit1 少一个", EngTrain.edit1("abc", "ab"));
        ok("edit1 多一个", EngTrain.edit1("ab", "abc"));
        ok("edit1 完全不同", !EngTrain.edit1("abc", "xyz"));
    }

    // ── ④ 五种题型都造得出来，且答案自洽 ─────────────────
    static void allModeTests() {
        // 词必须全是字母：带数字的词天然出不了拼写题（spellable() 会拒绝），
        // 用它当测试集会误判成"拼写题型坏了"。
        String[] stems = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot",
                "golf", "hotel", "india", "juliet", "kilo", "lima", "mike",
                "november", "oscar", "papa", "quebec", "romeo", "sierra",
                "tango", "uniform", "victor", "whiskey", "xray", "yankee", "zulu",
                "anchor", "border", "candle", "dragon"};
        List<EngTrain.W> pool = new ArrayList<EngTrain.W>();
        for (int i = 0; i < stems.length; i++) {
            pool.add(new EngTrain.W(i + 1, stems[i], "stem", "n.",
                    "释义" + (i + 1), "This is " + stems[i] + " here.",
                    "这是词" + (i + 1) + "。"));
        }
        // 混入几个特殊词：短语（不可拼写）、带连字符
        pool.add(new EngTrain.W(100, "in charge of", "", "prep.", "负责", "He is in charge of it.", "他负责。"));
        pool.add(new EngTrain.W(101, "well-known", "", "adj.", "有名的", "A well-known man.", "一个有名的人。"));

        Random rnd = new Random(7);
        int[] modes = {EngTrain.M_EC, EngTrain.M_CE, EngTrain.M_SPELL,
                EngTrain.M_CLOZE, EngTrain.M_POS};
        for (int mode : modes) {
            int built = 0;
            for (EngTrain.W w : pool) {
                EngTrain.Item it = EngTrain.make(mode, w, pool, pool, rnd);
                if (it == null) continue;
                built++;
                // 每道题都必须有答案
                if (it.answerText == null || it.answerText.isEmpty()) {
                    fails.add(EngTrain.modeName(mode) + " 空答案：" + w.w);
                }
                // 选择题：四个选项、无空项、答案下标合法、答案文本在选项里
                if (it.isChoice()) {
                    if (it.options.length != 4) {
                        fails.add(EngTrain.modeName(mode) + " 选项数 " + it.options.length);
                    }
                    int blank = 0, dup = 0;
                    for (int i = 0; i < it.options.length; i++) {
                        if (it.options[i] == null || it.options[i].isEmpty()) blank++;
                        for (int j = i + 1; j < it.options.length; j++) {
                            if (it.options[i] != null && it.options[i].equals(it.options[j])) dup++;
                        }
                    }
                    if (blank > 0) fails.add(EngTrain.modeName(mode) + " 有空选项：" + w.w);
                    if (dup > 0) fails.add(EngTrain.modeName(mode) + " 选项重复：" + w.w);
                    if (it.answer < 0 || it.answer >= it.options.length) {
                        fails.add(EngTrain.modeName(mode) + " 答案下标越界：" + w.w);
                    } else if (!it.answerText.equals(it.options[it.answer])) {
                        fails.add(EngTrain.modeName(mode) + " 答案与选项不符：" + w.w);
                    }
                    // 干扰项里不能出现和答案相同的文本
                    int same = 0;
                    for (int i = 0; i < it.options.length; i++) {
                        if (i != it.answer && it.answerText.equals(it.options[i])) same++;
                    }
                    if (same > 0) fails.add(EngTrain.modeName(mode) + " 干扰项等于答案：" + w.w);
                } else {
                    // 拼写/填空：无选项，答案为非空
                    if (it.options != null) fails.add(EngTrain.modeName(mode) + " 不该有选项：" + w.w);
                }
                // 题干不能是空
                if (it.prompt == null || it.prompt.isEmpty()) {
                    fails.add(EngTrain.modeName(mode) + " 空题干：" + w.w);
                }
            }
            ok(EngTrain.modeName(mode) + " 题型能出题", built > 15, "只出了 " + built + " 道");
        }

        // 判分闭环：每道选择题选正确答案必须判对，选任何一个干扰项必须判错
        int bad = 0;
        for (EngTrain.W w : pool) {
            for (int mode : modes) {
                EngTrain.Item it = EngTrain.make(mode, w, pool, pool, rnd);
                if (it == null || !it.isChoice()) continue;
                if (EngTrain.judgeChoice(it, it.answer) != EngTrain.J_RIGHT) bad++;
                for (int i = 0; i < it.options.length; i++) {
                    if (i == it.answer) continue;
                    if (EngTrain.judgeChoice(it, i) != EngTrain.J_WRONG) bad++;
                }
            }
        }
        ok("选择题判分闭环", bad == 0, bad + " 处错");

        // 拼写题答案必须能被判对
        int spellBad = 0;
        for (EngTrain.W w : pool) {
            EngTrain.Item it = EngTrain.make(EngTrain.M_SPELL, w, pool, pool, rnd);
            if (it == null) continue;
            if (EngTrain.judgeText(it, it.answerText) != EngTrain.J_RIGHT) spellBad++;
        }
        ok("拼写题答案能判对", spellBad == 0, spellBad + " 处");

        // 短语不能出拼写题
        EngTrain.W phrase = pool.get(pool.size() - 2);
        ok("短语不出拼写题",
                EngTrain.make(EngTrain.M_SPELL, phrase, pool, pool, rnd) == null,
                phrase.w);

        // build：不能造出 unsatisfiable 题
        List<EngTrain.Item> set = EngTrain.build(pool, pool, modes, new Random(11));
        ok("build 出卷非空", set.size() > 20, "实际 " + set.size());
        int badSet = 0;
        for (EngTrain.Item it : set) {
            if (it.answerText == null || it.answerText.isEmpty()) badSet++;
            if (it.isChoice() && (it.answer < 0 || it.answer >= it.options.length)) badSet++;
        }
        ok("build 每道题都有解", badSet == 0, badSet + " 道无解");
    }

    // ── ⑤ 真实词库 ───────────────────────────────────────
    static void realWordsTest() {
        // 用 assets/words.json 的真词跑一遍，确认线上数据不会造出废题。
        // 这里只读文件，不依赖 Android。
        List<EngTrain.W> all = loadRealWords();
        if (all == null) {
            notes.add("未找到 assets/words.json，跳过真实词库检查");
            return;
        }
        ok("真实词库规模", all.size() > 3000, "实际 " + all.size());

        Random rnd = new Random(2026);
        int[] modes = {EngTrain.M_EC, EngTrain.M_CE, EngTrain.M_SPELL,
                EngTrain.M_CLOZE, EngTrain.M_POS};

        // 抽 120 个词 × 5 种题型，统计造得出/造不出与废题
        int tried = 0, built = 0, broken = 0;
        int[] modeCount = new int[6];
        for (int i = 0; i < 120 && i < all.size(); i++) {
            EngTrain.W w = all.get((i * 37) % all.size());
            for (int mode : modes) {
                tried++;
                EngTrain.Item it = EngTrain.make(mode, w, all, all, rnd);
                if (it == null) continue;
                built++;
                modeCount[mode]++;
                boolean bad = false;
                if (it.answerText == null || it.answerText.isEmpty()) bad = true;
                if (it.isChoice()) {
                    if (it.options.length != 4) bad = true;
                    for (String o : it.options) if (o == null || o.isEmpty()) bad = true;
                    for (int a = 0; a < it.options.length && !bad; a++)
                        for (int b = a + 1; b < it.options.length; b++)
                            if (it.options[a] != null && it.options[a].equals(it.options[b])) bad = true;
                    if (!bad && !it.answerText.equals(it.options[it.answer])) bad = true;
                }
                if (bad) {
                    broken++;
                    if (broken <= 5) fails.add("真实词库废题：" + w.w + " / "
                            + EngTrain.modeName(mode) + " / " + it.prompt);
                }
            }
        }
        ok("真实词库造题成功率 ≥ 70%", built * 10 >= tried * 7,
                built + "/" + tried);
        ok("真实词库无废题", broken == 0, broken + " 道");
        for (int m : modes) {
            ok("真实词库能出「" + EngTrain.modeName(m) + "」",
                    modeCount[m] >= 60, "只出了 " + modeCount[m] + " 道");
        }
    }

    /** 直接读 JSON，避免依赖 Android。没 json 库时用极简解析。 */
    static List<EngTrain.W> loadRealWords() {
        String path = "assets/words.json";
        java.io.File f = new java.io.File(path);
        if (!f.exists()) {
            f = new java.io.File("../assets/words.json");
            if (!f.exists()) return null;
        }
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(f.toPath());
            String text = new String(raw, "UTF-8");
            return parseWords(text);
        } catch (Exception e) {
            notes.add("读取词库失败：" + e);
            return null;
        }
    }

    /**
     * 极简解析：只认 {"w":"..","ph":"..","pos":"..","cn":"..","ex":"..","exCn":".."} 这种形状。
     * 不用 org.json 是为了让这个测试在没有外部 jar 时也能跑。
     */
    static List<EngTrain.W> parseWords(String text) {
        List<EngTrain.W> out = new ArrayList<EngTrain.W>();
        int id = 0;
        // words.json 是 {"meta":..., "words":[{...},{...}]}。
        // 不能从第一个 '{' 开始配对 —— 那是整个文件的根对象，
        // 会把全文当成一个词条（第一版就这么错，只解析出 1 个词）。
        // 正确做法：只在深度 == 1 的层次取 '{' … '}' 作为词条。
        int depth = 0;
        boolean inStr = false;
        int objStart = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') {
                depth++;
                if (depth == 2) objStart = i;
            } else if (c == '}') {
                if (depth == 2 && objStart >= 0) {
                    String obj = text.substring(objStart, i + 1);
                    String w = field(obj, "w");
                    if (w != null && !w.isEmpty()) {
                        id++;
                        out.add(new EngTrain.W(id, w, nz(field(obj, "ph")),
                                nz(field(obj, "pos")), nz(field(obj, "cn")),
                                nz(field(obj, "ex")), nz(field(obj, "exCn"))));
                    }
                    objStart = -1;
                }
                depth--;
            }
        }
        return out;
    }

    static String nz(String s) { return s == null ? "" : s; }

    static int matchBrace(String t, int start) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    static String field(String obj, String key) {
        String pat = "\"" + key + "\"";
        int k = obj.indexOf(pat);
        if (k < 0) return null;
        int c = obj.indexOf(':', k + pat.length());
        if (c < 0) return null;
        int i = c + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) i++;
        if (i >= obj.length()) return null;
        if (obj.charAt(i) != '"') return null;
        i++;
        StringBuilder b = new StringBuilder();
        while (i < obj.length()) {
            char ch = obj.charAt(i);
            if (ch == '\\') {
                char n = obj.charAt(i + 1);
                if (n == 'n') b.append('\n');
                else if (n == 't') b.append('\t');
                else if (n == 'r') b.append('\r');
                else if (n == '"') b.append('"');
                else if (n == '\\') b.append('\\');
                else if (n == 'u') {
                    b.append((char) Integer.parseInt(obj.substring(i + 2, i + 6), 16));
                    i += 4;
                } else b.append(n);
                i += 2;
                continue;
            }
            if (ch == '"') break;
            b.append(ch);
            i++;
        }
        return b.toString();
    }
}
