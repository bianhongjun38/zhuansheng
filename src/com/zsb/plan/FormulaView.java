package com.zsb.plan;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/**
 * 公式显示控件 —— 排版全部交给 FormulaLayout（纯 Java，电脑上审计过），
 * 这里只把算好的坐标画出来。
 *
 * 分式画横线、幂画上标、根号画带顶线的√、乘号该省就省，
 * 都是为了让公式"像书上那样"，而不是一行 1/(2·sqrt(x))。
 */
public class FormulaView extends View {

    private Expr.Node root;
    private FormulaLayout.Box laid;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 画上下标时临时改字号用的画笔，避免每次 createPaint。 */
    private final Paint scaled = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float textSizeSp = 17f;
    private int color = 0xFFE8EAF0;

    private final FormulaLayout.Metrics metrics = new FormulaLayout.Metrics() {
        public float width(String text, float scale) {
            return measure(text, scale);
        }
        public float ascent(float scale) { return ascentOf(scale); }
        public float descent(float scale) { return descentOf(scale); }
        public float unit() { return dp(1f); }
    };

    public FormulaView(Context c) {
        super(c);
        paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        bar.setStyle(Paint.Style.STROKE);
        bar.setStrokeCap(Paint.Cap.ROUND);
        bar.setStrokeJoin(Paint.Join.ROUND);
        applySize();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private float px() { return textSizeSp * getResources().getDisplayMetrics().scaledDensity; }

    private void applySize() {
        paint.setTextSize(px());
        scaled.setTypeface(paint.getTypeface());
        scaled.setTextSize(px());
        bar.setStrokeWidth(Math.max(dp(1f), px() * 0.055f));
    }

    /**
     * 取某个缩放比对应的画笔。
     * FormulaLayout 已经把"上下标要小 0.62 倍"算进盒子的 s 里，
     * 但真实的字号也必须跟着变 —— 只缩小盒子不缩小字，上标会压住底数。
     */
    private Paint paintFor(float scale) {
        if (Math.abs(scale - 1f) < 0.001f) return paint;
        scaled.setTextSize(px() * scale);
        scaled.setTypeface(paint.getTypeface());
        return scaled;
    }

    /** 用当前字号量宽度，再乘缩放比。 */
    private float measure(String t, float scale) {
        if (t == null || t.isEmpty()) return 0;
        return paint.measureText(t) * scale;
    }

    private float ascentOf(float scale) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return -fm.ascent * scale;
    }

    private float descentOf(float scale) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return fm.descent * scale;
    }

    public void setTextSize(float sp) {
        textSizeSp = sp;
        applySize();
        relayout();
        requestLayout();
        invalidate();
    }

    public void setTextColor(int c) { color = c; invalidate(); }

    public void setExpr(Expr.Node n) {
        root = n;
        relayout();
        requestLayout();
        invalidate();
    }

    public void setExpr(String text) {
        try { setExpr(Expr.parse(text)); }
        catch (Exception e) { root = null; relayout(); requestLayout(); invalidate(); }
    }

    /** 不缩字号时的自然宽度。onMeasure 拿它和可用宽度比，决定要不要缩。 */
    private float plainW = Float.MAX_VALUE;
    /** 实际用的缩放比，onDraw 用它给画笔设定字号。 */
    private float fitFactor = 1f;

    private void relayout() {
        if (root == null) { laid = null; plainW = Float.MAX_VALUE; return; }
        laid = FormulaLayout.layout(root, metrics);
        FormulaLayout.place(laid, 0, 0, metrics);
        plainW = laid.w;
    }

    /**
     * 测量时按可用宽度自动缩字号（缩字逻辑在 FormulaLayout.fit 里，
     * 纯逻辑、电脑上也能断言）。
     *
     * 原来的实现完全不看父容器给的上限，公式有多宽就报多宽 ——
     * 放在固定列宽的格子里就会溢出。这在本地看不出来（Windows 的
     * sans-serif 比较窄），但换到别的字体系统上（CI 的 Ubuntu）
     * "(x+1)(x-1)" 会宽出 10%，直接画出格子外面。
     */
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int avail = MeasureSpec.getSize(wSpec);
        int mode = MeasureSpec.getMode(wSpec);

        // 只按宽度缩即可 —— 这是 FitAudit 实测得出的，不是想当然。
        //
        // 我一度以为固定高度的格子里分式会"高度溢出"，加了 fitBoth
        // 做宽高双约束。审计（16 个模板、62dp 格高）跑出来：
        // 旧逻辑 0 条漏判，height 从来没超过。加了只是多余复杂度，
        // 已撤回。真要处理高度，先有失败的审计案例再说。
        if (root != null && mode != MeasureSpec.UNSPECIFIED) {
            float limit = avail - getPaddingLeft() - getPaddingRight();
            if (limit > 0) {
                float plain = laid == null ? Float.MAX_VALUE : plainW;
                if (plain > limit) {
                    FormulaLayout.Fit fit = FormulaLayout.fit(root, metrics, limit);
                    laid = fit.box;
                    fitFactor = fit.factor;
                } else {
                    if (laid == null) relayout();
                    fitFactor = 1f;
                }
            } else {
                if (laid == null) relayout();
                fitFactor = 1f;
            }
        } else if (laid == null && root != null) {
            relayout();
        }

        int w, h;
        if (laid == null) {
            w = (int) dp(20); h = (int) dp(26);
        } else {
            w = (int) Math.ceil(laid.w) + getPaddingLeft() + getPaddingRight();
            h = (int) Math.ceil(laid.asc + laid.desc) + getPaddingTop() + getPaddingBottom();
        }
        setMeasuredDimension(resolveSize(w, wSpec), resolveSize(h, hSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (laid == null) return;
        paint.setColor(color);
        bar.setColor(color);
        // 缩过字号就整体缩小画笔；FormulaLayout 已经按这个比例算好了盒子
        if (Math.abs(fitFactor - 1f) > 0.001f) {
            paint.setTextSize(px() * fitFactor);
            bar.setStrokeWidth(Math.max(dp(1f), px() * fitFactor * 0.055f));
        }

        float availH = getHeight() - getPaddingTop() - getPaddingBottom();
        float topY = getPaddingTop() + (availH - (laid.asc + laid.desc)) / 2f;
        // place() 把根放在 (0,0) 基线；这里整体平移到控件里
        float dx = getPaddingLeft() - laid.x + 0;
        float dy = topY + laid.asc - laid.y;
        drawTree(c, laid, dx, dy);
    }

    private void drawTree(Canvas c, FormulaLayout.Box b, float dx, float dy) {
        if (b == null) return;
        float x = b.x + dx, y = b.y + dy;
        switch (b.kind) {
            case FormulaLayout.K_TEXT:
                c.drawText(b.text, x, y, paintFor(b.s));
                break;
            case FormulaLayout.K_ROW: {
                if (b.op != null && !b.op.isEmpty()) {
                    float opX = b.a == null ? x : b.a.x + dx + b.a.w;
                    c.drawText(b.op, opX, y, paintFor(b.s));
                }
                drawTree(c, b.a, dx, dy);
                drawTree(c, b.b, dx, dy);
                break;
            }
            case FormulaLayout.K_DIV: {
                float midY = y - dp(3f) * b.s;
                float x1 = x + dp(1f) * b.s;
                float x2 = x + b.w - dp(1f) * b.s;
                c.drawLine(x1, midY, x2, midY, bar);
                drawTree(c, b.a, dx, dy);
                drawTree(c, b.b, dx, dy);
                break;
            }
            case FormulaLayout.K_POW:
                drawTree(c, b.a, dx, dy);
                drawTree(c, b.b, dx, dy);
                break;
            case FormulaLayout.K_SQRT: {
                float hook = dp(10f) * b.s;
                float top = y - b.asc + dp(2f) * b.s;
                Path p = new Path();
                p.moveTo(x, y - dp(6f) * b.s);
                p.lineTo(x + dp(3f) * b.s, y - dp(8f) * b.s);
                p.lineTo(x + dp(5.5f) * b.s, y + dp(1.5f) * b.s);
                p.lineTo(x + hook, top);
                c.drawPath(p, bar);
                c.drawLine(x + hook, top, x + b.w - dp(1f) * b.s, top, bar);
                drawTree(c, b.a, dx, dy);
                break;
            }
            case FormulaLayout.K_ABS: {
                c.drawText("|", x, y, paintFor(b.s));
                drawTree(c, b.a, dx, dy);
                c.drawText("|", b.a.x + dx + b.a.w, y, paintFor(b.s));
                break;
            }
            case FormulaLayout.K_PAREN: {
                Paint p = paintFor(b.s * b.parScale);
                c.drawText("(", x, y, p);
                drawTree(c, b.a, dx, dy);
                c.drawText(")", b.a.x + dx + b.a.w + dp(1.5f) * b.s, y, p);
                break;
            }
            case FormulaLayout.K_FUNC: {
                c.drawText(b.text, x, y, paintFor(b.s));
                float cx = x + measure(b.text, b.s) + dp(3f) * b.s;
                if ("1".equals(b.op)) {
                    c.drawText("(", cx, y, paintFor(b.s));
                    cx += measure("(", b.s);
                    drawTree(c, b.a, dx, dy);
                    c.drawText(")", b.a.x + dx + b.a.w, y, paintFor(b.s));
                } else {
                    drawTree(c, b.a, dx, dy);
                }
                break;
            }
            default:
                break;
        }
    }

    /** 供列表里"按公式宽度自适应高度"用。 */
    public int idealHeight() {
        if (laid == null) return (int) dp(26);
        return (int) Math.ceil(laid.asc + laid.desc) + getPaddingTop() + getPaddingBottom();
    }
}
