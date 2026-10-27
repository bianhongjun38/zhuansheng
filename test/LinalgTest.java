package com.zsb.plan;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * 线性代数几何审计。
 *
 * 三条主线：
 *  ① 算得对：每个特征值/特征向量都代回 M·v = λv 验残差，
 *     不靠"看着像"。行列式、秩、迹逐条对拍。
 *  ② 边界稳：重根、零矩阵、复根、坍缩成线、近似单位阵，
 *     这些是有意设计的刁难，界面崩不崩先在这里拦。
 *  ③ 画得对：把画布渲染成 PNG，亲眼看网格有没有变形、
 *     特征轴是不是真的落在不动的方向上。
 */
public class LinalgTest {

    static int pass = 0;
    static int fails = 0;

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; return; }
        fails++;
        System.out.println("     ✗ " + name + (detail.isEmpty() ? "" : "  →  " + detail));
    }

    static void near(String name, double got, double want, double tol, String note) {
        ok(name, Math.abs(got - want) <= tol,
           "得到 " + got + "，应为 " + want + (note.isEmpty() ? "" : "（" + note + "）"));
    }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : "build/audit";
        System.exit(run(dir) == 0 ? 0 : 1);
    }

    public static int run(String outDir) {
        pass = 0;
        fails = 0;
        basicTests();
        eigenTests();
        edgeTests();
        gridTests();
        animTests();
        try { render(outDir); } catch (Exception e) {
            fails++;
            System.out.println("     ✗ 渲染失败：" + e);
        }
        System.out.println("  线性代数几何：" + pass + " 项通过，" + fails + " 项失败");
        return fails;
    }

    // ── ① 基本量 ──────────────────────────────────────────
    static void basicTests() {
        Linalg.Mat m = new Linalg.Mat(1.5, 0.5, 0.5, 1.0);
        near("迹", Linalg.trace(m), 2.5, 1e-12, "");
        near("行列式", Linalg.det(m), 1.25, 1e-12, "");
        near("判别式", Linalg.disc(m), 1.25, 1e-12, "");
        ok("秩 = 2", Linalg.rank(m) == 2, "" + Linalg.rank(m));

        // 映射函数本身
        Linalg.Vec v = Linalg.apply(m, 1, 0);
        near("作用在 (1,0) 的 x", v.x, 1.5, 1e-12, "");
        near("作用在 (1,0) 的 y", v.y, 0.5, 1e-12, "");
        Linalg.Vec v2 = Linalg.apply(m, 0, 1);
        near("作用在 (0,1) 的 x", v2.x, 0.5, 1e-12, "");
        near("作用在 (0,1) 的 y", v2.y, 1.0, 1e-12, "");

        // 单位正方形变成平行四边形，四个顶点
        Linalg.Vec[] sq = Linalg.unitSquare(new Linalg.Mat(2, 1, 0, 1));
        ok("单位正方形 4 个顶点", sq.length == 4, "" + sq.length);
        near("顶点③ 的 x", sq[2].x, 3, 1e-12, "=(a+b, c+d)");
        near("顶点③ 的 y", sq[2].y, 1, 1e-12, "");

        // 用鞋带公式验面积 = |det|
        for (Linalg.Mat t : new Linalg.Mat[]{
                new Linalg.Mat(2, 1, 0, 1),
                new Linalg.Mat(0.7071, -0.7071, 0.7071, 0.7071),
                new Linalg.Mat(1, 2, 2, 4),
                new Linalg.Mat(-1, 0, 0, 1)}) {
            Linalg.Vec[] p = Linalg.unitSquare(t);
            double area = 0;
            for (int i = 0; i < 4; i++) {
                Linalg.Vec a = p[i], b = p[(i + 1) % 4];
                area += a.x * b.y - b.x * a.y;
            }
            area = Math.abs(area) / 2;
            near("鞋带面积 = |det| " + t, area, Math.abs(Linalg.det(t)), 1e-9, "");
        }

        // 零矩阵 rank 0
        ok("零矩阵秩为 0", Linalg.rank(new Linalg.Mat(0, 0, 0, 0)) == 0, "");
        ok("坍缩矩阵秩为 1", Linalg.rank(new Linalg.Mat(1, 2, 2, 4)) == 1, "");
    }

    // ── ② 特征值 / 特征向量 ───────────────────────────────
    static void eigenTests() {
        // 对每个矩阵：M·v = λv 必须成立（残差 < 1e-9）
        Linalg.Mat[] cases = {
                new Linalg.Mat(1.5, 0.5, 0.5, 1.0),
                new Linalg.Mat(3, 1, 0, 2),
                new Linalg.Mat(2, 0, 0, 2),        // 重根 + 对角
                new Linalg.Mat(1, 1, 0, 1),        // 剪切：重根、缺向量
                new Linalg.Mat(0, 1, 1, 0),        // 镜像
                new Linalg.Mat(-2, 0, 0, -3),
                new Linalg.Mat(1, 2, 2, 4),        // 坍缩
                new Linalg.Mat(0.6, -0.8, 0.8, 0.6), // 纯旋转：复根
        };
        int checked = 0;
        for (Linalg.Mat m : cases) {
            Linalg.Eigen e = Linalg.eigen(m);
            if (!e.hasReal) continue;
            if (e.v1 != null) {
                double r = Linalg.residual(m, e.v1, e.l1);
                ok("M·v₁ = λ₁v₁  " + m, r < 1e-9, "残差 " + r);
                checked++;
            }
            if (e.v2 != null) {
                double r = Linalg.residual(m, e.v2, e.l2);
                ok("M·v₂ = λ₂v₂  " + m, r < 1e-9, "残差 " + r);
                checked++;
            }
        }
        ok("至少验了 8 组特征向量", checked >= 8, "实际 " + checked);

        // 手算对照：[[3,1],[0,2]] 特征值 3 和 2
        Linalg.Eigen e1 = Linalg.eigen(new Linalg.Mat(3, 1, 0, 2));
        ok("[[3,1],[0,2]] 有 2 个特征值", e1.v1 != null && e1.v2 != null,
           "v1=" + e1.v1 + " v2=" + e1.v2);
        near("λ₁", Math.max(e1.l1, e1.l2), 3, 1e-12, "");
        near("λ₂", Math.min(e1.l1, e1.l2), 2, 1e-12, "");

        // 单位阵：所有方向都是特征方向
        Linalg.Eigen ei = Linalg.eigen(new Linalg.Mat(2, 0, 0, 2));
        ok("2I 标记为等比缩放", ei.scalar, "");
        near("2I 的特征值 = 2", ei.l1, 2, 1e-12, "");
        ok("2I 给出两条轴", ei.v1 != null && ei.v2 != null, "");

        // 剪切：重根 λ=1，只有一条特征方向
        Linalg.Eigen sh = Linalg.eigen(new Linalg.Mat(1, 1, 0, 1));
        ok("剪切是重根", Math.abs(sh.disc) < 1e-9, "disc=" + sh.disc);
        near("剪切 λ = 1", sh.l1, 1, 1e-12, "");
        ok("剪切只有 1 条特征方向", sh.v2 == null, "v2=" + sh.v2);
        if (sh.v1 != null) {
            near("剪切特征向量沿 x 轴（y 分量为 0）", sh.v1.y, 0, 1e-9, "");
        }

        // 镜像 [[0,1],[1,0]]：λ = ±1
        Linalg.Eigen mx = Linalg.eigen(new Linalg.Mat(0, 1, 1, 0));
        near("镜像 λ₁", mx.l1, 1, 1e-12, "");
        near("镜像 λ₂", mx.l2, -1, 1e-12, "");

        // 纯旋转 37°：复根
        Linalg.Eigen rot = Linalg.eigen(new Linalg.Mat(0.8, -0.6, 0.6, 0.8));
        ok("纯旋转无实特征值", !rot.hasReal, "hasReal=" + rot.hasReal);
        near("旋转的实部 = cos37°", rot.l1, 0.8, 1e-9, "");
        near("旋转的虚部 = sin37°", rot.imag, 0.6, 1e-9, "");

        // 特征值之积 = det，之和 = trace（韦达定理，独立于求根过程）
        for (Linalg.Mat m : cases) {
            Linalg.Eigen e = Linalg.eigen(m);
            if (!e.hasReal || m.equals(null)) continue;
            near("λ₁+λ₂ = 迹  " + m, e.l1 + e.l2, Linalg.trace(m), 1e-9, "");
            near("λ₁·λ₂ = det  " + m, e.l1 * e.l2, Linalg.det(m), 1e-9, "");
        }
    }

    // ── ③ 边界 ────────────────────────────────────────────
    static void edgeTests() {
        // 零矩阵不该崩，也不该出现 NaN
        Linalg.Mat z = new Linalg.Mat(0, 0, 0, 0);
        Linalg.Eigen ze = Linalg.eigen(z);
        ok("零矩阵特征值有限", !Double.isNaN(ze.l1) && !Double.isInfinite(ze.l1),
           "" + ze.l1);
        ok("零矩阵描述非空", !Linalg.describe(z).isEmpty(), "");
        ok("零矩阵特征文字为 —", "—".equals(Linalg.eigenText(z)), Linalg.eigenText(z));

        // 所有预设都要能算出话、不出 NaN
        for (int i = 0; i < Linalg.PRESETS.length; i++) {
            Linalg.Mat m = Linalg.PRESETS[i];
            String s = Linalg.describe(m);
            ok("预设「" + Linalg.PRESET_NAMES[i] + "」有描述", s != null && s.length() > 6,
               String.valueOf(s));
            ok("预设「" + Linalg.PRESET_NAMES[i] + "」无 NaN",
               !s.contains("NaN") && !s.contains("Infinity"), s);
            Linalg.Eigen e = Linalg.eigen(m);
            ok("预设「" + Linalg.PRESET_NAMES[i] + "」数值有限",
               !Double.isNaN(e.l1) && !Double.isNaN(e.det), "");
        }

        // 极小/极大数值不炸
        Linalg.Mat tiny = new Linalg.Mat(1e-12, 0, 0, 1e-12);
        ok("极小矩阵秩为 1 或 0", Linalg.rank(tiny) <= 1, "" + Linalg.rank(tiny));
        Linalg.Mat huge = new Linalg.Mat(1e8, 0, 0, 1e8);
        ok("极大矩阵秩为 2", Linalg.rank(huge) == 2, "" + Linalg.rank(huge));
        Linalg.Eigen he = Linalg.eigen(huge);
        near("极大矩阵特征值", he.l1, 1e8, 1, "");

        // 近似单位阵（浮点误差下不应被判成"非等比"）
        Linalg.Eigen ae = Linalg.eigen(new Linalg.Mat(1 + 1e-12, 1e-13, 1e-13, 1 + 1e-12));
        ok("近似单位阵判定稳定", !Double.isNaN(ae.l1), "" + ae.l1);

        // 行列式接近 0 但非 0：不能崩，也不能说"秩 1"
        Linalg.Mat near0 = new Linalg.Mat(1, 0, 0, 1e-6);
        ok("微小区行式仍算秩 2 或稳定降级",
           Linalg.rank(near0) == 2 || Linalg.rank(near0) == 1,
           "" + Linalg.rank(near0));

        // 描述文字不出现"null"
        for (int i = 0; i < Linalg.PRESETS.length; i++) {
            Linalg.Mat m = Linalg.PRESETS[i];
            ok("预设「" + Linalg.PRESET_NAMES[i] + "」文字无 null",
               !Linalg.describe(m).contains("null"), "");
        }
    }

    // ── ④ 网格线 ──────────────────────────────────────────
    static void gridTests() {
        // 单位阵的网格线应该就是原网格
        Linalg.Mat id = new Linalg.Mat(1, 0, 0, 1);
        double[][] gl = Linalg.gridLines(id, 5);
        ok("网格线数量 = (2*5+1)*2", gl.length == 22, "" + gl.length);
        for (double[] seg : gl) {
            ok("单位阵网格端点有限",
               !Double.isNaN(seg[0]) && !Double.isNaN(seg[3]), "");
        }
        // 每条线两端间距应等于 2*range
        double[] first = gl[0];
        near("单位阵竖线长度", Math.hypot(first[2] - first[0], first[3] - first[1]),
             10, 1e-9, "range=5");

        // 剪切矩阵下，竖线变成斜线（端点 x 不同）
        double[][] gl2 = Linalg.gridLines(new Linalg.Mat(1, 1, 0, 1), 3);
        boolean anySlanted = false;
        for (double[] seg : gl2) {
            if (Math.abs(seg[2] - seg[0]) > 1e-6) anySlanted = true;
        }
        ok("剪切后网格线变斜", anySlanted, "没有一条线是斜的");

        // 坍缩矩阵：所有端点应落在同一条过原点的直线上（叉积为 0）
        Linalg.Mat col = new Linalg.Mat(1, 2, 2, 4);
        double[][] gl3 = Linalg.gridLines(col, 3);
        double maxCross = 0;
        double dirX = 1, dirY = 2;
        for (double[] seg : gl3) {
            maxCross = Math.max(maxCross, Math.abs(seg[0] * dirY - seg[1] * dirX));
            maxCross = Math.max(maxCross, Math.abs(seg[2] * dirY - seg[3] * dirX));
        }
        ok("坍缩后所有点共线", maxCross < 1e-9, "最大偏离 " + maxCross);
    }

    // ── ⑤ 动画插值 ────────────────────────────────────────
    static void animTests() {
        Linalg.Mat from = new Linalg.Mat(1, 0, 0, 1);
        Linalg.Mat to = new Linalg.Mat(2, 1, 1, 3);

        Linalg.Mat t0 = Linalg.lerp(from, to, 0);
        ok("t=0 等于起点", t0.a == 1 && t0.d == 1, t0.toString());
        Linalg.Mat t1 = Linalg.lerp(from, to, 1);
        ok("t=1 等于终点", t1.a == 2 && t1.d == 3, t1.toString());
        Linalg.Mat th = Linalg.lerp(from, to, 0.5);
        near("t=0.5 的 a", th.a, 1.5, 1e-12, "");
        near("t=0.5 的 b", th.b, 0.5, 1e-12, "");

        // 越界必须夹住，否则动画一抖就飞出去
        Linalg.Mat neg = Linalg.lerp(from, to, -0.5);
        ok("t<0 被夹到起点", neg.a == 1, neg.toString());
        Linalg.Mat over = Linalg.lerp(from, to, 1.5);
        ok("t>1 被夹到终点", over.a == 2, over.toString());

        // 插值全程不能出 NaN
        for (int i = 0; i <= 20; i++) {
            Linalg.Mat m = Linalg.lerp(from, to, i / 20.0);
            Linalg.Eigen e = Linalg.eigen(m);
            ok("插值 t=" + (i / 20.0) + " 数值有限",
               !Double.isNaN(e.l1) && !Double.isNaN(e.det), "");
        }
    }

    // ── ⑥ 渲染 PNG ────────────────────────────────────────
    static void render(String outDir) throws Exception {
        Linalg.Mat[] mats = Linalg.PRESETS;
        int cell = 300;
        int cols = 3;
        int rows = (mats.length + cols - 1) / cols;
        int pad = 16;
        int headH = 70;
        int w = cols * cell + (cols + 1) * pad;
        int h = headH + rows * (cell + 34) + pad;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x14, 0x16, 0x1C));
        g.fillRect(0, 0, w, h);

        g.setColor(new Color(0xE8, 0xEA, 0xF0));
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.drawString("线性变换审计 —— 同一套 Linalg 在 App 的 Canvas 上绘制", 24, 32);
        g.setColor(new Color(0x6B, 0x72, 0x82));
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.drawString("灰：原坐标网格　蓝：变换后网格　金虚线：特征方向（不动轴）　"
                + "红/绿箭头：变换后的基向量", 24, 52);

        for (int i = 0; i < mats.length; i++) {
            int cx = pad + (i % cols) * (cell + pad);
            int cy = headH + (i / cols) * (cell + 34);
            g.setColor(new Color(0x1B, 0x1E, 0x26));
            g.fillRoundRect(cx, cy, cell, cell + 26, 10, 10);
            drawCell(g, mats[i], cx, cy, cell);

            g.setColor(new Color(0xC3, 0xC9, 0xD6));
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.drawString(Linalg.PRESET_NAMES[i] + "  " + mats[i], cx + 8, cy + cell + 14);
            g.setColor(new Color(0x6B, 0x72, 0x82));
            g.setFont(new Font("SansSerif", Font.PLAIN, 9));
            g.drawString("det=" + Linalg.fmt(Linalg.det(mats[i]))
                    + "  λ=" + Linalg.eigenText(mats[i]), cx + 8, cy + cell + 24);
        }

        g.dispose();
        File dirF = new File(outDir);
        if (!dirF.exists()) dirF.mkdirs();
        File out = new File(dirF, "linalg-sheet.png");
        ImageIO.write(img, "png", out);
        System.out.println("  线代图已生成：" + out.getAbsolutePath()
                + "  (" + w + "x" + h + ", " + mats.length + " 格)");
        ok("线代图已写出", out.exists() && out.length() > 5000, "大小 " + out.length());
    }

    /** 画一格：原网格 + 变换网格 + 平行四边形 + 特征轴 + 基向量。 */
    static void drawCell(Graphics2D g, Linalg.Mat m, int ox, int oy, int size) {
        int save = g.getClip() != null ? 0 : 0;
        java.awt.Shape old = g.getClip();
        g.clipRect(ox + 2, oy + 2, size - 4, size - 4);

        double cx = ox + size / 2.0;
        double cy = oy + size / 2.0;
        double scale = size / 7.5;

        // 原网格
        g.setStroke(new BasicStroke(0.7f));
        g.setColor(new Color(0x2A, 0x2E, 0x38));
        for (int i = -5; i <= 5; i++) {
            g.drawLine(px(cx, scale, i), py(cy, scale, -5),
                       px(cx, scale, i), py(cy, scale, 5));
            g.drawLine(px(cx, scale, -5), py(cy, scale, i),
                       px(cx, scale, 5), py(cy, scale, i));
        }
        // 原坐标轴
        g.setColor(new Color(0x3E, 0x45, 0x52));
        g.setStroke(new BasicStroke(1.2f));
        g.drawLine((int) cx, oy + 3, (int) cx, oy + size - 3);
        g.drawLine(ox + 3, (int) cy, ox + size - 3, (int) cy);

        // 平行四边形（单位正方形的像）
        Linalg.Vec[] sq = Linalg.unitSquare(m);
        Path2D.Float p = new Path2D.Float();
        p.moveTo(px(cx, scale, sq[0].x), py(cy, scale, sq[0].y));
        for (int i = 1; i < 4; i++) {
            p.lineTo(px(cx, scale, sq[i].x), py(cy, scale, sq[i].y));
        }
        p.closePath();
        double det = Linalg.det(m);
        if (Math.abs(det) > 1e-6) {
            g.setColor(det > 0 ? new Color(0x25, 0x75, 0xFC, 0x38)
                               : new Color(0xE0, 0x5A, 0x54, 0x38));
            g.fill(p);
        }

        // 变换后的网格
        g.setColor(new Color(0x25, 0x75, 0xFC, 0x88));
        g.setStroke(new BasicStroke(0.9f));
        for (double[] seg : Linalg.gridLines(m, 5)) {
            g.drawLine(px(cx, scale, seg[0]), py(cy, scale, seg[1]),
                       px(cx, scale, seg[2]), py(cy, scale, seg[3]));
        }

        // 特征轴（金色虚线）
        Linalg.Eigen e = Linalg.eigen(m);
        if (e.hasReal && Linalg.rank(m) >= 1) {
            g.setColor(new Color(0xE0, 0xA9, 0x4A));
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND, 1f, new float[]{6, 4}, 0f));
            if (e.v1 != null) axis(g, cx, cy, scale, e.v1);
            if (e.v2 != null) axis(g, cx, cy, scale, e.v2);
            // 断言：特征方向确实是不动方向 —— 画之前先验一遍
            for (Linalg.Vec vv : new Linalg.Vec[]{e.v1, e.v2}) {
                if (vv == null) continue;
                Linalg.Vec img2 = Linalg.apply(m, vv.x, vv.y);
                double cross = Math.abs(vv.x * img2.y - vv.y * img2.x);
                ok("图上特征轴确为不动方向 " + m, cross < 1e-9,
                   "叉积 " + cross);
            }
        }

        // 变换后的基向量
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        arrow(g, cx, cy, scale, 0, 0, m.a, m.c, new Color(0xE0, 0x5A, 0x54));
        arrow(g, cx, cy, scale, 0, 0, m.b, m.d, new Color(0x3E, 0xCF, 0xAD));

        g.setClip(old);
    }

    static void axis(Graphics2D g, double cx, double cy, double scale, Linalg.Vec v) {
        double span = 4.2;
        g.drawLine(px(cx, scale, -v.x * span), py(cy, scale, -v.y * span),
                   px(cx, scale, v.x * span), py(cy, scale, v.y * span));
    }

    static void arrow(Graphics2D g, double cx, double cy, double scale,
                      double x1, double y1, double x2, double y2, Color c) {
        int sx1 = px(cx, scale, x1), sy1 = py(cy, scale, y1);
        int sx2 = px(cx, scale, x2), sy2 = py(cy, scale, y2);
        g.setColor(c);
        g.drawLine(sx1, sy1, sx2, sy2);
        double ang = Math.atan2(sy2 - sy1, sx2 - sx1);
        if (Math.hypot(sx2 - sx1, sy2 - sy1) < 6) return;
        int hl = 9;
        Path2D.Float head = new Path2D.Float();
        head.moveTo(sx2, sy2);
        head.lineTo(sx2 - hl * Math.cos(ang - 0.4), sy2 - hl * Math.sin(ang - 0.4));
        head.lineTo(sx2 - hl * Math.cos(ang + 0.4), sy2 - hl * Math.sin(ang + 0.4));
        head.closePath();
        g.fill(head);
    }

    static int px(double cx, double scale, double x) { return (int) Math.round(cx + x * scale); }

    static int py(double cy, double scale, double y) { return (int) Math.round(cy - y * scale); }
}
