package com.zsb.plan;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/**
 * 函数图像画布。
 *
 * 关键决定（每一条都有踩坑理由）：
 *  · 所有几何计算交给 PlotGeom（纯 Java），这里只负责"把点变成线"
 *    → 电脑上能跑同一套逻辑做审计，不必开模拟器
 *  · 曲线按段画 Path，段与段之间 moveTo 而不是 lineTo
 *    → 1/x、tan(x) 在渐近线处不会被连成一条竖线
 *  · 采样密度按屏幕宽度算（每像素一个点），放大时自动加密
 *    → 否则放大后 sin(10x) 会变成锯齿
 *  · 触摸用矩阵式缩放（绕双指中心），不是简单 scale()
 *  · 坐标刻度文字用 PlotGeom.tickLabel 去掉浮点噪声（不出现 0.30000000000004）
 */
public class PlotView extends View {

    private static final int C_BG = 0xFF101319;
    private static final int C_GRID = 0xFF20242E;
    private static final int C_AXIS = 0xFF4A5364;
    private static final int C_TICK = 0xFF6B7282;
    private static final int C_CURVE = 0xFF3ECFAF;
    private static final int C_DERIV = 0xFFE0A94A;
    private static final int C_ROOT = 0xFFE05A54;
    private static final int C_TANGENT = 0xFF7C6CF0;

    private final PlotGeom.View view = new PlotGeom.View();
    private final Paint pGrid = new Paint();
    private final Paint pAxis = new Paint();
    private final Paint pCurve = new Paint();
    private final Paint pDeriv = new Paint();
    private final Paint pTick = new Paint();
    private final Paint pRoot = new Paint();
    private final Paint pTangent = new Paint();

    private Expr.Node f;        // 当前函数
    private Expr.Node df;       // 它的导数（自动算，可关闭显示）
    private boolean showDeriv = false;
    private boolean showTangent = false;

    // 触摸状态
    private float lastX, lastY;
    private boolean dragging = false;
    private double pinchBase = 0;
    private double pinchCx, pinchCy;

    /** 单指长按/点按选中 x，用于显示切线。回调给外部显示数值。 */
    public interface OnPick {
        void onPick(double x, double y, double slope);
    }

    private OnPick onPick;

    public PlotView(Context c) { this(c, null); }

    public PlotView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(C_BG);
        pGrid.setColor(C_GRID);
        pGrid.setStrokeWidth(dp(1));
        pGrid.setStyle(Paint.Style.STROKE);

        pAxis.setColor(C_AXIS);
        pAxis.setStrokeWidth(dp(1.2f));
        pAxis.setStyle(Paint.Style.STROKE);

        pCurve.setColor(C_CURVE);
        pCurve.setStrokeWidth(dp(2.2f));
        pCurve.setStyle(Paint.Style.STROKE);
        pCurve.setAntiAlias(true);
        pCurve.setStrokeJoin(Paint.Join.ROUND);
        pCurve.setStrokeCap(Paint.Cap.ROUND);

        pDeriv.setColor(C_DERIV);
        pDeriv.setStrokeWidth(dp(1.4f));
        pDeriv.setStyle(Paint.Style.STROKE);
        pDeriv.setAntiAlias(true);
        pDeriv.setPathEffect(new android.graphics.DashPathEffect(
                new float[]{dp(6), dp(4)}, 0));

        pTick.setColor(C_TICK);
        pTick.setTextSize(dp(10));
        pTick.setAntiAlias(true);
        pTick.setTypeface(Typeface.MONOSPACE);

        pRoot.setColor(C_ROOT);
        pRoot.setStyle(Paint.Style.FILL);
        pRoot.setAntiAlias(true);

        pTangent.setColor(C_TANGENT);
        pTangent.setStrokeWidth(dp(1.6f));
        pTangent.setStyle(Paint.Style.STROKE);
        pTangent.setAntiAlias(true);
    }

    public void setOnPick(OnPick o) { this.onPick = o; }

    public PlotGeom.View window() { return view; }

    /** 设置函数。传 null 表示清空。返回错误文案，成功返回 null。 */
    public String setExpr(String text) {
        if (text == null || text.trim().isEmpty()) {
            f = null; df = null; invalidate();
            return null;
        }
        try {
            Expr.Node n = Expr.parse(text);
            f = n;
            try { df = Expr.diff(n); } catch (Exception e) { df = null; }
            double[] yr = PlotGeom.autoYRange(n, view.xMin, view.xMax, 400);
            view.yMin = yr[0];
            view.yMax = yr[1];
            view.makeSquare();
            invalidate();
            return null;
        } catch (Expr.ExprError e) {
            return e.getMessage();
        } catch (Exception e) {
            return "表达式看不懂：" + e.getClass().getSimpleName();
        }
    }

    public void setShowDeriv(boolean b) { showDeriv = b; invalidate(); }
    public void setShowTangent(boolean b) { showTangent = b; invalidate(); }
    public boolean isShowDeriv() { return showDeriv; }
    public boolean isShowTangent() { return showTangent; }
    public Expr.Node expr() { return f; }
    public Expr.Node deriv() { return df; }

    public void zoomBy(double factor) {
        view.zoom((view.xMin + view.xMax) / 2, (view.yMin + view.yMax) / 2, factor);
        view.makeSquare();
        invalidate();
    }

    public void resetView() {
        view.xMin = -6; view.xMax = 6; view.yMin = -4; view.yMax = 4;
        if (f != null) {
            double[] yr = PlotGeom.autoYRange(f, -6, 6, 400);
            view.yMin = yr[0]; view.yMax = yr[1];
        }
        view.makeSquare();
        invalidate();
    }

    /** 精确跳到一个 x 位置（结果面板的"看某点"用）。 */
    public void centerOn(double x) {
        double half = view.spanX() / 2;
        view.xMin = x - half;
        view.xMax = x + half;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0 && h > 0) {
            view.w = w;
            view.h = h;
            if (f == null) {
                view.xMin = -6; view.xMax = 6; view.yMin = -4; view.yMax = 4;
                view.makeSquare();
            } else {
                view.makeSquare();
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (view.w <= 0 || view.h <= 0) return;

        drawGrid(c);
        if (showDeriv && df != null) drawCurve(c, df, pDeriv, 1);
        if (f != null) drawCurve(c, f, pCurve, 1);
        if (f != null) drawRoots(c);
        if (showTangent && f != null) drawTangent(c);
        drawAxisLabels(c);
    }

    // ── 网格与坐标轴 ──────────────────────────────────────
    private void drawGrid(Canvas c) {
        double stepX = PlotGeom.niceStep(view.spanX(), view.w, dp(72));
        double stepY = PlotGeom.niceStep(view.spanY(), view.h, dp(64));

        // 竖线
        double x0 = Math.ceil(view.xMin / stepX) * stepX;
        for (double x = x0; x <= view.xMax + stepX * 1e-9; x += stepX) {
            float px = (float) view.px(x);
            c.drawLine(px, 0, px, view.h, isZeroLine(x, stepX) ? pAxis : pGrid);
        }
        // 横线
        double y0 = Math.ceil(view.yMin / stepY) * stepY;
        for (double y = y0; y <= view.yMax + stepY * 1e-9; y += stepY) {
            float py = (float) view.py(y);
            c.drawLine(0, py, view.w, py, isZeroLine(y, stepY) ? pAxis : pGrid);
        }
    }

    private boolean isZeroLine(double v, double step) {
        return Math.abs(v) < step * 1e-6;
    }

    private void drawAxisLabels(Canvas c) {
        double stepX = PlotGeom.niceStep(view.spanX(), view.w, dp(72));
        double stepY = PlotGeom.niceStep(view.spanY(), view.h, dp(64));

        float axisY = (float) view.py(0);
        if (axisY < 0 || axisY > view.h) axisY = view.h - dp(4);
        float axisX = (float) view.px(0);
        if (axisX < 0 || axisX > view.w) axisX = dp(40);

        double x0 = Math.ceil(view.xMin / stepX) * stepX;
        for (double x = x0; x <= view.xMax + stepX * 1e-9; x += stepX) {
            if (isZeroLine(x, stepX)) continue;
            float px = (float) view.px(x);
            String t = PlotGeom.tickLabel(x, stepX);
            pTick.setTextAlign(Paint.Align.CENTER);
            c.drawText(t, px, axisY + dp(14), pTick);
        }

        double y0 = Math.ceil(view.yMin / stepY) * stepY;
        for (double y = y0; y <= view.yMax + stepY * 1e-9; y += stepY) {
            if (isZeroLine(y, stepY)) continue;
            float py = (float) view.py(y);
            String t = PlotGeom.tickLabel(y, stepY);
            pTick.setTextAlign(Paint.Align.LEFT);
            c.drawText(t, axisX + dp(4), py + dp(4), pTick);
        }
        // 原点
        pTick.setTextAlign(Paint.Align.RIGHT);
        c.drawText("O", axisX - dp(3), axisY + dp(13), pTick);
    }

    // ── 曲线 ──────────────────────────────────────────────
    /**
     * 画曲线。必须把画布边框裁死。
     *
     * 为什么：tan(x) 靠近渐近线时，采样点会跑到纵向几百倍画布外。
     * 不裁的话 Canvas 仍会把这些线画出去 —— 在控件内部看不出来，
     * 但只要控件不是全屏（对话框、卡片、截图），
     * 就会在相邻区域看到莫名其妙的竖线。这是视觉审计抓到的真 bug。
     */
    private void drawCurve(Canvas c, Expr.Node n, Paint p, int stepPx) {
        List<PlotGeom.Seg> segs = PlotGeom.sample(n, view, stepPx);
        int save = c.save();
        c.clipRect(0, 0, view.w, view.h);
        for (PlotGeom.Seg s : segs) {
            Path path = new Path();
            boolean started = false;
            for (int i = 0; i < s.pts.size(); i++) {
                double mx = s.pts.get(i)[0], my = s.pts.get(i)[1];
                float px = (float) view.px(mx);
                float py = (float) view.py(my);
                // 横向也裁：点必须在 x 范围内
                if (px < -view.w || px > view.w * 2) {
                    started = false;
                    continue;
                }
                // 纵向裁到画布外合理倍数内：超出部分没信息，还会拖慢绘制。
                // 这里用 1 倍而不是 2 倍 —— 超出一个画布高度就已经画不出可见内容了。
                if (py < -view.h || py > view.h * 2) {
                    started = false;
                    continue;
                }
                if (!started) { path.moveTo(px, py); started = true; }
                else path.lineTo(px, py);
            }
            c.drawPath(path, p);
        }
        c.restoreToCount(save);
    }

    private void drawRoots(Canvas c) {
        List<Double> rs = PlotGeom.roots(f, view, 800);
        for (Double r : rs) {
            float px = (float) view.px(r);
            float py = (float) view.py(0);
            c.drawCircle(px, py, dp(3.4f), pRoot);
        }
    }

    private void drawTangent(Canvas c) {
        if (tangentX == null) return;
        double x = tangentX;
        double y = PlotGeom.safeEval(f, x);
        double k = PlotGeom.slope(f, x);
        if (!PlotGeom.isFinite(y) || !PlotGeom.isFinite(k)) return;
        // 在可视范围内画切线
        double xa = view.xMin, xb = view.xMax;
        double ya = y + k * (xa - x);
        double yb = y + k * (xb - x);
        c.drawLine((float) view.px(xa), (float) view.py(ya),
                (float) view.px(xb), (float) view.py(yb), pTangent);
        c.drawCircle((float) view.px(x), (float) view.py(y), dp(4), pRoot);
    }

    private Double tangentX = null;

    public Double tangentX() { return tangentX; }

    public void setTangentX(Double x) {
        tangentX = x;
        invalidate();
    }

    // ── 手势 ──────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY();
                dragging = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() >= 2) {
                    double d = spacing(e);
                    if (pinchBase > 0) {
                        double factor = pinchBase / d;
                        if (factor < 0.5) factor = 0.5;
                        if (factor > 2) factor = 2;
                        view.zoom(pinchCx, pinchCy, factor);
                        view.makeSquare();
                    }
                    pinchBase = d;
                    invalidate();
                } else if (dragging) {
                    double dxMath = view.toX(0) - view.toX(lastX - e.getX());
                    double dyMath = view.toY(0) - view.toY(lastY - e.getY());
                    // 用像素差直接换算，避免 toX(0) 这类边界值带来的偏差
                    dxMath = -(e.getX() - lastX) * view.spanX() / view.w;
                    dyMath = (e.getY() - lastY) * view.spanY() / view.h;
                    view.pan(dxMath, dyMath);
                    lastX = e.getX(); lastY = e.getY();
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (e.getPointerCount() >= 2) {
                    pinchBase = spacing(e);
                    pinchCx = view.toX((e.getX(0) + e.getX(1)) / 2);
                    pinchCy = view.toY((e.getY(0) + e.getY(1)) / 2);
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                pinchBase = 0;
                dragging = false;
                return true;
            case MotionEvent.ACTION_UP:
                dragging = false;
                pinchBase = 0;
                if (showTangent && f != null) {
                    double x = view.toX(e.getX());
                    tangentX = x;
                    if (onPick != null) {
                        onPick.onPick(x, PlotGeom.safeEval(f, x), PlotGeom.slope(f, x));
                    }
                    invalidate();
                }
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private double spacing(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return Math.max(1, Math.sqrt(dx * dx + dy * dy));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
