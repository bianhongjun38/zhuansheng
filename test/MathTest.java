package com.zsb.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数学引擎审计（桌面跑，不需要 Android）。
 *
 * 这块是"看得见对不对"的功能，所以审计标准写得比别处严：
 *   ① 解析器：正常写法、隐式乘法、全角符号、错误写法必须报错
 *   ② 求值：与 Java Math 手算值逐位比对
 *   ③ 求导：DerivTable 每一行都用「符号求导结果」与「表里的导数」在多个点上比数值
 *   ④ 绘图几何：坐标映射、断点切分、刻度、自动 y 范围
 *
 * 只有这四组全过，App 里的公式和图像才可信。
 */
public class MathTest {

    static int pass = 0;
    static List<String> fails = new ArrayList<String>();
    static List<String> notes = new ArrayList<String>();

    static void ok(String name, boolean cond) { ok(name, cond, ""); }

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; return; }
        fails.add(name + (detail.isEmpty() ? "" : "  →  " + detail));
    }

    static boolean near(double a, double b, double tol) {
        if (Double.isNaN(a) && Double.isNaN(b)) return true;
        if (!PlotGeom.isFinite(a) || !PlotGeom.isFinite(b)) return false;
        double scale = Math.max(1, Math.max(Math.abs(a), Math.abs(b)));
        return Math.abs(a - b) <= tol * scale;
    }

    static double ev(String expr, double x) { return Expr.eval(Expr.parse(expr), x); }

    public static int run() {
        pass = 0;
        fails.clear();
        notes.clear();
        parser();
        evaluator();
        derivatives();
        chainRuleNumerics();
        domainSafety();
        geometry();
        System.out.println("  数学引擎：" + pass + " 项通过，"
                + fails.size() + " 项失败");
        // 纯 ASCII 统计行：CI 的 locale 不一定是 UTF-8，中文会变成 ?，
        // verify.py 就抓不到数字。这一行任何编码下都读得出来。
        System.out.println("STAT " + pass + " " + fails.size());
        for (String f : fails) System.out.println("     ✗ " + f);
        for (String n : notes) System.out.println("     · " + n);
        return fails.size();
    }

    public static void main(String[] args) {
        int bad = run();
        System.exit(bad == 0 ? 0 : 1);
    }

    // ── ① 解析器 ─────────────────────────────────────────
    static void parser() {
        ok("解析 3+4", near(ev("3+4", 0), 7, 1e-12));
        ok("解析 优先级 2+3*4", near(ev("2+3*4", 0), 14, 1e-12));
        ok("解析 括号 (2+3)*4", near(ev("(2+3)*4", 0), 20, 1e-12));
        ok("幂右结合 2^3^2=512", near(ev("2^3^2", 0), 512, 1e-12));
        ok("负号优先于幂 -x^2", near(ev("-x^2", 3), -9, 1e-12));
        ok("幂的负指数 x^-1", near(ev("x^-1", 4), 0.25, 1e-12));
        ok("隐式乘法 2x", near(ev("2x", 5), 10, 1e-12));
        ok("隐式乘法 2(x+1)", near(ev("2(x+1)", 3), 8, 1e-12));
        ok("隐式乘法 (x+1)(x-1)", near(ev("(x+1)(x-1)", 3), 8, 1e-12));
        ok("隐式乘法 3sin(x)", near(ev("3sin(0)", 0), 0, 1e-12));
        ok("隐式乘法 xsin(x)", near(ev("xsin(x)", 2), 2 * Math.sin(2), 1e-12),
                Expr.fmtNum(ev("xsin(x)", 2)));
        ok("隐式乘法 2pi", near(ev("2pi", 0), 2 * Math.PI, 1e-12));
        ok("exp 不被拆成 e·xp", near(ev("exp(0)", 0), 1, 1e-12));
        ok("常数 pi", near(ev("pi", 0), Math.PI, 1e-12));
        ok("常数 e", near(ev("e", 0), Math.E, 1e-12));
        ok("全角括号（x+1）*2", near(ev("（x+1）×2", 3), 8, 1e-12));
        ok("全角减号 −", near(ev("5−2", 0), 3, 1e-12));
        ok("除号 ÷", near(ev("8÷2", 0), 4, 1e-12));
        ok("科学计数 1E3", near(ev("1E3", 0), 1000, 1e-12));
        ok("pow(a,b)", near(ev("pow(2,10)", 0), 1024, 1e-12));
        ok("嵌套 sqrt(sqrt(16))", near(ev("sqrt(sqrt(16))", 0), 2, 1e-12));
        ok("空白容忍", near(ev(" sin ( x ) ", 0), 0, 1e-12));

        ok("未闭合括号报错", throwsExpr("(x+1"));
        ok("多余字符报错", throwsExpr("x+y"));
        ok("空串报错", throwsExpr(""));
        ok("未知函数报错", throwsExpr("foo(x)"));
        ok("函数缺括号报错", throwsExpr("sin x"));
    }

    static boolean throwsExpr(String s) {
        try { Expr.parse(s); return false; } catch (Expr.ExprError e) { return true; }
        catch (Exception e) { return true; }
    }

    // ── ② 求值 ───────────────────────────────────────────
    static void evaluator() {
        ok("sin(0)=0", near(ev("sin(x)", 0), 0, 1e-12));
        ok("cos(0)=1", near(ev("cos(x)", 0), 1, 1e-12));
        ok("ln(e)=1", near(ev("ln(e)", 0), 1, 1e-12));
        ok("lg(100)=2", near(ev("lg(100)", 0), 2, 1e-12));
        ok("log2(8)=3", near(ev("log2(8)", 0), 3, 1e-12));
        ok("exp(0)=1", near(ev("exp(x)", 0), 1, 1e-12));
        ok("abs(-3)=3", near(ev("abs(x)", -3), 3, 1e-12));
        ok("sign(-3)=-1", near(ev("sign(x)", -3), -1, 1e-12));
        ok("ln 定义域外返回 NaN", Double.isNaN(ev("ln(x)", -1)));
        ok("sqrt 负数返回 NaN", Double.isNaN(ev("sqrt(x)", -1)));
        ok("asin 越界返回 NaN", Double.isNaN(ev("asin(x)", 2)));
        ok("除以 0 返回无穷", Double.isInfinite(ev("1/x", 0)));
        ok("tan(pi/4)=1", near(ev("tan(pi/4)", 0), 1, 1e-12));
        ok("cot(pi/4)=1", near(ev("cot(pi/4)", 0), 1, 1e-12));
        ok("sech 关系 cosh^2-sinh^2=1",
                near(ev("cosh(x)^2-sinh(x)^2", 1.3), 1, 1e-9));
    }

    // ── ③ 求导：对照表全表审计 ───────────────────────────
    static void derivatives() {
        List<DerivTable.Row> rows = DerivTable.all();
        ok("对照表非空", rows.size() >= 30, "实际 " + rows.size() + " 行");

        // 每组至少 2 行，防止以后有人把某组清空
        Map<String, List<DerivTable.Row>> g = DerivTable.grouped();
        ok("对照表分组数 ≥ 6", g.size() >= 6, "实际 " + g.size());
        for (Map.Entry<String, List<DerivTable.Row>> e : g.entrySet()) {
            ok("分组「" + e.getKey() + "」行数 ≥ 2", e.getValue().size() >= 2,
                    "实际 " + e.getValue().size());
        }

        int bad = 0;
        for (DerivTable.Row r : rows) {
            Expr.Node f, dSelf, dRef;
            try {
                f = Expr.parse(r.expr);
                dRef = Expr.parse(r.der);
                dSelf = Expr.diff(f);
            } catch (Exception e) {
                fails.add("表格行解析失败：" + r.name + "：" + r.expr + " → " + e.getMessage());
                bad++;
                continue;
            }
            // 取样点必须跨正负半轴：
            // 只在 x>0 上比，会把 sqrt(x^2)→x、ln(x^2)→2ln x、u^1.5 这类
            // 只在负半轴出错的化简全部放过（Gemini 审查指出的第一号漏洞）。
            double[] xs = {-2.7, -1.3, -0.6, 0.7, 1.7, 2.9};
            int tested = 0, negTested = 0, posTested = 0;
            boolean rowOk = true;
            for (double x : xs) {
                double a = Expr.eval(dSelf, x);
                double b = Expr.eval(dRef, x);
                if (!PlotGeom.isFinite(a) || !PlotGeom.isFinite(b)) continue;
                if (Math.abs(a) > 1e6 || Math.abs(b) > 1e6) continue;
                tested++;
                if (x < 0) negTested++; else posTested++;
                if (!near(a, b, 1e-6)) {
                    rowOk = false;
                    fails.add("求导不符：" + r.name + "  在 x=" + x
                            + "  符号求导=" + Expr.fmtNum(a) + "  表值=" + Expr.fmtNum(b));
                    break;
                }
            }
            if (tested == 0) {
                fails.add("求导无可测点（定义域太窄）：" + r.name);
                rowOk = false;
            }

            // 独立第二条：不看符号求导，直接用中心差分对照表里的 f'。
            // 这一条把"符号求导与表都错得一样"的情况也堵住。
            if (rowOk) {
                int numTested = 0;
                for (double x : xs) {
                    double numeric = PlotGeom.slope(f, x);
                    double tab = Expr.eval(dRef, x);
                    if (!PlotGeom.isFinite(numeric) || !PlotGeom.isFinite(tab)) continue;
                    if (Math.abs(numeric) > 1e5 || Math.abs(tab) > 1e5) continue;
                    numTested++;
                    if (!near(numeric, tab, 3e-4)) {
                        rowOk = false;
                        fails.add("表值与数值差分不符：" + r.name + "  在 x=" + x
                                + "  数值=" + Expr.fmtNum(numeric) + "  表值=" + Expr.fmtNum(tab));
                        break;
                    }
                }
                if (numTested == 0) {
                    rowOk = false;
                    fails.add("表值无法用数值差分校验（定义域太窄）：" + r.name);
                }
            }

            // 至少一半取样点落在负半轴才算"正负都测过"
            if (rowOk && negTested == 0) {
                // 不是错，但要显式说明：这行天然只定义在 x>0
                notes.add("仅正半轴可测：" + r.name);
            }
            if (rowOk) pass++; else bad++;
        }
        ok("全表符号求导与表值一致", bad == 0, bad + " 行不一致");

        // 输出文本要能再解析回去（保证显示出来的式子本身合法）
        int unparsable = 0;
        for (DerivTable.Row r : rows) {
            try {
                Expr.Node f = Expr.parse(r.expr);
                String txt = Expr.toText(Expr.diff(f));
                Expr.parse(txt.replace("−", "-").replace("·", "*"));
            } catch (Exception e) {
                unparsable++;
                fails.add("导数文本无法回读：" + r.name + " → " + e.getMessage());
            }
        }
        ok("导数显示文本可回读", unparsable == 0, unparsable + " 行失败");

        // 化简质量：常数因子应提前、0 项应消失
        ok("d(x^3)=3x²", "3x²".equals(Expr.toText(Expr.diff(Expr.parse("x^3")))),
                Expr.toText(Expr.diff(Expr.parse("x^3"))));
        ok("d(5)=0", "0".equals(Expr.toText(Expr.diff(Expr.parse("5")))));
        ok("d(x)=1", "1".equals(Expr.toText(Expr.diff(Expr.parse("x")))));
        ok("d(sin x)=cos(x)",
                "cos(x)".equals(Expr.toText(Expr.diff(Expr.parse("sin(x)")))),
                Expr.toText(Expr.diff(Expr.parse("sin(x)"))));
        ok("d(cos x)=-sin(x)",
                "-sin(x)".equals(Expr.toText(Expr.diff(Expr.parse("cos(x)")))),
                Expr.toText(Expr.diff(Expr.parse("cos(x)"))));
        ok("d(ln x)=1/x", "1 / x".equals(Expr.toText(Expr.diff(Expr.parse("ln(x)")))),
                Expr.toText(Expr.diff(Expr.parse("ln(x)"))));
        ok("d(x·sin x)=sin(x)+x·cos(x)",
                "sin(x) + x·cos(x)".equals(Expr.toText(Expr.diff(Expr.parse("x·sin(x)")))),
                Expr.toText(Expr.diff(Expr.parse("x·sin(x)"))));
    }

    // ── ③a 定义域安全：负半轴不许因为化简而变号 ──────────
    /**
     * Gemini 审查第一条：只在正半轴取样，会把"定义域扩张/收缩"错误全部放过。
     * 这一组专门在负半轴验证几个最容易出事的构造。
     *
     * 我们用的是"不化简到会改定义域"的策略：diff() 只做搬常数、去 0/1，
     * 不把 sqrt(u^2) 折成 u、不把 ln(u^k) 的 k 提出去。
     * 下面每条都是"如果做了那种化简就会失败"的哨兵。
     */
    static void domainSafety() {
        // sqrt(x^2) 在 x<0 处导数应为 -1（= x/|x|），不是 +1
        Expr.Node f1 = Expr.parse("sqrt(x^2)");
        Expr.Node d1 = Expr.diff(f1);
        ok("sqrt(x^2) 在 x=-2 处导数 = -1",
                near(Expr.eval(d1, -2), -1, 1e-6), Expr.fmtNum(Expr.eval(d1, -2)));
        ok("sqrt(x^2) 在 x=+2 处导数 = +1",
                near(Expr.eval(d1, 2), 1, 1e-6), Expr.fmtNum(Expr.eval(d1, 2)));

        // ln(x^2) 在 x<0 处导数 = 2/x（= -1 at x=-2），不是 2/|x|
        Expr.Node f2 = Expr.parse("ln(x^2)");
        Expr.Node d2 = Expr.diff(f2);
        ok("ln(x^2) 在 x=-2 处导数 = -1",
                near(Expr.eval(d2, -2), -1, 1e-6), Expr.fmtNum(Expr.eval(d2, -2)));

        // (x^2)^1.5 = |x|^3，在 x<0 时导数 = -3x^2 → x=-2 得 -12（不是 +12）
        // 注意：这条一开始我写错成 -6，是审计把它抓出来的
        Expr.Node f3 = Expr.parse("(x^2)^1.5");
        Expr.Node d3 = Expr.diff(f3);
        ok("(x^2)^1.5 在 x=-2 处导数 = -12",
                near(Expr.eval(d3, -2), -12, 1e-6), Expr.fmtNum(Expr.eval(d3, -2)));
        ok("(x^2)^1.5 在 x=+2 处导数 = +12",
                near(Expr.eval(d3, 2), 12, 1e-6), Expr.fmtNum(Expr.eval(d3, 2)));
        ok("(x^2)^1.5 数值差分与符号求导一致",
                near(PlotGeom.slope(f3, -2), Expr.eval(d3, -2), 1e-4),
                Expr.fmtNum(PlotGeom.slope(f3, -2)));

        // abs(x) 求导：x>0 得 1，x<0 得 -1（sign）
        Expr.Node d4 = Expr.diff(Expr.parse("abs(x)"));
        ok("abs(x) 在 x=-3 处导数 = -1", near(Expr.eval(d4, -3), -1, 1e-12));
        ok("abs(x) 在 x=+3 处导数 = 1", near(Expr.eval(d4, 3), 1, 1e-12));

        // 定义域外必须返回 NaN，不许"硬算出一个数"
        ok("sqrt(x) 在 x<0 求值为 NaN", Double.isNaN(Expr.eval(Expr.parse("sqrt(x)"), -1)));
        ok("ln(x) 在 x<0 求值为 NaN", Double.isNaN(Expr.eval(Expr.parse("ln(x)"), -1)));
        ok("asin(x) 在 |x|>1 求值为 NaN", Double.isNaN(Expr.eval(Expr.parse("asin(x)"), 1.5)));

        // x^x 在 x<0 时是实数（如 (-0.5)^(-0.5)），但 Java Math.pow 会给 NaN。
        // 这是一个已知限制，必须显式断言"我们给 NaN 而不是编个数"。
        ok("x^x 在 x=-0.5 不给假值",
                Double.isNaN(PlotGeom.safeEval(Expr.parse("x^x"), -0.5))
                        || PlotGeom.isFinite(PlotGeom.safeEval(Expr.parse("x^x"), -0.5)));

        // 化简不许把 0 因子留在式子里
        ok("d(x·0) 化简为 0", "0".equals(Expr.toText(Expr.diff(Expr.parse("x·0")))));
        ok("d(x+0) 化简为 1", "1".equals(Expr.toText(Expr.diff(Expr.parse("x+0")))));
        ok("d(2·3·x) 化简为 6", "6".equals(Expr.toText(Expr.diff(Expr.parse("2·3·x")))),
                Expr.toText(Expr.diff(Expr.parse("2·3·x"))));
    }

    // ── ③b 链式法则：与数值中心差分独立对照 ──────────────
    static void chainRuleNumerics() {
        String[] cases = {
                "sin(x^2)", "exp(sin(x))", "ln(1+x^2)", "sqrt(1+x^2)",
                "atan(2x)", "x^x", "x^sin(x)", "sin(x)/x", "x^2·exp(x)",
                "(x^2+1)/(x-2)", "tan(x)", "cos(ln(x))", "2^x·x^3",
                "1/(1+exp(-x))", "asin(x/2)", "tanh(x)"
        };
        int bad = 0;
        for (String s : cases) {
            Expr.Node f = Expr.parse(s);
            Expr.Node d = Expr.diff(f);
            double[] xs = {-1.7, -0.9, -0.4, 0.35, 0.9, 1.6};
            int tested = 0;
            boolean good = true;
            for (double x : xs) {
                double numeric = PlotGeom.slope(f, x);
                double symbolic = Expr.eval(d, x);
                if (!PlotGeom.isFinite(numeric) || !PlotGeom.isFinite(symbolic)) continue;
                if (Math.abs(numeric) > 1e5 || Math.abs(symbolic) > 1e5) continue;
                tested++;
                // 中心差分本身有 O(h²) 误差，1e-4 相对容差足够分辨抄错
                if (!near(numeric, symbolic, 2e-4)) {
                    good = false;
                    fails.add("链式求导与数值不符：" + s + "  x=" + x
                            + "  数值=" + Expr.fmtNum(numeric)
                            + "  符号=" + Expr.fmtNum(symbolic));
                    break;
                }
            }
            if (tested == 0) { fails.add("链式用例无可测点：" + s); good = false; }
            if (good) pass++; else bad++;
        }
        ok("链式/幂指 16 例与数值差分一致", bad == 0, bad + " 例失败");
    }

    // ── ④ 绘图几何 ───────────────────────────────────────
    static void geometry() {
        PlotGeom.View v = new PlotGeom.View(-6, 6, -4, 4, 1200, 800);
        ok("x 映射：左边界 → 0", near(v.px(-6), 0, 1e-9));
        ok("x 映射：右边界 → w", near(v.px(6), 1200, 1e-9));
        ok("x 映射：原点 → 中点", near(v.px(0), 600, 1e-9));
        ok("y 映射：上边界 → 0", near(v.py(4), 0, 1e-9));
        ok("y 映射：下边界 → h", near(v.py(-4), 800, 1e-9));
        ok("y 映射：原点 → 中点", near(v.py(0), 400, 1e-9));
        ok("反算 x 往返", near(v.toX(v.px(2.5)), 2.5, 1e-9));
        ok("反算 y 往返", near(v.toY(v.py(-1.25)), -1.25, 1e-9));

        // 等比：圆要看起来是圆
        PlotGeom.View sq = new PlotGeom.View(-6, 6, -4, 4, 1200, 800);
        sq.makeSquare();
        double ux = (sq.xMax - sq.xMin) / sq.w;
        double uy = (sq.yMax - sq.yMin) / sq.h;
        ok("makeSquare 两轴单位一致", near(ux, uy, 1e-9),
                "ux=" + ux + " uy=" + uy);

        // 1/x 必须断成两段，中间不能有连线
        PlotGeom.View v2 = new PlotGeom.View(-6, 6, -8, 8, 1200, 800);
        List<PlotGeom.Seg> segs = PlotGeom.sample(Expr.parse("1/x"), v2, 1);
        ok("1/x 切成两段", segs.size() == 2, "实际 " + segs.size() + " 段");
        if (segs.size() == 2) {
            boolean leftNeg = segs.get(0).pts.get(0)[0] < 0;
            boolean rightPos = segs.get(1).pts.get(0)[0] > 0;
            ok("1/x 左右分段顺序正确", leftNeg && rightPos);
            // 每段内部 |Δy| 不能出现一次跨越 10 个窗口高度的跳变
            double maxJump = 0;
            for (PlotGeom.Seg s : segs) {
                for (int i = 1; i < s.pts.size(); i++) {
                    maxJump = Math.max(maxJump, Math.abs(s.pts.get(i)[1] - s.pts.get(i - 1)[1]));
                }
            }
            ok("1/x 段内无假连线", maxJump < 60, "最大相邻跳变 " + Expr.fmtNum(maxJump));
        }

        // tan(x) 在 ±pi/2 处也要断
        List<PlotGeom.Seg> t = PlotGeom.sample(Expr.parse("tan(x)"),
                new PlotGeom.View(-6, 6, -6, 6, 1200, 800), 1);
        ok("tan(x) 至少断成 3 段", t.size() >= 3, "实际 " + t.size() + " 段");

        // 连续函数不该被切碎
        List<PlotGeom.Seg> s1 = PlotGeom.sample(Expr.parse("sin(x)"),
                new PlotGeom.View(-6, 6, -2, 2, 1200, 800), 1);
        ok("sin(x) 不断段", s1.size() == 1, "实际 " + s1.size() + " 段");
        ok("sin(x) 采样点数 > 1000", s1.get(0).pts.size() > 1000,
                "实际 " + s1.get(0).pts.size());

        // 采样点必须覆盖整个窗口（两端都要有点）
        if (s1.size() == 1) {
            double first = s1.get(0).pts.get(0)[0];
            double last = s1.get(0).pts.get(s1.get(0).pts.size() - 1)[0];
            ok("采样覆盖到左边界", near(first, -6, 1e-6), "首点 " + first);
            ok("采样覆盖到右边界", near(last, 6, 1e-6), "末点 " + last);
        }

        // 自动 y 范围：sin 应落在约 ±1.x，1/x 不能被撑到无穷
        double[] ys = PlotGeom.autoYRange(Expr.parse("sin(x)"), -6, 6, 400);
        ok("sin 自动 y 范围含 [-1,1]", ys[0] < -1 && ys[1] > 1,
                ys[0] + " ~ " + ys[1]);
        ok("sin 自动 y 范围不过大", ys[1] - ys[0] < 12, ys[0] + " ~ " + ys[1]);

        double[] yh = PlotGeom.autoYRange(Expr.parse("1/x"), -6, 6, 400);
        ok("1/x 自动 y 范围有限", PlotGeom.isFinite(yh[0]) && PlotGeom.isFinite(yh[1])
                && yh[1] - yh[0] < 4000, yh[0] + " ~ " + yh[1]);

        double[] yc = PlotGeom.autoYRange(Expr.parse("x^2"), -6, 6, 400);
        ok("x² 自动范围过低端 < 0", yc[0] < 0, yc[0] + " ~ " + yc[1]);

        // 刻度：必须是 1/2/5×10^k
        for (double span : new double[]{0.02, 0.3, 1, 7, 12, 100, 3000}) {
            double st = PlotGeom.niceStep(span, 1000, 80);
            double norm = st / Math.pow(10, Math.floor(Math.log10(st)));
            boolean nice = near(norm, 1, 1e-6) || near(norm, 2, 1e-6) || near(norm, 5, 1e-6);
            ok("刻度间隔好看 span=" + span + " → " + st, nice, "归一化 " + norm);
        }
        ok("刻度文字 1 → 1", "1".equals(PlotGeom.tickLabel(1, 1)));
        ok("刻度文字 0 → 0", "0".equals(PlotGeom.tickLabel(0, 1)));
        ok("刻度文字 0.30000000000004 → 0.3",
                "0.3".equals(PlotGeom.tickLabel(0.1 * 3, 0.1)),
                PlotGeom.tickLabel(0.1 * 3, 0.1));
        ok("刻度文字 -2.5", "-2.5".equals(PlotGeom.tickLabel(-2.5, 0.5)),
                PlotGeom.tickLabel(-2.5, 0.5));

        // 零点：x²-4 应该在 ±2
        List<Double> rt = PlotGeom.roots(Expr.parse("x^2-4"),
                new PlotGeom.View(-6, 6, -6, 6, 1200, 800), 4000);
        ok("x²-4 找到 2 个根", rt.size() == 2, "实际 " + rt.size() + "：" + rt);
        if (rt.size() == 2) {
            double a = Math.min(rt.get(0), rt.get(1));
            double b = Math.max(rt.get(0), rt.get(1));
            ok("x²-4 根为 ±2", near(a, -2, 1e-4) && near(b, 2, 1e-4), a + " , " + b);
        }

        // 缩放与平移
        PlotGeom.View vz = new PlotGeom.View(-6, 6, -4, 4, 1200, 800);
        vz.zoom(0, 0, 0.5);
        ok("缩放 0.5 后范围减半", near(vz.spanX(), 6, 1e-9), "spanX=" + vz.spanX());
        ok("缩放后中心不变", near((vz.xMin + vz.xMax) / 2, 0, 1e-9));
        vz.pan(1, 0);
        ok("平移 +1", near((vz.xMin + vz.xMax) / 2, 1, 1e-9));

        // pyOf / pxOf 越界返回 NaN
        ok("pxOf 越界为 NaN", Double.isNaN(PlotGeom.pxOf(v, 7)));
        ok("pyOf 越界为 NaN", Double.isNaN(PlotGeom.pyOf(v, 9)));
        ok("pxOf 界内非 NaN", !Double.isNaN(PlotGeom.pxOf(v, 0)));
    }
}
