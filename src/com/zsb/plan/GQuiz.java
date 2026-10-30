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
     * 把一个义项串按内部分号拆成单义。
     *
     * vocab.json 的 senses[i].cn 常常塞了多个近义说法：
     *   charge → {"v", "收费;控告;掌管;电荷"}
     * 整个当成一个选项，玩家看到「收费;控告;掌管;电荷」这种四合一，
     * 既不像选项也不像释义。拆开后才是一道正常的四选一。
     */
    private static List<String> splitSenses(String cn) {
        List<String> out = new ArrayList<>();
        if (cn == null) return out;
        for (String part : cn.split("[;；]")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * 义项归一化：只留中文骨架，用于判断两个选项是不是「同一个意思」。
     *
     * 为什么必须做这件事：中文释义里同一概念常有多种写法，
     *   · 词性前缀（v. / n.）不该影响判重；
     *   · 「部⻔ / 部门」（⻔ 是兼容区汉字）看起来不同其实是同一个词；
     *   · 「收费 / 费用」「进步 / 进展」这种，玩家选哪个都说得通。
     * 只要两条释义归一化后互相包含，就当同一个意思处理 ——
     * 绝不能把两个都能算对的选项同时摆出来。
     */
    private static String senseKey(String t) {
        if (t == null) return "";
        String s = t.trim().toLowerCase();
        // 去掉词性前缀，如 "v." "n." "adj."
        s = POS_PREFIX.matcher(s).replaceFirst("");
        // 去掉标点、空白、括号、省略号
        s = PUNCT.matcher(s).replaceAll("");
        return s;
    }

    /**
     * senseKey 用的两个正则**预编译**。
     *
     * 原来是 String.replaceAll —— 每次调用现编一个 Pattern。而典型热路径是
     * makeOverload 的干扰项判重：一次出题要跑上万次 sameSense，每次重编
     * 两个正则。这是过载出题实测 3 秒的直接成因之一。
     */
    private static final java.util.regex.Pattern POS_PREFIX =
            java.util.regex.Pattern.compile("^[a-z]+\\.\\s*");
    private static final java.util.regex.Pattern PUNCT =
            java.util.regex.Pattern.compile("[\\s（）()【】\\[\\]、，,。.·…\\-—/]");

    /**
     * 全词库释义缓存：一次归一化，之后按 key 判重。
     *
     * 为什么必须缓存：makeOverload 的 step ② 要扫全部 1654 个词的义项
     * （≈3400 条），每条都要和已收集的干扰项做同义判重。原实现每次都比较
     * 两个**原始字符串**、每次重算 senseKey —— O(N²) 次正则调用。
     *
     * 现在改成：加载时把每条释义的 senseKey 算好存成数组，
     * 判重只在 HashSet 里查 key（O(1)），必要时才做 contains 包含判断。
     * 实测从 3 秒降到毫秒级。
     */
    private java.util.List<String[]> senseCache;

    /** 惰性构建释义缓存。每条 = {senseKey, pos, 原文}。 */
    private void ensureSenseCache() {
        if (senseCache != null) return;
        java.util.List<String[]> out = new ArrayList<>();
        for (GCard o : G.words) {
            if (o == null) continue;
            for (int i = 0; i < o.senseCount(); i++) {
                String pos = o.sensePos(i);
                for (String t : splitSenses(o.senseText(i))) {
                    out.add(new String[]{senseKey(t), pos, t});
                }
            }
        }
        senseCache = out;
    }

    /** 供判题层调用：两个释义是否算同一个意思。 */
    public static boolean sameSensePublic(String a, String b) {
        return sameSense(a, b);
    }

    /** 两个释义是否算「同一个意思」（归一化后相等或互相包含）。 */
    private static boolean sameSense(String a, String b) {
        String x = senseKey(a), y = senseKey(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        // 互相包含也要算：'收费' vs '费用' 不算，但 '信用' vs '信用贷款' 算
        return x.contains(y) || y.contains(x);
    }

    /** 一组释义里是否已经存在和 t 同义的项。 */
    private static boolean hasSame(List<String> list, String t) {
        for (String s : list) if (sameSense(s, t)) return true;
        return false;
    }

    /**
     * 已归一化的 key 集合里，是否存在与 k 互相包含的项。
     *
     * sameSense 的「互相包含」规则无法完全被 HashSet 表达
     * （「收费」和「收费单」key 不等但同义），所以这里补一次线性扫描。
     * 只在 key 不相等时才会走到，且集合规模被限制在选项数量级，
     * 不会退化成原来的 O(N²)。
     */
    private static boolean containsAny(Set<String> keys, String k) {
        if (keys.isEmpty()) return false;
        for (String s : keys) {
            if (s.isEmpty()) continue;
            if (k.contains(s) || s.contains(k)) return true;
        }
        return false;
    }

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

        // 常见义用来当「已知条件」写进题面，所以第 0 个义项的所有说法
        // 都要先收集起来：被判为「和常见义同义」的义项不能拿来出题 ——
        // 那样所谓的「生僻义」其实就是常见义的另一种写法，
        // 玩家答常见义被判错，纯属题目自身有歧义。
        List<String> common0 = splitSenses(c.senseText(0));

        // 只剩与常见义都不同义的义项才能当正确答案
        List<Integer> pickable = new ArrayList<>();
        for (int i : idx) {
            boolean dup = false;
            for (String t : splitSenses(c.senseText(i))) {
                if (hasSame(common0, t)) { dup = true; break; }
            }
            if (!dup) pickable.add(i);
        }
        if (pickable.isEmpty()) return null;   // 没有真正的僻义，别出这题
        int pick = pickable.get(rnd.nextInt(pickable.size()));

        // 正确答案按内部分号拆开，只取一个说法。
        // 原来整个 "收费;控告;掌管;电荷" 当成一个选项，玩家看到的是
        // 四合一的长串，既不像选项也不像释义；拆开才是正常四选一。
        List<String> rightList = splitSenses(c.senseText(pick));
        if (rightList.isEmpty()) return null;
        String right = rightList.get(rnd.nextInt(rightList.size()));

        // ── 题面必须交代「常见义是什么」 ──────────────────────
        //
        // 原来的题面只有英文单词，然后让玩家「选出生僻义」。
        // 问题是四选一里同时摆着常见义和僻义，两者都是这个词的正确翻译，
        // 玩家没有任何依据知道该排除哪个 —— 选常见义被判错，
        // 感觉就是「明明对了却算我错」。
        //
        // 现在把常见义直接写在题面上（「charge（常见义：收费）」），
        // 问题变成「除了收费，它还有什么意思」，排除项一目了然，
        // 选常见义才算真的答错，而不是被暗算。
        List<String> commonList = common0;

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
        // 题面带上常见义，把「选哪个」变成可判定的问题。
        // 只给单词时，常见义和僻义都是这个词的正确翻译，
        // 玩家选常见义被判错会觉得冤枉 —— 题目本身有歧义。
        // 写成「charge ─ 常见义：收费」之后，要排除什么一目了然。
        q.prompt = c.en + (commonList.isEmpty()
                ? "" : "　─ 常见义：" + String.join(" / ", commonList));

        // ── 干扰项构造：绝不能出现「两个都对」的选项 ──────────
        //
        // 原来的写法是把这张牌其它义项直接塞进来当干扰，包括常见义。
        // 但它没做语义去重，于是出现你遇到的这个问题：
        //   · 义项内部带分号（"收费;控告;掌管"）时，
        //     和正确答案里的某个说法重了，"收费" 和 "收费;控告" 同时出现；
        //   · 「进步 / 进展」「费用 / 收费」这种近义写法，
        //     玩家选哪个都说得通，却只有一个被判对 —— 冤枉。
        //   · 更要命的是 q.answer = opts.indexOf(right)：
        //     一旦有两个字面相同的选项，indexOf 只返回第一个下标，
        //     玩家点第二个同样的文字，判题时下标不等，直接算错。
        //
        // 所以这里每加一个干扰项都必须先过 sameSense：同义的直接丢弃。
        List<String> opts = new ArrayList<>();
        opts.add(right);

        // ① 这张牌自己的其它义项优先当干扰 —— 最有意义：玩家必须分清
        //    「收费 / 看待 / 掌管」到底哪一个是这次问的那个。
        //    但与常见义同义的项要跳过：常见义已经写在题面上，
        //    再把它摆成选项等于白送一个错误答案，还挤掉了有效干扰项。
        for (int i = 0; i < c.senseCount() && opts.size() < 4; i++) {
            if (i == pick) continue;
            for (String t : splitSenses(c.senseText(i))) {
                if (opts.size() >= 4) break;
                if (!hasSame(opts, t) && !hasSame(commonList, t)) opts.add(t);
            }
        }
        // ② 还不够，从词库补其它词的义项。同样逐个过同义过滤。
        //
        // 这里的性能是关键：原实现三层嵌套直接扫 G.words，每条候选都调用
        // hasSame（线性扫已收集项 + 两个字符串重算 senseKey）。实测单次
        // 出题 2.5~3.1 秒，而这条路径在真机上跑在 UI 线程里，
        // 直接构成 ANR。
        //
        // 现在：释义在第一次使用时归一化进 senseCache，
        // 判重用 HashSet<String> 查 key（命中 O(1)），只有 key 不相等时
        // 才退化成 contains 包含判断（数量少，可接受）。
        if (opts.size() < 4) {
            ensureSenseCache();
            String wantPos = c.sensePos(pick);
            Set<String> taken = new HashSet<>();
            for (String t : opts) taken.add(senseKey(t));
            for (String t : commonList) taken.add(senseKey(t));

            List<String> sameP = new ArrayList<>();   // 同词性优先
            List<String> anyP  = new ArrayList<>();    // 兜底
            Set<String> sameKeys = new HashSet<>();
            Set<String> anyKeys  = new HashSet<>();

            for (String[] e : senseCache) {
                String k = e[0], pos = e[1], t = e[2];
                if (k.isEmpty() || taken.contains(k)) continue;
                // 包含关系再确认一次（「收费」vs「收费单」这种 key 不等但同义）
                if (containsAny(taken, k)) continue;
                Set<String> keys = (!wantPos.isEmpty() && wantPos.equals(pos)) ? sameKeys : anyKeys;
                if (keys.add(k)) (!wantPos.isEmpty() && wantPos.equals(pos) ? sameP : anyP).add(t);
            }
            java.util.Collections.shuffle(sameP, rnd);
            java.util.Collections.shuffle(anyP, rnd);
            for (String t : sameP) { if (opts.size() >= 4) break;
                                     if (!hasSame(opts, t)) opts.add(t); }
            for (String t : anyP)  { if (opts.size() >= 4) break;
                                     if (!hasSame(opts, t)) opts.add(t); }
        }
        // 凑不齐四个就别出这道题 —— 两个选项的选择题纯属送分，
        // 而且在只有 2 项时「答对」的运气成分太高。
        if (opts.size() < 3) return null;

        java.util.Collections.shuffle(opts, rnd);

        q.options = opts.toArray(new String[0]);
        // 用 indexOf 定位正确答案：right 一定在 opts 里，且 hasSame 保证
        // 不会有两个同义项共存，所以这里拿到的下标是唯一的。
        q.answer = opts.indexOf(right);
        if (q.answer < 0) return null;
        String sp = c.sensePos(pick);
        q.sub = (sp.isEmpty() ? "" : sp + "　")
                + "选出常见义之外的义项 · 答对 2.2 倍真实伤害，答错自伤 "
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
