package com.zsb.plan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.List;

/**
 * 对照表的缩略曲线。用 PlotGeom 真采样，不是手画的示意图 ——
 * 用户看到的就是"这个式子在 App 里画出来的样子"。
 *
 * 只负责画一小格，不管点击（点击在卡片上）。
 */
public class MiniPlot extends View {

    private Expr.Node node;
    private PlotGeom.View geom;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public MiniPlot(Context c) {
        super(c);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1.7f));
        line.setStrokeCap(Paint.Cap.ROUND);
        axis.setStyle(Paint.Style.STROKE);
        axis.setStrokeWidth(dp(1f));
        axis.setColor(0x33383F4C);
    }

    public void set(Expr.Node n, int color) {
        node = n;
        line.setColor(color);
        requestLayout();
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        if (w <= 0) w = (int) dp(74);
        if (h <= 0) h = (int) dp(52);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (node == null) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 缩略图必须自己裁边界。
        //
        // View 默认不会裁同伴：exp(x)、x^2 这类曲线在小窗口里很快就
        // 冲出顶部，不裁的话会画到隔壁卡片上 —— 审计渲染图里
        // 抛物线和指数线直接压到了下一格的文字上。
        c.save();
        c.clipRect(0, 0, w, h);

        // 每格都重新建 View：范围固定，才能横向比较形状
        geom = new PlotGeom.View(-6, 6, -4, 4, w, h);

        // 坐标轴
        float ax = (float) geom.px(0), ay = (float) geom.py(0);
        if (ay >= 0 && ay <= h) c.drawLine(0, ay, w, ay, axis);
        if (ax >= 0 && ax <= w) c.drawLine(ax, 0, ax, h, axis);

        // 曲线
        List<PlotGeom.Seg> segs = PlotGeom.sample(node, geom, 1);
        for (PlotGeom.Seg s : segs) {
            path.reset();
            boolean started = false;
            for (int i = 0; i < s.pts.size(); i++) {
                double[] p = s.pts.get(i);
                float x = (float) geom.px(p[0]);
                float y = (float) geom.py(p[1]);
                // 冲出画面太多的点直接跳过，否则长直线会画到格子外面
                if (Math.abs(y) > h * 6) { started = false; continue; }
                if (!started) { path.moveTo(x, y); started = true; }
                else path.lineTo(x, y);
            }
            c.drawPath(path, line);
        }
        c.restore();
    }
}
