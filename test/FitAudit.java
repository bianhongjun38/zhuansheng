package com.zsb.plan;

/**
 * 模板格子公式缩字的审计。
 *
 * 起因：实机截图上「常用图形」那排格子一大一小、分式挤在一起。
 * 我一开始判断是"固定高度导致分式高度溢出"，准备加宽高双约束。
 * 先写审计验证，结果：16 个模板在 62dp 格高下，旧的"只按宽度缩"
 * 已经全部放得下（0 条高度溢出）—— 判断是错的，多余代码已撤回。
 *
 * 这个审计留下来守两件事：
 *   ① 将来谁改格子高度、改字号，只要公式塞不进去就当场红；
 *   ② 记住"一大一小"的真实成因是 WRAP_CONTENT 高度不齐（已在
 *      MathView 里改成固定高度），不是公式溢出。
 */
public class FitAudit {

    static int pass = 0, fail = 0;

    static void ok(String w, boolean c, String d) {
        if (c) pass++;
        else { fail++; System.out.println("  ✗ " + w + "  →  " + d); }
    }

    static class FM implements FormulaLayout.Metrics {
        public float width(String t, float s) { return t.length() * 7.4f * s; }
        public float ascent(float s) { return 9.2f * s; }
        public float descent(float s) { return 2.6f * s; }
        public float unit() { return 1f; }
    }

    public static void main(String[] a) {
        System.out.println("▶ 模板格子公式缩字");
        FormulaLayout.Metrics m = new FM();

        // 3 列（原 4 列时 "(x+1)(x-1)" 要缩到 64%，"x³ − 3x" 被截断）。
        // 360dp 屏、3 列、左右 padding 3dp：
        // (360 - 24 页边 - 3*3 间距) / 3 = 109dp 宽，减 padding 6 → 103dp
        // 格高 62dp，减上下 padding 8+8 → 46dp
        final float CELL_W = 103f, CELL_H = 46f;

        int tooSmall = 0;
        for (String e : ExprInput.TEMPLATES) {
            Expr.Node n = Expr.parse(e);
            FormulaLayout.Fit fit = FormulaLayout.fit(n, m, CELL_W);
            float h = fit.box.asc + fit.box.desc;

            ok("「" + e + "」宽度放得下", fit.box.w <= CELL_W + 0.5f,
               fit.box.w + " > " + CELL_W);
            ok("「" + e + "」高度也放得下", h <= CELL_H + 0.5f,
               h + " > " + CELL_H + "（那就要做高度缩放）");
            if (fit.factor < 0.7f) {
                tooSmall++;
                System.out.println("  · 缩得过小：「" + e + "」factor=" + fit.factor
                        + " 宽 " + fit.box.w + "/" + CELL_W);
            }
        }

        ok("没有公式缩到 70% 以下（再小就看不清）", tooSmall == 0,
           tooSmall + " 条过小");

        // 简单的式子不该被无谓缩小
        FormulaLayout.Fit sf = FormulaLayout.fit(Expr.parse("x"), m, CELL_W);
        ok("简单式子不无谓缩字", sf.factor == 1f, "" + sf.factor);

        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }
}
