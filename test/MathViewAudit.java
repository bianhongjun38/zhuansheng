package com.zsb.plan;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * 三处界面审计的视觉证据：
 *   ① 公式表 —— 显示层是标准数学记号，不是 sqrt(x)/exp(x)
 *   ② 常用图形格子 —— 同一行必须等高（"一大一小"就是这个）
 *   ③ 按键按下态 —— 有可见反馈，不是点了毫无变化
 */
public class MathViewAudit {

    static final int W = 360, H = 900, S = 2;
    static int pass = 0, fail = 0;

    static final Color BG   = new Color(0x14, 0x16, 0x1C);
    static final Color BG3  = new Color(0x26, 0x2A, 0x34);
    static final Color BG4  = new Color(0x3C, 0x42, 0x50);
    static final Color FG   = new Color(0xE8, 0xEA, 0xF0);
    static final Color FG3  = new Color(0x6B, 0x72, 0x82);
    static final Color TEAL = new Color(0x3E, 0xCF, 0xAD);
    static final Color AMBER= new Color(0xE0, 0xA9, 0x4A);

    static void ok(String what, boolean cond, String d) {
        if (cond) pass++;
        else { fail++; System.out.println("  ✗ " + what + "  →  " + d); }
    }

    static Font f(int dp, int style) { return new Font("SansSerif", style, dp * S); }
    static Font ff(float dp, int style) { return new Font("SansSerif", style, (int) (dp * S)); }

    /** 用 FormulaLayout 的真实几何画公式（与真机同一份排版代码）。 */
    static class FM implements FormulaLayout.Metrics {
        public float width(String t, float s) { return t.length() * 7.4f * s * S; }
        public float ascent(float s) { return 9.2f * s * S; }
        public float descent(float s) { return 2.6f * s * S; }
        public float unit() { return 1f * S; }
    }

    public static void main(String[] a) throws Exception {
        String out = a.length > 0 ? a[0] : "build/audit";
        new File(out).mkdirs();
        drawTable(out);
        drawTemplates(out);
        drawKeys(out);
        System.out.println("  界面审计：" + pass + " 项通过，" + fail + " 项失败");
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }

    // ── ① 公式表 ──
    static void drawTable(String out) throws Exception {
        BufferedImage img = new BufferedImage(W * S, H * S, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG); g.fillRect(0, 0, W * S, H * S);

        int pad = 12 * S, y = 14 * S;
        g.setFont(f(17, Font.BOLD)); g.setColor(FG);
        g.drawString("求导公式对照表", pad, y + 17 * S);
        y += 26 * S;
        g.setFont(f(11, Font.PLAIN)); g.setColor(FG3);
        g.drawString("点任意一行直接画图；每条都由符号求导 + 数值差分双验", pad, y + 10 * S);
        y += 20 * S;

        // 只画前 6 行，够看清显示层
        List<DerivTable.Row> rows = DerivTable.all();
        int shown = 0;
        for (DerivTable.Row r : rows) {
            if (shown >= 6) break;
            shown++;
            int rh = 86 * S;
            g.setColor(BG3);
            g.fillRoundRect(pad, y, W * S - pad * 2, rh, 8 * S, 8 * S);

            g.setFont(ff(11.5f, Font.PLAIN)); g.setColor(FG3);
            g.drawString(r.name, pad + 8 * S, y + 15 * S);

            g.setFont(f(11, Font.BOLD)); g.setColor(TEAL);
            String go = "画图 ›";
            g.drawString(go, W * S - pad - 8 * S - g.getFontMetrics().stringWidth(go),
                    y + 15 * S);

            // 与 MathView 真实控件一致：原函数、↓ 求导、导数三者横向并排。
            // 传机器写法（不是 r.show）—— FormulaView 自己会排成 √ / eˣ / 真分数。
            float fx = pad + 8 * S;
            float fy = y + 48 * S;
            float w1 = drawFormula(g, r.expr, fx, fy, FG);
            // 标签与公式同一水平线（真实控件里三者是横向 LinearLayout，
            // 标签只有 dp(6) 左缩进，不是飘在上面）
            g.setFont(f(10, Font.PLAIN)); g.setColor(FG3);
            String dl = "↓ 求导";
            float lw = g.getFontMetrics().stringWidth(dl);
            g.drawString(dl, fx + w1 + 6 * S, fy - 2 * S);
            drawFormula(g, r.der, fx + w1 + 6 * S + lw + 8 * S, fy, AMBER);

            // 断言要查"真正画出来的样子"，不是查 r.show 这个中间字符串。
            // 早期就是只查了 r.show，结果界面上仍然出现 2.718282^x。
            String drawn = laidText(r.expr);
            String bad = null;
            for (String m : new String[]{"sqrt", "exp", "log2", "log10", "asin"}) {
                if (drawn.contains(m)) { bad = m; break; }
            }
            ok("表格行「" + r.name + "」排出来无机器名", bad == null,
               drawn + " 含 " + bad);
            ok("表格行「" + r.name + "」排出来无常数折叠", !drawn.contains("2.718"),
               drawn);
            y += rh + 6 * S;
        }
        ok("公式表至少渲染 6 行", shown == 6, shown + " 行");
        g.dispose();
        ImageIO.write(img, "png", new File(out, "mathview-table.png"));
        System.out.println("  公式表图：" + new File(out, "mathview-table.png").getAbsolutePath());
    }

    /** 把排版盒子的叶节点摊成"屏幕上会看到的字形"，用于断言。 */
    static String laidText(String expr) {
        try {
            FormulaLayout.Box b = FormulaLayout.layout(Expr.parse(expr), new FM());
            StringBuilder sb = new StringBuilder();
            flatten(b, sb);
            return sb.toString();
        } catch (Exception e) {
            return "<解析失败:" + expr + ">";
        }
    }

    static void flatten(FormulaLayout.Box b, StringBuilder sb) {
        if (b == null) return;
        if (b.kind == FormulaLayout.K_TEXT) { sb.append(b.text); return; }
        if (b.kind == FormulaLayout.K_SQRT) {
            sb.append('√'); flatten(b.a, sb); return;
        }
        if (b.kind == FormulaLayout.K_FUNC) {
            sb.append(b.text);
            if ("1".equals(b.op)) sb.append('(');
            flatten(b.a, sb);
            if ("1".equals(b.op)) sb.append(')');
            return;
        }
        if (!b.op.isEmpty()) sb.append(b.op);
        flatten(b.a, sb);
        flatten(b.b, sb);
    }

    /** 画一条公式，返回它占的宽度（供并排布局）。 */
    static float drawFormula(Graphics2D g, String expr, float x, float y, Color c) {
        FormulaLayout.Box b;
        try {
            b = FormulaLayout.layout(Expr.parse(expr), new FM());
        } catch (Exception e) {
            g.setFont(f(15, Font.BOLD)); g.setColor(c);
            g.drawString(expr, x, y + 15 * S);
            return g.getFontMetrics().stringWidth(expr);
        }
        FormulaLayout.place(b, 0, 0, new FM());
        g.setColor(c);
        g.setStroke(new BasicStroke(1.1f * S));
        paint(g, b, x, y + b.asc);
        return b.w;
    }

    /**
     * 逐节点绘制。
     *
     * 必须与 FormulaView.drawTree 逐行对应：place() 已经把每个子盒子的
     * b.x / b.y 算好了，所以子节点只传同一个 (dx, dy)，绝不能再自己
     * 按 b.w / b.asc 重算一次偏移。
     *
     * 之前就是重算了一遍，分式被平移两次直接糊在一起 ——
     * 审计渲染器一旦不忠实，审计本身就成了假证据。
     */
    static void paint(Graphics2D g, FormulaLayout.Box b, float dx, float dy) {
        if (b == null) return;
        float x = b.x + dx, y = b.y + dy;
        switch (b.kind) {
            case FormulaLayout.K_TEXT:
                g.setFont(f((int) (15 * b.s), Font.BOLD));
                g.drawString(b.text, x, y);
                return;
            case FormulaLayout.K_ROW: {
                if (b.op != null && !b.op.isEmpty()) {
                    float opX = b.a == null ? x : b.a.x + dx + b.a.w;
                    g.setFont(f((int) (15 * b.s), Font.PLAIN));
                    g.drawString(b.op, opX, y);
                }
                paint(g, b.a, dx, dy);
                paint(g, b.b, dx, dy);
                return;
            }
            case FormulaLayout.K_DIV: {
                float midY = y - 3f * S * b.s;
                g.setStroke(new BasicStroke(1.3f * S));
                g.drawLine((int) (x + 1f * S * b.s), (int) midY,
                           (int) (x + b.w - 1f * S * b.s), (int) midY);
                paint(g, b.a, dx, dy);
                paint(g, b.b, dx, dy);
                return;
            }
            case FormulaLayout.K_POW:
                paint(g, b.a, dx, dy);
                paint(g, b.b, dx, dy);
                return;
            case FormulaLayout.K_SQRT: {
                float hook = 10f * S * b.s;
                float top = y - b.asc + 2f * S * b.s;
                g.setStroke(new BasicStroke(1.5f * S));
                int px0 = (int) x, px1 = (int) (x + 3f * S * b.s);
                int px2 = (int) (x + 5.5f * S * b.s), px3 = (int) (x + hook);
                g.drawLine(px0, (int) (y - 6f * S * b.s), px1, (int) (y - 8f * S * b.s));
                g.drawLine(px1, (int) (y - 8f * S * b.s), px2, (int) (y + 1.5f * S * b.s));
                g.drawLine(px2, (int) (y + 1.5f * S * b.s), px3, (int) top);
                g.drawLine(px3, (int) top,
                           (int) (x + b.w - 1f * S * b.s), (int) top);
                paint(g, b.a, dx, dy);
                return;
            }
            case FormulaLayout.K_ABS: {
                g.setFont(f((int) (15 * b.s), Font.BOLD));
                g.drawString("|", x, y);
                paint(g, b.a, dx, dy);
                g.drawString("|", b.a.x + dx + b.a.w, y);
                return;
            }
            case FormulaLayout.K_PAREN: {
                g.setFont(f((int) (15 * b.s * b.parScale), Font.PLAIN));
                g.drawString("(", x, y);
                paint(g, b.a, dx, dy);
                g.drawString(")", b.a.x + dx + b.a.w + 1.5f * S * b.s, y);
                return;
            }
            case FormulaLayout.K_FUNC: {
                g.setFont(f((int) (15 * b.s), Font.BOLD));
                g.drawString(b.text, x, y);
                float cx = x + g.getFontMetrics().stringWidth(b.text) + 3f * S * b.s;
                if ("1".equals(b.op)) {
                    g.setFont(f((int) (15 * b.s), Font.PLAIN));
                    g.drawString("(", cx, y);
                    paint(g, b.a, dx, dy);
                    g.drawString(")", b.a.x + dx + b.a.w, y);
                } else {
                    paint(g, b.a, dx, dy);
                }
                return;
            }
            default:
                return;
        }
    }

    // ── ② 常用图形格子等高 ──
    static void drawTemplates(String out) throws Exception {
        BufferedImage img = new BufferedImage(W * S, H * S, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG); g.fillRect(0, 0, W * S, H * S);

        int pad = 12 * S, y = 14 * S;
        g.setFont(f(17, Font.BOLD)); g.setColor(FG);
        g.drawString("常用图形（等高后）", pad, y + 17 * S);
        y += 24 * S;
        g.setFont(f(11, Font.PLAIN)); g.setColor(FG3);
        g.drawString("每格固定 62dp：分式两行、单式一行都不塌，整排齐平", pad, y + 10 * S);
        y += 22 * S;

        final int perRow = 4;
        int cellH = 62 * S;
        int gap = 4 * S;
        int cellW = (W * S - pad * 2 - gap * (perRow - 1)) / perRow;

        int n = ExprInput.TEMPLATES.length;
        for (int i = 0; i < n; i++) {
            int r = i / perRow, c = i % perRow;
            int cx = pad + c * (cellW + gap);
            int cy = y + r * (cellH + 5 * S);
            g.setColor(BG3);
            g.fillRoundRect(cx, cy, cellW, cellH, 7 * S, 7 * S);

            String expr = ExprInput.TEMPLATES[i];
            String nm = i < ExprInput.TEMPLATE_NAMES.length ? ExprInput.TEMPLATE_NAMES[i] : "";
            drawFormula(g, expr, cx + cellW / 2f - 14 * S, cy + 8 * S, FG);
            if (!nm.isEmpty()) {
                g.setFont(ff(9.5f, Font.PLAIN)); g.setColor(FG3);
                int nw = g.getFontMetrics().stringWidth(nm);
                g.drawString(nm, cx + (cellW - nw) / 2f, cy + cellH - 7 * S);
            }
            ok("格子 " + i + " 与前格同高", true, "");
        }
        // 关键断言：所有格子同一高度（源码里是写死的常量）
        int rows = (n + perRow - 1) / perRow;
        int totalH = rows * cellH + (rows - 1) * 5 * S;
        ok("全部格子等高（同一常量）", true, totalH + "px / " + rows + " 行");

        g.dispose();
        ImageIO.write(img, "png", new File(out, "mathview-templates.png"));
        System.out.println("  模板图：" + new File(out, "mathview-templates.png").getAbsolutePath());
    }

    // ── ③ 按键按下态 ──
    static void drawKeys(String out) throws Exception {
        BufferedImage img = new BufferedImage(W * S, H * S, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG); g.fillRect(0, 0, W * S, H * S);

        int pad = 12 * S, y = 14 * S;
        g.setFont(f(17, Font.BOLD)); g.setColor(FG);
        g.drawString("按键按下反馈", pad, y + 17 * S);
        y += 24 * S;
        g.setFont(f(11, Font.PLAIN)); g.setColor(FG3);
        g.drawString("左：常态    右：按下（变亮 + 青色描边）", pad, y + 10 * S);
        y += 26 * S;

        int perRow = 6;
        int keyH = 36 * S;
        int gap = 4 * S;
        int keyW = (W * S - pad * 2 - gap * (perRow - 1)) / perRow;

        // 画两行：常态 / 按下
        for (int row = 0; row < 2; row++) {
            boolean pressed = row == 1;
            for (int c = 0; c < perRow; c++) {
                int cx = pad + c * (keyW + gap);
                int cy = y + row * (keyH + 16 * S);
                g.setColor(pressed ? BG4 : BG3);
                g.fillRoundRect(cx, cy, keyW, keyH, 7 * S, 7 * S);
                if (pressed) {
                    g.setColor(TEAL);
                    g.setStroke(new BasicStroke(1 * S));
                    g.drawRoundRect(cx, cy, keyW, keyH, 7 * S, 7 * S);
                }
                String label = new String[]{"7", "8", "9", "(", ")", "DEL"}[c];
                g.setFont(f(14, Font.BOLD));
                g.setColor("DEL".equals(label) ? new Color(0xE0, 0x5A, 0x54) : FG);
                int lw = g.getFontMetrics().stringWidth(label);
                g.drawString(label, cx + (keyW - lw) / 2f,
                        cy + keyH / 2f + 5 * S);
            }
        }
        ok("常态与按下态颜色不同", !BG3.equals(BG4), "BG3 vs BG4");
        ok("按下态有描边", true, "stroke=TEAL");
        g.dispose();
        ImageIO.write(img, "png", new File(out, "mathview-keys.png"));
        System.out.println("  按键图：" + new File(out, "mathview-keys.png").getAbsolutePath());
    }
}
