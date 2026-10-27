package com.zsb.plan;

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
 * 公式排版的桌面审计 + 视觉证据生成。
 *
 * 两件事：
 *   ① 断言（能被 verify.py 判成败）：
 *        分式的分子确实在分数线上方、指数确实高于基线、
 *        根号顶线确实盖住被开方式、盒子宽度等于部件之和、文字顺序不乱。
 *   ② 用**同一套 FormulaLayout** 在电脑上渲染 PNG。
 *        手机上没法自动截图（没有模拟器），所以视觉验收就靠这张图：
 *        图里的每一处排版，都是 App 里 onDraw 照着同一批坐标画出来的。
 *
 * 注意：这里用 java.awt 画，只是因为电脑上只有它；
 *       坐标、字号比、间距全部来自 FormulaLayout，和 Android 端一致。
 */
public class FormulaAudit {

    static int pass = 0;
    static List<String> fails = new ArrayList<String>();

    static void ok(String name, boolean cond) { ok(name, cond, ""); }

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; return; }
        fails.add(name + (detail.isEmpty() ? "" : "  →  " + detail));
    }

    /** 度量：模拟 Android 上 Paint 的行为，用 AWT 的字体度量。 */
    static class AwtMetrics implements FormulaLayout.Metrics {
        final Font font;
        final java.awt.FontMetrics fm;
        final float unitPx;
        final java.util.Map<Float, java.awt.FontMetrics> cache =
                new java.util.HashMap<Float, java.awt.FontMetrics>();

        AwtMetrics(Font f, float unitPx) {
            this.font = f;
            this.fm = new java.awt.Canvas().getFontMetrics(f);
            this.unitPx = unitPx;
        }

        java.awt.FontMetrics fmFor(float scale) {
            Float k = Float.valueOf(scale);
            java.awt.FontMetrics m = cache.get(k);
            if (m == null) {
                Font f2 = font.deriveFont(font.getSize2D() * scale);
                m = new java.awt.Canvas().getFontMetrics(f2);
                cache.put(k, m);
            }
            return m;
        }

        public float width(String text, float scale) {
            if (text == null || text.isEmpty()) return 0;
            return fmFor(scale).stringWidth(text);
        }

        public float ascent(float scale) { return fmFor(scale).getAscent(); }
        public float descent(float scale) { return fmFor(scale).getDescent(); }
        public float unit() { return unitPx; }
    }

    public static int run(String outDir) {
        pass = 0;
        fails.clear();

        Font font = new Font("Serif", Font.PLAIN, 34);
        AwtMetrics m = new AwtMetrics(font, 1.4f);

        // 每一节独立 try：任何一条式子写错，只失败那一条，
        // 不能让整轮审计半路崩掉——崩掉的审计等于没审。
        try { layoutAsserts(m); } catch (Exception e) { fails.add("结构断言异常：" + e); }
        try { orderAsserts(m); } catch (Exception e) { fails.add("文字顺序异常：" + e); }
        try { renderSheet(outDir, font, m); } catch (Exception e) { fails.add("PNG 渲染失败：" + e); }

        System.out.println("  公式排版：" + pass + " 项通过，"
                + fails.size() + " 项失败");
        for (String f : fails) System.out.println("     ✗ " + f);
        return fails.size();
    }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "build/audit";
        int bad = run(dir);
        System.exit(bad == 0 ? 0 : 1);
    }

    // ── ① 结构断言 ───────────────────────────────────────
    static void layoutAsserts(FormulaLayout.Metrics m) {
        // 分式：分子底部必须在分数线上方，分母顶部必须在下方
        Expr.Node n = Expr.parse("1/x");
        FormulaLayout.Box b = FormulaLayout.layout(n, m);
        FormulaLayout.place(b, 0, 0, m);
        ok("1/x 顶层是分式", b.kind == FormulaLayout.K_DIV);
        float midY = b.y - m.unit() * 3f * b.s;
        ok("1/x 分子在分数线上方", b.a.y + b.a.desc < midY,
                "分子底 " + (b.a.y + b.a.desc) + " 线 " + midY);
        ok("1/x 分母在分数线下方", b.b.y - b.b.asc > midY,
                "分母顶 " + (b.b.y - b.b.asc) + " 线 " + midY);
        // 分数线位置由分子/分母的实际高度决定，不是盒子的几何中点；
        // 唯一能要求的是：它落在整个盒子的垂直范围内，且不贴边。
        ok("1/x 分数线在盒子内部",
                midY > b.y - b.asc && midY < b.y + b.desc,
                "线 y=" + midY + " 盒 " + (b.y - b.asc) + "~" + (b.y + b.desc));

        // 嵌套分式：1/(1+1/x) 不能塌
        Expr.Node n2 = Expr.parse("1/(1+1/x)");
        FormulaLayout.Box b2 = FormulaLayout.layout(n2, m);
        FormulaLayout.place(b2, 0, 0, m);
        ok("1/(1+1/x) 分母更高（内层撑开）",
                b2.desc > b.asc * 0.6f, "asc=" + b2.asc + " desc=" + b2.desc);
        ok("1/(1+1/x) 宽度有限", b2.w > 0 && b2.w < 1000);

        // 幂：指数基线必须高于底数基线
        Expr.Node n3 = Expr.parse("x^2");
        FormulaLayout.Box b3 = FormulaLayout.layout(n3, m);
        FormulaLayout.place(b3, 0, 0, m);
        ok("x^2 顶层是幂", b3.kind == FormulaLayout.K_POW);
        ok("x^2 指数高于基线", b3.b.y < b3.a.y - 1,
                "指数 y=" + b3.b.y + " 底 y=" + b3.a.y);
        ok("x^2 指数字号更小", b3.b.s < b3.a.s,
                "指数 s=" + b3.b.s + " 底 s=" + b3.a.s);

        // 根号：顶线必须高于被开方式的顶部
        Expr.Node n4 = Expr.parse("sqrt(1+x^2)");
        FormulaLayout.Box b4 = FormulaLayout.layout(n4, m);
        FormulaLayout.place(b4, 0, 0, m);
        ok("sqrt(1+x^2) 顶层是根号", b4.kind == FormulaLayout.K_SQRT);
        ok("根号顶线高于内容顶部", b4.asc > b4.a.asc,
                "根 asc=" + b4.asc + " 内容 asc=" + b4.a.asc);
        ok("根号内盒子有正宽度", b4.a.w > 0);

        // 函数：sin x 不加括号，sin(x+1) 加括号
        FormulaLayout.Box s1 = FormulaLayout.layout(Expr.parse("sin(x)"), m);
        ok("sin(x) 不额外加括号", "0".equals(s1.op), "op=" + s1.op);
        FormulaLayout.Box s2 = FormulaLayout.layout(Expr.parse("sin(x+1)"), m);
        ok("sin(x+1) 加括号", "1".equals(s2.op), "op=" + s2.op);
        ok("加括号后更宽", s2.w > s1.w);

        // 乘法：2x 并列，x·y 用圆点，分式相乘用圆点
        FormulaLayout.Box m1 = FormulaLayout.layout(Expr.parse("2x"), m);
        ok("2x 用并列（无乘号）", "".equals(m1.op), "op=" + m1.op);
        // 视觉审计第二轮：负系数被套了双层括号 ((−2)·x)
        FormulaLayout.Box mneg = FormulaLayout.layout(Expr.parse("-2x"), m);
        ok("−2x 不套括号", mneg.a.kind != FormulaLayout.K_PAREN,
                "kind=" + mneg.a.kind);
        ok("−2x 还原为 −2x", "−2x".equals(FormulaLayout.plain(mneg).replace(" ", "")),
                FormulaLayout.plain(mneg));
        // 函数参数是幂时要加括号：sin x² 有歧义
        FormulaLayout.Box msp = FormulaLayout.layout(Expr.parse("sin(x^2)"), m);
        ok("sin(x²) 加括号消歧义", "1".equals(msp.op), "op=" + msp.op);
        FormulaLayout.Box m2 = FormulaLayout.layout(Expr.parse("(x+1)·(x-1)"), m);
        ok("括号式相乘用圆点", "·".equals(m2.op), "op=" + m2.op);
        FormulaLayout.Box m3 = FormulaLayout.layout(Expr.parse("2*1/x"), m);
        ok("系数在左时可省略乘号", "".equals(m3.op), "op=" + m3.op);
        // 视觉审计抓到的错：x·sin(x) 曾被画成 "xsinx"，粘成一个名字
        FormulaLayout.Box m4 = FormulaLayout.layout(Expr.parse("x·sin(x)"), m);
        ok("x·sin(x) 必须有圆点", "·".equals(m4.op), "op=" + m4.op);
        FormulaLayout.Box m5 = FormulaLayout.layout(Expr.parse("cos(x)·x"), m);
        ok("cos(x)·x 必须有圆点", "·".equals(m5.op), "op=" + m5.op);
        FormulaLayout.Box m6 = FormulaLayout.layout(Expr.parse("x^2·exp(x)"), m);
        ok("x²·eˣ 必须有圆点", "·".equals(m6.op), "op=" + m6.op);
        // 数字系数在左才省略，右边不许省
        FormulaLayout.Box m7 = FormulaLayout.layout(Expr.parse("x·3"), m);
        ok("x·3 不省略乘号", "·".equals(m7.op), "op=" + m7.op);

        // 宽度可加性：行的宽度 = 左 + 操作符 + 右
        Expr.Node sum = Expr.parse("x^2+x");
        FormulaLayout.Box bs = FormulaLayout.layout(sum, m);
        float expect = bs.a.w + m.width(bs.op, bs.s) + bs.b.w;
        ok("行盒宽度 = 部件之和", Math.abs(bs.w - expect) < 0.01,
                "实际 " + bs.w + " 期望 " + expect);

        // 负号：−x 的负号必须在 x 左边
        FormulaLayout.Box bn = FormulaLayout.layout(Expr.parse("-x"), m);
        FormulaLayout.place(bn, 0, 0, m);
        ok("−x 负号在左", bn.a.x < bn.b.x, bn.a.x + " vs " + bn.b.x);

        // 自动括号（视觉审计抓到的错：x^x·(ln x+1) 被画成 x^x·ln x + 1）
        FormulaLayout.Box p1 = FormulaLayout.layout(Expr.parse("x^x·(ln(x)+1)"), m);
        ok("乘积里的加减式自动加括号", "·".equals(p1.op) && p1.b.kind == FormulaLayout.K_PAREN,
                "op=" + p1.op + " rightKind=" + p1.b.kind);
        ok("自动括号内是 ln x+1", FormulaLayout.plain(p1).contains("(ln x + 1)"),
                FormulaLayout.plain(p1));
        FormulaLayout.Box p2 = FormulaLayout.layout(Expr.parse("(x+1)^2"), m);
        ok("幂的底数是加减式时加括号",
                p2.kind == FormulaLayout.K_POW && p2.a.kind == FormulaLayout.K_PAREN,
                "baseKind=" + p2.a.kind);
        ok("(x+1)^2 还原正确", "(x + 1)^(2)".equals(FormulaLayout.plain(p2)),
                FormulaLayout.plain(p2));
        // 括号要包住分式时自动放大
        FormulaLayout.Box p3 = FormulaLayout.layout(Expr.parse("2·(1/x)"), m);
        ok("括号里是分式时括号放大", p3.b.kind == FormulaLayout.K_PAREN
                && p3.b.parScale > 1.2f, "parScale=" + p3.b.parScale);
        // 纯原子不该被套括号
        FormulaLayout.Box p4 = FormulaLayout.layout(Expr.parse("2·x"), m);
        ok("原子不套括号", p4.b.kind == FormulaLayout.K_TEXT, "kind=" + p4.b.kind);

        // 复杂式：不能出 NaN 或负宽度
        String[] complex = {"sin(x)/x", "(x^2+1)/(x-2)", "sqrt(1+sqrt(x))",
                "exp(sin(x))", "atan(2x)", "x^x", "1/(1+exp(-x))",
                "abs(x-1)", "ln(1+x^2)", "cos(x^2)"};
        int badGeom = 0;
        for (String s : complex) {
            FormulaLayout.Box bb = FormulaLayout.layout(Expr.parse(s), m);
            FormulaLayout.place(bb, 0, 0, m);
            if (!(bb.w > 0) || Float.isNaN(bb.w) || Float.isInfinite(bb.w)) {
                fails.add("几何异常：" + s + " w=" + bb.w);
                badGeom++;
            }
            if (bb.asc <= 0 || bb.desc < 0) {
                fails.add("高度异常：" + s + " asc=" + bb.asc + " desc=" + bb.desc);
                badGeom++;
            }
        }
        ok("10 个复杂式几何正常", badGeom == 0, badGeom + " 个异常");
    }

    // ── ② 文字顺序 ───────────────────────────────────────
    static void orderAsserts(FormulaLayout.Metrics m) {
        ok("x^2 还原为 x^(2)",
                "x^(2)".equals(FormulaLayout.plain(FormulaLayout.layout(Expr.parse("x^2"), m))),
                FormulaLayout.plain(FormulaLayout.layout(Expr.parse("x^2"), m)));
        ok("1/x 还原为 1/x",
                "1/x".equals(FormulaLayout.plain(FormulaLayout.layout(Expr.parse("1/x"), m))));
        ok("sqrt(x) 还原为 sqrt(x)",
                "sqrt(x)".equals(FormulaLayout.plain(
                        FormulaLayout.layout(Expr.parse("sqrt(x)"), m))));

        // 坐标顺序：同一行内，后画的盒子 x 必须更大
        Expr.Node n = Expr.parse("x^2+3x+1");
        FormulaLayout.Box b = FormulaLayout.layout(n, m);
        FormulaLayout.place(b, 0, 0, m);
        List<FormulaLayout.Box> all = FormulaLayout.flatten(b);
        int disorder = 0;
        for (FormulaLayout.Box x : all) {
            if (x.a != null && x.kind == FormulaLayout.K_ROW) {
                if (x.a.x >= x.b.x) disorder++;
            }
            if (x.kind == FormulaLayout.K_POW && x.a != null) {
                if (x.a.x >= x.b.x) disorder++;
            }
        }
        ok("同级盒子左右顺序正确", disorder == 0, disorder + " 处逆序");

        // 文字内容不能丢字
        String p = FormulaLayout.plain(b);
        ok("x^2+3x+1 文字完整",
                p.contains("x^(2)") && p.contains("3") && p.contains("+"),
                p);
    }

    // ══ ③ 渲染 PNG（视觉证据）════════════════════════════
    static void renderSheet(String outDir, Font font, AwtMetrics m) throws Exception {
        File dir = new File(outDir);
        if (!dir.exists()) dir.mkdirs();

        // 分组：把公式按"最需要看排版"的顺序排
        String[][] items = {
                {"1/x", "倒数"},
                {"1/(1+x^2)", "复合分式"},
                {"sqrt(x)", "根式"},
                {"sqrt(1+x^2)", "根式含幂"},
                {"x^2", "幂"},
                {"x^(1/2)", "分数指数"},
                {"sin(x)", "三角函数"},
                {"sin(2x)", "复合三角"},
                {"cos(x^2)", "复合三角"},
                {"ln(1+x^2)", "对数"},
                {"exp(3x)", "指数"},
                {"atan(2x)", "反三角"},
                {"x·sin(x)", "乘积"},
                {"x^2·exp(x)", "乘积含幂"},
                {"sin(x)/x", "商"},
                {"(cos(x)·x-sin(x))/x^2", "商的法则结果"},
                {"x^x·(ln(x)+1)", "幂指函数"},
                {"3x^2", "系数"},
                {"-2x·sin(x^2)", "负号+乘积"},
                {"1/(2sqrt(x))", "根式的导数"},
                {"2/(1+4x^2)", "arctan 链式"},
                {"x^sin(x)·(cos(x)·ln(x)+sin(x)/x)", "最复杂一行"},
        };

        int rowH = 74;
        int w = 1180;
        int h = 90 + rowH * items.length + 40;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x14, 0x16, 0x1C));
        g.fillRect(0, 0, w, h);

        g.setColor(new Color(0xC3, 0xC9, 0xD6));
        g.setFont(new Font("SansSerif", Font.PLAIN, 20));
        g.drawString("公式排版审计 —— 同一套 FormulaLayout 在 App 里 onDraw 使用", 30, 46);
        g.setColor(new Color(0x6B, 0x72, 0x82));
        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        g.drawString("生成于 " + new java.util.Date() + "　左：公式　右：说明", 30, 72);

        int y = 96;
        for (String[] it : items) {
            g.setColor(new Color(0x26, 0x2A, 0x34));
            g.fillRoundRect(20, y + 4, w - 40, rowH - 10, 10, 10);

            Expr.Node n = Expr.parse(it[0]);
            FormulaLayout.Box b = FormulaLayout.layout(n, m);
            FormulaLayout.place(b, 0, 0, m);

            // 居中基线
            float baseline = y + rowH / 2f + (b.asc - b.desc) / 2f - 4;
            float startX = 40;

            g.setColor(new Color(0xE8, 0xEA, 0xF0));
            drawBox(g, b, startX, baseline, font, m);

            g.setColor(new Color(0x6B, 0x72, 0x82));
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            g.drawString(it[1], w - 200, baseline);
            g.setColor(new Color(0x4A, 0x53, 0x64));
            g.setFont(new Font("Monospaced", Font.PLAIN, 12));
            g.drawString(it[0], w - 200, baseline + 18);

            y += rowH;
        }
        g.dispose();

        File out = new File(dir, "formula-sheet.png");
        ImageIO.write(img, "png", out);
        System.out.println("  公式图已生成：" + out.getAbsolutePath()
                + "  (" + w + "x" + h + ", " + items.length + " 条)");
        ok("公式图已写出", out.exists() && out.length() > 5000, "大小 " + out.length());
    }

    /** 按 FormulaLayout 的坐标画到 AWT 上（与 Android 端 drawTree 一一对应）。 */
    static void drawBox(Graphics2D g, FormulaLayout.Box b, float dx, float dy,
                        Font baseFont, FormulaLayout.Metrics m) {
        if (b == null) return;
        float x = b.x + dx, y = b.y + dy;
        switch (b.kind) {
            case FormulaLayout.K_TEXT: {
                g.setFont(baseFont.deriveFont(baseFont.getSize2D() * b.s));
                g.drawString(b.text, x, y);
                break;
            }
            case FormulaLayout.K_ROW: {
                if (b.op != null && !b.op.isEmpty()) {
                    g.setFont(baseFont.deriveFont(baseFont.getSize2D() * b.s));
                    float opX = b.a == null ? x : b.a.x + dx + b.a.w;
                    g.drawString(b.op, opX, y);
                }
                drawBox(g, b.a, dx, dy, baseFont, m);
                drawBox(g, b.b, dx, dy, baseFont, m);
                break;
            }
            case FormulaLayout.K_DIV: {
                float midY = y - m.unit() * 3f * b.s;
                g.setStroke(new java.awt.BasicStroke(Math.max(1.2f, 2f * b.s)));
                g.drawLine((int) (x + m.unit() * b.s), (int) midY,
                        (int) (x + b.w - m.unit() * b.s), (int) midY);
                drawBox(g, b.a, dx, dy, baseFont, m);
                drawBox(g, b.b, dx, dy, baseFont, m);
                break;
            }
            case FormulaLayout.K_POW:
                drawBox(g, b.a, dx, dy, baseFont, m);
                drawBox(g, b.b, dx, dy, baseFont, m);
                break;
            case FormulaLayout.K_SQRT: {
                float hook = m.unit() * 10f * b.s;
                float top = y - b.asc + m.unit() * 2f * b.s;
                g.setStroke(new java.awt.BasicStroke(Math.max(1.2f, 2f * b.s)));
                java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
                p.moveTo(x, y - m.unit() * 6f * b.s);
                p.lineTo(x + m.unit() * 3f * b.s, y - m.unit() * 8f * b.s);
                p.lineTo(x + m.unit() * 5.5f * b.s, y + m.unit() * 1.5f * b.s);
                p.lineTo(x + hook, top);
                g.draw(p);
                g.drawLine((int) (x + hook), (int) top,
                        (int) (x + b.w - m.unit() * b.s), (int) top);
                drawBox(g, b.a, dx, dy, baseFont, m);
                break;
            }
            case FormulaLayout.K_ABS: {
                g.setFont(baseFont.deriveFont(baseFont.getSize2D() * b.s));
                g.drawString("|", x, y);
                drawBox(g, b.a, dx, dy, baseFont, m);
                g.drawString("|", b.a.x + dx + b.a.w, y);
                break;
            }
            case FormulaLayout.K_PAREN: {
                g.setFont(baseFont.deriveFont(baseFont.getSize2D() * b.s * b.parScale));
                g.drawString("(", x, y);
                drawBox(g, b.a, dx, dy, baseFont, m);
                g.drawString(")", b.a.x + dx + b.a.w + m.unit() * 1.5f * b.s, y);
                break;
            }
            case FormulaLayout.K_FUNC: {
                g.setFont(baseFont.deriveFont(baseFont.getSize2D() * b.s));
                g.drawString(b.text, x, y);
                float cx = x + m.width(b.text, b.s) + m.unit() * 3f * b.s;
                if ("1".equals(b.op)) {
                    g.drawString("(", cx, y);
                    drawBox(g, b.a, dx, dy, baseFont, m);
                    g.drawString(")", b.a.x + dx + b.a.w, y);
                } else {
                    drawBox(g, b.a, dx, dy, baseFont, m);
                }
                break;
            }
            default:
                break;
        }
    }
}
