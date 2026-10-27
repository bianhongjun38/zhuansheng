package com.zsb.plan;

/**
 * 2×2 矩阵的几何。纯 Java，不碰 Android。
 *
 * 为什么单独一个类：
 *   特征值/特征向量这类东西，界面上"画出来像不像"和"算得对不对"是两回事。
 *   画错一条轴看起来只是"位置有点怪"，学生不会发现自己在学错东西。
 *   所以计算全部放在这里，电脑上逐条断言 M·v = λv，界面只负责画。
 *
 * Gemini 给的公式方向是对的（迹 T、行列式 D、判别式 T²−4D、求根公式），
 * 但它的 computeEigen 有两个真问题，这里都改了（见 eigen() 注释）。
 */
public class Linalg {

    public static final double EPS = 1e-9;

    // ── 矩阵 ──────────────────────────────────────────────
    /** [[a, b], [c, d]]。列向量约定：e1=(a,c)，e2=(b,d)。 */
    public static class Mat {
        public double a, b, c, d;

        public Mat(double a, double b, double c, double d) {
            this.a = a; this.b = b; this.c = c; this.d = d;
        }

        public Mat copy() { return new Mat(a, b, c, d); }

        @Override public String toString() {
            return "[" + fmt(a) + ", " + fmt(b) + "; " + fmt(c) + ", " + fmt(d) + "]";
        }
    }

    /** 二维向量。 */
    public static class Vec {
        public double x, y;

        public Vec(double x, double y) { this.x = x; this.y = y; }

        public double len() { return Math.hypot(x, y); }

        public Vec norm() {
            double l = len();
            return l < EPS ? new Vec(0, 0) : new Vec(x / l, y / l);
        }

        @Override public String toString() { return "(" + fmt(x) + ", " + fmt(y) + ")"; }
    }

    /** 特征信息。 */
    public static class Eigen {
        public boolean hasReal;      // 有无实特征值
        public double trace, det, disc;
        public double l1, l2;        // 实特征值（无实根时是实部）
        public double imag;          // 无实根时的虚部
        public Vec v1, v2;           // 单位特征向量（可能为 null）
        public boolean scalar;       // 单位阵的倍数：整个平面都是特征方向
    }

    // ── 基本量 ────────────────────────────────────────────
    public static double trace(Mat m) { return m.a + m.d; }

    public static double det(Mat m) { return m.a * m.d - m.b * m.c; }

    /** 判别式 T² − 4D。>0 两个不同实根，=0 重根，<0 一对共轭复根。 */
    public static double disc(Mat m) {
        double t = trace(m);
        return t * t - 4 * det(m);
    }

    /** 秩：0 / 1 / 2。 */
    public static int rank(Mat m) {
        // 先看是不是零矩阵
        if (Math.abs(m.a) < 1e-9 && Math.abs(m.b) < 1e-9
                && Math.abs(m.c) < 1e-9 && Math.abs(m.d) < 1e-9) return 0;
        // 行列式非零 → 满秩
        if (Math.abs(det(m)) > 1e-9) return 2;
        return 1;
    }

    /** 作用在点上。这是映射函数本身 —— 不用 Canvas 矩阵变形，就靠它。 */
    public static Vec apply(Mat m, double x, double y) {
        return new Vec(m.a * x + m.b * y, m.c * x + m.d * y);
    }

    /** 单位正方形的像的面积（带符号）。 */
    public static double signedArea(Mat m) { return det(m); }

    // ── 特征值 / 特征向量 ─────────────────────────────────
    /**
     * 2×2 特征分解，直接用求根公式，不需要 QR 迭代。
     *
     * Gemini 版本的两个问题，这里改掉：
     *  ① 它是用 eps=1e-5 判 disc<−eps 才认为"无实根"。判别式恰好为 0
     *    （重根，例如剪切矩阵）时它会走实根分支，但随后用
     *    |l1−l2|>1e-3 判断"要不要算第二个向量"，重根情况下第二个向量
     *    悄悄消失 —— 界面上看起来像"只有一个特征方向"，学生记错。
     *     这里改成显式区分三种情况（两不同实根 / 重根 / 复根）。
     *  ② 它的 getVec 在 (a−λ, b) 与 (λ−d, c) 都接近零时返回 null，
     *    但没考虑"整个平面都是特征方向"（单位阵倍数）。此时任意向量
     *    都是特征向量，正确做法是给出两条坐标轴而不是空手而归。
     */
    public static Eigen eigen(Mat m) {
        Eigen e = new Eigen();
        e.trace = trace(m);
        e.det = det(m);
        e.disc = disc(m);

        double t = e.trace;
        double d = e.disc;

        // 整个平面都是特征方向：A = kI
        if (Math.abs(m.b) < 1e-9 && Math.abs(m.c) < 1e-9
                && Math.abs(m.a - m.d) < 1e-9) {
            e.hasReal = true;
            e.scalar = true;
            e.l1 = m.a;
            e.l2 = m.a;
            e.v1 = new Vec(1, 0);
            e.v2 = new Vec(0, 1);
            return e;
        }

        if (d < -1e-9) {
            // 一对共轭复根：没有实的不动直线（会把平面转起来）
            e.hasReal = false;
            e.l1 = t / 2;
            e.l2 = t / 2;
            e.imag = Math.sqrt(-d) / 2;
            return e;
        }

        e.hasReal = true;
        double sq = Math.sqrt(Math.max(0, d));
        e.l1 = (t + sq) / 2;
        e.l2 = (t - sq) / 2;

        e.v1 = eigenVec(m, e.l1);
        // 重根只看一个方向；两不同根才找第二个
        if (sq > 1e-9) e.v2 = eigenVec(m, e.l2);
        else e.v2 = null;
        return e;
    }

    /**
     * 求 λ 对应的单位特征向量。
     * 解 (A − λI)v = 0。取法向量 (b, λ−a) 或 (λ−d, c)，哪个不退化用哪个。
     */
    public static Vec eigenVec(Mat m, double lambda) {
        double bx = m.b, by = lambda - m.a;
        if (Math.hypot(bx, by) > 1e-9) return new Vec(bx, by).norm();

        double cx = lambda - m.d, cy = m.c;
        if (Math.hypot(cx, cy) > 1e-9) return new Vec(cx, cy).norm();

        return null;
    }

    /** 校验：M·v 是否等于 λ·v（给审计用，也让界面能自检）。 */
    public static double residual(Mat m, Vec v, double lambda) {
        Vec mv = apply(m, v.x, v.y);
        return Math.hypot(mv.x - lambda * v.x, mv.y - lambda * v.y);
    }

    // ── 几何描述 ──────────────────────────────────────────
    /**
     * 一句话讲清这个矩阵干了什么。
     * 按 rank → 复根 → det 正负 → 一般，四层分。
     */
    public static String describe(Mat m) {
        int r = rank(m);
        double D = det(m);
        Eigen e = eigen(m);

        if (r == 0) {
            return "零矩阵：整个平面被压到原点，什么都剩不下。";
        }
        if (r == 1) {
            return "行列式为 0：平面被压成一条直线，面积归零，"
                    + "信息丢了（不可逆）。";
        }
        if (!e.hasReal) {
            return String.format(
                    "判别式 %.2f < 0：没有实数特征方向。"
                    + "这个变换把平面「转」了一下，"
                    + "每条直线都被拧走了，面积缩放 %.2f 倍。",
                    e.disc, Math.abs(D));
        }
        if (e.scalar) {
            return String.format(
                    "这是一个「等比缩放」：所有方向都是特征方向，"
                    + "每条向量都被拉长到 %.2f 倍，方向完全不变。", e.l1);
        }
        if (D < 0) {
            return String.format(
                    "行列式为 %.2f < 0：空间被「翻了个面」（镜像），"
                    + "面积缩放 %.2f 倍。有 2 条不动直线"
                    + "（一条上的向量反向，一条不变）。", D, Math.abs(D));
        }
        if (Math.abs(e.disc) < 1e-9) {
            return String.format(
                    "判别式 = 0（重根 λ = %.2f）：只有 1 条特征方向，"
                    + "沿它不转向，其它方向都被拧过去。"
                    + "面积缩放 %.2f 倍。典型的「剪切」。", e.l1, D);
        }
        return String.format(
                "行列式为 %.2f > 0，方向不变（没有翻转），"
                + "面积缩放 %.2f 倍。有 2 条特征方向："
                + "沿它们的方向不变，只被拉长 λ₁ = %.2f / λ₂ = %.2f 倍。",
                D, D, e.l1, e.l2);
    }

    /** 给界面显示的特征值文字。 */
    public static String eigenText(Mat m) {
        Eigen e = eigen(m);
        if (rank(m) == 0) return "—";
        if (!e.hasReal) {
            return String.format("%.2f ± %.2fi（复根）", e.l1, e.imag);
        }
        if (e.scalar) return String.format("%.2f（所有方向）", e.l1);
        if (Math.abs(e.disc) < 1e-9) return String.format("%.2f（重根）", e.l1);
        return String.format("%.2f, %.2f", e.l1, e.l2);
    }

    // ── 网格线（供绘制，线段端点直接给屏幕无关的数学坐标）──
    /**
     * 变换后的网格线。返回 {x1,y1,x2,y2} 数组。
     * 每条原网格线（x=k 或 y=k）在变换后仍是一条直线，端点取两端即可。
     */
    public static double[][] gridLines(Mat m, int range) {
        int n = (range * 2 + 1) * 2;
        double[][] out = new double[n][];
        int k = 0;
        for (int i = -range; i <= range; i++) {
            // 原直线 x = i，y 从 -range 到 range
            Vec p1 = apply(m, i, -range);
            Vec p2 = apply(m, i, range);
            out[k++] = new double[]{p1.x, p1.y, p2.x, p2.y};
            // 原直线 y = i
            Vec q1 = apply(m, -range, i);
            Vec q2 = apply(m, range, i);
            out[k++] = new double[]{q1.x, q1.y, q2.x, q2.y};
        }
        return out;
    }

    /** 单位正方形的像（平行四边形的四个顶点）。 */
    public static Vec[] unitSquare(Mat m) {
        return new Vec[]{
                new Vec(0, 0),
                apply(m, 1, 0),
                apply(m, 1, 1),
                apply(m, 0, 1)
        };
    }

    // ── 动画：4 个参数线性插值 ────────────────────────────
    public static Mat lerp(Mat from, Mat to, double t) {
        double u = t < 0 ? 0 : (t > 1 ? 1 : t);
        return new Mat(
                from.a + (to.a - from.a) * u,
                from.b + (to.b - from.b) * u,
                from.c + (to.c - from.c) * u,
                from.d + (to.d - from.d) * u);
    }

    // ── 预设 ──────────────────────────────────────────────
    public static final Mat[] PRESETS = {
            new Mat(1.5, 0.5, 0.5, 1.0),   // 对称拉伸
            new Mat(1, 0, 0, 1),           // 单位阵
            new Mat(1, 1, 0, 1),           // 水平剪切
            new Mat(0.7071, -0.7071, 0.7071, 0.7071), // 旋转 45°
            new Mat(0, 1, 1, 0),           // 镜像翻面
            new Mat(1, 2, 2, 4),           // 坍缩成线
    };

    public static final String[] PRESET_NAMES = {
            "拉伸剪切", "不变", "剪切", "旋转45°", "镜像翻面", "压成一条线",
    };

    /** 数字格式化：整数不带小数点，其它保留两位。 */
    public static String fmt(double v) {
        if (Math.abs(v) < 5e-5) return "0";
        if (Math.abs(v - Math.rint(v)) < 5e-5) return String.valueOf((long) Math.rint(v));
        return String.format("%.2f", v);
    }
}
