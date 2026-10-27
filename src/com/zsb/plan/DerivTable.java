package com.zsb.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 求导公式对照表。
 *
 * 这张表不是"抄来的文字"，每一行都是可执行的：
 *   expr   用 Expr 能解析的原函数
 *   der    用 Expr 能解析的导数（对照答案）
 * MathTest 会对每一行做两件事：
 *   ① 把 expr 交给 Expr.diff() 真符号求导
 *   ② 在若干取样点上比较 diff(expr) 与 der 的数值（相对误差 < 1e-6）
 * 两条都过，这一行才算"对"。
 *
 * 所以表里任何一行抄错，verify.py 会当场失败，不会悄悄留在 App 里。
 */
public class DerivTable {

    public static class Row {
        public final String group;
        public final String name;      // 公式名，如「幂函数」
        public final String expr;      // 原函数（可解析）
        public final String der;       // 导数（可解析，用来对照）
        public final String note;      // 一句话记法/易错点

        public Row(String group, String name, String expr, String der, String note) {
            this.group = group;
            this.name = name;
            this.expr = expr;
            this.der = der;
            this.note = note == null ? "" : note;
        }
    }

    private static List<Row> rows;

    public static synchronized List<Row> all() {
        if (rows == null) rows = build();
        return rows;
    }

    /** 按组返回，保持插入顺序（组的顺序就是学习顺序）。 */
    public static Map<String, List<Row>> grouped() {
        Map<String, List<Row>> m = new LinkedHashMap<String, List<Row>>();
        for (Row r : all()) {
            List<Row> l = m.get(r.group);
            if (l == null) { l = new ArrayList<Row>(); m.put(r.group, l); }
            l.add(r);
        }
        return m;
    }

    static List<Row> build() {
        List<Row> r = new ArrayList<Row>();

        // ── 一、基本初等函数 ──────────────────────────────
        String g1 = "基本初等函数";
        r.add(new Row(g1, "常函数", "5", "0", "常数求导为 0（C 视为常系数）"));
        r.add(new Row(g1, "幂函数 x^n", "x^3", "3x^2", "n 次方降一次，系数提前"));
        r.add(new Row(g1, "根式 √x", "sqrt(x)", "1/(2sqrt(x))", "√x = x^(1/2)，用幂函数法则"));
        r.add(new Row(g1, "倒数 1/x", "1/x", "-1/x^2", "x^(-1) 求导"));
        r.add(new Row(g1, "指数 e^x", "exp(x)", "exp(x)", "唯一求导等于自己的函数"));
        r.add(new Row(g1, "一般指数 a^x", "2^x", "2^x·ln(2)", "别漏掉 ln a"));
        r.add(new Row(g1, "对数 ln x", "ln(x)", "1/x", "定义域 x>0"));
        r.add(new Row(g1, "常用对数 lg x", "lg(x)", "1/(x·ln(10))", "lg 是底 10，别当成 ln"));
        r.add(new Row(g1, "以 2 为底", "log2(x)", "1/(x·ln(2))", "底数越换越要乘 ln a"));

        // ── 二、三角与反三角 ──────────────────────────────
        String g2 = "三角函数";
        r.add(new Row(g2, "sin", "sin(x)", "cos(x)", "正弦导余弦，符号为正"));
        r.add(new Row(g2, "cos", "cos(x)", "-sin(x)", "余弦导负正弦，负号最容易漏"));
        r.add(new Row(g2, "tan", "tan(x)", "1/cos(x)^2", "= sec²x = 1+tan²x"));
        r.add(new Row(g2, "cot", "cot(x)", "-1/sin(x)^2", "= -csc²x，负号在这"));
        r.add(new Row(g2, "sec", "sec(x)", "sec(x)·tan(x)", "sec 求导带 tan"));
        r.add(new Row(g2, "csc", "csc(x)", "-csc(x)·cot(x)", "csc 求导带负号"));
        r.add(new Row(g2, "arcsin", "asin(x)", "1/sqrt(1-x^2)", "根号里是 1-x²，别写反"));
        r.add(new Row(g2, "arccos", "acos(x)", "-1/sqrt(1-x^2)", "与 arcsin 差一个负号"));
        r.add(new Row(g2, "arctan", "atan(x)", "1/(1+x^2)", "分母是 1+x²，没有根号"));

        // ── 三、双曲函数 ──────────────────────────────────
        String g3 = "双曲函数";
        r.add(new Row(g3, "sinh", "sinh(x)", "cosh(x)", "与 sin/cos 不同：这里没有负号"));
        r.add(new Row(g3, "cosh", "cosh(x)", "sinh(x)", "cosh 求导为正"));
        r.add(new Row(g3, "tanh", "tanh(x)", "1/cosh(x)^2", "= sech²x"));

        // ── 四、四则运算 ──────────────────────────────────
        String g4 = "四则运算";
        r.add(new Row(g4, "和差", "sin(x)+x^2", "cos(x)+2x", "逐项求导即可"));
        r.add(new Row(g4, "数乘", "5·sin(x)", "5·cos(x)", "常数可以提出来"));
        r.add(new Row(g4, "乘积法则", "x^2·exp(x)", "2x·exp(x)+x^2·exp(x)",
                "(uv)′ = u′v + uv′，两项都要写"));
        r.add(new Row(g4, "乘积法则·三角", "x·sin(x)", "sin(x)+x·cos(x)", "常见考法"));
        r.add(new Row(g4, "商的法则", "sin(x)/x", "(cos(x)·x-sin(x))/x^2",
                "(u/v)′ = (u′v − uv′)/v²"));
        r.add(new Row(g4, "商的法则·常数", "1/(1+x^2)", "-2x/(1+x^2)^2", "分母平方，分子减"));

        // ── 五、复合函数（链式法则）──────────────────────
        String g5 = "复合函数（链式）";
        r.add(new Row(g5, "sin 套一次", "sin(2x)", "2cos(2x)", "外层导数 × 内层导数"));
        r.add(new Row(g5, "cos 套一次", "cos(x^2)", "-2x·sin(x^2)", "别忘内层 2x"));
        r.add(new Row(g5, "指数套一次", "exp(3x)", "3exp(3x)", "内层系数提前"));
        r.add(new Row(g5, "对数套一次", "ln(1+x^2)", "2x/(1+x^2)", "内层 2x 进分子"));
        r.add(new Row(g5, "根号套一次", "sqrt(1+x^2)", "x/sqrt(1+x^2)", "内层 2x 与 1/2 约掉"));
        r.add(new Row(g5, "arctan 套一次", "atan(2x)", "2/(1+4x^2)", "内层 2x 平方得 4x²"));
        r.add(new Row(g5, "三层复合", "sin(ln(x))", "cos(ln(x))/x", "由外向内逐层剥"));
        r.add(new Row(g5, "三层复合·指数", "exp(sin(x))", "cos(x)·exp(sin(x))", "外层 e^u 不变"));

        // ── 六、幂指函数 ──────────────────────────────────
        String g6 = "幂指函数";
        r.add(new Row(g6, "x^x", "x^x", "x^x·(ln(x)+1)",
                "底和指数都含 x：取对数或写成 e^(x ln x)"));
        r.add(new Row(g6, "x^sin x", "x^sin(x)",
                "x^sin(x)·(cos(x)·ln(x)+sin(x)/x)",
                "(u^v)′ = u^v (v′ln u + v·u′/u)"));

        // ── 七、高阶与常用结论 ────────────────────────────
        String g7 = "常考结论";
        r.add(new Row(g7, "一阶导为 0", "x^2-4x", "2x-4", "令 f′=0 找驻点：x=2"));
        r.add(new Row(g7, "二阶导", "x^3", "3x^2", "再求一次得 6x，可判凹凸"));
        r.add(new Row(g7, "水平渐近线素材", "1/(1+exp(-x))", "exp(-x)/(1+exp(-x))^2",
                "S 型函数，导数恒正"));
        r.add(new Row(g7, "偶函数求导", "cos(x^2)", "-2x·sin(x^2)",
                "偶函数求导得奇函数，可自查"));

        return r;
    }

    /** 组内一行在界面上的主标题，如「幂函数 x^n」。 */
    public static String title(Row r) {
        return r.name + "：" + r.expr;
    }
}
