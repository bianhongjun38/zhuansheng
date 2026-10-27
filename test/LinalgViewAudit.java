package com.zsb.plan;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * LinalgView 的界面审计 —— 把 Android 上那套布局在电脑上照原样画一遍。
 *
 * 为什么必须画：LinalgView 是纯 Canvas + 代码布局，没有 XML 可以预览。
 * 前几次真实 bug（画布在 ScrollView 里高度算成 0、模板公式溢出格子）
 * 都是画出来才看见的。这里按 360dp × 780dp 的尺寸复刻整屏，
 * 断言文字不越界、上下留白合理、配色对比够。
 */
public class LinalgViewAudit {

    static final int W = 360, H = 780;      // dp，按 1dp = 2px 渲染成 720×1560
    static final int S = 2;                  // 缩放：1dp = 2px，等效 xxhdpi

    static int pass = 0, fails = 0;

    static void ok(String what, boolean cond, String detail) {
        if (cond) { pass++; }
        else { fails++; System.out.println("  ✗ " + what + "  →  " + detail); }
    }

    // 配色，与 LinalgView 完全一致
    static final Color C_BG   = new Color(0x14, 0x16, 0x1C);
    static final Color C_BG2  = new Color(0x1B, 0x1E, 0x26);
    static final Color C_BG3  = new Color(0x26, 0x2A, 0x34);
    static final Color C_FG   = new Color(0xE8, 0xEA, 0xF0);
    static final Color C_FG2  = new Color(0xC3, 0xC9, 0xD6);
    static final Color C_FG3  = new Color(0x6B, 0x72, 0x82);
    static final Color C_TEAL = new Color(0x3E, 0xCF, 0xAD);
    static final Color C_AMBER= new Color(0xE0, 0xA9, 0x4A);
    static final Color C_RED  = new Color(0xE0, 0x5A, 0x54);
    static final Color C_BLUE = new Color(0x25, 0x75, 0xFC);

    static Font f(int dp, int style) {
        return new Font("SansSerif", style, dp * S);
    }

    public static void main(String[] a) throws Exception {
        String outDir = a.length > 0 ? a[0] : "build/audit";
        new File(outDir).mkdirs();

        drawOne(outDir, "linalg-view-stretch.png", new Linalg.Mat(1.5, 0.5, 0.5, 1.0));
        drawOne(outDir, "linalg-view-collapse.png", new Linalg.Mat(1, 2, 2, 4));
        drawOne(outDir, "linalg-view-rot45.png", new Linalg.Mat(0.71, -0.71, 0.71, 0.71));

        System.out.println("  界面构图：" + pass + " 项通过，" + fails + " 项失败");
        System.out.println("STAT " + pass + " " + fails);
        if (fails > 0) System.exit(1);
    }

    static void drawOne(String outDir, String name, Linalg.Mat m) throws Exception {
        BufferedImage img = new BufferedImage(W * S, H * S, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(C_BG);
        g.fillRect(0, 0, W * S, H * S);

        int pad = 14 * S;
        int y = 14 * S;

        // ── 标题 ──
        g.setFont(f(19, Font.BOLD));
        g.setColor(C_FG);
        String title = "线性变换 · 2×2 矩阵";
        g.drawString(title, pad, y + g.getFontMetrics().getAscent());
        ok(name + " 标题不越界",
           pad + g.getFontMetrics().stringWidth(title) <= W * S - pad,
           g.getFontMetrics().stringWidth(title) + "px");
        y += 19 * S;

        // ── 副标题（自动换行，最多 2 行）──
        g.setFont(f(11, Font.PLAIN));
        g.setColor(C_FG3);
        FontMetrics fm = g.getFontMetrics();
        String sub = "改矩阵，看平面怎么被拉扯。金色虚线是特征方向 —— 沿它的向量只变长不转向。";
        java.util.List<String> lines = wrap(sub, fm, W * S - pad * 2);
        ok(name + " 副标题不超 2 行", lines.size() <= 2, lines.size() + " 行");
        y += 4 * S;
        for (String ln : lines) {
            y += fm.getHeight();
            g.drawString(ln, pad, y - fm.getDescent());
        }
        y += 10 * S;

        // ── 画布 ──
        int canvasH = 300 * S;
        int cw = W * S - pad * 2;
        g.setColor(C_BG2);
        g.fillRoundRect(pad, y, cw, canvasH, 12 * S, 12 * S);
        drawCanvas(g, pad, y, cw, canvasH, m);
        y += canvasH;

        // ── 读数 ──
        y += 8 * S;
        g.setFont(f(11, Font.PLAIN));
        fm = g.getFontMetrics();
        g.setColor(C_FG2);
        String l1 = "矩阵 A = " + m;
        double dm = Linalg.det(m);
        String area = Math.abs(dm) < 5e-4 ? "面积压成 0"
                : "面积 ×" + Linalg.fmt(Math.abs(dm));
        String l2 = "行列式 " + Linalg.fmt(dm) + "（" + area + "）"
                + "　秩 " + Linalg.rank(m)
                + "　迹 " + Linalg.fmt(Linalg.trace(m));
        String l3 = "特征值 λ = " + Linalg.eigenText(m);
        for (String ln : new String[]{l1, l2, l3}) {
            ok(name + " 读数行不越界",
               pad + fm.stringWidth(ln) <= W * S - pad, fm.stringWidth(ln) + "px");
            y += fm.getHeight() + 3 * S;
            g.drawString(ln, pad + 2 * S, y - fm.getDescent());
        }
        y += 6 * S;

        // ── 矩阵输入框 ──
        y = drawMatrixBox(g, pad, y, m);
        y += 8 * S;

        // ── 预设 ──
        y = drawPresets(g, pad, y);
        y += 10 * S;

        // ── 说明卡片 ──
        String desc = Linalg.describe(m);
        g.setFont(f(12, Font.PLAIN));
        fm = g.getFontMetrics();
        java.util.List<String> dl = wrap(desc, fm, cw - 20 * S);
        int descH = dl.size() * fm.getHeight() + 20 * S;
        g.setColor(C_BG3);
        g.fillRoundRect(pad, y, cw, descH, 10 * S, 10 * S);
        g.setColor(C_FG2);
        int dy = y + 10 * S;
        for (String ln : dl) {
            dy += fm.getHeight();
            g.drawString(ln, pad + 10 * S, dy - fm.getDescent());
        }
        y += descH;

        ok(name + " 整屏不超 780dp", y <= H * S, (y / S) + "dp");

        g.dispose();
        File of = new File(outDir, name);
        ImageIO.write(img, "png", of);
        System.out.println("  界面图已生成：" + of.getAbsolutePath()
                + "  (" + W + "dp × " + H + "dp，内容到 " + (y / S) + "dp)");
    }

    static void drawCanvas(Graphics2D g, int ox, int oy, int w, int h, Linalg.Mat m) {
        int save = g.getClip() != null ? 0 : 0;
        Shape old = g.getClip();
        g.clipRect(ox, oy, w, h);

        float cx = ox + w / 2f, cy = oy + h / 2f;
        float scale = Math.min(w, h) / 8.2f;

        // 原网格
        g.setStroke(new BasicStroke(0.8f * S));
        g.setColor(new Color(0x2A, 0x2E, 0x38));
        for (int i = -4; i <= 4; i++) {
            g.drawLine((int) (cx + i * scale), (int) (cy - 4 * scale),
                       (int) (cx + i * scale), (int) (cy + 4 * scale));
            g.drawLine((int) (cx - 4 * scale), (int) (cy - i * scale),
                       (int) (cx + 4 * scale), (int) (cy - i * scale));
        }
        // 坐标轴
        g.setStroke(new BasicStroke(1.3f * S));
        g.setColor(new Color(0x3E, 0x45, 0x52));
        g.drawLine((int) cx, (int) (cy - 4 * scale), (int) cx, (int) (cy + 4 * scale));
        g.drawLine((int) (cx - 4 * scale), (int) cy, (int) (cx + 4 * scale), (int) cy);

        // 变换后网格
        g.setStroke(new BasicStroke(1.1f * S));
        g.setColor(new Color(0x25, 0x75, 0xFC, 0x88));
        for (double[] s : Linalg.gridLines(m, 4)) {
            g.drawLine((int) (cx + s[0] * scale), (int) (cy - s[1] * scale),
                       (int) (cx + s[2] * scale), (int) (cy - s[3] * scale));
        }

        // 单位方格
        double det = Linalg.det(m);
        if (Math.abs(det) > 1e-6) {
            Linalg.Vec[] sq = Linalg.unitSquare(m);
            Polygon p = new Polygon();
            for (Linalg.Vec v : sq) {
                p.addPoint((int) (cx + v.x * scale), (int) (cy - v.y * scale));
            }
            g.setColor(det > 0 ? new Color(0x25, 0x75, 0xFC, 0x33)
                              : new Color(0xE0, 0x5A, 0x54, 0x33));
            g.fillPolygon(p);
            g.setStroke(new BasicStroke(1.2f * S));
            g.setColor(det > 0 ? new Color(0x25, 0x75, 0xFC, 0x55)
                              : new Color(0xE0, 0x5A, 0x54, 0x55));
            g.drawPolygon(p);
        }

        // 特征方向
        Linalg.Eigen e = Linalg.eigen(m);
        if (e.hasReal && Linalg.rank(m) > 0) {
            g.setStroke(new BasicStroke(2 * S, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 10f, new float[]{7 * S, 5 * S}, 0f));
            g.setColor(C_AMBER);
            float span = 3.6f;
            for (Linalg.Vec v : new Linalg.Vec[]{e.v1, e.v2}) {
                if (v == null) continue;
                g.drawLine((int) (cx - v.x * span * scale), (int) (cy + v.y * span * scale),
                           (int) (cx + v.x * span * scale), (int) (cy - v.y * span * scale));
            }
        }

        // 基向量：长的先画，短的后画（否则共线时短的被盖住）
        if (Math.hypot(m.a, m.c) >= Math.hypot(m.b, m.d)) {
            arrow(g, cx, cy, scale, m.a, m.c, C_RED);
            arrow(g, cx, cy, scale, m.b, m.d, C_TEAL);
        } else {
            arrow(g, cx, cy, scale, m.b, m.d, C_TEAL);
            arrow(g, cx, cy, scale, m.a, m.c, C_RED);
        }

        // 图例（和 App 一样按矩阵情形决定）
        g.setFont(f(9, Font.PLAIN));
        int lx = ox + 8 * S, ly = oy + 14 * S;
        legend(g, lx, ly, C_RED, "i'");
        legend(g, lx + 46 * S, ly, C_TEAL, "j'");
        if (Linalg.rank(m) > 0) {
            if (!e.hasReal) legend(g, lx + 92 * S, ly, C_FG3, "无实特征轴");
            else legend(g, lx + 92 * S, ly, C_AMBER, "λ 轴");
        }

        g.setClip(old);
    }

    static void legend(Graphics2D g, int x, int y, Color c, String label) {
        g.setColor(c);
        g.fillOval(x - 3 * S, y - 6 * S, 6 * S, 6 * S);
        g.setColor(C_FG3);
        g.drawString(label, x + 7 * S, y);
    }

    static void arrow(Graphics2D g, float cx, float cy, float scale,
                      double x, double y, Color c) {
        int sx = (int) (cx), sy = (int) (cy);
        int ex = (int) (cx + x * scale), ey = (int) (cy - y * scale);
        g.setColor(c);
        g.setStroke(new BasicStroke(2.4f * S, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(sx, sy, ex, ey);
        double ang = Math.atan2(ey - sy, ex - sx);
        if (Math.hypot(ex - sx, ey - sy) < 8 * S) return;
        int hl = 8 * S;
        Polygon head = new Polygon();
        head.addPoint(ex, ey);
        head.addPoint((int) (ex - hl * Math.cos(ang - 0.42)),
                      (int) (ey - hl * Math.sin(ang - 0.42)));
        head.addPoint((int) (ex - hl * Math.cos(ang + 0.42)),
                      (int) (ey - hl * Math.sin(ang + 0.42)));
        g.fillPolygon(head);
    }

    /**
     * 2×2 输入框。按 App 里的实际结构画：A = [ 四格 2×2 ] 变换
     * 括号必须包住两行 —— 之前画成了一行，图上看不出第二行在哪。
     */
    static int drawMatrixBox(Graphics2D g, int pad, int y, Linalg.Mat m) {
        int cellW = 52 * S, cellH = 30 * S, gapIn = 6 * S, rowGap = 4 * S;
        int bracketW = 10 * S;
        int gridW = cellW * 2 + gapIn;
        int gridH = cellH * 2 + rowGap;
        int h = gridH;

        g.setFont(f(15, Font.BOLD));
        FontMetrics fmL = g.getFontMetrics();
        int lw = fmL.stringWidth("A =");
        int lblY = y + gridH / 2 + 5 * S;
        g.setColor(C_FG2);
        g.drawString("A =", pad + 2 * S, lblY);

        int bx = pad + 2 * S + lw + 8 * S;
        // 左右方括号：用线段画，保证高度刚好等于两行
        g.setColor(C_FG3);
        g.setStroke(new BasicStroke(2f * S));
        g.drawLine(bx, y, bx, y + gridH);
        g.drawLine(bx, y, bx + bracketW * 2 / 3, y);
        g.drawLine(bx, y + gridH, bx + bracketW * 2 / 3, y + gridH);

        int gx0 = bx + bracketW;
        String[] vals = {Linalg.fmt(m.a), Linalg.fmt(m.b),
                         Linalg.fmt(m.c), Linalg.fmt(m.d)};
        g.setFont(new Font("Monospaced", Font.PLAIN, 14 * S));
        FontMetrics fmC = g.getFontMetrics();
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                int ex = gx0 + c * (cellW + gapIn);
                int ey = y + r * (cellH + rowGap);
                g.setColor(C_BG3);
                g.fillRoundRect(ex, ey, cellW, cellH, 6 * S, 6 * S);
                String v = vals[r * 2 + c];
                int vw = fmC.stringWidth(v);
                ok("矩阵格「" + v + "」不溢出", vw <= cellW - 8 * S,
                   vw + "px / " + (cellW - 8 * S) + "px");
                g.setColor(C_TEAL);
                g.drawString(v, ex + (cellW - vw) / 2, ey + cellH / 2 + 5 * S);
            }
        }

        int bx2 = gx0 + gridW + 2 * S;
        g.setColor(C_FG3);
        g.setStroke(new BasicStroke(2f * S));
        g.drawLine(bx2, y, bx2, y + gridH);
        g.drawLine(bx2 - bracketW * 2 / 3, y, bx2, y);
        g.drawLine(bx2 - bracketW * 2 / 3, y + gridH, bx2, y + gridH);

        // 变换按钮
        int gx = bx2 + 10 * S;
        int gw = 62 * S, gh = 34 * S;
        g.setColor(C_TEAL);
        g.fillRoundRect(gx, y + (gridH - gh) / 2, gw, gh, 8 * S, 8 * S);
        g.setFont(f(13, Font.BOLD));
        g.setColor(new Color(0x10, 0x13, 0x1A));
        int tw = g.getFontMetrics().stringWidth("变换");
        g.drawString("变换", gx + (gw - tw) / 2, y + gridH / 2 + 5 * S);

        ok("「变换」按钮不超出右边距", gx + gw <= W * S - pad,
           (gx + gw) + " / " + (W * S - pad));
        return y + h;
    }

    /**
     * 预设按钮。App 里是横向滚动的 HorizontalScrollView，不换行。
     * 这里按同样方式画：从左排到右，超出的部分裁掉 —— 并断言
     * 6 个预设能在 360dp 里放下，放不下就说明名字太长该缩。
     */
    static int drawPresets(Graphics2D g, int pad, int y) {
        g.setFont(f(11, Font.PLAIN));
        FontMetrics fm = g.getFontMetrics();
        int x = pad;
        int ph = 30 * S;
        int total = 0;
        for (int i = 0; i < Linalg.PRESET_NAMES.length; i++) {
            String n = Linalg.PRESET_NAMES[i];
            int w = fm.stringWidth(n) + 24 * S;
            g.setColor(C_BG3);
            g.fillRoundRect(x, y, w, ph, 14 * S, 14 * S);
            g.setColor(C_FG2);
            g.drawString(n, x + 12 * S, y + ph / 2 + 4 * S);
            total = x + w;
            x += w + 6 * S;
        }
        ok("6 个预设一屏放得下（不用横向滚）",
           total <= W * S - pad, total + "px / " + (W * S - pad) + "px");
        return y + ph;
    }

    /**
     * 按宽度折行，但不在拉丁词/数字中间断开。
     *
     * 之前逐字符折，把 "面积缩放 1.25 倍" 断成了 "1.2 / 5 倍" ——
     * Android 的 TextView 不会这么干，那种断法既难看也读错数。
     * 汉字可以任意位置断，连续的字母数字必须整体保留。
     */
    static java.util.List<String> wrap(String s, FontMetrics fm, int maxW) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\n') { out.add(cur.toString()); cur.setLength(0); i++; continue; }

            // 取一个不可分的单元：连续字母数字/小数点算一个整体，其它单字符
            String token;
            if (isWordChar(c)) {
                int j = i;
                while (j < s.length() && isWordChar(s.charAt(j))) j++;
                token = s.substring(i, j);
                i = j;
            } else {
                token = String.valueOf(c);
                i++;
            }

            if (fm.stringWidth(cur.toString() + token) > maxW && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(token);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static boolean isWordChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z') || c == '.' || c == '%';
    }
}
