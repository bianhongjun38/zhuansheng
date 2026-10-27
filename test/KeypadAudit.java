package com.zsb.plan;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 输入面板视觉审计。
 *
 * MathView 的按键板和模板是纯 Java 布局，所以能在电脑上按同样的行列规则
 * 画出来看。手机屏幕只有 360dp 宽，6 个键一行 —— 这个宽度下
 * "atan" 会不会被挤成 "ata"、"抛物线" 会不会换行，只有画出来才知道。
 */
public class KeypadAudit {

    /** 直接复用 FormulaAudit 的绘制，避免两份绘制逻辑各画各的。 */
    static class Draw {
        static void drawBox(Graphics2D g, FormulaLayout.Box b, float dx, float dy,
                            Font f, FormulaLayout.Metrics m) {
            FormulaAudit.drawBox(g, b, dx, dy, f, m);
        }
    }

    static int pass = 0;
    static int fails = 0;

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; return; }
        fails++;
        System.out.println("     ✗ " + name + (detail.isEmpty() ? "" : "  →  " + detail));
    }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "build/audit";
        System.exit(run(dir) == 0 ? 0 : 1);
    }

    public static int run(String outDir) {
        pass = 0;
        fails = 0;
        try { render(outDir); } catch (Exception e) {
            fails++;
            System.out.println("     ✗ 渲染失败：" + e);
        }
        System.out.println("  输入面板：" + pass + " 项通过，" + fails + " 项失败");
        return fails;
    }

    static void render(String outDir) throws Exception {
        drawPage(outDir, 0);
        drawPage(outDir, 1);
    }

    static void drawPage(String outDir, int which) throws Exception {
        // 360dp 宽的机身，按键板 6 列、模板 4 列 —— 和 MathView 一致
        int phoneDp = 360;
        int pad = 12;
        int gap = 2;
        int keyRowH = 36;
        int tmplRowH = 62;   // 分式要两行，格子得给够高

        int cols = 6;
        int tmplCols = 4;
        int innerW = phoneDp - pad * 2;

        // 两页都要画：数字页和函数页各出一张，和 App 里 paddedKeys(6,pg) 一致
        String[][] keys = ExprInput.paddedKeys(cols, which);
        int keyRows = keys.length / cols;
        int tmplRows = (ExprInput.TEMPLATES.length + tmplCols - 1) / tmplCols;

        int headH = 200;
        int h = headH + keyRows * (keyRowH + 5) + 30
                + tmplRows * (tmplRowH + 5) + 60;

        BufferedImage img = new BufferedImage(phoneDp * 2, h * 2,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(2, 2);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x14, 0x16, 0x1C));
        g.fillRect(0, 0, phoneDp, h);

        int y = 16;
        g.setColor(new Color(0xE8, 0xEA, 0xF0));
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.drawString("函数表达式", pad, y + 12);

        // 输入框
        y += 22;
        g.setColor(new Color(0x26, 0x2A, 0x34));
        g.fillRoundRect(pad, y, innerW, 32, 8, 8);
        g.setColor(new Color(0xE8, 0xEA, 0xF0));
        g.setFont(new Font("Monospaced", Font.BOLD, 14));
        g.drawString("x^2-4", pad + 8, y + 21);
        g.setColor(new Color(0x3E, 0xCF, 0xAF));
        g.fillRect(pad + 8 + 46, y + 8, 2, 16);   // 光标

        y += 36;
        g.setColor(new Color(0x6B, 0x72, 0x82));
        g.setFont(new Font("SansSerif", Font.PLAIN, 9));
        g.drawString("可以写 x、pi、e；sin cos tan ln lg sqrt abs exp", pad, y + 10);
        g.drawString("下面有按键，不用切输入法符号页。", pad, y + 22);

        // 按键板
        y += 34;
        int keyW = (innerW - gap * 2 * cols) / cols;
        for (int i = 0; i < keys.length; i++) {
            int cx = pad + (i % cols) * (keyW + gap * 2);
            int cy = y + (i / cols) * (keyRowH + 5);
            String[] k = keys[i];
            if (ExprInput.isPad(k)) continue;   // 占位键只留空位，不画
            boolean isBack = "\\b".equals(k[1]);
            g.setColor(new Color(0x26, 0x2A, 0x34));
            g.fillRoundRect(cx, cy, keyW, keyRowH, 7, 7);
            g.setColor(isBack ? new Color(0xE0, 0x5A, 0x54)
                              : new Color(0xE8, 0xEA, 0xF0));
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(k[0]);
            g.drawString(k[0], cx + (keyW - tw) / 2, cy + keyRowH / 2 + 5);
            // 断言：键文字不能超出键宽
            ok("键「" + k[0] + "」不溢出", tw <= keyW - 4,
               "文字 " + tw + "px / 键 " + keyW + "px");
        }
        y += keyRows * (keyRowH + 5) + 16;

        // 模板
        g.setColor(new Color(0x6B, 0x72, 0x82));
        g.setFont(new Font("SansSerif", Font.BOLD, 9));
        g.drawString("常用图形（点一下直接画）", pad, y + 8);
        y += 16;

        int tW = (innerW - gap * 2 * tmplCols) / tmplCols;
        for (int i = 0; i < ExprInput.TEMPLATES.length; i++) {
            int cx = pad + (i % tmplCols) * (tW + gap * 2);
            int cy = y + (i / tmplCols) * (tmplRowH + 5);
            g.setColor(new Color(0x26, 0x2A, 0x34));
            g.fillRoundRect(cx, cy, tW, tmplRowH, 7, 7);

            String expr = ExprInput.TEMPLATES[i];
            String nm = i < ExprInput.TEMPLATE_NAMES.length
                    ? ExprInput.TEMPLATE_NAMES[i] : "";

            // 公式行：用同一套 FormulaLayout 排版（和 App 里 FormulaView 同源）
            try {
                Expr.Node n = Expr.parse(expr);
                FormulaLayout.Box box = FormulaLayout.layout(n, new FM());
                FormulaLayout.place(box, 0, 0, new FM());
                int bw = (int) box.w;
                ok("模板「" + expr + "」公式不溢出", bw <= tW - 6,
                   bw + "px / " + (tW - 6) + "px");
                float base = cy + 22 + (box.asc - box.desc) / 2f;
                g.setColor(new Color(0xE8, 0xEA, 0xF0));
                Draw.drawBox(g, box, cx + (tW - bw) / 2f, base, BASE_FONT, new FM());
            } catch (Exception e) {
                g.setColor(new Color(0xE0, 0x5A, 0x54));
                g.setFont(new Font("Monospaced", Font.PLAIN, 8));
                g.drawString(expr, cx + 3, cy + 22);
            }

            if (!nm.isEmpty()) {
                g.setColor(new Color(0x6B, 0x72, 0x82));
                g.setFont(new Font("SansSerif", Font.PLAIN, 8));
                FontMetrics fm = g.getFontMetrics();
                int nw = fm.stringWidth(nm);
                ok("模板名「" + nm + "」不溢出", nw <= tW - 4,
                   nw + "px / " + (tW - 4) + "px");
                g.drawString(nm, cx + (tW - nw) / 2, cy + tmplRowH - 8);
            }
        }

        g.dispose();
        File dirF = new File(outDir);
        if (!dirF.exists()) dirF.mkdirs();
        File out = new File(dirF, which == 0 ? "keypad-num.png" : "keypad-fn.png");
        ImageIO.write(img, "png", out);
        System.out.println("  输入面板图已生成：" + out.getAbsolutePath()
                + "  (360dp × " + h + "dp)");
        ok((which == 0 ? "数字页" : "函数页") + "图已写出",
           out.exists() && out.length() > 5000,
           "大小 " + out.length());
    }

    static final Font BASE_FONT = new Font("SansSerif", Font.PLAIN, 11);

    /** Metrics：直接用 AWT 字体度量，和手机上 Paint.measureText 行为一致。 */
    static class FM implements FormulaLayout.Metrics {
        private final Graphics2D g = new BufferedImage(1, 1,
                BufferedImage.TYPE_INT_RGB).createGraphics();

        public float width(String text, float scale) {
            g.setFont(BASE_FONT.deriveFont(BASE_FONT.getSize2D() * scale));
            return g.getFontMetrics().stringWidth(text);
        }
        public float ascent(float scale) {
            g.setFont(BASE_FONT.deriveFont(BASE_FONT.getSize2D() * scale));
            return g.getFontMetrics().getAscent();
        }
        public float descent(float scale) {
            g.setFont(BASE_FONT.deriveFont(BASE_FONT.getSize2D() * scale));
            return g.getFontMetrics().getDescent();
        }
        public float unit() { return 1f; }
    }
}
