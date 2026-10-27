package com.zsb.plan;

/**
 * 检查公式表到底画成什么样：把 FormulaLayout 排出来的盒子叶节点
 * 的文字按顺序吐出来。机器名（sqrt/exp/log2）如果还在，说明没走排版。
 */
public class Probe {
    static class FM implements FormulaLayout.Metrics {
        public float width(String t, float s) { return t.length() * 7.2f * s; }
        public float ascent(float s) { return 9f * s; }
        public float descent(float s) { return 2.4f * s; }
        public float unit() { return 1f; }
    }
    public static void main(String[] a) {
        String[] q = {"sqrt(x)", "exp(x)", "1/x", "1/(2sqrt(x))", "2^x*ln(2)",
                      "log2(x)", "lg(x)", "sqrt(1+x^2)", "x^x", "asin(x)"};
        FormulaLayout.Metrics m = new FM();
        for (String s : q) {
            Expr.Node n = Expr.parse(s);
            FormulaLayout.Box b = FormulaLayout.layout(n, m);
            StringBuilder sb = new StringBuilder();
            leaves(b, sb);
            String drawn = sb.toString();
            System.out.printf("%-16s drawn=[%s]  raw=%s%n", s, drawn, Expr.mathText(n));
        }
    }
    static void leaves(FormulaLayout.Box b, StringBuilder sb) {
        if (b == null) return;
        if (b.kind == FormulaLayout.K_TEXT) { sb.append(b.text); return; }
        if (b.kind == FormulaLayout.K_SQRT) { sb.append("√("); leaves(b.a, sb); sb.append(")"); return; }
        if (b.kind == FormulaLayout.K_DIV) { sb.append("{"); leaves(b.a, sb); sb.append("/"); leaves(b.b, sb); sb.append("}"); return; }
        if (b.kind == FormulaLayout.K_POW) { leaves(b.a, sb); sb.append("^"); leaves(b.b, sb); return; }
        if (b.kind == FormulaLayout.K_FUNC) { sb.append(b.text); sb.append(b.op.equals("1") ? "(" : ""); leaves(b.a, sb); sb.append(b.op.equals("1") ? ")" : ""); return; }
        if (b.kind == FormulaLayout.K_ABS) { sb.append("|"); leaves(b.a, sb); sb.append("|"); return; }
        if (!b.op.isEmpty()) sb.append(b.op);
        leaves(b.a, sb);
        leaves(b.b, sb);
    }
}
