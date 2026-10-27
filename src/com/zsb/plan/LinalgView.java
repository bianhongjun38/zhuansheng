package com.zsb.plan;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 线性变换 —— 2×2 矩阵把平面怎么拧。
 *
 * 三个关键决定：
 *  ① 不用 canvas.concat(Matrix) 整体变形。那样网格变了，但文字标签
 *    和线宽也跟着被拉扁，箭头会变成歪的扁片。这里用 Linalg.apply
 *    一行算出新坐标再画线，线宽和字始终是正的。
 *  ② 用 ValueAnimator 对 4 个参数线性插值（400ms）。点"变换"时
 *    看着方格一步步被拧歪，比直接跳出结果直观得多。
 *  ③ 特征向量用求根公式解析算（见 Linalg.eigen），不写迭代。
 */
public class LinalgView extends LinearLayout {

    // ── 配色（沿用全局深色调）────────────────────────────
    private static final int C_BG = 0xFF14161C;
    private static final int C_BG2 = 0xFF1B1E26;
    private static final int C_BG3 = 0xFF262A34;
    private static final int C_FG = 0xFFE8EAF0;
    private static final int C_FG2 = 0xFFC3C9D6;
    private static final int C_FG3 = 0xFF6B7282;
    private static final int C_TEAL = 0xFF3ECFAD;
    private static final int C_AMBER = 0xFFE0A94A;
    private static final int C_RED = 0xFFE05A54;
    private static final int C_BLUE = 0xFF2575FC;

    private final MainActivity act;
    private MatCanvas canvas;
    private EditText[] et = new EditText[4];
    private TextView tvDesc, tvInfo;
    private AlertDialog dlg;

    /** 当前 / 目标矩阵。动画在这两者之间插值。 */
    private Linalg.Mat cur = new Linalg.Mat(1.5, 0.5, 0.5, 1.0);
    private Linalg.Mat target = cur.copy();
    private android.animation.ValueAnimator anim;

    public LinalgView(MainActivity a) {
        super(a);
        this.act = a;
        setOrientation(VERTICAL);
        setBackgroundColor(C_BG);
        setPadding(dp(14), dp(14), dp(14), dp(14));
        build();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void build() {
        TextView title = new TextView(act);
        title.setText("线性变换 · 2×2 矩阵");
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_FG);
        addView(title);

        TextView sub = new TextView(act);
        sub.setText("改矩阵，看平面怎么被拉扯。金色虚线是特征方向 —— "
                + "沿它的向量只变长不转向。");
        sub.setTextSize(11.5f);
        sub.setTextColor(C_FG3);
        sub.setPadding(0, dp(4), 0, dp(10));
        addView(sub);

        // ── 画布 ──
        canvas = new MatCanvas(act);
        // 固定高度，不用 weight。这个 View 会被包进 ScrollView 打开，
        // 而 ScrollView 给子视图的高度上限是 UNSPECIFIED，
        // weight=1 配 height=0 在那种情况下会算出 0 高 —— 画布直接不可见。
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        clp.topMargin = dp(2);
        canvas.setLayoutParams(clp);
        GradientDrawable cg = new GradientDrawable();
        cg.setCornerRadius(dp(12));
        cg.setColor(C_BG2);
        canvas.setBackground(cg);
        addView(canvas);

        // ── 读数 ──
        tvInfo = new TextView(act);
        tvInfo.setTextSize(11.5f);
        tvInfo.setTextColor(C_FG2);
        tvInfo.setPadding(dp(2), dp(8), dp(2), dp(6));
        tvInfo.setLineSpacing(dp(3), 1f);
        addView(tvInfo);

        // ── 矩阵输入 ──
        addView(buildMatrixBox());

        // ── 预设 ──
        addView(buildPresets());

        // ── 说明 ──
        tvDesc = new TextView(act);
        tvDesc.setTextSize(12);
        tvDesc.setTextColor(C_FG2);
        tvDesc.setLineSpacing(dp(4), 1f);
        tvDesc.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable dg = new GradientDrawable();
        dg.setCornerRadius(dp(10));
        dg.setColor(C_BG3);
        tvDesc.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(10);
        tvDesc.setLayoutParams(dlp);
        addView(tvDesc);

        refresh(false);
    }

    /** 2×2 输入框，按矩阵的样子摆（两行两列 + 括号）。 */
    private View buildMatrixBox() {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(0, dp(6), 0, dp(2));

        TextView lb = new TextView(act);
        lb.setText("A =");
        lb.setTextSize(15);
        lb.setTypeface(Typeface.DEFAULT_BOLD);
        lb.setTextColor(C_FG2);
        lb.setPadding(dp(2), 0, dp(8), 0);
        wrap.addView(lb);

        // 左括号
        wrap.addView(bracket(true));

        LinearLayout grid = new LinearLayout(act);
        grid.setOrientation(VERTICAL);
        String[] init = {"1.5", "0.5", "0.5", "1"};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(HORIZONTAL);
            if (r == 1) {
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = dp(4);
                row.setLayoutParams(rlp);
            }
            for (int c = 0; c < 2; c++) {
                EditText e = new EditText(act);
                e.setText(init[r * 2 + c]);
                e.setTextSize(14);
                e.setTextColor(C_TEAL);
                e.setGravity(Gravity.CENTER);
                e.setSingleLine(true);
                e.setTypeface(Typeface.MONOSPACE);
                e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                        | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(6));
                g.setColor(C_BG3);
                e.setBackground(g);
                e.setPadding(dp(4), dp(5), dp(4), dp(5));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(30));
                lp.leftMargin = dp(3);
                lp.rightMargin = dp(3);
                e.setLayoutParams(lp);
                et[r * 2 + c] = e;
                row.addView(e);
            }
            grid.addView(row);
        }
        wrap.addView(grid);
        wrap.addView(bracket(false));

        TextView go = new TextView(act);
        go.setText("变换");
        go.setTextSize(13);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setTextColor(0xFF10131A);
        go.setGravity(Gravity.CENTER);
        GradientDrawable gg = new GradientDrawable();
        gg.setCornerRadius(dp(8));
        gg.setColor(C_TEAL);
        go.setBackground(gg);
        go.setPadding(dp(14), dp(9), dp(14), dp(9));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(10);
        go.setLayoutParams(glp);
        go.setOnClickListener(v -> {
            readInputs();
            hideIme();
            refresh(true);
        });
        wrap.addView(go);

        return wrap;
    }

    /** 画一个高的方括号 —— 用 TextView 拼，不借助图片资源。 */
    private View bracket(boolean left) {
        TextView t = new TextView(act);
        t.setText(left ? "[" : "]");
        t.setTextSize(30);
        t.setTextColor(C_FG3);
        t.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, 0);
        return t;
    }

    private View buildPresets() {
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        for (int i = 0; i < Linalg.PRESETS.length; i++) {
            final Linalg.Mat m = Linalg.PRESETS[i];
            TextView b = new TextView(act);
            b.setText(Linalg.PRESET_NAMES[i]);
            b.setTextSize(11.5f);
            b.setTextColor(C_FG2);
            b.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(14));
            g.setColor(C_BG3);
            b.setBackground(g);
            b.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                et[0].setText(Linalg.fmt(m.a));
                et[1].setText(Linalg.fmt(m.b));
                et[2].setText(Linalg.fmt(m.c));
                et[3].setText(Linalg.fmt(m.d));
                hideIme();
                readInputs();
                refresh(true);
            });
            row.addView(b);
        }
        hs.addView(row);
        return hs;
    }

    /** 从输入框读出目标矩阵。读不动就保留原值，不弹错。 */
    private void readInputs() {
        double[] v = new double[4];
        double[] old = {target.a, target.b, target.c, target.d};
        for (int i = 0; i < 4; i++) {
            v[i] = old[i];
            if (et[i] == null) continue;
            String s = et[i].getText().toString().trim();
            if (s.isEmpty()) continue;
            try { v[i] = Double.parseDouble(s); } catch (Exception ignore) { }
        }
        target = new Linalg.Mat(v[0], v[1], v[2], v[3]);
    }

    private void hideIme() {
        try {
            InputMethodManager im = (InputMethodManager)
                    act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null && et[0] != null) {
                im.hideSoftInputFromWindow(et[0].getWindowToken(), 0);
            }
        } catch (Exception ignore) { }
    }

    /** 刷新读数与说明；animated=true 时做 400ms 插值动画。 */
    private void refresh(boolean animated) {
        if (anim != null) anim.cancel();

        if (!animated) {
            cur = target.copy();
            applyState();
            return;
        }
        final Linalg.Mat from = cur.copy();
        final Linalg.Mat to = target.copy();
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(400);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            cur = Linalg.lerp(from, to, (float) a.getAnimatedValue());
            applyState();
        });
        anim.start();
    }

    private void applyState() {
        if (canvas != null) canvas.invalidate();
        Linalg.Mat m = cur;

        if (tvInfo != null) {
            double d = Linalg.det(m);
            // det=0 时说"面积 0 倍"是别扭的，那本来就是被压成线了
            String area = Math.abs(d) < 5e-4
                    ? "面积压成 0"
                    : "面积 ×" + Linalg.fmt(Math.abs(d));
            tvInfo.setText(
                    "矩阵 A = " + m + "\n"
                    + "行列式 " + Linalg.fmt(d) + "（" + area + "）"
                    + "　秩 " + Linalg.rank(m)
                    + "　迹 " + Linalg.fmt(Linalg.trace(m)) + "\n"
                    + "特征值 λ = " + Linalg.eigenText(m));
        }
        if (tvDesc != null) tvDesc.setText(Linalg.describe(m));
    }

    /** 打开这个工具（对话框形式，和 MathView 一致）。 */
    public void open() {
        if (dlg != null && dlg.isShowing()) return;
        ScrollView sv = new ScrollView(act);
        sv.setBackgroundColor(C_BG);
        LinalgView body = new LinalgView(act);
        sv.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        dlg = new AlertDialog.Builder(act, android.R.style.Theme_Material_NoActionBar)
                .setView(sv)
                .setNegativeButton("关闭", (d, w) -> d.dismiss())
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dlg.show();
    }

    // ── 画布 ──────────────────────────────────────────────
    /**
     * 自己的 View 而不是复用一个通用画布 —— 因为要画的东西
     * （网格、平行四边形、特征轴、基向量）和 PlotView 完全不同。
     *
     * 关键：所有坐标都经 Linalg.apply 算出"数学坐标"，再单独转屏幕像素。
     * 绝不用 canvas.concat，否则线宽和字号会跟着被拉扁。
     */
    private class MatCanvas extends View {
        private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pBlue = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pGold = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pRed = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pTeal = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        MatCanvas(Context c) {
            super(c);
            pGrid.setStyle(Paint.Style.STROKE);
            pGrid.setStrokeWidth(dp0(0.8f));
            pGrid.setColor(0xFF2A2E38);

            pAxis.setStyle(Paint.Style.STROKE);
            pAxis.setStrokeWidth(dp0(1.3f));
            pAxis.setColor(0xFF3E4552);

            pBlue.setStyle(Paint.Style.STROKE);
            pBlue.setStrokeWidth(dp0(1.1f));
            pBlue.setColor(0x882575FC);

            pFill.setStyle(Paint.Style.FILL);

            pGold.setStyle(Paint.Style.STROKE);
            pGold.setStrokeWidth(dp0(2f));
            pGold.setColor(C_AMBER);
            pGold.setPathEffect(new DashPathEffect(
                    new float[]{dp0(7), dp0(5)}, 0));

            pRed.setStyle(Paint.Style.STROKE);
            pRed.setStrokeWidth(dp0(2.4f));
            pRed.setStrokeCap(Paint.Cap.ROUND);
            pRed.setColor(C_RED);

            pTeal.setStyle(Paint.Style.STROKE);
            pTeal.setStrokeWidth(dp0(2.4f));
            pTeal.setStrokeCap(Paint.Cap.ROUND);
            pTeal.setColor(C_TEAL);

            pText.setColor(C_FG2);
            pText.setTextSize(dp0(9.5f));
        }

        private float dp0(float v) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    getResources().getDisplayMetrics());
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            float cx = w / 2f, cy = h / 2f;
            float scale = Math.min(w, h) / 8.2f;

            // 裁剪：变换后的网格可能画出界，不裁会花到旁边的控件上
            int save = c.save();
            c.clipRect(0, 0, w, h);

            drawGrid(c, cx, cy, scale);
            drawSquare(c, cx, cy, scale);
            drawTransformedGrid(c, cx, cy, scale);
            drawEigen(c, cx, cy, scale);
            drawBasis(c, cx, cy, scale);
            drawLegend(c);

            c.restoreToCount(save);
        }

        private void drawGrid(Canvas c, float cx, float cy, float scale) {
            for (int i = -4; i <= 4; i++) {
                c.drawLine(px(cx, scale, i), py(cy, scale, -4),
                           px(cx, scale, i), py(cy, scale, 4), pGrid);
                c.drawLine(px(cx, scale, -4), py(cy, scale, i),
                           px(cx, scale, 4), py(cy, scale, i), pGrid);
            }
            c.drawLine(cx, py(cy, scale, -4), cx, py(cy, scale, 4), pAxis);
            c.drawLine(px(cx, scale, -4), cy, px(cx, scale, 4), cy, pAxis);
        }

        private void drawSquare(Canvas c, float cx, float cy, float scale) {
            double det = Linalg.det(cur);
            if (Math.abs(det) < 1e-6) return;
            Linalg.Vec[] sq = Linalg.unitSquare(cur);
            path.reset();
            path.moveTo(px(cx, scale, sq[0].x), py(cy, scale, sq[0].y));
            for (int i = 1; i < 4; i++) {
                path.lineTo(px(cx, scale, sq[i].x), py(cy, scale, sq[i].y));
            }
            path.close();
            // 翻面（det<0）用红色，正常用蓝色 —— 一眼看出手性变了没有
            pFill.setColor(det > 0 ? 0x332575FC : 0x33E05A54);
            c.drawPath(path, pFill);
            pFill.setColor(det > 0 ? 0x552575FC : 0x55E05A54);
            pFill.setStyle(Paint.Style.STROKE);
            pFill.setStrokeWidth(dp0(1.2f));
            c.drawPath(path, pFill);
            pFill.setStyle(Paint.Style.FILL);
        }

        private void drawTransformedGrid(Canvas c, float cx, float cy, float scale) {
            for (double[] seg : Linalg.gridLines(cur, 4)) {
                c.drawLine(px(cx, scale, seg[0]), py(cy, scale, seg[1]),
                           px(cx, scale, seg[2]), py(cy, scale, seg[3]), pBlue);
            }
        }

        private void drawEigen(Canvas c, float cx, float cy, float scale) {
            Linalg.Eigen e = Linalg.eigen(cur);
            if (!e.hasReal || Linalg.rank(cur) == 0) return;
            float span = 3.6f;
            if (e.v1 != null) {
                c.drawLine(px(cx, scale, -e.v1.x * span), py(cy, scale, -e.v1.y * span),
                           px(cx, scale, e.v1.x * span), py(cy, scale, e.v1.y * span), pGold);
            }
            if (e.v2 != null) {
                c.drawLine(px(cx, scale, -e.v2.x * span), py(cy, scale, -e.v2.y * span),
                           px(cx, scale, e.v2.x * span), py(cy, scale, e.v2.y * span), pGold);
            }
        }

        /**
         * 两条基向量。长的先画、短的后画 —— 否则矩阵把两条基向量
         * 压到同一条方向时（比如 [1,2;2,4]），长的绿箭头会把短的
         * 红箭头整根盖住，看着像只有一条。
         */
        private void drawBasis(Canvas c, float cx, float cy, float scale) {
            double lenI = Math.hypot(cur.a, cur.c);
            double lenJ = Math.hypot(cur.b, cur.d);
            if (lenI >= lenJ) {
                arrow(c, cx, cy, scale, 0, 0, cur.a, cur.c, pRed);
                arrow(c, cx, cy, scale, 0, 0, cur.b, cur.d, pTeal);
            } else {
                arrow(c, cx, cy, scale, 0, 0, cur.b, cur.d, pTeal);
                arrow(c, cx, cy, scale, 0, 0, cur.a, cur.c, pRed);
            }
        }

        private void arrow(Canvas c, float cx, float cy, float scale,
                           double x1, double y1, double x2, double y2, Paint p) {
            float sx1 = px(cx, scale, x1), sy1 = py(cy, scale, y1);
            float sx2 = px(cx, scale, x2), sy2 = py(cy, scale, y2);
            c.drawLine(sx1, sy1, sx2, sy2, p);
            float dx = sx2 - sx1, dy = sy2 - sy1;
            if (Math.hypot(dx, dy) < dp0(8)) return;
            double ang = Math.atan2(dy, dx);
            float hl = dp0(8);
            Path head = new Path();
            head.moveTo(sx2, sy2);
            head.lineTo((float) (sx2 - hl * Math.cos(ang - 0.42)),
                        (float) (sy2 - hl * Math.sin(ang - 0.42)));
            head.lineTo((float) (sx2 - hl * Math.cos(ang + 0.42)),
                        (float) (sy2 - hl * Math.sin(ang + 0.42)));
            head.close();
            Paint fp = new Paint(p);
            fp.setStyle(Paint.Style.FILL);
            c.drawPath(head, fp);
        }

        /**
         * 左上角图例。文字是正着画的 —— 这就是不用 concat 的原因。
         *
         * 特征轴只在真有实特征方向时才出现。旋转类矩阵（复根）
         * 画不出金线，若图例还写着"λ 轴"，用户会以为自己漏看了。
         * 所以按当前矩阵决定这一项显示什么。
         */
        private void drawLegend(Canvas c) {
            float x = dp0(8), y = dp0(14);
            dotLabel(c, x, y, C_RED, "i'");
            dotLabel(c, x + dp0(46), y, C_TEAL, "j'");
            Linalg.Eigen e = Linalg.eigen(cur);
            if (Linalg.rank(cur) == 0) return;
            if (!e.hasReal) {
                dotLabel(c, x + dp0(92), y, C_FG3, "无实特征轴");
            } else {
                dotLabel(c, x + dp0(92), y, C_AMBER, "λ 轴");
            }
        }

        private void dotLabel(Canvas c, float x, float y, int color, String label) {
            pFill.setColor(color);
            c.drawCircle(x, y - dp0(3), dp0(3), pFill);
            pText.setColor(C_FG3);
            c.drawText(label, x + dp0(7), y, pText);
        }

        private float px(float cx, float scale, double x) {
            return (float) (cx + x * scale);
        }

        private float py(float cy, float scale, double y) {
            return (float) (cy - y * scale);
        }
    }
}
