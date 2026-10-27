package com.zsb.plan;

/**
 * 函数输入模板 —— 手机上敲数学表达式的补位工具。
 *
 * 为什么要单独做一个类：
 *   手机输入法敲 x^2 要切到符号页、敲 sin( 要关掉联想（否则 sin 会被
 *   自动纠正成 son / sin. 之类），敲 sqrt 更是灾难。所以给一排按键，
 *   按一下就插到光标处。
 *
 * 为什么逻辑不写在界面里：
 *   "插到光标处"看着简单，实际有三个坑，全都只有试过才知道：
 *     ① 有选区时要替换选区，不是插在选区前面；
 *     ② 插 sin( 之后光标要落在括号「里面」，不然用户还得手动左移；
 *     ③ 退格遇到 sin( 要整段删掉 —— 一个个删至少要按 4 次，
 *        而且删到 sin 中间会变成 s 这种残词。
 *   这三条在电脑上逐条断言，比在手机上点划算得多。
 */
public class ExprInput {

    /** 光标位置标记：模板里写 @ 表示"插完光标停这儿"，插入时 @ 本身会去掉。 */
    public static final String CARET = "@";

    /** 插入结果：新文本 + 新的光标位置。 */
    public static class R {
        public final String text;
        public final int cursor;

        public R(String text, int cursor) {
            this.text = text;
            this.cursor = cursor;
        }
    }

    // ── 常用函数模板（点一下整条换掉，不是插入）──────────
    /** 完整的函数，点了直接画。挑的是"最常要画、手机上最难敲"的那些。 */
    public static final String[] TEMPLATES = {
        "x^2", "x^3-3x", "1/x", "sin(x)", "cos(x)", "tan(x)",
        "ln(x)", "sqrt(x)", "exp(x)", "abs(x)", "atan(x)", "sin(x)/x",
        "2x+1", "(x+1)(x-1)", "x^3", "1/(1+x^2)",
    };
    public static final String[] TEMPLATE_NAMES = {
        "抛物线", "三次函数", "双曲线", "正弦", "余弦", "正切",
        "对数", "根号", "指数", "绝对值", "反正切", "sinc",
        "直线", "双根", "立方", "钟形",
    };

    // ── 按键表 ────────────────────────────────────────────
    /**
     * 按键分两层：数字层和函数层。
     *
     * 原来只有一层，24 个键全是符号和函数 —— 结果打 x^2-4 的时候
     * 数字还是得弹系统输入法。那排按键等于白做。
     *
     * 现在按计算器的习惯分页：
     *   数字页：0-9 和四则运算，数字才是你最高频要敲的东西；
     *   函数页：sin/cos/积分号这类，一次看全。
     * 切页按钮上标 "fn" / "123"。
     */
    public static final String[][] KEYS_NUM = {
        {"7", "7"}, {"8", "8"}, {"9", "9"},
        {"(", "("}, {")", ")"}, {"DEL", "\b"},

        {"4", "4"}, {"5", "5"}, {"6", "6"},
        {"x", "x"}, {"^", "^"}, {"÷", "/"},

        {"1", "1"}, {"2", "2"}, {"3", "3"},
        {"+", "+"}, {"−", "-"}, {"×", "*"},

        {"0", "0"}, {".", "."}, {"π", "pi"},
        {"x²", "x^2"}, {"x³", "x^3"}, {"fn", "\f"},
    };

    public static final String[][] KEYS_FN = {
        {"sin", "sin(@)"}, {"cos", "cos(@)"}, {"tan", "tan(@)"},
        {"ln", "ln(@)"}, {"lg", "lg(@)"}, {"√", "sqrt(@)"},

        {"asin", "asin(@)"}, {"acos", "acos(@)"}, {"atan", "atan(@)"},
        {"|x|", "abs(@)"}, {"eˣ", "exp(@)"}, {"1/x", "1/(@)"},

        {"x²", "x^2"}, {"x³", "x^3"}, {"pi", "pi"},
        {"e", "e"}, {"·", "*"}, {"DEL", "\b"},

        {"123", "\n"}, {"sin(x)", "sin(@)"}, {"√x", "sqrt(@)"},
        {"(x+1)", "(x+1)"}, {"x-1", "x-1"}, {"( )", "(@)"},
    };

    /** 默认页（数字页）。界面首次渲染用它。 */
    public static final String[][] KEYS = KEYS_NUM;

    /** 特殊片段：切换键盘页。 */
    public static final String SWITCH = "\f";
    /** 切回数字页。 */
    public static final String BACK_TO_NUM = "\n";

    public static String[][] page(int which) {
        return which == 0 ? KEYS_NUM : KEYS_FN;
    }

    /**
     * 按键表补齐到整行。KEYS.length 若不是每行 6 个的整数倍，
     * 最后一行会残缺（原来的 x³ / 退格只占两格，右半边空着很难看）。
     * 这里用空显示文字的占位键补满 —— 界面看到空文字就不加点击事件。
     */
    public static String[][] paddedKeys(int perRow, int which) {
        String[][] src = page(which);
        int n = src.length;
        int target = ((n + perRow - 1) / perRow) * perRow;
        if (target == n) return src;
        String[][] out = new String[target][];
        System.arraycopy(src, 0, out, 0, n);
        for (int i = n; i < target; i++) out[i] = new String[]{"", ""};
        return out;
    }

    /** 占位键：显示文字为空，界面应跳过。 */
    public static boolean isPad(String[] key) {
        return key == null || key.length < 2 || key[0].isEmpty();
    }

    /** 可以被整段退格删掉的函数名（长的在前，避免 asin 被 sin 抢走）。 */
    private static final String[] FUNCS = {
        "arcsin", "arccos", "arctan", "asin", "acos", "atan",
        "sqrt", "abs", "exp", "sin", "cos", "tan", "log", "ln", "lg",
    };

    // ── 插入 ──────────────────────────────────────────────
    /**
     * 在选区处插入片段。
     *
     * @param text     原文本（null 当作空串）
     * @param selStart 选区起点（光标位置）
     * @param selEnd   选区终点；等于 selStart 表示没有选区
     * @param frag     要插入的片段，可含 @ 标记光标落点；"\b" 表示退格
     */
    public static R insert(String text, int selStart, int selEnd, String frag) {
        String t = text == null ? "" : text;
        int n = t.length();
        int a = clamp(Math.min(selStart, selEnd), 0, n);
        int b = clamp(Math.max(selStart, selEnd), 0, n);

        if ("\b".equals(frag)) return backspace(t, a, b);
        // 翻页键不是输入内容，原样返回文本、光标不动
        if (SWITCH.equals(frag) || BACK_TO_NUM.equals(frag)) return new R(t, a);
        if (frag == null || frag.isEmpty()) return new R(t, a);

        String head = t.substring(0, a);
        String tail = t.substring(b);

        int mark = frag.indexOf(CARET);
        String body;
        int cursorInFrag;
        if (mark >= 0) {
            body = frag.substring(0, mark) + frag.substring(mark + CARET.length());
            cursorInFrag = mark;
        } else {
            body = frag;
            cursorInFrag = body.length();
        }
        return new R(head + body + tail, head.length() + cursorInFrag);
    }

    // ── 退格 ──────────────────────────────────────────────
    /**
     * 退格。有选区就删选区；没有选区时，如果光标前正好是一个「函数名 + (」，
     * 整段删掉 —— 否则 sin( 要按 4 次，中途还会留下 s、si 这种残词。
     */
    public static R backspace(String text, int selStart, int selEnd) {
        String t = text == null ? "" : text;
        int n = t.length();
        int a = clamp(Math.min(selStart, selEnd), 0, n);
        int b = clamp(Math.max(selStart, selEnd), 0, n);

        if (a != b) {
            return new R(t.substring(0, a) + t.substring(b), a);
        }
        if (a == 0) return new R(t, 0);

        String head = t.substring(0, a);
        int cut = funcPrefixLen(head);
        if (cut > 0) {
            return new R(t.substring(0, a - cut) + t.substring(a), a - cut);
        }
        // 普通退格：删一个字符（
        // 用 codePoint 判断，别把 emoji/生僻字劈成半个）
        int cp = head.codePointBefore(head.length());
        int len = Character.charCount(cp);
        return new R(t.substring(0, a - len) + t.substring(a), a - len);
    }

    /** head 末尾若是「函数名 + (」，返回要整段删掉的长度；否则 0。 */
    static int funcPrefixLen(String head) {
        if (head == null || head.isEmpty()) return 0;
        if (!head.endsWith("(")) return 0;
        String before = head.substring(0, head.length() - 1);
        String low = before.toLowerCase();
        for (String f : FUNCS) {
            if (low.endsWith(f)) {
                // 函数名前不能紧跟字母（否则是别人名字的尾巴，如 "asin" 里的 "sin"）
                int start = before.length() - f.length();
                if (start > 0) {
                    char prev = before.charAt(start - 1);
                    if (isLetter(prev)) continue;
                }
                return f.length() + 1;
            }
        }
        return 0;
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    // ── 括号平衡提示 ──────────────────────────────────────
    /** 未闭合的左括号数量；负数表示右括号多了。给输入框下面的提示用。 */
    public static int unclosedParens(String text) {
        if (text == null) return 0;
        int d = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '（') d++;
            else if (c == ')' || c == '）') d--;
        }
        return d;
    }

    /** 结尾少括号时，直接补上。返回补好的文本；本来就平衡则原样返回。 */
    public static String closeParens(String text) {
        String t = text == null ? "" : text;
        int d = unclosedParens(t);
        if (d <= 0) return t;
        StringBuilder b = new StringBuilder(t);
        for (int i = 0; i < d; i++) b.append(')');
        return b.toString();
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
