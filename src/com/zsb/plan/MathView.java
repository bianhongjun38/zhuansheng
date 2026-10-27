package com.zsb.plan;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数学工具 —— 替代原来的数学题库。
 *
 * 用户原话："把数学那部分不要了，只留计算机和英语部分……
 *           数学你可以做一个函数图像绘制的功能和求导常见公式对照表"
 *
 * 为什么这样做比题库更适合数学：
 *   数学考的是"会不会推"，不是"记不记得"。选择题只能测记忆，
 *   而一个能画图、能当场求导的工具，能让人把"f 和 f′ 长什么样"看出关系来。
 *
 * 三个页签：
 *   ① 函数图像：输入表达式，画曲线；可叠导数曲线、看切线、找零点
 *   ② 公式对照：40 条求导规则，按组排列，点一下就能把它画出来
 *   ③ 求导练习：随机给一个函数，自己算导数，程序判分（对/错/等价）
 *
 * 所有计算都在 Expr / PlotGeom / DerivTable 里，本类只做界面。
 * 那些类在电脑上被 MathTest 逐条审计过，界面里不重复算一遍。
 */
public class MathView {

    private static final int C_BG = 0xFF14161C;
    private static final int C_BG2 = 0xFF1C1F27;
    private static final int C_BG3 = 0xFF262A34;
    private static final int C_LINE = 0xFF2C313C;
    private static final int C_FG = 0xFFE8EAF0;
    private static final int C_FG2 = 0xFFC3C9D6;
    private static final int C_FG3 = 0xFF6B7282;
    private static final int C_TEAL = 0xFF3ECFAF;
    private static final int C_PURPLE = 0xFF7C6CF0;
    private static final int C_AMBER = 0xFFE0A94A;
    private static final int C_RED = 0xFFE05A54;

    private final Activity act;
    private Dialog dlg;
    private int tab = 0;   // 0 图像  1 公式  2 练习

    private LinearLayout body;
    private TextView[] tabBtn = new TextView[3];

    // 图像页状态
    private PlotView plot;
    private EditText etExpr;
    private TextView tvPlotInfo;
    private String exprText = "sin(x)";

    // 练习页状态
    private Expr.Node quizF;
    private String quizExpr = "";
    private String quizMsg = "";
    private int quizRight = 0, quizAll = 0;

    public MathView(Activity a) { this.act = a; }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }

    /** 从某个入口打开：0=图像 1=公式表 2=练习 */
    public void open(int which) {
        tab = Math.max(0, Math.min(2, which));
        build();
        render();
        dlg.show();
    }

    private void build() {
        dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // 顶栏
        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(12), dp(14), dp(8));

        TextView close = new TextView(act);
        close.setText("✕");
        close.setTextSize(22);
        close.setTextColor(C_FG2);
        close.setPadding(dp(6), dp(4), dp(14), dp(4));
        close.setOnClickListener(v -> dlg.dismiss());
        top.addView(close);

        TextView t = new TextView(act);
        t.setText("数学工具");
        t.setTextSize(15);
        t.setTextColor(C_FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top);

        // 页签
        LinearLayout tabs = new LinearLayout(act);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(10), 0, dp(10), dp(8));
        String[] names = {"函数图像", "求导公式", "求导练习"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            TextView tv = new TextView(act);
            tv.setText(names[i]);
            tv.setTextSize(13.5f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(8), 0, dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(3); lp.rightMargin = dp(3);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> { tab = k; render(); });
            tabBtn[i] = tv;
            tabs.addView(tv);
        }
        root.addView(tabs);

        // 内容
        ScrollView sc = new ScrollView(act);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        sc.setFillViewport(true);
        body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(4), dp(12), dp(24));
        sc.addView(body);
        root.addView(sc);

        dlg.setContentView(root);
    }

    // ── 渲染分发 ──────────────────────────────────────────
    private void render() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == tab;
            tabBtn[i].setTextColor(on ? C_BG : C_FG3);
            tabBtn[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(8));
            g.setColor(on ? C_TEAL : C_BG2);
            tabBtn[i].setBackground(g);
        }
        body.removeAllViews();
        if (tab == 0) renderPlot();
        else if (tab == 1) renderTable();
        else renderPractice();
    }

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(C_BG2);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(C_BG2);
        c.setBackground(g);
        c.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        if (title != null) {
            TextView t = new TextView(act);
            t.setText(title);
            t.setTextSize(12);
            t.setTextColor(C_FG3);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(0, 0, 0, dp(6));
            c.addView(t);
        }
        return c;
    }

    private TextView btn(String text, int color) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextSize(12.5f);
        b.setTextColor(color);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(C_BG3);
        b.setBackground(g);
        return b;
    }

    // ══ 输入辅助：按键板 + 模板 ═══════════════════════════
    /**
     * 符号按键板。
     *
     * 手机上敲 x^2 要切三次符号页，敲 sqrt 会被联想改成别的词。
     * 这排按键按一下就插到光标处，插完光标停在括号里。
     * 插入逻辑全在 ExprInput（电脑上 52 项断言全过），这里只负责画。
     */
    private LinearLayout buildKeypad() {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(2), 0, dp(6));

        final int perRow = 6;
        keyPage = 0;
        keyWrap = wrap;
        buildKeysInto(wrap, perRow);
        return wrap;
    }

    /** 按当前页重建按键。切页时清空重画。 */
    private void buildKeysInto(LinearLayout wrap, int perRow) {
        wrap.removeAllViews();
        // 补齐到整行：否则最后一行只剩两个键、右半边空着
        final String[][] keys = ExprInput.paddedKeys(perRow, keyPage);
        LinearLayout row = null;
        for (int i = 0; i < keys.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dp(5);
                row.setLayoutParams(rlp);
                wrap.addView(row);
            }
            final String[] key = keys[i];
            if (ExprInput.isPad(key)) {
                // 占位键：透明占位，保持列对齐
                View pad = new View(act);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        0, dp(36), 1f);
                plp.leftMargin = dp(2);
                plp.rightMargin = dp(2);
                pad.setLayoutParams(plp);
                row.addView(pad);
                continue;
            }
            boolean isBack = "\b".equals(key[1]);
            TextView b = new TextView(act);
            b.setText(key[0]);
            b.setTextSize(isBack ? 15f : 14f);
            b.setTextColor(isBack ? C_RED : C_FG);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, dp(9), 0, dp(9));
            // 按下要变样，否则手指按下去没有任何反馈，
            // 用户根本不知道点中没有 —— 实机反馈就是这个问题。
            b.setBackground(pressable(dp(7)));
            b.setClickable(true);
            b.setFocusable(true);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(2);
            lp.rightMargin = dp(2);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> applyKey(key[1]));
            row.addView(b);
        }
    }

    /** 常用函数模板：点一下整条替换输入框，直接画。 */
    private LinearLayout buildTemplates() {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, 0, 0, dp(6));

        TextView t = new TextView(act);
        t.setText("常用图形（点一下直接画）");
        t.setTextSize(11);
        t.setTextColor(C_FG3);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, dp(5));
        wrap.addView(t);

        // 3 列而不是 4 列。
        //
        // 4 列时每格只有约 75dp 可用宽，"(x+1)(x-1)" 必须缩到 64% 才塞得下，
        // "x³ − 3x" 直接被截断 —— FitAudit 量出来的（74.04/75.0 卡在临界）。
        // 减号两侧的空格是课本排版，不能为了塞进格子去删；
        // 该让格子变宽。3 列每格约 103dp，16 个模板排 6 行，仍然一屏多可见。
        final int perRow = 3;
        LinearLayout row = null;
        for (int i = 0; i < ExprInput.TEMPLATES.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dp(5);
                row.setLayoutParams(rlp);
                wrap.addView(row);
            }
            final String expr = ExprInput.TEMPLATES[i];
            final String nm = i < ExprInput.TEMPLATE_NAMES.length
                    ? ExprInput.TEMPLATE_NAMES[i] : "";
            LinearLayout cell = new LinearLayout(act);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            // 上下留够：分式要两行，正弦式只有一行。
            // 按一行的高度给格子，sin(x)/x 会被压扁到看不清分数线。
            cell.setPadding(dp(3), dp(8), dp(3), dp(8));
            cell.setMinimumHeight(dp(46));
            cell.setBackground(pressable(dp(7)));
            cell.setClickable(true);
            cell.setFocusable(true);

            // 上排显示已经排好版的公式，下排是图形种类 ——
            // 只写 "x^2" 看不出是抛物线，只写"抛物线"又不知道点了会画什么。
            FormulaView fv = new FormulaView(act);
            fv.setTextSize(12.5f);
            fv.setTextColor(C_FG);
            try { fv.setExpr(expr); } catch (Exception e) { /* 模板都过审计了 */ }
            cell.addView(fv);

            if (!nm.isEmpty()) {
                TextView nt = new TextView(act);
                nt.setText(nm);
                nt.setTextSize(9.5f);
                nt.setTextColor(C_FG3);
                nt.setPadding(0, dp(1), 0, 0);
                cell.addView(nt);
            }

            // 同一行必须等高。
            //
            // 原来用 WRAP_CONTENT：分式格子（x²−3x 上面一个 1/x、sin x/x 这种）
            // 内容高两行，单行的格子只占一行 —— 一行之内高矮不齐，
            // 视觉上就是"一大一小"。固定成统一高度后整排齐平。
            cell.setMinimumHeight(dp(TEMPLATE_CELL_H));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, dp(TEMPLATE_CELL_H), 1f);
            lp.leftMargin = dp(2);
            lp.rightMargin = dp(2);
            cell.setLayoutParams(lp);
            cell.setOnClickListener(v -> {
                if (etExpr == null) return;
                etExpr.setText(expr);
                etExpr.setSelection(expr.length());
                exprText = expr;
                draw();
            });
            row.addView(cell);
        }
        return wrap;
    }

    /** 模板格子统一高度：够放下"分式 + 一行说明"，单行式子也不显得空。 */
    private static final int TEMPLATE_CELL_H = 62;

    /**
     * 「怎么写 → 画出来什么样」对照表。
     *
     * 用户原话："函数图像那个你得写 sqrt 和 abs 和 exp 对照什么，不然谁知道"。
     * 只给模板格子（"sqrt(x)"）等于只给了名字，不知道形状、不知道定义域、
     * 不知道和 x^2 有什么不同 —— 模板再多也用不上。
     *
     * 每条给四样：真缩略曲线（PlotGeom 采样，和主图同一份代码）、
     * 怎么写、中文名、一句形状人话、一句能自己验证的话。
     * 整条可点，点了就填进输入框并直接画出来。
     */
    private LinearLayout buildOutline() {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(10), 0, dp(4));

        TextView t = new TextView(act);
        t.setText("这些写法画出来是什么样");
        t.setTextSize(11);
        t.setTextColor(C_FG3);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(2));
        wrap.addView(t);

        TextView sub = new TextView(act);
        sub.setText("点任意一行直接画。曲线就是它真实的形状，不是示意图。");
        sub.setTextSize(10.5f);
        sub.setTextColor(C_FG3);
        sub.setPadding(0, 0, 0, dp(7));
        wrap.addView(sub);

        ExprOutline.Item[] items = ExprOutline.all();
        int lastGroup = -1;
        for (int i = 0; i < items.length; i++) {
            final ExprOutline.Item it = items[i];

            int g = ExprOutline.groupOf(i);
            if (g != lastGroup) {
                lastGroup = g;
                TextView gh = new TextView(act);
                gh.setText(ExprOutline.GROUPS[g]);
                gh.setTextSize(11.5f);
                gh.setTextColor(C_TEAL);
                gh.setTypeface(Typeface.DEFAULT_BOLD);
                gh.setPadding(0, dp(i == 0 ? 2 : 9), 0, dp(4));
                wrap.addView(gh);
            }

            wrap.addView(outlineRow(it));
        }
        return wrap;
    }

    /** 一条对照：左缩略图，右边写法 + 名字 + 形状 + 自验。整条可点。 */
    private View outlineRow(final ExprOutline.Item it) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setBackground(pressable(dp(8)));
        row.setClickable(true);
        row.setFocusable(true);

        MiniPlot mp = new MiniPlot(act);
        try {
            mp.set(Expr.parse(it.sample), it.color);
        } catch (Exception e) {
            // 数据都过审计了；真出问题也不该整块白屏
        }
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                (int) dp(78), (int) dp(56));
        mlp.rightMargin = dp(10);
        row.addView(mp, mlp);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);

        // 第一行：写法（等宽感，一眼看出该敲什么）
        TextView w = new TextView(act);
        w.setText(it.write);
        w.setTextSize(14);
        w.setTextColor(C_FG);
        w.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        col.addView(w);

        // 第二行：中文名
        TextView nm = new TextView(act);
        nm.setText(it.name);
        nm.setTextSize(11.5f);
        nm.setTextColor(it.color);
        nm.setPadding(0, dp(1), 0, 0);
        col.addView(nm);

        // 第三行：形状（这才是用户真正缺的）
        TextView sh = new TextView(act);
        sh.setText(it.shape);
        sh.setTextSize(11);
        sh.setTextColor(C_FG2);
        sh.setPadding(0, dp(4), 0, 0);
        col.addView(sh);

        // 第四行：自己能验的一句话
        TextView ask = new TextView(act);
        ask.setText("试试：" + it.ask);
        ask.setTextSize(10.5f);
        ask.setTextColor(C_FG3);
        ask.setPadding(0, dp(3), 0, 0);
        col.addView(ask);

        row.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(4);
        row.setLayoutParams(rlp);

        row.setOnClickListener(v -> {
            if (etExpr == null) return;
            etExpr.setText(it.write);
            etExpr.setSelection(it.write.length());
            exprText = it.write;
            draw();
        });
        return row;
    }


    /** 按一个键：插到光标处（或替换选区），然后刷新。 */
    private void applyKey(String frag) {
        if (etExpr == null) return;

        // 翻页：不插入任何内容，只换一张键盘
        if (ExprInput.SWITCH.equals(frag)) {
            keyPage = 1;
            buildKeysInto(keyWrap, 6);
            return;
        }
        if (ExprInput.BACK_TO_NUM.equals(frag)) {
            keyPage = 0;
            buildKeysInto(keyWrap, 6);
            return;
        }
        int a = etExpr.getSelectionStart();
        int b = etExpr.getSelectionEnd();
        // Android 的 getSelectionStart 在极端情况下会返回 -1
        if (a < 0) a = etExpr.getText().length();
        if (b < 0) b = a;

        ExprInput.R r = ExprInput.insert(etExpr.getText().toString(), a, b, frag);
        etExpr.setText(r.text);
        int pos = Math.max(0, Math.min(r.cursor, etExpr.getText().length()));
        etExpr.setSelection(pos);

        // 括号没闭上就补一下，省得用户数括号
        int un = ExprInput.unclosedParens(r.text);
        if (un != 0) updateKeyHint(un);
        else updateKeyHint(0);
        if (!"\b".equals(frag)) {
            // 插入后立刻重画，边写边看
            exprText = etExpr.getText().toString();
            draw();
        }
    }

    private TextView keyHint;
    private int keyPage;              // 0=数字页 1=函数页
    private LinearLayout keyWrap;     // 按键容器，切页时重画

    private void updateKeyHint(int unclosed) {
        if (keyHint == null) return;
        if (unclosed > 0) {
            keyHint.setText("缺 " + unclosed + " 个右括号");
            keyHint.setTextColor(C_AMBER);
        } else if (unclosed < 0) {
            keyHint.setText("右括号多了 " + (-unclosed) + " 个");
            keyHint.setTextColor(C_RED);
        } else {
            keyHint.setText("");
        }
    }

    // ══ ① 函数图像 ═══════════════════════════════════════
    private void renderPlot() {
        LinearLayout c1 = card("函数表达式");

        EditText et = new EditText(act);
        et.setText(exprText);
        et.setTextColor(C_FG);
        et.setTextSize(15);
        et.setBackgroundColor(C_BG3);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        et.setSingleLine(true);
        // TYPE_TEXT_FLAG_NO_SUGGESTIONS：手机输入法会把 sin 自动纠正成
        // "son"/"sin."，一边打字一边被改，比不能打还气人。
        // 关掉联想和自动纠错，输入框里进去什么就是什么。
        et.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // 数学表达式不该被当作密码/邮箱去猜，给个纯 ASCII 的键盘
        et.setPrivateImeOptions("nm");
        etExpr = et;
        c1.addView(et);

        TextView hint = new TextView(act);
        hint.setText("可以写 x、pi、e；sin cos tan ln lg sqrt abs exp；2x、3sin(x)、(x+1)(x-1) 都认。"
                + "\n下面有按键，不用切输入法符号页。");
        hint.setTextSize(11);
        hint.setTextColor(C_FG3);
        hint.setPadding(0, dp(6), 0, dp(8));
        c1.addView(hint);

        keyHint = new TextView(act);
        keyHint.setTextSize(11);
        keyHint.setTextColor(C_AMBER);
        keyHint.setPadding(0, dp(2), 0, 0);
        c1.addView(keyHint);
        updateKeyHint(ExprInput.unclosedParens(exprText));

        c1.addView(buildKeypad());
        c1.addView(buildTemplates());
        c1.addView(buildOutline());

        LinearLayout row1 = new LinearLayout(act);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        TextView go = btn("画出来", C_BG);
        GradientDrawable gg = new GradientDrawable();
        gg.setCornerRadius(dp(8));
        gg.setColor(C_TEAL);
        go.setBackground(gg);
        go.setOnClickListener(v -> {
            exprText = etExpr.getText().toString();
            draw();
        });
        row1.addView(go, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView reset = btn("复位", C_FG2);
        reset.setOnClickListener(v -> {
            if (plot != null) { plot.resetView(); draw(); }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = dp(8);
        reset.setLayoutParams(rlp);
        row1.addView(reset);
        c1.addView(row1);

        LinearLayout row2 = new LinearLayout(act);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(8), 0, 0);
        TextView zin = btn("放大 +", C_FG2);
        zin.setOnClickListener(v -> { if (plot != null) plot.zoomBy(0.6); });
        row2.addView(zin, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView zout = btn("缩小 −", C_FG2);
        LinearLayout.LayoutParams zlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        zlp.leftMargin = dp(8);
        zout.setLayoutParams(zlp);
        zout.setOnClickListener(v -> { if (plot != null) plot.zoomBy(1.6); });
        row2.addView(zout);
        c1.addView(row2);
        body.addView(c1);

        // 画布
        LinearLayout c2 = new LinearLayout(act);
        c2.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g2 = new GradientDrawable();
        g2.setCornerRadius(dp(12));
        g2.setColor(0xFF101319);
        c2.setBackground(g2);
        c2.setClipToOutline(true);
        LinearLayout.LayoutParams c2lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        c2lp.bottomMargin = dp(10);
        c2.setLayoutParams(c2lp);

        plot = new PlotView(act);
        plot.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        c2.addView(plot);
        body.addView(c2);

        // 显示开关
        LinearLayout c3 = card("显示");
        LinearLayout switches = new LinearLayout(act);
        switches.setOrientation(LinearLayout.HORIZONTAL);

        final TextView swD = btn("导数曲线 f′", C_FG3);
        swD.setOnClickListener(v -> {
            if (plot == null) return;
            boolean nv = !plot.isShowDeriv();
            plot.setShowDeriv(nv);
            swD.setTextColor(nv ? C_AMBER : C_FG3);
            updateInfo();
        });
        switches.addView(swD, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView swT = btn("切线（点图选点）", C_FG3);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(8);
        swT.setLayoutParams(slp);
        swT.setOnClickListener(v -> {
            if (plot == null) return;
            boolean nv = !plot.isShowTangent();
            plot.setShowTangent(nv);
            swT.setTextColor(nv ? C_PURPLE : C_FG3);
            updateInfo();
        });
        switches.addView(swT);
        c3.addView(switches);

        tvPlotInfo = new TextView(act);
        tvPlotInfo.setTextSize(12);
        tvPlotInfo.setTextColor(C_FG2);
        tvPlotInfo.setPadding(0, dp(8), 0, 0);
        tvPlotInfo.setLineSpacing(dp(3), 1f);
        c3.addView(tvPlotInfo);
        body.addView(c3);

        // 快捷函数
        LinearLayout c4 = card("常用图像（点一下直接画）");
        HorizontalScrollView hs = new HorizontalScrollView(act);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String[] quicks = {"x^2", "x^3", "1/x", "sqrt(x)", "ln(x)", "exp(x)",
                "sin(x)", "cos(x)", "tan(x)", "abs(x)", "x^2-4", "sin(x)/x",
                "x^x", "atan(x)"};
        for (String q : quicks) {
            TextView chip = btn(q, C_TEAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.rightMargin = dp(6);
            chip.setLayoutParams(clp);
            chip.setOnClickListener(v -> {
                exprText = q;
                if (etExpr != null) etExpr.setText(q);
                draw();
            });
            chips.addView(chip);
        }
        hs.addView(chips);
        c4.addView(hs);
        body.addView(c4);

        draw();

        for (int i = 0; i < 3; i++) if (false) tabBtn[i].setText("");
        if (swD != null && plot != null && plot.isShowDeriv()) swD.setTextColor(C_AMBER);
        if (swT != null && plot != null && plot.isShowTangent()) swT.setTextColor(C_PURPLE);

        plot.setOnPick((x, y, slope) -> updateInfo());
    }

    private void draw() {
        if (plot == null) return;
        String err = plot.setExpr(exprText);
        if (err != null) {
            if (tvPlotInfo != null) {
                tvPlotInfo.setTextColor(C_RED);
                tvPlotInfo.setText("画不出来：" + err);
            }
            return;
        }
        updateInfo();
    }

    private void updateInfo() {
        if (tvPlotInfo == null || plot == null) return;
        Expr.Node f = plot.expr();
        if (f == null) { tvPlotInfo.setText("还没输入函数"); return; }

        StringBuilder b = new StringBuilder();
        b.append("f(x) = ").append(exprText);
        Expr.Node d = plot.deriv();
        if (d != null) b.append("\nf′(x) = ").append(Expr.toText(d));

        List<Double> rs = PlotGeom.roots(f, plot.window(), 600);
        if (!rs.isEmpty()) {
            b.append("\n零点：");
            for (int i = 0; i < rs.size() && i < 5; i++) {
                if (i > 0) b.append("，");
                b.append("x=").append(Expr.fmtNum(rs.get(i)));
            }
            if (rs.size() > 5) b.append(" 等 ").append(rs.size()).append(" 个");
        } else {
            b.append("\n零点：本窗口内没有");
        }

        Double tx = plot.tangentX();
        if (tx != null && plot.isShowTangent()) {
            double y = PlotGeom.safeEval(f, tx);
            double k = PlotGeom.slope(f, tx);
            b.append("\n点 x=").append(Expr.fmtNum(tx))
                    .append("：f(x)=").append(Expr.fmtNum(y))
                    .append("，f′=").append(Expr.fmtNum(k));
            if (PlotGeom.isFinite(k) && Math.abs(k) > 1e-9) {
                b.append("，切线 y=").append(Expr.fmtNum(k)).append("x");
                double c = y - k * tx;
                if (c >= 0) b.append("+").append(Expr.fmtNum(c));
                else b.append("−").append(Expr.fmtNum(-c));
            } else if (PlotGeom.isFinite(k)) {
                b.append("，切线水平 y=").append(Expr.fmtNum(y));
            }
        }
        tvPlotInfo.setTextColor(C_FG2);
        tvPlotInfo.setText(b.toString());
    }

    // ══ ② 求导公式对照表 ═════════════════════════════════
    private void renderTable() {
        TextView intro = new TextView(act);
        intro.setText("点任意一行 → 直接画它的图像，并叠出它的导数曲线。"
                + "\n表里每一条都由 MathTest 用「符号求导 + 数值差分」双重验证过，抄错会当场报错。");
        intro.setTextSize(11.5f);
        intro.setTextColor(C_FG3);
        intro.setPadding(dp(4), 0, dp(4), dp(10));
        intro.setLineSpacing(dp(3), 1f);
        body.addView(intro);

        Map<String, List<DerivTable.Row>> groups = DerivTable.grouped();
        for (Map.Entry<String, List<DerivTable.Row>> e : groups.entrySet()) {
            LinearLayout c = card(e.getKey() + " · " + e.getValue().size() + " 条");
            for (DerivTable.Row r : e.getValue()) c.addView(rowView(r));
            body.addView(c);
        }
    }

    /**
     * 带按下态的圆角背景。
     *
     * 原来每个可点元素都是 GradientDrawable 单色 —— 按下、松手、禁用
     * 长得一模一样，手指按下去屏幕毫无变化。真机上用户会说"点了没反应"，
     * 其实事件是收到的，只是没有任何视觉确认。
     *
     * 用 StateListDrawable 给 pressed 一个明显更亮的底色：
     * 深色界面上 C_BG3 → C_BG4 的差别肉眼能看出来，又不会太跳。
     */
    private android.graphics.drawable.Drawable pressable(final int radius) {
        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(radius);
        normal.setColor(C_BG3);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setCornerRadius(radius);
        // 比常态亮一档：深色底上这个差值足够看清，又不至于像选中态
        pressed.setColor(0xFF3C4250);
        // 按下时描一圈青色细边，短按也能看到闪一下
        pressed.setStroke(dp(1), C_TEAL);

        android.graphics.drawable.StateListDrawable sd =
                new android.graphics.drawable.StateListDrawable();
        sd.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sd.addState(new int[]{android.R.attr.state_focused}, pressed);
        sd.addState(new int[]{}, normal);
        return sd;
    }

    /**
     * 排好版的公式控件。解析失败就退回纯文本 —— 表里的式子都过审计了，
     * 但界面不该因为一条数据坏了整块白屏。
     */
    private View formula(String expr, int color, float sp) {
        FormulaView fv = new FormulaView(act);
        fv.setTextSize(sp);
        fv.setTextColor(color);
        int padTop = dp(3);
        fv.setPadding(0, padTop, 0, 0);
        try {
            fv.setExpr(expr);
        } catch (Exception e) {
            TextView tv = new TextView(act);
            tv.setText(expr);
            tv.setTextSize(sp);
            tv.setTextColor(color);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            return tv;
        }
        return fv;
    }

    /** 「↓ 求导」这类过程标注，统一字号与缩进。 */
    private TextView hintLabel(String s) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(10.5f);
        tv.setTextColor(C_FG3);
        tv.setPadding(dp(6), dp(2), 0, dp(2));
        return tv;
    }

    private View rowView(final DerivTable.Row r) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(C_BG3);
        row.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);

        TextView name = new TextView(act);
        name.setText(r.name);
        name.setTextSize(12);
        name.setTextColor(C_FG3);
        head.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView go = new TextView(act);
        go.setText("画图 ›");
        go.setTextSize(11.5f);
        go.setTextColor(C_TEAL);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setPadding(dp(8), 0, dp(2), 0);
        go.setOnClickListener(v -> {
            exprText = r.expr;
            tab = 0;
            render();
        });
        head.addView(go);
        row.addView(head);

        // 用 FormulaView 排真公式，不用 Expr.mathText。
        //
        // mathText 是"能粘回输入框的机器写法"，它会原样输出 sqrt(x)、
        // exp(x)、log2(x) —— 用户看的是这个，当然觉得不是人话。
        // FormulaView 走 FormulaLayout，sqrt 会画成根号、exp 画成 e 的
        // 上标、分式画成真的分数线，这才是课本上的样子。
        //
        // 注意 r.expr / r.der 仍是机器可解析的字符串，MathTest 照旧拿它们
        // 做符号求导与数值差分对照 —— 显示换了，审计一点没减弱。
        // 注意这里传的是 r.expr / r.der（机器写法），不是 r.show。
        //
        // FormulaView 自己就会把 sqrt 排成根号、exp 排成 e 的上标 ——
        // 它需要的是能被 Expr 解析的表达式。若传 r.show（"e^(x)"），
        // 里面的 e 会被 Expr 当成欧拉数常量折成 2.718282，
        // 界面上就会出现 "2.718282^x" —— 审计渲染图当场抓到的。
        // r.show / r.showDer 只用于纯文本场合（列表标题、说明）。
        row.addView(formula(r.expr, C_FG, 16f));
        row.addView(hintLabel("↓ 求导"));
        row.addView(formula(r.der, C_AMBER, 16f));

        if (!r.note.isEmpty()) {
            TextView note = new TextView(act);
            note.setText("· " + r.note);
            note.setTextSize(11);
            note.setTextColor(C_FG3);
            note.setPadding(0, dp(4), 0, 0);
            row.addView(note);
        }
        return row;
    }

    // ══ ③ 求导练习 ═══════════════════════════════════════
    private void renderPractice() {
        if (quizF == null) nextQuiz();

        LinearLayout c1 = card("算它的导数");
        TextView tvF = new TextView(act);
        tvF.setText("f(x) =  " + Expr.mathText(quizF));
        tvF.setTextSize(19);
        tvF.setTextColor(C_FG);
        tvF.setTypeface(Typeface.DEFAULT_BOLD);
        tvF.setPadding(0, dp(4), 0, dp(10));
        c1.addView(tvF);

        TextView tvAsk = new TextView(act);
        tvAsk.setText("写下 f′(x)：");
        tvAsk.setTextSize(12);
        tvAsk.setTextColor(C_FG3);
        c1.addView(tvAsk);

        final EditText et = new EditText(act);
        et.setHint("例如 2x+1、cos(x)、1/x");
        et.setHintTextColor(0xFF4E5565);
        et.setTextColor(C_FG);
        et.setTextSize(16);
        et.setBackgroundColor(C_BG3);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        c1.addView(et);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);

        TextView check = btn("对答案", C_BG);
        GradientDrawable gg = new GradientDrawable();
        gg.setCornerRadius(dp(8));
        gg.setColor(C_TEAL);
        check.setBackground(gg);
        check.setOnClickListener(v -> {
            String in = et.getText().toString();
            quizMsg = judgeDeriv(quizF, in);
            if (quizMsg.startsWith("✓")) quizRight++;
            quizAll++;
            render();
        });
        row.addView(check, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView skip = btn("换一题", C_FG2);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = dp(8);
        skip.setLayoutParams(slp);
        skip.setOnClickListener(v -> { nextQuiz(); quizMsg = ""; render(); });
        row.addView(skip);
        c1.addView(row);
        body.addView(c1);

        if (!quizMsg.isEmpty()) {
            LinearLayout c2 = card("结果");
            TextView t = new TextView(act);
            t.setText(quizMsg);
            t.setTextSize(13);
            t.setTextColor(quizMsg.startsWith("✓") ? C_TEAL : C_RED);
            t.setLineSpacing(dp(4), 1f);
            c2.addView(t);
            body.addView(c2);
        }

        LinearLayout c3 = card("本轮");
        TextView t3 = new TextView(act);
        t3.setText("做了 " + quizAll + " 题，对 " + quizRight + " 题"
                + (quizAll > 0 ? "（正确率 " + (quizRight * 100 / quizAll) + "%）" : ""));
        t3.setTextSize(12.5f);
        t3.setTextColor(C_FG2);
        c3.addView(t3);
        body.addView(c3);
    }

    /** 随机挑一个"求导有意义且不难看"的函数。 */
    private void nextQuiz() {
        String[] pool = {
                "x^2", "x^3", "x^4", "sin(x)", "cos(x)", "tan(x)",
                "exp(x)", "ln(x)", "sqrt(x)", "1/x",
                "sin(2x)", "cos(3x)", "exp(2x)", "ln(1+x^2)", "sqrt(1+x^2)",
                "x·sin(x)", "x^2·exp(x)", "sin(x)/x", "x^2-4x", "atan(x)",
                "x^3-3x", "exp(sin(x))", "tan(x)/2", "2^x"
        };
        int i = (int) (Math.random() * pool.length);
        if (pool[i].equals(quizExpr)) i = (i + 1) % pool.length;
        quizExpr = pool[i];
        try { quizF = Expr.parse(quizExpr); } catch (Exception e) { quizF = Expr.parse("x"); }
    }

    /**
     * 判分。不比对文本，比对"数值上是不是同一个函数"：
     * 学生可能写成 (2)·x 或 x·2 或 2 x，字面不同但都对。
     * 做法：在 5 个点上比较他的表达式与标准导数；全部接近才算对。
     * 这样既接受等价写法，也不会把 x 和 2x 判成一样。
     */
    private String judgeDeriv(Expr.Node f, String input) {
        if (input == null || input.trim().isEmpty()) return "还没写";
        Expr.Node mine;
        try {
            mine = Expr.parse(input);
        } catch (Expr.ExprError e) {
            return "表达式看不懂：" + e.getMessage();
        }
        Expr.Node ref = Expr.diff(f);
        String refText = Expr.toText(ref);

        double[] xs = {-2.3, -1.1, -0.4, 0.6, 1.4, 2.2};
        int tested = 0;
        for (double x : xs) {
            double a = Expr.eval(ref, x);
            double b = Expr.eval(mine, x);
            if (!PlotGeom.isFinite(a) || !PlotGeom.isFinite(b)) continue;
            if (Math.abs(a) > 1e6 || Math.abs(b) > 1e6) continue;
            tested++;
            double scale = Math.max(1, Math.max(Math.abs(a), Math.abs(b)));
            if (Math.abs(a - b) > 1e-6 * scale) {
                return "✗ 不对。\n正确答案：" + refText
                        + "\n（你在 x=" + Expr.fmtNum(x) + " 处得到 "
                        + Expr.fmtNum(b) + "，正确是 " + Expr.fmtNum(a) + "）";
            }
        }
        if (tested == 0) {
            return "✗ 无法判定：你的表达式和标准答案没有公共定义域。\n正确答案：" + refText;
        }
        if (Expr.toText(mine).equals(refText)) {
            return "✓ 正确，而且写法和标准答案一致：" + refText;
        }
        return "✓ 正确（写法不同但等价）。\n你写的：" + Expr.toText(mine)
                + "\n标准写法：" + refText;
    }
}
