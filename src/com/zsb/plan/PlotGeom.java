package com.zsb.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * 绘图几何 —— 纯数字，不碰 Android。
 *
 * 为什么要单独抽出来：
 *   画函数图像最容易出的错不是"画不出来"，而是
 *   ① y 轴范围没算好，曲线全被裁掉  ② 渐近线两侧被连成一条竖线
 *   ③ 高倍缩放时采样点不够，光滑曲线变锯齿
 *   这些都能在电脑上用数字检查，不必装模拟器。verify.py 的 MathTest 就在查这些。
 *
 * 坐标约定：
 *   数学坐标 (x, y) → 画布像素 (px, py)，px 向右增，py 向下增
 *   view 对象里保存的是数学窗口 [xMin,xMax] × [yMin,yMax]
 */
public class PlotGeom {

    /** 数学窗口 + 画布尺寸。 */
    public static class View {
        public double xMin = -6, xMax = 6, yMin = -4, yMax = 4;
        public int w = 1080, h = 900;

        public View() {}

        public View(double xMin, double xMax, double yMin, double yMax, int w, int h) {
            this.xMin = xMin; this.xMax = xMax;
            this.yMin = yMin; this.yMax = yMax;
            this.w = w; this.h = h;
        }

        public double spanX() { return xMax - xMin; }
        public double spanY() { return yMax - yMin; }

        public double px(double x) {
            return (x - xMin) / (xMax - xMin) * w;
        }

        public double py(double y) {
            return (yMax - y) / (yMax - yMin) * h;
        }

        public double toX(double pxv) { return xMin + pxv / w * (xMax - xMin); }
        public double toY(double pyv) { return yMax - pyv / h * (yMax - yMin); }

        /** 等比：让 1 个数学单位在横竖两个方向长度相同（圆看起来才是圆的）。 */
        public void makeSquare() {
            double cx = (xMin + xMax) / 2, cy = (yMin + yMax) / 2;
            double unitX = spanX() / w, unitY = spanY() / h;
            double u = Math.max(unitX, unitY);
            double hx = u * w / 2, hy = u * h / 2;
            xMin = cx - hx; xMax = cx + hx;
            yMin = cy - hy; yMax = cy + hy;
        }

        /** 以 (cx,cy) 为中心按 factor 缩放；factor<1 放大。 */
        public void zoom(double cx, double cy, double factor) {
            xMin = cx + (xMin - cx) * factor;
            xMax = cx + (xMax - cx) * factor;
            yMin = cy + (yMin - cy) * factor;
            yMax = cy + (yMax - cy) * factor;
        }

        public void pan(double dxMath, double dyMath) {
            xMin += dxMath; xMax += dxMath;
            yMin += dyMath; yMax += dyMath;
        }

        public View copy() { return new View(xMin, xMax, yMin, yMax, w, h); }
    }

    /**
     * 一段连续曲线。断点（无定义 / 跳到无穷 / 陡到不像话）会切成多段，
     * 这样 1/x 在 x=0 两侧不会被一条竖线连起来。
     */
    public static class Seg {
        public final List<double[]> pts = new ArrayList<double[]>();  // 每项 {x, y}
    }

    /** 一行求值结果，null 意义是"这里没有点"。 */
    public static double safeEval(Expr.Node n, double x) {
        try { return Expr.eval(n, x); } catch (Exception e) { return Double.NaN; }
    }

    /**
     * 采样 + 断点切分。
     *
     * @param n     表达式
     * @param v     窗口
     * @param stepPx 每隔几个像素取一个样（1 = 每像素，2 = 隔一个）
     * @return 若干段；每段点已经按 x 递增
     *
     * 断点判定（三条都要）：
     *   ① 值非有限（NaN/±Inf）
     *   ② |y| 超出窗口 40 倍（画出来等于一条竖线，无信息）
     *   ③ 相邻两点 |Δy| > 窗口高度 × 8 且 |Δx| 很小 —— 典型渐近线穿越
     *      注意 tan(x) 这类是「真跳」，而 sin(1/x) 在 0 附近是「抖」，
     *      两者都以断点处理更安全：宁可断开，不要假连线。
     */
    public static List<Seg> sample(Expr.Node n, View v, int stepPx) {
        if (stepPx < 1) stepPx = 1;
        List<Seg> out = new ArrayList<Seg>();
        Seg cur = null;
        double limit = Math.max(Math.abs(v.yMin), Math.abs(v.yMax)) * 40 + 1e6 * 0;
        double prevX = Double.NaN, prevY = Double.NaN;
        int steps = Math.max(2, v.w / stepPx);
        for (int i = 0; i <= steps; i++) {
            double x = v.toX((double) i * v.w / steps);
            double y = safeEval(n, x);
            boolean bad = !isFinite(y) || Math.abs(y) > limit;
            if (bad) {
                cur = null;
                prevX = x; prevY = Double.NaN;
                continue;
            }
            if (cur != null && isFinite(prevY)) {
                double dy = Math.abs(y - prevY);
                double dx = Math.abs(x - prevX);
                if (dy > v.spanY() * 8 && dx < v.spanX() * 0.02) {
                    cur = null;   // 疑似穿过渐近线，断开
                }
            }
            if (cur == null) { cur = new Seg(); out.add(cur); }
            cur.pts.add(new double[]{x, y});
            prevX = x; prevY = y;
        }
        // 丢掉只有一个点的碎段（画不出线，只会在屏幕上点一个像素）
        List<Seg> keep = new ArrayList<Seg>();
        for (Seg s : out) if (s.pts.size() >= 2) keep.add(s);
        return keep;
    }

    public static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    /**
     * 自动选 y 范围：先用 [-4,4]，再看曲线实际落在哪。
     * 只统计「窗口内、连续性合理」的值，避免 1/x 在 0 附近把范围撑到无穷。
     */
    public static double[] autoYRange(Expr.Node n, double xMin, double xMax, int samples) {
        if (samples < 16) samples = 16;
        double[] vs = new double[samples + 1];
        int cnt = 0;
        for (int i = 0; i <= samples; i++) {
            double x = xMin + (xMax - xMin) * i / samples;
            double y = safeEval(n, x);
            if (isFinite(y) && Math.abs(y) < 1e6) vs[cnt++] = y;
        }
        if (cnt == 0) return new double[]{-4, 4};
        double[] a = new double[cnt];
        System.arraycopy(vs, 0, a, 0, cnt);
        java.util.Arrays.sort(a);
        // 用 5% / 95% 分位，而不是极值：极值往往是渐近线附近的离群点
        double lo = a[(int) Math.floor(cnt * 0.05)];
        double hi = a[(int) Math.floor(cnt * 0.95)];
        if (!isFinite(lo) || !isFinite(hi)) return new double[]{-4, 4};
        if (hi - lo < 1e-9) { lo -= 2; hi += 2; }
        double pad = (hi - lo) * 0.35;
        lo -= pad; hi += pad;
        // 至少留 2 个单位高度，免得平函数被放大成一条噪声
        double mid = (lo + hi) / 2;
        if (hi - lo < 4) { lo = mid - 2; hi = mid + 2; }
        // 极端情况兜底
        if (hi - lo > 4000) { mid = (lo + hi) / 2; lo = mid - 2000; hi = mid + 2000; }
        return new double[]{lo, hi};
    }

    /**
     * 选「好看的刻度间隔」：1、2、5 × 10^k，目标是每格约 targetPx 像素。
     * 网格不贴这个规则会出现 0.33333 这种刻度，没法读。
     */
    public static double niceStep(double span, int px, double targetPx) {
        if (span <= 0 || px <= 0) return 1;
        double raw = span / px * targetPx;         // 期望的每格数学长度
        if (raw <= 0 || !isFinite(raw)) return 1;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double nice;
        if (norm <= 1) nice = 1;
        else if (norm <= 2) nice = 2;
        else if (norm <= 5) nice = 5;
        else nice = 10;
        return nice * mag;
    }

    /** 刻度文字：避免 0.30000000000000004 这种浮点噪声。 */
    public static String tickLabel(double v, double step) {
        if (Math.abs(v) < step * 1e-6) return "0";
        int dec = 0;
        double s = step;
        while (dec < 6 && Math.abs(s - Math.rint(s)) > 1e-9) { s *= 10; dec++; }
        String t = String.format(java.util.Locale.US, "%." + dec + "f", v);
        if (t.contains(".")) {
            while (t.endsWith("0")) t = t.substring(0, t.length() - 1);
            if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /**
     * 某条水平线是否落在窗口里（画 x 轴 / 网格线用），返回像素 y 或 NaN。
     * 用 NaN 而不是 -1 当"没有"：像素坐标可能真的等于 -1。
     */
    public static double pyOf(View v, double y) {
        if (y < Math.min(v.yMin, v.yMax) || y > Math.max(v.yMin, v.yMax)) return Double.NaN;
        return v.py(y);
    }

    public static double pxOf(View v, double x) {
        if (x < v.xMin || x > v.xMax) return Double.NaN;
        return v.px(x);
    }

    /** 求某点切线斜率（数值中心差分），用于「切线」显示。 */
    public static double slope(Expr.Node n, double x) {
        double h = 1e-6 * Math.max(1, Math.abs(x));
        double y1 = safeEval(n, x + h), y0 = safeEval(n, x - h);
        if (!isFinite(y1) || !isFinite(y0)) return Double.NaN;
        return (y1 - y0) / (2 * h);
    }

    /**
     * 找窗口内的零点（变号法 + 二分细化）。
     * 用来在图上标「根」，比只画线有用得多。
     */
    public static List<Double> roots(Expr.Node n, View v, int samples) {
        List<Double> out = new ArrayList<Double>();
        if (samples < 32) samples = 32;
        double px0 = Double.NaN;
        double py0 = Double.NaN;
        for (int i = 0; i <= samples; i++) {
            double x = v.xMin + v.spanX() * i / samples;
            double y = safeEval(n, x);
            if (!isFinite(y)) { px0 = Double.NaN; continue; }
            if (Math.abs(y) < 1e-12) { addRoot(out, x); px0 = x; py0 = y; continue; }
            if (isFinite(py0) && (py0 > 0) != (y > 0)) {
                double lo = px0, hi = x;
                for (int k = 0; k < 60; k++) {
                    double mid = (lo + hi) / 2;
                    double ym = safeEval(n, mid);
                    if (!isFinite(ym)) break;
                    if ((ym > 0) == (py0 > 0)) lo = mid;
                    else hi = mid;
                }
                addRoot(out, (lo + hi) / 2);
            }
            px0 = x; py0 = y;
        }
        return out;
    }

    static void addRoot(List<Double> out, double x) {
        for (Double d : out) if (Math.abs(d - x) < 1e-6) return;
        out.add(x);
    }
}
