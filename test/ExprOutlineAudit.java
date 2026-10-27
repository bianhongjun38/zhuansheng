package com.zsb.plan;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * 「写法 → 形状」对照表的审计与视觉证据。
 *
 * 两件事必须同时成立，否则这张表就是骗人的：
 *   ① 每条 write 都真能被 Expr 解析、真能画出曲线
 *   ② 给出的说明与真实曲线一致 —— 用采样点去验证"描述"，
 *      不是用描述去验证描述
 *
 * ② 的做法：对每条抽特征（定义域边界、极值、单调方向、是否过原点、
 * 奇偶对称），然后断言它符合 shape 里写的话。
 * 例如 abs(x) 写的是"V 形、负号被吃掉"，就用 f(-5)==f(5) 证明。
 */
public class ExprOutlineAudit {

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond, String d) {
        if (cond) pass++;
        else { fail++; System.out.println("  ✗ " + what + "  →  " + d); }
    }

    static final int CW = 74, CH = 52, S = 2;

    public static void main(String[] a) throws Exception {
        String out = a.length > 0 ? a[0] : "build/audit";
        File dir = new File(out);
        dir.mkdirs();

        System.out.println("▶ 函数图像「写法→形状」对照表");
        ExprOutline.Item[] items = ExprOutline.all();

        ok("条目数与分组标记一致", items.length == 16, items.length + " 条");

        // ① 每条都能解析、能采样
        java.util.List<BufferedImage> thumbs = new java.util.ArrayList<BufferedImage>();
        for (int i = 0; i < items.length; i++) {
            ExprOutline.Item it = items[i];
            Expr.Node n;
            try {
                n = Expr.parse(it.sample);
            } catch (Exception e) {
                ok("「" + it.name + "」可解析", false, it.sample + " : " + e.getMessage());
                thumbs.add(null);
                continue;
            }
            ok("「" + it.name + "」可解析", true, "");

            // ② 真采样，必须画得出线（不是空白）
            PlotGeom.View v = new PlotGeom.View(-6, 6, -4, 4, CW, CH);
            List<PlotGeom.Seg> segs = PlotGeom.sample(n, v, 1);
            int pts = 0;
            for (PlotGeom.Seg s : segs) pts += s.pts.size();
            ok("「" + it.name + "」能画出曲线", pts >= 8, pts + " 个采样点");

            thumbs.add(render(n, segs, v, it.color));
            verifyShape(it, n);
        }

        ok("全部 16 条都有缩略图", thumbs.size() == 16, thumbs.size() + " 张");
        writeSheet(dir, items, thumbs);

        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 用采样点检验「形状说明」写的是不是真的。 */
    static void verifyShape(ExprOutline.Item it, Expr.Node n) {
        String w = it.write;

        if ("sqrt(x)".equals(w)) {
            ok("sqrt 定义域只到 x≥0", Double.isNaN(Expr.eval(n, -1)),
               "sqrt(-1)=" + Expr.eval(n, -1));
            ok("sqrt(9)=3 不是 81", near(Expr.eval(n, 9), 3), "" + Expr.eval(n, 9));
            // 增量递减要在"相同步长"上比较：0→1 与 1→2，不能拿 0.25→1 比
            double d1 = Expr.eval(n, 1) - Expr.eval(n, 0);
            double d2 = Expr.eval(n, 4) - Expr.eval(n, 3);
            ok("sqrt 越走越慢（同一步长增量递减）", d1 > d2,
               "Δ[0,1]=" + d1 + " vs Δ[3,4]=" + d2);
        } else if ("abs(x)".equals(w)) {
            ok("abs 负号被吃掉 f(-5)=f(5)",
               near(Expr.eval(n, -5), Expr.eval(n, 5)),
               Expr.eval(n, -5) + " vs " + Expr.eval(n, 5));
            ok("abs 在原点取 0 且是尖角（左右斜率相反）",
               near(Expr.eval(n, 0), 0)
                       && Expr.eval(n, 0.1) > 0 && Expr.eval(n, -0.1) > 0, "");
        } else if ("exp(x)".equals(w)) {
            ok("exp(0)=1 不是 0", near(Expr.eval(n, 0), 1), "" + Expr.eval(n, 0));
            ok("exp 每加 1 乘 e≈2.718",
               near(Expr.eval(n, 1) / Expr.eval(n, 0), Math.E), "");
            ok("exp 涨得比 x^2 快", Expr.eval(n, 10) > 100, "" + Expr.eval(n, 10));
        } else if ("x^2".equals(w)) {
            ok("平方左右对称 f(-3)=f(3)", near(Expr.eval(n, -3), Expr.eval(n, 3)), "");
            ok("平方最低点在原点", Expr.eval(n, 0) <= Expr.eval(n, 0.5), "");
        } else if ("x^3".equals(w)) {
            ok("立方不对称 f(-2)=-8（负号保留）",
               near(Expr.eval(n, -2), -8), "" + Expr.eval(n, -2));
        } else if ("ln(x)".equals(w)) {
            ok("ln 定义域只到 x>0", Double.isNaN(Expr.eval(n, -1)), "");
            ok("ln(1)=0", near(Expr.eval(n, 1), 0), "" + Expr.eval(n, 1));
            ok("ln(e)=1", near(Expr.eval(n, Math.E), 1), "" + Expr.eval(n, Math.E));
        } else if ("lg(x)".equals(w)) {
            ok("lg(100)=2", near(Expr.eval(n, 100), 2), "" + Expr.eval(n, 100));
        } else if ("2^x".equals(w)) {
            ok("2^3=8", near(Expr.eval(n, 3), 8), "" + Expr.eval(n, 3));
            ok("2^-1=0.5 不是负数", near(Expr.eval(n, -1), 0.5), "" + Expr.eval(n, -1));
        } else if ("sin(x)".equals(w)) {
            ok("sin(0)=0", near(Expr.eval(n, 0), 0), "");
            ok("sin 最高只到 1", Expr.eval(n, Math.PI / 2) <= 1.0001, "");
        } else if ("cos(x)".equals(w)) {
            ok("cos(0)=1（与 sin 的唯一区别）", near(Expr.eval(n, 0), 1), "");
        } else if ("tan(x)".equals(w)) {
            // tan(π/2) 未必正好落成 Infinity（浮点逼近会给出 1.6e16），
            // 真正的判据是"绝对值冲出画面"——那正是界面上断开的成因
            double tp = Math.abs(Expr.eval(n, Math.PI / 2));
            ok("tan 在 π/2 附近冲出画面（所以断开）", tp > 1e6, "" + tp);
            ok("tan 连续两段之间确实换号",
               Expr.eval(n, 1.5) > 0 && Expr.eval(n, 1.7) < 0,
               Expr.eval(n, 1.5) + " / " + Expr.eval(n, 1.7));
        } else if ("1/x".equals(w)) {
            ok("1/x 在 0 处无定义", !PlotGeom.isFinite(Expr.eval(n, 0)), "");
            ok("1/x 越大越贴 x 轴", Math.abs(Expr.eval(n, 1000)) < 0.01, "");
        } else if ("1/(1+x^2)".equals(w)) {
            ok("钟形 x=0 最高 1", near(Expr.eval(n, 0), 1), "");
            ok("钟形 x=±1 降到 0.5", near(Expr.eval(n, 1), 0.5)
                    && near(Expr.eval(n, -1), 0.5), "");
        }
    }

    static boolean near(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) return false;
        return Math.abs(a - b) < 1e-6;
    }

    static BufferedImage render(Expr.Node n, List<PlotGeom.Seg> segs,
                                PlotGeom.View v, int rgb) throws Exception {
        BufferedImage img = new BufferedImage(CW * S, CH * S, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x1B, 0x1E, 0x26));
        g.fillRect(0, 0, CW * S, CH * S);

        // 坐标轴
        g.setColor(new Color(0x33, 0x38, 0x44));
        g.setStroke(new BasicStroke(1f * S));
        int ax = (int) (v.px(0) * S), ay = (int) (v.py(0) * S);
        if (ay >= 0 && ay <= CH * S) g.drawLine(0, ay, CW * S, ay);
        if (ax >= 0 && ax <= CW * S) g.drawLine(ax, 0, ax, CH * S);

        // 曲线
        g.setColor(new Color(rgb));
        g.setStroke(new BasicStroke(1.6f * S));
        for (PlotGeom.Seg s : segs) {
            for (int i = 1; i < s.pts.size(); i++) {
                double[] p0 = s.pts.get(i - 1), p1 = s.pts.get(i);
                int x0 = (int) (v.px(p0[0]) * S), y0 = (int) (v.py(p0[1]) * S);
                int x1 = (int) (v.px(p1[0]) * S), y1 = (int) (v.py(p1[1]) * S);
                // 裁到框内，避免长线飞出图外
                if (Math.abs(y0) > CH * S * 6 || Math.abs(y1) > CH * S * 6) continue;
                g.drawLine(x0, y0, x1, y1);
            }
        }
        g.dispose();
        return img;
    }

    static void writeSheet(File dir, ExprOutline.Item[] items,
                           List<BufferedImage> thumbs) throws Exception {
        int cols = 4, cellW = 168, cellH = 108;
        int rows = (items.length + cols - 1) / cols;
        BufferedImage sheet = new BufferedImage(cellW * cols * S, cellH * rows * S,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x14, 0x16, 0x1C));
        g.fillRect(0, 0, cellW * cols * S, cellH * rows * S);

        for (int i = 0; i < items.length; i++) {
            int r = i / cols, c = i % cols;
            int x = c * cellW * S, y = r * cellH * S;
            ExprOutline.Item it = items[i];

            Graphics2D gg = (Graphics2D) g.create();
            gg.translate(x + (cellW - CW) / 2 * S, y + 6 * S);
            if (thumbs.get(i) != null) gg.drawImage(thumbs.get(i), 0, 0, null);
            gg.dispose();

            g.setFont(new Font("SansSerif", Font.BOLD, 11 * S));
            g.setColor(new Color(0xE8, 0xEA, 0xF0));
            String wr = it.write;
            int ww = g.getFontMetrics().stringWidth(wr);
            g.drawString(wr, x + (cellW * S - ww) / 2, y + (6 + CH + 16) * S - 2 * S);

            g.setFont(new Font("SansSerif", Font.PLAIN, 9 * S));
            g.setColor(new Color(0x6B, 0x72, 0x82));
            String nm = it.name;
            int nw = g.getFontMetrics().stringWidth(nm);
            g.drawString(nm, x + (cellW * S - nw) / 2, y + (6 + CH + 30) * S - 2 * S);
        }
        g.dispose();
        File f = new File(dir, "expr-outline.png");
        ImageIO.write(sheet, "png", f);
        System.out.println("  对照图：" + f.getAbsolutePath());
    }
}
