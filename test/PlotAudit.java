package com.zsb.plan;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 函数图像的桌面审计 + 视觉证据。
 *
 * 为什么必须画出来看：
 *   "曲线不连错"这种要求，靠断言只能覆盖一部分。真正会让人用错的画面是
 *   tan(x) 在渐近线处连成一条竖线、1/x 在 0 附近被连起来、
 *   ln(x) 在负半轴凭空出现一段 —— 这些只有眼睛能一眼看出。
 *   这里用**同一套 PlotGeom.sample()** 在电脑上渲染，
 *   图上每一段线都是 App 里 PlotView.drawCurve 照着同一批点画的。
 *
 * 断言的则是能断的：分段数、分段边界是否落在奇点、采样点是否都有限、
 * 自动纵轴范围是否包含大部分取值。
 */
public class PlotAudit {

    static int pass = 0;
    static List<String> fails = new ArrayList<String>();

    static void ok(String name, boolean cond) { ok(name, cond, ""); }

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; return; }
        fails.add(name + (detail.isEmpty() ? "" : "  →  " + detail));
    }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "build/audit";
        System.exit(run(dir) == 0 ? 0 : 1);
    }

    public static int run(String outDir) {
        pass = 0;
        fails.clear();

        try { geometryAsserts(); } catch (Exception e) { fails.add("几何断言异常：" + e); }
        try { domainAsserts(); } catch (Exception e) { fails.add("定义域断言异常：" + e); }
        try { autoRangeAsserts(); } catch (Exception e) { fails.add("自适应纵轴异常：" + e); }
        try { renderSheet(outDir); } catch (Exception e) { fails.add("PNG 渲染失败：" + e); }

        System.out.println("  函数图像：" + pass + " 项通过，"
                + fails.size() + " 项失败");
        // 纯 ASCII 统计行：CI 的 locale 不一定是 UTF-8，中文会变成 ?，
        // verify.py 就抓不到数字。这一行任何编码下都读得出来。
        System.out.println("STAT " + pass + " " + fails.size());
        for (String f : fails) System.out.println("     ✗ " + f);
        return fails.size();
    }

    // ── ① 分段几何 ───────────────────────────────────────
    static void geometryAsserts() {
        PlotGeom.View v = new PlotGeom.View(-6, 6, -4, 4, 720, 480);

        // sin(x)：连续函数必须是一整段
        List<PlotGeom.Seg> s1 = PlotGeom.sample(Expr.parse("sin(x)"), v, 1);
        ok("sin(x) 是一整段", s1.size() == 1, "实际 " + s1.size() + " 段");
        ok("sin(x) 点数足够", s1.get(0).pts.size() > 400,
                "实际 " + s1.get(0).pts.size());

        // 1/x：中间断开，不能有一条从 -∞ 到 +∞ 的竖线
        List<PlotGeom.Seg> s2 = PlotGeom.sample(Expr.parse("1/x"), v, 1);
        ok("1/x 分成两段", s2.size() == 2, "实际 " + s2.size() + " 段");
        if (s2.size() == 2) {
            double maxLeft = -1e9, minRight = 1e9;
            for (double[] p : s2.get(0).pts) maxLeft = Math.max(maxLeft, p[0]);
            for (double[] p : s2.get(1).pts) minRight = Math.min(minRight, p[0]);
            ok("1/x 左段全在 0 左边", maxLeft < 0, "最大 x=" + maxLeft);
            ok("1/x 右段全在 0 右边", minRight > 0, "最小 x=" + minRight);
        }

        // tan(x)：在 [-6,6] 里有若干条渐近线，必须切成多段
        List<PlotGeom.Seg> s3 = PlotGeom.sample(Expr.parse("tan(x)"), v, 1);
        ok("tan(x) 切成多段", s3.size() >= 3, "实际 " + s3.size() + " 段");
        int hasVerticalJump = 0;
        for (PlotGeom.Seg s : s3) {
            for (int i = 1; i < s.pts.size(); i++) {
                double dy = Math.abs(s.pts.get(i)[1] - s.pts.get(i - 1)[1]);
                if (dy > 100) hasVerticalJump++;
            }
        }
        ok("tan(x) 段内无百倍跳变", hasVerticalJump == 0,
                hasVerticalJump + " 处跳变（画出来就是一条假竖线）");

        // ln(x)：负半轴必须没有点
        List<PlotGeom.Seg> s4 = PlotGeom.sample(Expr.parse("ln(x)"), v, 1);
        double minX = 1e9;
        for (PlotGeom.Seg s : s4)
            for (double[] p : s.pts) minX = Math.min(minX, p[0]);
        ok("ln(x) 不画到负半轴", minX >= 0, "最小 x=" + minX);

        // sqrt(x)：同样
        List<PlotGeom.Seg> s5 = PlotGeom.sample(Expr.parse("sqrt(x)"), v, 1);
        double minX2 = 1e9;
        for (PlotGeom.Seg s : s5)
            for (double[] p : s.pts) minX2 = Math.min(minX2, p[0]);
        ok("sqrt(x) 不画到负半轴", minX2 >= 0, "最小 x=" + minX2);

        // 所有点必须是有限数（NaN/Inf 混进来会画出鬼影）
        String[] all = {"1/x", "tan(x)", "ln(x)", "sqrt(x)", "x^x", "sin(x)/x",
                "exp(x)", "abs(x)", "atan(x)", "1/(1+exp(-x))"};
        int bad = 0;
        for (String e : all) {
            for (PlotGeom.Seg s : PlotGeom.sample(Expr.parse(e), v, 1)) {
                for (double[] p : s.pts) {
                    if (!PlotGeom.isFinite(p[0]) || !PlotGeom.isFinite(p[1])) bad++;
                }
            }
        }
        ok("10 条函数采样点全有限", bad == 0, bad + " 个坏点");

        // 每段至少 2 个点（1 个点画不出线，只会留个脏点）
        int thin = 0;
        for (String e : all) {
            for (PlotGeom.Seg s : PlotGeom.sample(Expr.parse(e), v, 1)) {
                if (s.pts.size() < 2) thin++;
            }
        }
        ok("没有少于 2 点的段", thin == 0, thin + " 段");
    }

    // ── ② 定义域 / 极端缩放 ──────────────────────────────
    static void domainAsserts() {
        // 极窄窗口：1/x 在 [0.001,0.002] 取值 500~1000，
        // 而纵轴是 [-1,1] —— 曲线确实整体在视窗外。
        // 正确行为是"不画"，不是"硬拉一条线进来"，更不是崩。
        PlotGeom.View tiny = new PlotGeom.View(0.001, 0.002, -1, 1, 720, 480);
        List<PlotGeom.Seg> t1 = null;
        boolean tinyOk = true;
        try { t1 = PlotGeom.sample(Expr.parse("1/x"), tiny, 1); }
        catch (Exception e) { tinyOk = false; }
        ok("极窄窗口不崩", tinyOk && t1 != null);
        ok("视窗外的曲线不乱画", t1 != null && t1.isEmpty(),
                t1 == null ? "null" : t1.size() + " 段（应 0 段）");

        // 把纵轴调到能装下，就该画出来了
        PlotGeom.View tiny2 = new PlotGeom.View(0.001, 0.002, 400, 1100, 720, 480);
        List<PlotGeom.Seg> t1b = PlotGeom.sample(Expr.parse("1/x"), tiny2, 1);
        ok("纵轴装得下时能画", !t1b.isEmpty(), t1b.size() + " 段");
        if (!t1b.isEmpty()) {
            int bad2 = 0;
            for (PlotGeom.Seg s : t1b)
                for (double[] pt : s.pts)
                    if (pt[1] < 400 || pt[1] > 1100) bad2++;
            ok("采样点都在纵轴范围内", bad2 == 0, bad2 + " 个越界");
        }

        // 放大到极大范围：sin(x) 在 ±1e6 里照样要画得出来
        PlotGeom.View huge = new PlotGeom.View(-1e6, 1e6, -4, 4, 720, 480);
        List<PlotGeom.Seg> t2 = PlotGeom.sample(Expr.parse("sin(x)"), huge, 1);
        ok("极宽窗口 sin(x) 有曲线", !t2.isEmpty(), t2.size() + " 段");

        // 纵轴范围退化（yMin == yMax）不能除零
        PlotGeom.View flat = new PlotGeom.View(-5, 5, 1, 1, 100, 100);
        boolean nocrash = true;
        try { PlotGeom.sample(Expr.parse("x"), flat, 1); }
        catch (Exception e) { nocrash = false; }
        ok("纵轴退化不崩", nocrash);

        // 零点：x^2-4 在 [-6,6] 上正好两个根 ±2
        List<Double> roots = PlotGeom.roots(Expr.parse("x^2-4"),
                new PlotGeom.View(-6, 6, -5, 5, 720, 480), 600);
        ok("x²−4 有两个零点", roots.size() == 2, "实际 " + roots.size());
        if (roots.size() == 2) {
            double lo = Math.min(roots.get(0), roots.get(1));
            double hi = Math.max(roots.get(0), roots.get(1));
            ok("零点 ≈ −2", Math.abs(lo + 2) < 1e-3, "实际 " + lo);
            ok("零点 ≈ +2", Math.abs(hi - 2) < 1e-3, "实际 " + hi);
        }

        // 无零点：exp(x) 不该报根
        List<Double> noRoot = PlotGeom.roots(Expr.parse("exp(x)"),
                new PlotGeom.View(-6, 6, -1, 10, 720, 480), 600);
        ok("exp(x) 无零点", noRoot.isEmpty(), "报了 " + noRoot.size() + " 个");

        // 切线斜率：x^2 在 x=3 处应是 6
        double k = PlotGeom.slope(Expr.parse("x^2"), 3);
        ok("x² 在 x=3 处斜率 ≈ 6", Math.abs(k - 6) < 1e-4, "实际 " + k);

        // 缩放：窗口按比例收缩
        PlotGeom.View z = new PlotGeom.View(-10, 10, -10, 10, 400, 400);
        double w0 = z.spanX();
        z.zoom(0, 0, 0.5);
        ok("缩放 0.5 后跨度减半", Math.abs(z.spanX() - w0 * 0.5) < 1e-6,
                "跨度 " + z.spanX() + " 原 " + w0);
        ok("缩放保持正方形比例", Math.abs(z.spanX() - z.spanY()) < 1e-6,
                z.spanX() + " vs " + z.spanY());
    }

    // ── ③ 自适应纵轴 ─────────────────────────────────────
    static void autoRangeAsserts() {
        // 1/x 在 [-6,6] 上取值极大，自适应必须截断到合理范围
        double[] r = PlotGeom.autoYRange(Expr.parse("1/x"), -6, 6, 400);
        ok("1/x 自适应范围有效", r[1] > r[0], r[0] + " ~ " + r[1]);
        ok("1/x 自适应不会到 1e6", Math.abs(r[1]) < 1e5, "上界 " + r[1]);
        ok("1/x 自适应对称", Math.abs(r[0] + r[1]) < Math.abs(r[1]) * 0.6,
                r[0] + " / " + r[1]);

        // sin(x) 自适应应接近 [-1,1] 加余量
        double[] r2 = PlotGeom.autoYRange(Expr.parse("sin(x)"), -6, 6, 400);
        ok("sin 自适应包含 ±1", r2[0] < -1 && r2[1] > 1, r2[0] + " ~ " + r2[1]);
        ok("sin 自适应不过度扩张", r2[1] < 4, "上界 " + r2[1]);

        // 常数函数不能算出零高度窗口
        double[] r3 = PlotGeom.autoYRange(Expr.parse("3"), -6, 6, 400);
        ok("常数函数范围非退化", r3[1] - r3[0] > 1, r3[0] + " ~ " + r3[1]);

        // 全 NaN（定义域完全不含 x）不能崩
        double[] r4 = PlotGeom.autoYRange(Expr.parse("sqrt(-1-x^2)"), -6, 6, 400);
        ok("全无定义不崩", r4[0] < r4[1], r4[0] + " ~ " + r4[1]);
    }

    // ══ ④ 渲染 PNG ═══════════════════════════════════════
    static void renderSheet(String outDir) throws Exception {
        File dir = new File(outDir);
        if (!dir.exists()) dir.mkdirs();

        String[] exprs = {"sin(x)", "x^2", "1/x", "tan(x)", "ln(x)", "sqrt(x)",
                "abs(x)", "exp(x)", "sin(x)/x", "x^3-3x", "atan(x)", "x^x"};
        String[] notes = {"连续振荡", "抛物线", "间断 + 渐近线", "多条渐近线",
                "只在 x>0", "只在 x≥0", "尖点", "指数增长",
                "可去间断（补 x=0）", "三次函数三根", "S 型", "只在 x>0（实数）"};

        int cellW = 380, cellH = 300, cols = 3;
        int rows = (exprs.length + cols - 1) / cols;
        int w = cols * cellW + 40;
        int h = rows * cellH + 100;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x10, 0x13, 0x19));
        g.fillRect(0, 0, w, h);

        g.setColor(new Color(0xC3, 0xC9, 0xD6));
        g.setFont(new Font("SansSerif", Font.PLAIN, 20));
        g.drawString("函数图像审计 —— 同一套 PlotGeom.sample() 在 App 里 drawCurve 使用", 20, 38);
        g.setColor(new Color(0x6B, 0x72, 0x82));
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        g.drawString("重点看：渐近线处没有假竖线、定义域外没有凭空长出的曲线", 20, 62);

        for (int i = 0; i < exprs.length; i++) {
            int cx = 20 + (i % cols) * cellW;
            int cy = 80 + (i / cols) * cellH;
            drawCell(g, exprs[i], notes[i], cx, cy, cellW - 10, cellH - 12);
        }
        g.dispose();

        File out = new File(dir, "plot-sheet.png");
        ImageIO.write(img, "png", out);
        System.out.println("  函数图已生成：" + out.getAbsolutePath()
                + "  (" + w + "x" + h + ", " + exprs.length + " 张)");
        ok("函数图已写出", out.exists() && out.length() > 10000,
                "大小 " + out.length());
    }

    static void drawCell(Graphics2D g, String expr, String note,
                         int x0, int y0, int w, int h) {
        g.setColor(new Color(0x1C, 0x1F, 0x27));
        g.fillRoundRect(x0, y0, w, h, 10, 10);
        g.setColor(new Color(0x2C, 0x31, 0x3C));
        g.drawRoundRect(x0, y0, w, h, 10, 10);

        int plotX = x0 + 8, plotY = y0 + 26, plotW = w - 16, plotH = h - 52;
        Expr.Node f;
        try { f = Expr.parse(expr); }
        catch (Exception e) { return; }

        PlotGeom.View v = new PlotGeom.View(-6, 6, -4, 4, plotW, plotH);
        double[] yr = PlotGeom.autoYRange(f, -6, 6, plotW);
        v.yMin = yr[0];
        v.yMax = yr[1];

        // 网格
        double stepX = PlotGeom.niceStep(v.spanX(), plotW, 60);
        g.setColor(new Color(0x20, 0x24, 0x2E));
        for (double gx = Math.ceil(v.xMin / stepX) * stepX; gx <= v.xMax; gx += stepX) {
            int px = (int) (plotX + v.px(gx));
            g.drawLine(px, plotY, px, plotY + plotH);
        }
        double stepY = PlotGeom.niceStep(v.spanY(), plotH, 50);
        for (double gy = Math.ceil(v.yMin / stepY) * stepY; gy <= v.yMax; gy += stepY) {
            int py = (int) (plotY + v.py(gy));
            g.drawLine(plotX, py, plotX + plotW, py);
        }

        // 坐标轴
        g.setColor(new Color(0x4A, 0x53, 0x64));
        if (v.yMin <= 0 && v.yMax >= 0) {
            int py = (int) (plotY + v.py(0));
            g.drawLine(plotX, py, plotX + plotW, py);
        }
        if (v.xMin <= 0 && v.xMax >= 0) {
            int px = (int) (plotX + v.px(0));
            g.drawLine(px, plotY, px, plotY + plotH);
        }

        // 曲线：严格按 PlotGeom 的分段，段与段之间绝不连线。
        // 必须 clip —— 不裁的话渐近线附近的线会画到相邻面板里，
        // 那不是 App 的行为，却会让人误判绘图有问题（第一版就吃过这个亏）。
        java.awt.Shape oldClip = g.getClip();
        g.clipRect(plotX, plotY, plotW, plotH);
        g.setColor(new Color(0x3E, 0xCF, 0xAF));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        List<PlotGeom.Seg> segs = PlotGeom.sample(f, v, 1);
        for (PlotGeom.Seg s : segs) {
            java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
            boolean started = false;
            for (double[] pt : s.pts) {
                double sx = plotX + v.px(pt[0]);
                double sy = plotY + v.py(pt[1]);
                if (sx < plotX - plotW || sx > plotX + 2 * plotW) { started = false; continue; }
                if (sy < plotY - plotH || sy > plotY + 2 * plotH) { started = false; continue; }
                if (!started) { p.moveTo(sx, sy); started = true; }
                else p.lineTo(sx, sy);
            }
            g.draw(p);
        }
        g.setClip(oldClip);
        g.setStroke(new BasicStroke(1f));

        // 标题
        g.setColor(new Color(0xE8, 0xEA, 0xF0));
        g.setFont(new Font("Monospaced", Font.BOLD, 14));
        g.drawString(expr, x0 + 10, y0 + 18);
        g.setColor(new Color(0x6B, 0x72, 0x82));
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.drawString(note + "　" + segs.size() + " 段", x0 + 10, y0 + h - 8);
    }
}
