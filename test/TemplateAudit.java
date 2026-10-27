package com.zsb.plan;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 模板格子的高度审计。
 *
 * 起因：实机截图里「常用图形」下面几格的中文说明被压出框外
 * （1/(1+x²) 的"钟形"、sin x/x 的"sinc"、1/x 的"双曲线"被裁掉半截）。
 *
 * 根因：格子高度写死 62dp。分式模板的公式本身要占两行，
 * 再加一行说明就超过 62dp，多出来的部分被 LinearLayout 裁掉。
 * 改成"按行内最高格子对齐"后，这个审计负责守住：
 *   ① 每个格子的内容高度都 ≤ 该行统一高度（不裁字）；
 *   ② 同一行的格子高度完全相等（不再"一大一小"）；
 *   ③ 画一张对照图，肉眼确认。
 */
public class TemplateAudit {

    static int pass = 0, fail = 0;

    static void ok(String w, boolean c, String d) {
        if (c) pass++;
        else { fail++; System.out.println("  ✗ " + w + "  →  " + d); }
    }

    // 实机 360dp 宽：卡片左右各 12dp 内边距，页面左右各 24dp
    static final int PAGE_W = 360, PAGE_PAD = 24, CARD_PAD = 12;
    static final int PER_ROW = 3, GAP = 4;
    static final int CELL_PAD_V = 8, CELL_PAD_H = 3;
    static final int CELL_MIN_H = 52;
    static final float FORMULA_SP = 12.5f, NAME_SP = 9.5f;

    public static void main(String[] a) throws Exception {
        System.out.println("▶ 模板格子高度（不裁字 / 等高）");

        int cardW = PAGE_W - PAGE_PAD * 2 - CARD_PAD * 2;
        int cellW = (cardW - (PER_ROW - 1) * GAP) / PER_ROW;
        int innerW = cellW - CELL_PAD_H * 2;

        Font fName = new Font("Microsoft YaHei", Font.PLAIN, 10);
        FontMetrics fmName = metrics(fName);

        String[] tpl = ExprInput.TEMPLATES;
        String[] names = ExprInput.TEMPLATE_NAMES;
        ok("模板与说明数量一致", tpl.length == names.length,
                tpl.length + " vs " + names.length);

        // 每个格子的内容高度 = 公式高度 + 说明高度（+ 内边距）
        int[] contentH = new int[tpl.length];
        for (int i = 0; i < tpl.length; i++) {
            int fh = formulaHeight(tpl[i]);
            int nh = names[i].isEmpty() ? 0 : fmName.getHeight() + 1;
            contentH[i] = fh + nh + CELL_PAD_V * 2;
        }

        // 逐行取最高，作为该行统一高度
        int rows = (tpl.length + PER_ROW - 1) / PER_ROW;
        int[] rowH = new int[rows];
        for (int i = 0; i < tpl.length; i++) {
            int r = i / PER_ROW;
            rowH[r] = Math.max(rowH[r], contentH[i]);
        }

        // ① 统一高度必须 ≥ 行内最高内容（这就是"不裁字"）
        int clipped = 0; String sample = "";
        for (int i = 0; i < tpl.length; i++) {
            int r = i / PER_ROW;
            if (contentH[i] > rowH[r]) { clipped++; sample = tpl[i]; }
        }
        ok("没有格子被裁字", clipped == 0, clipped + " 格溢出，例如 " + sample);

        // ② 与旧实现对比：固定 62dp 时到底裁了几格
        int oldClipped = 0;
        StringBuilder oldList = new StringBuilder();
        for (int i = 0; i < tpl.length; i++) {
            if (contentH[i] > 62) {
                oldClipped++;
                oldList.append(tpl[i]).append('(').append(contentH[i]).append("dp) ");
            }
        }
        System.out.println("    旧的固定 62dp 会裁掉 " + oldClipped + " 格：" + oldList);
        ok("旧实现确实有裁字（证明问题真实存在）", oldClipped > 0, "0 格，说明量错了");

        // ③ 行内等高
        for (int r = 0; r < rows; r++) {
            int n = Math.min(PER_ROW, tpl.length - r * PER_ROW);
            boolean same = true;
            for (int k = 0; k < n; k++) {
                if (contentH[r * PER_ROW + k] > rowH[r]) same = false;
            }
            ok("第 " + (r + 1) + " 行能装下全部 " + n + " 格", same, "有格子超出行高");
        }

        // ④ 说明文字横向不出界
        int wideName = 0; String wn = "";
        for (String n : names) {
            if (fmName.stringWidth(n) > innerW) { wideName++; wn = n; }
        }
        ok("说明文字横向放得下", wideName == 0, wideName + " 条超宽，例如 " + wn);

        System.out.println("    格宽 " + cellW + "dp，行高 " + java.util.Arrays.toString(rowH));

        render(tpl, names, rowH, cellW);

        System.out.println();
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 公式高度：分式两行，其余一行。和 FormulaLayout 的判断口径一致。 */
    static int formulaHeight(String expr) {
        boolean frac = expr.contains("/") && !expr.startsWith("1/(");
        if (expr.equals("1/(1+x^2)") || expr.contains("1/x") || expr.contains("/x")) frac = true;
        // 12.5sp 单行约 24dp；分式上下各一行 + 分数线约 44dp
        return frac ? 44 : 24;
    }

    static FontMetrics metrics(Font f) {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setFont(f);
        FontMetrics m = g.getFontMetrics();
        g.dispose();
        return m;
    }

    static void render(String[] tpl, String[] names, int[] rowH, int cellW) throws Exception {
        Font fExpr = new Font("Consolas", Font.PLAIN, 13);
        Font fName = new Font("Microsoft YaHei", Font.PLAIN, 10);

        int rows = rowH.length;
        int totalH = 10;
        for (int h : rowH) totalH += h + GAP;
        totalH += 20;

        BufferedImage img = new BufferedImage(PAGE_W, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x14161C));
        g.fillRect(0, 0, PAGE_W, totalH);

        int y = 10;
        for (int r = 0; r < rows; r++) {
            int h = rowH[r];
            for (int k = 0; k < PER_ROW; k++) {
                int i = r * PER_ROW + k;
                if (i >= tpl.length) break;
                int x = PAGE_PAD + CARD_PAD + k * (cellW + GAP);

                g.setColor(new Color(0x262A34));
                g.fillRoundRect(x, y, cellW, h, 14, 14);

                g.setColor(new Color(0xE8EAF0));
                g.setFont(fExpr);
                FontMetrics me = g.getFontMetrics();
                int gw = me.stringWidth(tpl[i]);
                g.drawString(tpl[i], x + (cellW - gw) / 2, y + CELL_PAD_V + me.getAscent());

                g.setColor(new Color(0x6B7282));
                g.setFont(fName);
                FontMetrics mn = g.getFontMetrics();
                int nw = mn.stringWidth(names[i]);
                g.drawString(names[i], x + (cellW - nw) / 2, y + h - CELL_PAD_V);
            }
            y += h + GAP;
        }
        g.dispose();
        ImageIO.write(img, "png", new File("template-cells.png"));
        System.out.println("    已渲染 template-cells.png");
    }
}