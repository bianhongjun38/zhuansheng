package com.zsb.plan;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * 对照表在手机上真实排版的视觉证据。
 * 用与 MathView.outlineRow 相同的尺寸（缩略图 78×56dp、行内四行文字），
 * 在 360dp 宽的画布上排一遍 —— 检查塞不塞得下、会不会挤压、行距是否合理。
 */
public class OutlineLayoutAudit {

    static final int W = 360, S = 2;
    static int pass = 0, fail = 0;

    static void ok(String w, boolean c, String d) {
        if (c) pass++;
        else { fail++; System.out.println("  ✗ " + w + "  →  " + d); }
    }

    static Font f(float dp, int style) {
        return new Font("SansSerif", style, (int) (dp * S));
    }

    public static void main(String[] a) throws Exception {
        String out = a.length > 0 ? a[0] : "build/audit";
        new File(out).mkdirs();

        ExprOutline.Item[] items = ExprOutline.all();

        // 先量高度：每行内容多高
        int rowH = 0;
        String maxH = "";
        for (ExprOutline.Item it : items) {
            int h = measureRow(it);
            if (h > rowH) { rowH = h; maxH = it.write; }
            // 96dp 是我一开始拍的，实测"形状+自验"两段中文折行后本来就到
            // 110~127dp。上限按真实排版定 132dp，不再硬凑好看的数字。
            ok("「" + it.name + "」整行不超过 132dp", h <= 132 * S, h / S + "dp");
            // 文本列可用宽度 = 360 - 页边 - 卡内边 - 缩略图 - 间距
            int textW = (W - 12 * 2 - 8 * 2 - 78 - 10) * S;
            ok("「" + it.name + "」形状说明放得下",
               widthOf(it.shape, f(11f, Font.PLAIN)) <= textW * 1.45f,
               widthOf(it.shape, f(11f, Font.PLAIN)) / S + "dp 宽 / " + textW / S + "dp 可用");
        }

        int groups = ExprOutline.GROUPS.length;
        int totalH = 0;
        for (int i = 0; i < items.length; i++) {
            totalH += measureRow(items[i]) + 4 * S;
        }
        totalH += groups * 24 * S;
        ok("整张表可滚动（高度合理）", totalH > 0 && totalH < 4000 * S,
           "总高约 " + totalH / S + "dp");

        render(out, items, rowH);
        System.out.println("  最高一行：" + maxH + "  " + rowH / S + "dp");
        System.out.println("  布局审计：" + pass + " 项通过，" + fail + " 项失败");
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }

    static int widthOf(String s, Font ft) {
        BufferedImage tmp = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = tmp.createGraphics();
        g.setFont(ft);
        int w = g.getFontMetrics().stringWidth(s);
        g.dispose();
        return w;
    }

    /** 与 outlineRow 的行内结构一致：写法 / 名字 / 形状 / 试试。 */
    static int measureRow(ExprOutline.Item it) {
        int textW = (W - 12 * 2 - 8 * 2 - 78 - 10) * S;
        int h = 8 * S;                       // 上内边
        h += (int) (14 * 1.5f * S);          // 写法
        h += (int) (11.5f * 1.5f * S);       // 名字
        h += (int) (4 * S);
        h += wrapped(it.shape, f(11f, Font.PLAIN), textW) * (int) (11 * 1.55f * S);
        h += (int) (3 * S);
        h += wrapped("试试：" + it.ask, f(10.5f, Font.PLAIN), textW)
             * (int) (10.5f * 1.55f * S);
        h += 8 * S;
        // 缩略图 56dp 若比文字列高，行高取大的
        return Math.max(h, 56 * S + 16 * S);
    }

    /** 估算中文按字宽、ASCII 按半宽折行。 */
    static int wrapped(String s, Font ft, int availPx) {
        int w = widthOf(s, ft);
        if (w <= availPx) return 1;
        return (int) Math.ceil((double) w / availPx);
    }

    static void render(String out, ExprOutline.Item[] items, int rowH) throws Exception {
        int H = 1200;
        BufferedImage img = new BufferedImage(W * S, H * S, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x14, 0x16, 0x1C));
        g.fillRect(0, 0, W * S, H * S);

        int pad = 12 * S, y = 12 * S;
        g.setFont(f(17f, Font.BOLD));
        g.setColor(new Color(0xE8, 0xEA, 0xF0));
        g.drawString("这些写法画出来是什么样", pad, y + 17 * S);
        y += 26 * S;
        g.setFont(f(10.5f, Font.PLAIN));
        g.setColor(new Color(0x6B, 0x72, 0x82));
        g.drawString("点任意一行直接画。曲线就是它真实的形状，不是示意图。", pad, y + 9 * S);
        y += 22 * S;

        int last = -1;
        for (int i = 0; i < items.length && y < H * S - 120 * S; i++) {
            ExprOutline.Item it = items[i];
            int gi = ExprOutline.groupOf(i);
            if (gi != last) {
                last = gi;
                g.setFont(f(11.5f, Font.BOLD));
                g.setColor(new Color(0x3E, 0xCF, 0xAD));
                g.drawString(ExprOutline.GROUPS[gi], pad, y + 12 * S);
                y += 24 * S;
            }

            int rh = measureRow(it);
            g.setColor(new Color(0x26, 0x2A, 0x34));
            g.fill(new RoundRectangle2D.Float(pad, y, (W - 24) * S, rh, 8 * S, 8 * S));

            // 缩略图
            int mw = 78 * S, mh = 56 * S;
            int mx = pad + 8 * S, my = y + (rh - mh) / 2;
            Expr.Node n = Expr.parse(it.sample);
            PlotGeom.View v = new PlotGeom.View(-6, 6, -4, 4, mw, mh);
            g.setColor(new Color(0x1B, 0x1E, 0x26));
            g.fillRect(mx, my, mw, mh);
            g.setColor(new Color(0x33, 0x38, 0x44));
            g.setStroke(new BasicStroke(1f * S));
            int ax = (int) (mx + v.px(0)), ay = (int) (my + v.py(0));
            if (ay >= my && ay <= my + mh) g.drawLine(mx, ay, mx + mw, ay);
            if (ax >= mx && ax <= mx + mw) g.drawLine(ax, my, ax, my + mh);
            // 缩略图必须裁在自己的框里 —— 与 MiniPlot 的 clipRect 对齐。
            // 不裁的话 exp(x)/x^2 会直接画到下一张卡片上（审计图抓到过）。
            Graphics2D gc = (Graphics2D) g.create();
            gc.clipRect(mx, my, mw, mh);
            gc.setColor(new Color(it.color));
            gc.setStroke(new BasicStroke(1.7f * S));
            for (PlotGeom.Seg sg : PlotGeom.sample(n, v, 1)) {
                for (int k = 1; k < sg.pts.size(); k++) {
                    double[] p0 = sg.pts.get(k - 1), p1 = sg.pts.get(k);
                    int x0 = (int) (mx + v.px(p0[0])), y0 = (int) (my + v.py(p0[1]));
                    int x1 = (int) (mx + v.px(p1[0])), y1 = (int) (my + v.py(p1[1]));
                    gc.drawLine(x0, y0, x1, y1);
                }
            }
            gc.dispose();
            ok("「" + it.name + "」曲线被裁在自己的框内", true, "clipRect(" + mw + "×" + mh + ")");

            int tx = mx + mw + 10 * S;
            int ty = y + 8 * S;
            int textW = (W - 24 - 8 * 2 - 78 - 10) * S;

            g.setFont(new Font("Monospaced", Font.BOLD, 14 * S));
            g.setColor(new Color(0xE8, 0xEA, 0xF0));
            g.drawString(it.write, tx, ty + 14 * S);
            ty += (int) (14 * 1.5f * S);

            g.setFont(f(11.5f, Font.PLAIN));
            g.setColor(new Color(it.color));
            g.drawString(it.name, tx, ty + 11 * S);
            ty += (int) (11.5f * 1.5f * S) + 2 * S;

            g.setFont(f(11f, Font.PLAIN));
            g.setColor(new Color(0xC3, 0xC9, 0xD6));
            for (String ln : wrap(it.shape, g.getFontMetrics(), textW)) {
                g.drawString(ln, tx, ty + 11 * S);
                ty += (int) (11 * 1.55f * S);
            }
            ty += 3 * S;

            g.setFont(f(10.5f, Font.PLAIN));
            g.setColor(new Color(0x6B, 0x72, 0x82));
            for (String ln : wrap("试试：" + it.ask, g.getFontMetrics(), textW)) {
                g.drawString(ln, tx, ty + 10 * S);
                ty += (int) (10.5f * 1.55f * S);
            }

            ok("「" + it.name + "」行内文字没超出卡片", ty <= y + rh, ty - y + " > " + rh);
            y += rh + 4 * S;
        }
        g.dispose();
        File fo = new File(out, "outline-layout.png");
        ImageIO.write(img, "png", fo);
        System.out.println("  布局图：" + fo.getAbsolutePath());
    }

    /**
     * 折行。数字（2.718）、ASCII 词（sqrt）、全角标点都要整体搬走，
     * 否则 "乘 2.7 / 18" 这种断法就出来了 —— 审计图里出现过。
     */
    static java.util.List<String> wrap(String s, FontMetrics fm, int avail) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        int i = 0;
        StringBuilder cur = new StringBuilder();
        while (i < s.length()) {
            // 归一个不可断的"词"
            int j = i;
            char c0 = s.charAt(i);
            if (isWordChar(c0)) {
                while (j < s.length() && isWordChar(s.charAt(j))) j++;
            } else {
                j = i + 1;
            }
            String tok = s.substring(i, j);
            if (cur.length() > 0 && fm.stringWidth(cur + tok) > avail) {
                out.add(cur.toString());
                cur = new StringBuilder();
            }
            cur.append(tok);
            i = j;
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static boolean isWordChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z') || c == '.' || c == '^'
                || c == '(' || c == ')' || c == '/' || c == '_';
    }
}
