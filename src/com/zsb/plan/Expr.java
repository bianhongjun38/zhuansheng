package com.zsb.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * 表达式引擎：把 "sin(x^2)" 这样的文本解析成语法树，然后
 *   ① 数值求值（画图用）
 *   ② 符号求导（求导结果用，不是数值近似）
 *   ③ 规范化输出（求导结果要人能看懂）
 *
 * 为什么单独写一个：这些逻辑必须能在电脑上跑，才能被审计。
 * 纯 Java，零 Android 依赖——verify.py 会拿它做数值对照审计。
 *
 * 输入语法（比手写数学宽松）：
 *   数字 3、3.5、1E3        变量 x
 *   常数 pi、e              运算 + - * / ^（^ 右结合，-x^2 = -(x^2)）
 *   函数 sin cos tan cot sec csc asin acos atan
 *        sinh cosh tanh ln lg log10 log2 exp sqrt abs sign floor ceil
 *        pow(a,b) min(a,b) max(a,b)
 *   隐式乘法：2x、3sin(x)、(x+1)(x-1)、x(x+1)、2pi 都按乘法算
 *   全角括号、×、÷、−、· 会自动换成半角（手机输入法常打出这些）
 *
 * 约定：数学上无定义的地方一律返回 NaN（例如 ln(-1)、asin(2)），
 * 除以 0 返回 ±Infinity。画图端据此断开曲线，不连线。
 */
public class Expr {

    public static final int T_NUM = 1;
    public static final int T_VAR = 2;
    public static final int T_ADD = 3;
    public static final int T_SUB = 4;
    public static final int T_MUL = 5;
    public static final int T_DIV = 6;
    public static final int T_POW = 7;
    public static final int T_NEG = 8;
    public static final int T_FUNC = 9;

    /** 解析/求值错误。文案直接给用户看，所以写中文。 */
    public static class ExprError extends RuntimeException {
        public ExprError(String m) { super(m); }
    }

    public static class Node {
        public final int t;
        public double v;        // T_NUM
        public String fn;       // T_FUNC
        public Node a, b;
        Node(int t) { this.t = t; }
    }

    // ── 构造 ──────────────────────────────────────────────
    public static Node num(double v) { Node n = new Node(T_NUM); n.v = v; return n; }
    public static Node var() { return new Node(T_VAR); }
    public static Node bin(int t, Node a, Node b) { Node n = new Node(t); n.a = a; n.b = b; return n; }
    public static Node neg(Node a) { Node n = new Node(T_NEG); n.a = a; return n; }
    public static Node fn(String name, Node a) { Node n = new Node(T_FUNC); n.fn = name; n.a = a; return n; }

    // ── 入口：解析 ────────────────────────────────────────
    public static Node parse(String src) {
        if (src == null) throw new ExprError("表达式为空");
        Parser p = new Parser(normalize(src));
        Node n = p.parseSum();
        p.skipSpace();
        if (!p.eof()) throw new ExprError("第 " + (p.i + 1) + " 个字符看不懂：" + p.rest());
        return n;
    }

    /** 全角/数学符号统一成 ASCII，去掉空白。 */
    public static String normalize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '（': b.append('('); break;
                case '）': b.append(')'); break;
                case '，': b.append(','); break;
                case '×': case '·': case '⋅': case '*': b.append('*'); break;
                case '÷': b.append('/'); break;
                case '−': case '–': case '—': b.append('-'); break;
                case '＋': b.append('+'); break;
                case '５': case '３': case '２': case '１': case '４':
                case '６': case '７': case '８': case '９': case '０':
                    b.append((char) ('0' + (c - '０'))); break;
                case '²': b.append("^2"); break;
                case '³': b.append("^3"); break;
                case 'π': b.append("pi"); break;
                case '　': break;
                case ' ': case '\t': case '\n': case '\r': break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    // ── 递归下降解析 ──────────────────────────────────────
    static class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        boolean eof() { return i >= s.length(); }
        char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
        char peek2() { return i + 1 < s.length() ? s.charAt(i + 1) : '\0'; }
        void skipSpace() { while (i < s.length() && s.charAt(i) == ' ') i++; }
        String rest() { return s.substring(i, Math.min(s.length(), i + 8)); }

        Node parseSum() {
            Node n = parseTerm();
            while (true) {
                skipSpace();
                char c = peek();
                if (c == '+') { i++; n = bin(T_ADD, n, parseTerm()); }
                else if (c == '-') { i++; n = bin(T_SUB, n, parseTerm()); }
                else return n;
            }
        }

        Node parseTerm() {
            Node n = parseUnary();
            while (true) {
                skipSpace();
                char c = peek();
                if (c == '*') { i++; n = bin(T_MUL, n, parseUnary()); continue; }
                if (c == '/') { i++; n = bin(T_DIV, n, parseUnary()); continue; }
                // 隐式乘法：后面紧跟一个“原子的开头”就乘
                if (startsPrimary(c)) { n = bin(T_MUL, n, parseUnary()); continue; }
                return n;
            }
        }

        boolean startsPrimary(char c) {
            return (c >= '0' && c <= '9') || c == '.' || c == '(' || isAlpha(c);
        }

        static boolean isAlpha(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }

        Node parseUnary() {
            skipSpace();
            if (peek() == '-') { i++; return neg(parseUnary()); }
            if (peek() == '+') { i++; return parseUnary(); }
            return parsePower();
        }

        Node parsePower() {
            Node base = parsePrimary();
            skipSpace();
            if (peek() == '^') {
                i++;
                // 右结合：x^2^3 = x^(2^3)；指数允许带负号
                Node exp = parseUnary();
                return bin(T_POW, base, exp);
            }
            return base;
        }

        Node parsePrimary() {
            skipSpace();
            if (eof()) throw new ExprError("表达式不完整");
            char c = peek();
            if (c == '(') {
                i++;
                Node n = parseSum();
                skipSpace();
                if (peek() != ')') throw new ExprError("括号没有配对");
                i++;
                return n;
            }
            if ((c >= '0' && c <= '9') || c == '.') return parseNumber();
            if (isAlpha(c)) return parseName();
            throw new ExprError("第 " + (i + 1) + " 个字符看不懂：" + c);
        }

        Node parseNumber() {
            int st = i;
            while (i < s.length() && ((s.charAt(i) >= '0' && s.charAt(i) <= '9') || s.charAt(i) == '.')) i++;
            // 大写 E 表示科学计数：1E3 = 1000。小写 e 是自然常数，避免 2e5 歧义
            if (i < s.length() && s.charAt(i) == 'E') {
                int save = i;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
                } else {
                    i = save;   // 不是合法指数，退回
                }
            }
            String t = s.substring(st, i);
            try {
                return num(Double.parseDouble(t));
            } catch (Exception e) {
                throw new ExprError("数字写错了：" + t);
            }
        }

        /**
         * 名字识别用「最长已知前缀」，不是贪婪吃光整串。
         * 否则 xsin(x) 会被当成一个叫 xsin 的函数；学生手写时这种连写极常见。
         * 反例必须保留：exp 不能被拆成 e·xp。
         */
        Node parseName() {
            int st = i;
            while (i < s.length() && (isAlpha(s.charAt(i)) || (s.charAt(i) >= '0' && s.charAt(i) <= '9'))) i++;
            String run = s.substring(st, i);

            String name = null;
            for (int len = run.length(); len >= 1; len--) {
                String cand = run.substring(0, len);
                if (isKnownName(cand)) { name = cand; break; }
            }
            if (name == null) throw new ExprError("不认识的名字：" + run + "（变量只有 x）");
            i = st + name.length();

            if ("pi".equals(name)) return num(Math.PI);
            if ("e".equals(name)) return num(Math.E);
            if ("x".equals(name)) return var();

            skipSpace();
            if (peek() != '(') throw new ExprError(name + " 后面要跟括号，例如 " + name + "(x)");
            i++;
            Node a1 = parseSum();
            Node a2 = null;
            skipSpace();
            if (peek() == ',') {
                i++;
                a2 = parseSum();
                skipSpace();
            }
            if (peek() != ')') throw new ExprError(name + " 的括号没有配对");
            i++;
            if (a2 != null) {
                if ("pow".equals(name)) return bin(T_POW, a1, a2);
                Node n = new Node(T_FUNC);
                n.fn = name;
                n.a = a1;
                n.b = a2;
                return n;
            }
            Node n = new Node(T_FUNC);
            n.fn = name;
            n.a = a1;
            return n;
        }
    }

    static boolean isKnownName(String n) {
        return "x".equals(n) || "pi".equals(n) || "e".equals(n) || isFunc(n);
    }

    public static boolean isFunc(String name) {
        return "sin".equals(name) || "cos".equals(name) || "tan".equals(name)
                || "cot".equals(name) || "sec".equals(name) || "csc".equals(name)
                || "asin".equals(name) || "acos".equals(name) || "atan".equals(name)
                || "sinh".equals(name) || "cosh".equals(name) || "tanh".equals(name)
                || "ln".equals(name) || "lg".equals(name) || "log10".equals(name)
                || "log2".equals(name) || "exp".equals(name) || "sqrt".equals(name)
                || "abs".equals(name) || "sign".equals(name) || "floor".equals(name)
                || "ceil".equals(name) || "pow".equals(name) || "min".equals(name)
                || "max".equals(name);
    }

    // ── 求值 ──────────────────────────────────────────────
    public static double eval(Node n, double x) {
        if (n == null) return Double.NaN;
        switch (n.t) {
            case T_NUM: return n.v;
            case T_VAR: return x;
            case T_ADD: return eval(n.a, x) + eval(n.b, x);
            case T_SUB: return eval(n.a, x) - eval(n.b, x);
            case T_MUL: return eval(n.a, x) * eval(n.b, x);
            case T_DIV: return eval(n.a, x) / eval(n.b, x);
            case T_POW: return Math.pow(eval(n.a, x), eval(n.b, x));
            case T_NEG: return -eval(n.a, x);
            default: {
                double u = eval(n.a, x);
                double w = n.b == null ? Double.NaN : eval(n.b, x);
                return callFn(n.fn, u, w);
            }
        }
    }

    static double callFn(String f, double u, double w) {
        if ("sin".equals(f)) return Math.sin(u);
        if ("cos".equals(f)) return Math.cos(u);
        if ("tan".equals(f)) return Math.tan(u);
        if ("cot".equals(f)) return 1.0 / Math.tan(u);
        if ("sec".equals(f)) return 1.0 / Math.cos(u);
        if ("csc".equals(f)) return 1.0 / Math.sin(u);
        if ("asin".equals(f)) return (u > 1 || u < -1) ? Double.NaN : Math.asin(u);
        if ("acos".equals(f)) return (u > 1 || u < -1) ? Double.NaN : Math.acos(u);
        if ("atan".equals(f)) return Math.atan(u);
        if ("sinh".equals(f)) return Math.sinh(u);
        if ("cosh".equals(f)) return Math.cosh(u);
        if ("tanh".equals(f)) return Math.tanh(u);
        if ("ln".equals(f)) return u > 0 ? Math.log(u) : Double.NaN;
        if ("lg".equals(f) || "log10".equals(f)) return u > 0 ? Math.log10(u) : Double.NaN;
        if ("log2".equals(f)) return u > 0 ? Math.log(u) / Math.log(2) : Double.NaN;
        if ("exp".equals(f)) return Math.exp(u);
        if ("sqrt".equals(f)) return u >= 0 ? Math.sqrt(u) : Double.NaN;
        if ("abs".equals(f)) return Math.abs(u);
        if ("sign".equals(f)) return u > 0 ? 1 : (u < 0 ? -1 : 0);
        if ("floor".equals(f)) return Math.floor(u);
        if ("ceil".equals(f)) return Math.ceil(u);
        if ("min".equals(f)) return Math.min(u, w);
        if ("max".equals(f)) return Math.max(u, w);
        return Double.NaN;
    }

    // ── 符号求导 ──────────────────────────────────────────
    /**
     * 对 x 求一阶导。返回的树已经化简（去 0/1、合并常数、常数提前）。
     * 这是真符号求导，不是数值差分——所以它显示的是公式，不是近似值。
     * 数值正确性由 verify.py 的 MathTest 用中心差分对照审计（24 组公式）。
     */
    public static Node diff(Node n) {
        return simplify(d0(n));
    }

    static Node d0(Node n) {
        if (n == null) return num(0);
        switch (n.t) {
            case T_NUM: return num(0);
            case T_VAR: return num(1);
            case T_ADD: return bin(T_ADD, d0(n.a), d0(n.b));
            case T_SUB: return bin(T_SUB, d0(n.a), d0(n.b));
            case T_NEG: return neg(d0(n.a));
            case T_MUL:
                return bin(T_ADD,
                        bin(T_MUL, d0(n.a), n.b),
                        bin(T_MUL, n.a, d0(n.b)));
            case T_DIV: {
                // (u/v)' = (u'v - uv') / v^2
                Node nume = bin(T_SUB, bin(T_MUL, d0(n.a), n.b), bin(T_MUL, n.a, d0(n.b)));
                return bin(T_DIV, nume, bin(T_POW, n.b, num(2)));
            }
            case T_POW: {
                boolean baseConst = isConst(n.a);
                boolean expConst = isConst(n.b);
                if (expConst) {
                    // (u^c)' = c·u^(c-1)·u'
                    Node coef = n.b;
                    Node p = bin(T_POW, n.a, bin(T_SUB, n.b, num(1)));
                    return bin(T_MUL, bin(T_MUL, coef, p), d0(n.a));
                }
                if (baseConst) {
                    // (a^v)' = a^v·ln a·v'
                    return bin(T_MUL,
                            bin(T_MUL, n, fn("ln", n.a)),
                            d0(n.b));
                }
                // 一般幂指函数：u^v = e^(v ln u)
                // (u^v)' = u^v·(v'·ln u + v·u'/u)
                Node inner = bin(T_ADD,
                        bin(T_MUL, d0(n.b), fn("ln", n.a)),
                        bin(T_DIV, bin(T_MUL, n.b, d0(n.a)), n.a));
                return bin(T_MUL, n, inner);
            }
            default: {
                String f = n.fn;
                Node u = n.a;
                Node du = d0(u);
                if ("sin".equals(f)) return bin(T_MUL, fn("cos", u), du);
                if ("cos".equals(f)) return bin(T_MUL, neg(fn("sin", u)), du);
                if ("tan".equals(f))
                    return bin(T_DIV, du, bin(T_POW, fn("cos", u), num(2)));
                if ("cot".equals(f))
                    return neg(bin(T_DIV, du, bin(T_POW, fn("sin", u), num(2))));
                if ("sec".equals(f)) return bin(T_MUL, bin(T_MUL, fn("sec", u), fn("tan", u)), du);
                if ("csc".equals(f)) return neg(bin(T_MUL, bin(T_MUL, fn("csc", u), fn("cot", u)), du));
                if ("asin".equals(f))
                    return bin(T_DIV, du, fn("sqrt", bin(T_SUB, num(1), bin(T_POW, u, num(2)))));
                if ("acos".equals(f))
                    return neg(bin(T_DIV, du, fn("sqrt", bin(T_SUB, num(1), bin(T_POW, u, num(2))))));
                if ("atan".equals(f))
                    return bin(T_DIV, du, bin(T_ADD, num(1), bin(T_POW, u, num(2))));
                if ("sinh".equals(f)) return bin(T_MUL, fn("cosh", u), du);
                if ("cosh".equals(f)) return bin(T_MUL, fn("sinh", u), du);
                if ("tanh".equals(f))
                    return bin(T_DIV, du, bin(T_POW, fn("cosh", u), num(2)));
                if ("ln".equals(f)) return bin(T_DIV, du, u);
                if ("lg".equals(f) || "log10".equals(f))
                    return bin(T_DIV, du, bin(T_MUL, u, fn("ln", num(10))));
                if ("log2".equals(f))
                    return bin(T_DIV, du, bin(T_MUL, u, fn("ln", num(2))));
                if ("exp".equals(f)) return bin(T_MUL, fn("exp", u), du);
                if ("sqrt".equals(f))
                    return bin(T_DIV, du, bin(T_MUL, num(2), fn("sqrt", u)));
                if ("abs".equals(f)) return bin(T_MUL, fn("sign", u), du);
                if ("sign".equals(f) || "floor".equals(f) || "ceil".equals(f)) return num(0);
                if ("min".equals(f) || "max".equals(f)) return num(Double.NaN);
                throw new ExprError("这个函数不支持求导：" + f);
            }
        }
    }

    /** 是否与 x 无关（子树里没有 T_VAR）。 */
    public static boolean isConst(Node n) {
        if (n == null) return true;
        if (n.t == T_VAR) return false;
        return isConst(n.a) && isConst(n.b);
    }

    // ── 化简 ──────────────────────────────────────────────
    public static Node simplify(Node n) {
        if (n == null) return num(0);
        switch (n.t) {
            case T_NUM:
            case T_VAR:
                return n;
            case T_NEG: {
                Node a = simplify(n.a);
                if (a.t == T_NUM) return num(-a.v);
                if (a.t == T_NEG) return a.a;
                return neg(a);
            }
            case T_ADD: {
                Node a = simplify(n.a), b = simplify(n.b);
                if (isZero(a)) return b;
                if (isZero(b)) return a;
                if (a.t == T_NUM && b.t == T_NUM) return num(a.v + b.v);
                return bin(T_ADD, a, b);
            }
            case T_SUB: {
                Node a = simplify(n.a), b = simplify(n.b);
                if (isZero(b)) return a;
                if (isZero(a)) return neg(b);
                if (a.t == T_NUM && b.t == T_NUM) return num(a.v - b.v);
                if (same(a, b)) return num(0);
                return bin(T_SUB, a, b);
            }
            case T_MUL: return simplifyMul(n);
            case T_DIV: {
                Node a = simplify(n.a), b = simplify(n.b);
                if (isZero(a)) return num(0);
                if (isOne(b)) return a;
                if (same(a, b)) return num(1);
                if (a.t == T_NUM && b.t == T_NUM && b.v != 0) {
                    double q = a.v / b.v;
                    if (Math.rint(q) == q && Math.abs(q) < 1e9) return num(q);
                }
                // 负号提到分式外：(-a)/b = -(a/b)
                if (a.t == T_NEG) return neg(bin(T_DIV, a.a, b));
                return bin(T_DIV, a, b);
            }
            case T_POW: {
                Node a = simplify(n.a), b = simplify(n.b);
                if (isZero(b)) return num(1);
                if (isOne(b)) return a;
                if (isZero(a)) return num(0);
                if (isOne(a)) return num(1);
                if (a.t == T_NUM && b.t == T_NUM && Math.rint(b.v) == b.v
                        && Math.abs(b.v) <= 12 && b.v >= 0) {
                    return num(Math.pow(a.v, b.v));
                }
                return bin(T_POW, a, b);
            }
            default: {
                Node a = simplify(n.a);
                Node b = n.b == null ? null : simplify(n.b);
                if (a.t == T_NUM && (b == null || b.t == T_NUM) && !Double.isNaN(a.v)) {
                    double r = callFn(n.fn, a.v, b == null ? Double.NaN : b.v);
                    if (!Double.isNaN(r)) return num(r);
                }
                Node out = new Node(T_FUNC);
                out.fn = n.fn;
                out.a = a;
                out.b = b;
                return out;
            }
        }
    }

    static Node simplifyMul(Node n) {
        // 把乘法摊平成因子表，合并常数、去掉 1、把常数排到最前
        List<Node> fs = new ArrayList<Node>();
        flattenMul(simplify(n.a), fs);
        flattenMul(simplify(n.b), fs);
        double c = 1;
        List<Node> keep = new ArrayList<Node>();
        for (int k = 0; k < fs.size(); k++) {
            Node f = fs.get(k);
            if (f.t == T_NUM) { c *= f.v; continue; }
            if (isOne(f)) continue;
            keep.add(f);
        }
        if (c == 0) return num(0);
        if (keep.isEmpty()) return num(c);
        Node out;
        if (c == 1) {
            out = keep.get(0);
        } else if (c == -1) {
            out = neg(keep.get(0));
        } else {
            out = bin(T_MUL, num(c), keep.get(0));
        }
        for (int k = 1; k < keep.size(); k++) out = bin(T_MUL, out, keep.get(k));
        return out;
    }

    static void flattenMul(Node n, List<Node> out) {
        if (n != null && n.t == T_MUL) { flattenMul(n.a, out); flattenMul(n.b, out); }
        else if (n != null) out.add(n);
    }

    static boolean isZero(Node n) { return n != null && n.t == T_NUM && n.v == 0; }
    static boolean isOne(Node n) { return n != null && n.t == T_NUM && n.v == 1; }

    static boolean same(Node a, Node b) {
        if (a == null || b == null) return a == b;
        if (a.t != b.t) return false;
        if (a.t == T_NUM) return a.v == b.v;
        if (a.t == T_VAR) return true;
        if (a.t == T_FUNC) {
            if (!a.fn.equals(b.fn)) return false;
        }
        return same(a.a, b.a) && same(a.b, b.b);
    }

    // ── 输出成可读文本 ────────────────────────────────────
    /**
     * 输出刻意保持“能再喂回输入框”的语法：乘号用 ·，幂用 ^，
     * 这样用户看到 d/dx 结果可以直接复制去画图。
     */
    public static String toText(Node n) {
        return text(n, 0);
    }

    static final String[] SUP = {"⁰", "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹"};

    static String sup(long v) {
        if (v < 0 || v > 9) return null;
        return SUP[(int) v];
    }

    static String text(Node n, int prec) {
        if (n == null) return "0";
        switch (n.t) {
            case T_NUM: return fmtNum(n.v);
            case T_VAR: return "x";
            case T_NEG: {
                String s = "-" + text(n.a, 3);
                return prec > 2 ? "(" + s + ")" : s;
            }
            case T_ADD: {
                String s = text(n.a, 1) + " + " + text(n.b, 2);
                return prec > 1 ? "(" + s + ")" : s;
            }
            case T_SUB: {
                String s = text(n.a, 1) + " − " + text(n.b, 2);
                return prec > 1 ? "(" + s + ")" : s;
            }
            case T_MUL: {
                String a = text(n.a, 2);
                String b = text(n.b, 2);
                String s;
                if (n.a.t == T_NUM) s = a + b;                 // 2x 而不是 2·x
                else s = a + "·" + b;
                return prec > 2 ? "(" + s + ")" : s;
            }
            case T_DIV: {
                String s = text(n.a, 3) + " / " + text(n.b, 4);
                return prec > 3 ? "(" + s + ")" : s;
            }
            case T_POW: {
                String base = text(n.a, 5);
                if (n.b.t == T_NUM && Math.rint(n.b.v) == n.b.v) {
                    String sp = sup((long) n.b.v);
                    if (sp != null) return base + sp;
                }
                return base + "^" + text(n.b, 4);
            }
            default: {
                if (n.b != null) {
                    return n.fn + "(" + text(n.a, 0) + ", " + text(n.b, 0) + ")";
                }
                return n.fn + "(" + text(n.a, 0) + ")";
            }
        }
    }

    /**
     * 给"纯文本显示"用的写法（不是排版）。
     * FormulaView 走 FormulaLayout 画真公式；这里只给不需要排版的场合
     * （例如列表标题、日志、判分反馈），保持和输入框语法一致，方便复制回输入框。
     */
    public static String mathText(Node n) {
        return toText(n);
    }

    /** 数字输出：整数不带小数点，其余最多 6 位有效小数，去掉末尾 0。 */
    public static String fmtNum(double v) {
        if (Double.isNaN(v)) return "无定义";
        if (Double.isInfinite(v)) return v > 0 ? "+∞" : "−∞";
        if (Math.rint(v) == v && Math.abs(v) < 1e15) {
            long l = (long) v;
            return String.valueOf(l);
        }
        String s = String.format(java.util.Locale.US, "%.6f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
