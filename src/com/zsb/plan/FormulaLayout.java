package com.zsb.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * 公式排版几何 —— 纯 Java，不碰 Android。
 *
 * 为什么抽出来：排版错了（分式线画歪、指数压到基线上、根号顶线没盖住内容）
 * 在手机上只能靠肉眼看，说不出错在哪。抽成纯计算后，
 * 电脑上可以逐条断言：分子是否在线上方、上标是否真的高于基线、
 * 根号顶线是否覆盖了整个被开方式、盒子宽度是否等于各部件之和。
 *
 * 与 FormulaView 的分工：
 *   本类只算 w / asc / desc / 每个子盒子的偏移，不画。
 *   FormulaView 拿到算好的树，照着坐标 drawText / drawLine。
 *
 * 盒子尺寸靠外部注入的 Metrics（真机是 Paint，电脑上是按字宽估算），
 * 所以两边排版逻辑完全一致。
 */
public class FormulaLayout {

    public static final int K_TEXT = 0;
    public static final int K_ROW = 1;      // 左右拼接，中间可能有运算符
    public static final int K_DIV = 2;
    public static final int K_POW = 3;
    public static final int K_SQRT = 4;
    public static final int K_FUNC = 5;     // sin / ln / log / min / max
    public static final int K_ABS = 6;
    public static final int K_PAREN = 7;    // 自动补的括号（非用户输入）

    /** 字体度量。真机用 Paint 实现，电脑上用近似实现。 */
    public interface Metrics {
        /** 文本宽度（已含字号）。 */
        float width(String text, float scale);
        /** 基线以上高度。 */
        float ascent(float scale);
        /** 基线以下高度。 */
        float descent(float scale);
        /** 1dp 对应的像素数（间距用）。 */
        float unit();
    }

    public static class Box {
        public int kind;
        public String text = "";
        public Box a, b;
        public String op = "";
        public float w, asc, desc;
        public float s = 1f;        // 字号缩放
        public float parScale = 1f; // K_PAREN：括号相对正文字号的放大比
        public float x, y;          // 基线起点，由 place() 填

        public boolean isText() { return kind == K_TEXT; }
    }

    /** 上标缩放比。0.62 是印刷上常见的上下标比例。 */
    public static final float SCRIPT = 0.62f;

    // ── 按可用宽度自动缩字 ────────────────────────────────
    /**
     * 把一个 Metrics 整体按 f 倍缩小字号。
     *
     * 为什么要它：字体度量是实现相关的。同一串 "(x+1)(x-1)"
     * 在 Windows 的 sans-serif 下量出 74px，在 Ubuntu 的
     * 字体下量出 83px —— 放同一个固定列宽的格子里，后者就溢出。
     * 靠"格子按本地字宽算够大"是赌字体；正确做法是排版时
     * 按实际可用宽度缩，缩到放得下为止。
     */
    public static class Scaled implements Metrics {
        public final Metrics base;
        public final float f;
        public Scaled(Metrics base, float f) {
            this.base = base;
            this.f = f;
        }
        public float width(String text, float scale) {
            return base.width(text, scale * f);
        }
        public float ascent(float scale) { return base.ascent(scale * f); }
        public float descent(float scale) { return base.descent(scale * f); }
        public float unit() { return base.unit(); }
    }

    /** 缩字结果：排版树 + 实际用的缩放比。 */
    public static class Fit {
        public Box box;
        public float factor = 1f;
        public boolean overflow;   // 缩到下限仍放不下
    }

    /** 缩字候选。从原文号往下试，第一个放得下的就用。 */
    private static final float[] FIT_STEPS =
            {1f, 0.94f, 0.88f, 0.82f, 0.76f, 0.70f, 0.64f, 0.58f, 0.52f};

    /**
     * 在 maxW 宽度内排下 n。
     *
     * 不缩字号时调用方应直接用 layout()；这里只在必要时才缩。
     * 缩到 52% 还放不下就认了 —— 再小字就看不清，宁可溢出
     * 也不能把公式糊成一团。此时 overflow=true 让调用方能断言到。
     */
    public static Fit fit(Expr.Node n, Metrics m, float maxW) {
        Fit r = new Fit();
        if (n == null) { r.box = text("0", 1f, m); return r; }
        if (maxW <= 0) { r.box = layout(n, m); return r; }

        Box best = null;
        float bestF = FIT_STEPS[FIT_STEPS.length - 1];
        for (float f : FIT_STEPS) {
            Metrics mm = (f == 1f) ? m : new Scaled(m, f);
            Box b = layout(n, mm);
            if (best == null) best = b;
            if (b.w <= maxW) {
                r.box = b;
                r.factor = f;
                r.overflow = false;
                return r;
            }
            best = b;
            bestF = f;
        }
        r.box = best;
        r.factor = bestF;
        r.overflow = true;
        return r;
    }

    // ── 顶层 ──────────────────────────────────────────────
    public static Box layout(Expr.Node n, Metrics m) {
        return measure(n, 1f, m);
    }

    // ── 布局 ──────────────────────────────────────────────
    static Box measure(Expr.Node n, float s, Metrics m) {
        if (n == null) return text("0", s, m);
        switch (n.t) {
            case Expr.T_NUM: return text(Expr.fmtNum(n.v), s, m);
            case Expr.T_VAR: return text("x", s, m);
            case Expr.T_NEG: return row(text("−", s, m), "", measure(n.a, s, m), m);
            case Expr.T_ADD: return row(measure(n.a, s, m), " + ", measure(n.b, s, m), m);
            case Expr.T_SUB: return row(measure(n.a, s, m), " − ", measure(n.b, s, m), m);
            case Expr.T_MUL: return mul(n, s, m);
            case Expr.T_DIV: return div(n, s, m);
            case Expr.T_POW: return powGrouped(n.a, measure(n.b, s * SCRIPT, m), s, m);
            default: return func(n, s, m);
        }
    }

    public static Box text(String t, float s, Metrics m) {
        Box b = new Box();
        b.kind = K_TEXT;
        b.text = t == null ? "" : t;
        b.s = s;
        b.w = m.width(b.text, s);
        b.asc = m.ascent(s);
        b.desc = m.descent(s);
        return b;
    }

    static Box row(Box a, String op, Box b, Metrics m) {
        Box r = new Box();
        r.kind = K_ROW;
        r.a = a; r.b = b; r.op = op == null ? "" : op;
        r.s = Math.max(a.s, b.s);
        r.w = a.w + m.width(r.op, r.s) + b.w;
        r.asc = Math.max(a.asc, b.asc);
        r.desc = Math.max(a.desc, b.desc);
        return r;
    }

    /**
     * 乘法。省略乘号只有一条合法情形：**数字系数在左**，即 2x、3sin x、0.5x²。
     * 其余一律写圆点。
     *
     * 为什么收紧到这么窄：视觉审计（build/audit/formula-sheet.png）第一版
     * 把所有"原子"都并列，结果 x·sin(x) 画成 "xsinx"、cos(x)·x 画成 "cos xx"，
     * 读起来像一个名字而不是两个因子相乘 —— 比写乘号更难懂。
     */
    static Box mul(Expr.Node n, float s, Metrics m) {
        // 加减式做因子必须加括号：
        // x^x·(ln x+1) 若画成 x^x·ln x + 1，数学含义就变了（视觉审计抓到的错）。
        Box a = wrapIfNeeded(n.a, s, m), b = wrapIfNeeded(n.b, s, m);
        // −2x 也要并列：负常数和正常数都算"系数在左"
        boolean coefFirst = isNum(n.a) && atomLike(n.b);
        return row(a, coefFirst ? "" : "·", b, m);
    }

    /** 是不是数字（含负常数 −2）。 */
    static boolean isNum(Expr.Node n) {
        if (n == null) return false;
        if (n.t == Expr.T_NUM) return true;
        return n.t == Expr.T_NEG && n.a != null && n.a.t == Expr.T_NUM;
    }

    /**
     * 乘除幂运算里，非原子操作数要套括号。
     * 例外：负常数（−2）本身就是系数，套括号会变成 ((−2)·x)，
     * 比直接写 −2x 更难认 —— 视觉审计第二轮抓到的。
     */
    static Box wrapIfNeeded(Expr.Node n, float s, Metrics m) {
        Box b = measure(n, s, m);
        if (atomLike(n)) return b;
        if (n.t == Expr.T_FUNC && "sqrt".equals(n.fn)) return b;
        if (n.t == Expr.T_NEG && n.a != null && n.a.t == Expr.T_NUM) return b;
        return paren(b, s, m);
    }

    /** 括号盒子：宽度是左右括号加内容；高度至少盖住内容。 */
    static Box paren(Box inner, float s, Metrics m) {
        Box r = new Box();
        r.kind = K_PAREN;
        r.a = inner;
        r.s = s;
        // 内容比一行字高时（例如里面是分式），括号要跟着放大
        float lineH = m.ascent(s) + m.descent(s);
        float innerH = inner.asc + inner.desc;
        float pw = m.width("(", s);
        // 内容比一行字高时（里面是分式），括号要跟着放大，否则包不住
        r.parScale = innerH > lineH ? Math.min(2.6f, innerH / lineH) : 1f;
        float bw = pw * r.parScale;
        r.w = bw * 2 + m.unit() * 1.5f * s + inner.w;
        r.asc = Math.max(inner.asc, lineH * r.parScale / 2f);
        r.desc = Math.max(inner.desc, lineH * r.parScale / 2f);
        return r;
    }

    /** 幂的底数不是原子时必须加括号：(x+1)^2 不能画成 x+1²。 */
    static Box powGrouped(Expr.Node baseNode, Box ex, float s, Metrics m) {
        Box base = atomLike(baseNode) ? measure(baseNode, s, m) : wrapIfNeeded(baseNode, s, m);
        return pow(base, ex, s, m);
    }

    static Box div(Expr.Node n, float s, Metrics m) {
        Box up = measure(n.a, s, m), dn = measure(n.b, s, m);
        Box r = new Box();
        r.kind = K_DIV;
        r.a = up; r.b = dn;
        r.s = s;
        float gap = m.unit() * 3f * s;      // 内容与分数线的间距
        float inner = Math.max(up.w, dn.w);
        r.w = inner + m.unit() * 8f * s;    // 分数线左右各留 4dp
        r.asc = up.asc + up.desc + gap;
        r.desc = dn.asc + dn.desc + gap;
        return r;
    }

    static Box pow(Box base, Box ex, float s, Metrics m) {
        Box r = new Box();
        r.kind = K_POW;
        r.a = base; r.b = ex;
        r.s = s;
        float raise = base.asc * 0.42f;     // 指数抬到基线以上
        r.w = base.w + ex.w + m.unit() * 1f * s;
        r.asc = Math.max(base.asc, raise + ex.asc);
        r.desc = base.desc;
        return r;
    }

    static Box func(Expr.Node n, float s, Metrics m) {
        String f = n.fn;
        if ("sqrt".equals(f)) {
            Box a = measure(n.a, s, m);
            Box r = new Box();
            r.kind = K_SQRT;
            r.a = a;
            r.s = s;
            float hook = m.unit() * 10f * s;
            r.w = hook + a.w + m.unit() * 4f * s;
            r.asc = Math.max(a.asc + m.unit() * 3f * s, m.unit() * 12f * s);
            r.desc = a.desc;
            return r;
        }
        if ("abs".equals(f)) {
            Box a = measure(n.a, s, m);
            Box r = new Box();
            r.kind = K_ABS;
            r.a = a;
            r.s = s;
            float bw = m.width("|", s);
            r.w = bw * 2 + a.w;
            r.asc = Math.max(a.asc, m.unit() * 9f * s);
            r.desc = a.desc;
            return r;
        }
        if ("exp".equals(f)) {
            // 写成 e^(...) 比 exp(...) 更像课本
            Box base = text("e", s, m);
            Box ex = measure(n.a, s * SCRIPT, m);
            return pow(base, ex, s, m);
        }
        if (n.b != null) {
            // min/max/pow 这类双参数：f(a, b)，一定带括号
            Box a = measure(n.a, s, m);
            Box b = measure(n.b, s, m);
            Box r = new Box();
            r.kind = K_FUNC;
            r.text = displayName(f);
            r.op = "1";
            r.s = s;
            float nameW = m.width(r.text, s);
            float comma = m.width(", ", s);
            float par = m.width("(", s) + m.width(")", s);
            r.w = nameW + par + a.w + comma + b.w;
            r.asc = Math.max(Math.max(a.asc, b.asc), m.unit() * 9f * s);
            r.desc = Math.max(a.desc, b.desc);
            r.a = a;
            Box pair = new Box();
            pair.kind = K_ROW;
            pair.a = a; pair.b = b; pair.op = ", ";
            pair.s = s;
            pair.w = a.w + comma + b.w;
            pair.asc = Math.max(a.asc, b.asc);
            pair.desc = Math.max(a.desc, b.desc);
            r.a = pair;
            r.w = nameW + par + pair.w;
            return r;
        }

        Box arg = measure(n.a, s, m);
        Box r = new Box();
        r.kind = K_FUNC;
        r.text = displayName(f);
        r.a = arg;
        r.s = s;
        // 只有"纯数字/变量"才省略括号。sin x² 有歧义（是 sin(x²) 还是 (sin x)²），
        // 所以幂也加括号：sin(x²)。视觉审计第二轮改的。
        boolean needParen = !(n.a.t == Expr.T_NUM || n.a.t == Expr.T_VAR);
        r.op = needParen ? "1" : "0";
        float nameW = m.width(r.text, s);
        float par = needParen ? m.width("(", s) + m.width(")", s) : 0;
        r.w = nameW + m.unit() * 3f * s + par + arg.w;
        r.asc = Math.max(arg.asc, m.unit() * 9f * s);
        r.desc = Math.max(arg.desc, m.unit() * 2f * s);
        return r;
    }

    /** 能直接并列写吗：数字、变量、函数调用、幂。分式/加减/负号不行。 */
    public static boolean atomLike(Expr.Node n) {
        if (n == null) return true;
        switch (n.t) {
            case Expr.T_NUM:
            case Expr.T_VAR:
                return true;
            case Expr.T_FUNC:
                return !"pow".equals(n.fn);
            case Expr.T_POW:
                return atomLike(n.a);
            default:
                return false;
        }
    }

    public static String displayName(String f) {
        if ("asin".equals(f)) return "arcsin";
        if ("acos".equals(f)) return "arccos";
        if ("atan".equals(f)) return "arctan";
        if ("log10".equals(f)) return "lg";
        return f;
    }

    // ── 定位：把所有子盒子填上绝对坐标（基线起点）────────
    /**
     * 把整棵树铺到具体坐标上。根盒子放在 (0, 0) 基线处，
     * 调用方按整体取反偏移即可居中。
     */
    public static void place(Box b, float x, float y, Metrics m) {
        if (b == null) return;
        b.x = x;
        b.y = y;
        switch (b.kind) {
            case K_ROW: {
                place(b.a, x, y, m);
                float cx = x + b.a.w + m.width(b.op, b.s);
                place(b.b, cx, y, m);
                break;
            }
            case K_DIV: {
                float midY = y - m.unit() * 3f * b.s;
                float gap = m.unit() * 3f * b.s;
                float upBase = midY - gap / 2f - b.a.desc;
                float dnBase = midY + gap / 2f + b.b.asc;
                place(b.a, x + (b.w - b.a.w) / 2f, upBase, m);
                place(b.b, x + (b.w - b.b.w) / 2f, dnBase, m);
                break;
            }
            case K_POW: {
                place(b.a, x, y, m);
                float raise = b.a.asc * 0.42f;
                place(b.b, x + b.a.w + m.unit() * 1f * b.s, y - raise, m);
                break;
            }
            case K_SQRT: {
                float hook = m.unit() * 10f * b.s;
                place(b.a, x + hook + m.unit() * 1.5f * b.s, y, m);
                break;
            }
            case K_ABS: {
                float bw = m.width("|", b.s);
                place(b.a, x + bw, y, m);
                break;
            }
            case K_PAREN: {
                float bw = m.width("(", b.s) * b.parScale;
                place(b.a, x + bw + m.unit() * 1.5f * b.s, y, m);
                break;
            }
            case K_FUNC: {
                float nameW = m.width(b.text, b.s);
                float cx = x + nameW + m.unit() * 3f * b.s;
                if ("1".equals(b.op)) {
                    cx += m.width("(", b.s);
                }
                place(b.a, cx, y, m);
                break;
            }
            default:
                break;
        }
    }

    /** 收集所有盒子（审计用：遍历检查偏移是否合理）。 */
    public static List<Box> flatten(Box b) {
        List<Box> out = new ArrayList<Box>();
        collect(b, out);
        return out;
    }

    static void collect(Box b, List<Box> out) {
        if (b == null) return;
        out.add(b);
        collect(b.a, out);
        collect(b.b, out);
    }

    /** 纯文本还原（审计用：确认文字顺序没乱）。 */
    public static String plain(Box b) {
        if (b == null) return "";
        switch (b.kind) {
            case K_TEXT: return b.text;
            case K_ROW: return plain(b.a) + b.op + plain(b.b);
            case K_DIV: return plain(b.a) + "/" + plain(b.b);
            case K_POW: return plain(b.a) + "^(" + plain(b.b) + ")";
            case K_SQRT: return "sqrt(" + plain(b.a) + ")";
            case K_ABS: return "|" + plain(b.a) + "|";
            case K_PAREN: return "(" + plain(b.a) + ")";
            default:
                if ("1".equals(b.op)) return b.text + "(" + plain(b.a) + ")";
                return b.text + " " + plain(b.a);
        }
    }
}
