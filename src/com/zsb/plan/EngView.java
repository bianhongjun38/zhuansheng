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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 英语训练界面 —— 五种题型，替代原来的"认识/不认识"翻卡。
 *
 * 为什么翻卡不够（用户要求"英语可以丰富教法了"）：
 *   翻卡只测"见过没"。一个词你看着眼熟，但拼不出来、放进句子里认不出、
 *   不知道它是什么词性，考场上照样错。所以这里分五种能力分别练：
 *     英译中 → 读得上不上道
 *     中译英 → 能不能主动取出这个词
 *     拼写   → 会不会写
 *     例句填空 → 在语境里认不认得
 *     词性   → 语法位对不对
 *
 * 判分不只看对错：
 *   差一个字母给"就差一个字母，第 N 个字母不对"——
 *   直接判错不给线索，下次还会错在同一个字母上。
 *
 * 引擎逻辑全在 EngTrain（纯 Java，电脑上 61 项断言全过），本类只做界面。
 */
public class EngView {

    private static final int C_BG = 0xFF14161C;
    private static final int C_BG2 = 0xFF1C1F27;
    private static final int C_BG3 = 0xFF262A34;
    private static final int C_FG = 0xFFE8EAF0;
    private static final int C_FG2 = 0xFFC3C9D6;
    private static final int C_FG3 = 0xFF6B7282;
    private static final int C_TEAL = 0xFF3ECFAF;
    private static final int C_AMBER = 0xFFE0A94A;
    private static final int C_RED = 0xFFE05A54;
    private static final int C_PURPLE = 0xFF7C6CF0;

    private final Activity act;
    private final Store store;
    private Dialog dlg;

    private List<EngTrain.W> all = new ArrayList<EngTrain.W>();
    private List<EngTrain.W> queue = new ArrayList<EngTrain.W>();
    private int[] modes = {1, 2, 3, 4, 5};
    private String title = "英语训练";

    private int idx = 0;
    private EngTrain.Item cur;
    private EngTrain.Item answered;       // 已作答的题（用于回显）
    private int picked = -1;              // 选择题选了哪个
    private int verdict = -1;
    private String feedback = "";
    private int right = 0, wrong = 0;
    private final Random rnd = new Random();

    public EngView(Activity a, Store s) {
        this.act = a;
        this.store = s;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }

    // ── 入口 ──────────────────────────────────────────────
    /** 全部词库、五种题型混练。 */
    public void open() {
        open(0, -1, "英语训练");
    }

    /**
     * @param from  词库起始下标（1 基，和计划里的 vocab 区间一致）
     * @param to    结束下标（含）；-1 表示到末尾
     */
    public void open(int from, int to, String title) {
        this.title = title == null || title.isEmpty() ? "英语训练" : title;
        loadWords();
        queue.clear();
        int a = Math.max(1, from);
        int b = to <= 0 ? all.size() : Math.min(to, all.size());
        for (int i = a - 1; i < b && i < all.size(); i++) queue.add(all.get(i));
        if (queue.isEmpty()) queue.addAll(all);
        idx = 0;
        right = 0;
        wrong = 0;
        cur = null;
        answered = null;
        feedback = "";
        build();
        next();
        dlg.show();
    }

    /** 只练错题：传入单词列表。 */
    public void openList(List<String> words, String title) {
        this.title = title;
        loadWords();
        queue.clear();
        for (EngTrain.W w : all) {
            for (String s : words) {
                if (w.w.equalsIgnoreCase(s)) { queue.add(w); break; }
            }
        }
        if (queue.isEmpty()) queue.addAll(all);
        idx = 0;
        right = 0;
        wrong = 0;
        cur = null;
        answered = null;
        feedback = "";
        build();
        next();
        dlg.show();
    }

    // ── 词库 ──────────────────────────────────────────────
    private void loadWords() {
        if (!all.isEmpty()) return;
        try {
            InputStream in = act.getAssets().open("words.json");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            JSONArray arr = root.optJSONArray("words");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                all.add(new EngTrain.W(i + 1,
                        o.optString("w", ""), o.optString("ph", ""),
                        o.optString("pos", ""), o.optString("cn", ""),
                        o.optString("ex", ""), o.optString("exCn", "")));
            }
        } catch (Exception e) {
            // 词库读不出来就直说，不要给一个空界面让人以为加载中
            toast("词库读取失败：" + e.getMessage());
        }
    }

    // ── 界面骨架 ──────────────────────────────────────────
    private LinearLayout root;
    private LinearLayout cardBody;
    private TextView tvProgress;

    private void build() {
        dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

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
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(C_FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvProgress = new TextView(act);
        tvProgress.setTextSize(12);
        tvProgress.setTextColor(C_FG3);
        top.addView(tvProgress);
        root.addView(top);

        // 题型开关
        LinearLayout chips = new LinearLayout(act);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(10), 0, dp(10), dp(8));
        String[] names = {"英译中", "中译英", "拼写", "填空", "词性"};
        for (int i = 0; i < 5; i++) {
            final int m = i + 1;
            final TextView chip = new TextView(act);
            chip.setText(names[i]);
            chip.setTextSize(11.5f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(0, dp(6), 0, dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(2); lp.rightMargin = dp(2);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                setOnlyMode(m);
                renderChips(chips, names, m);
                next();
            });
            chips.addView(chip);
        }
        renderChips(chips, names, modes.length == 1 ? modes[0] : 0);
        root.addView(chips);

        ScrollView sc = new ScrollView(act);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        cardBody = new LinearLayout(act);
        cardBody.setOrientation(LinearLayout.VERTICAL);
        cardBody.setPadding(dp(12), dp(4), dp(12), dp(24));
        sc.addView(cardBody);
        root.addView(sc);

        dlg.setContentView(root);
    }

    private void setOnlyMode(int m) {
        modes = new int[]{m};
    }

    private void renderChips(LinearLayout chips, String[] names, int active) {
        for (int i = 0; i < chips.getChildCount() && i < names.length; i++) {
            TextView tv = (TextView) chips.getChildAt(i);
            boolean on = (i + 1) == active;
            tv.setTextColor(on ? C_BG : C_FG3);
            tv.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(7));
            g.setColor(on ? C_TEAL : C_BG2);
            tv.setBackground(g);
        }
    }

    // ── 下一题 ────────────────────────────────────────────
    private void next() {
        if (queue.isEmpty()) return;
        if (idx >= queue.size()) {
            renderSummary();
            return;
        }
        answered = null;
        picked = -1;
        verdict = -1;
        feedback = "";

        // 最多试 12 次：某个词可能五种题型都出不了（没例句又不可拼写）
        cur = null;
        for (int attempt = 0; attempt < 12 && cur == null; attempt++) {
            EngTrain.W w = queue.get(idx % queue.size());
            int mode = modes[rnd.nextInt(modes.length)];
            cur = EngTrain.make(mode, w, queue, all, rnd);
            if (cur == null) idx++;
            if (idx >= queue.size()) break;
        }
        if (cur == null) {
            // 这一轮里剩下的词都出不了题：直接结算，别让人卡住
            renderSummary();
            return;
        }
        renderQuestion();
    }

    private void renderQuestion() {
        cardBody.removeAllViews();
        tvProgress.setText((idx + 1) + " / " + queue.size()
                + "　✓" + right + " ✗" + wrong);

        // 题干卡
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(C_BG2);
        card.setBackground(g);
        card.setPadding(dp(14), dp(14), dp(14), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);

        TextView modeTag = new TextView(act);
        modeTag.setText(EngTrain.modeName(cur.mode));
        modeTag.setTextSize(11);
        modeTag.setTextColor(C_TEAL);
        modeTag.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(modeTag);

        TextView sub2 = new TextView(act);
        sub2.setText(EngTrain.modeHint(cur.mode));
        sub2.setTextSize(11);
        sub2.setTextColor(C_FG3);
        sub2.setPadding(0, dp(2), 0, dp(10));
        card.addView(sub2);

        TextView prompt = new TextView(act);
        prompt.setText(cur.prompt);
        prompt.setTextSize(cur.mode == EngTrain.M_CLOZE ? 16f : 22f);
        prompt.setTextColor(C_FG);
        prompt.setTypeface(Typeface.DEFAULT_BOLD);
        prompt.setLineSpacing(dp(5), 1f);
        card.addView(prompt);

        if (cur.sub != null && !cur.sub.isEmpty()) {
            TextView s = new TextView(act);
            s.setText(cur.sub);
            s.setTextSize(12.5f);
            s.setTextColor(C_FG3);
            s.setPadding(0, dp(6), 0, 0);
            card.addView(s);
        }
        cardBody.addView(card);

        if (cur.isChoice()) renderChoices();
        else renderInput();

        if (answered != null) renderFeedback();
    }

    // ── 选择题 ────────────────────────────────────────────
    private void renderChoices() {
        for (int i = 0; i < cur.options.length; i++) {
            final int k = i;
            TextView b = new TextView(act);
            b.setText(((char) ('A' + i)) + "． " + cur.options[i]);
            b.setTextSize(15);
            b.setTextColor(C_FG);
            b.setPadding(dp(14), dp(13), dp(14), dp(13));
            b.setLineSpacing(dp(3), 1f);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            // 已作答：正确答案标绿，选错的标红
            if (answered != null) {
                if (i == cur.answer) {
                    bg.setColor(0xFF1E3A34);
                    b.setTextColor(C_TEAL);
                } else if (i == picked) {
                    bg.setColor(0xFF3A2020);
                    b.setTextColor(C_RED);
                } else {
                    bg.setColor(C_BG2);
                    b.setTextColor(C_FG3);
                }
            } else {
                bg.setColor(C_BG2);
            }
            b.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            b.setLayoutParams(lp);

            if (answered == null) {
                b.setOnClickListener(v -> {
                    picked = k;
                    verdict = EngTrain.judgeChoice(cur, k);
                    answered = cur;
                    if (verdict == EngTrain.J_RIGHT) right++;
                    else {
                        wrong++;
                        recordWrong(cur);
                    }
                    feedback = "";
                    renderQuestion();
                });
            }
            cardBody.addView(b);
        }
    }

    // ── 拼写 / 填空 ───────────────────────────────────────
    private void renderInput() {
        EditText et = new EditText(act);
        et.setHint(cur.mode == EngTrain.M_SPELL ? "敲出这个单词" : "填这一空");
        et.setHintTextColor(0xFF4E5565);
        et.setTextColor(C_FG);
        et.setTextSize(17);
        et.setBackgroundColor(C_BG2);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        if (answered != null) {
            et.setText(cur.answerText);
            et.setEnabled(false);
            et.setTextColor(C_TEAL);
        }
        cardBody.addView(et);

        if (answered == null) {
            TextView go = new TextView(act);
            go.setText("确认");
            go.setTextSize(14);
            go.setTextColor(C_BG);
            go.setTypeface(Typeface.DEFAULT_BOLD);
            go.setGravity(Gravity.CENTER);
            go.setPadding(0, dp(11), 0, dp(11));
            GradientDrawable gg = new GradientDrawable();
            gg.setCornerRadius(dp(10));
            gg.setColor(C_TEAL);
            go.setBackground(gg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            go.setLayoutParams(lp);
            go.setOnClickListener(v -> {
                String in = et.getText().toString();
                if (EngTrain.norm(in).isEmpty()) return;
                verdict = EngTrain.judgeText(cur, in);
                answered = cur;
                if (verdict == EngTrain.J_RIGHT) right++;
                else {
                    wrong++;
                    recordWrong(cur);
                }
                feedback = verdict == EngTrain.J_NEAR
                        ? EngTrain.nearHint(cur.answerText, in) : "";
                renderQuestion();
            });
            cardBody.addView(go);
        }
    }

    // ── 反馈 ──────────────────────────────────────────────
    private void renderFeedback() {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(C_BG2);
        c.setBackground(g);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);

        TextView head = new TextView(act);
        if (verdict == EngTrain.J_RIGHT) {
            head.setText("✓ 正确");
            head.setTextColor(C_TEAL);
        } else if (verdict == EngTrain.J_NEAR) {
            head.setText("✗ 差一点。" + feedback);
            head.setTextColor(C_AMBER);
        } else {
            head.setText("✗ 不对");
            head.setTextColor(C_RED);
        }
        head.setTextSize(15);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(head);

        TextView ans = new TextView(act);
        ans.setText("答案：" + cur.answerText
                + (cur.word != null && !cur.word.ph.isEmpty() ? "　/" + cur.word.ph + "/" : ""));
        ans.setTextSize(14);
        ans.setTextColor(C_FG);
        ans.setPadding(0, dp(6), 0, 0);
        c.addView(ans);

        if (cur.word != null && !cur.word.cn.isEmpty()) {
            TextView cn = new TextView(act);
            cn.setText("释义：" + cur.word.cn);
            cn.setTextSize(13);
            cn.setTextColor(C_FG2);
            cn.setPadding(0, dp(3), 0, 0);
            c.addView(cn);
        }
        // 完形填空 / 其它题型都补上原例句，把词放回语境里看一遍
        if (cur.word != null && !cur.word.ex.isEmpty()) {
            TextView ex = new TextView(act);
            ex.setText("例句：" + cur.word.ex);
            ex.setTextSize(12.5f);
            ex.setTextColor(C_FG3);
            ex.setPadding(0, dp(5), 0, 0);
            ex.setLineSpacing(dp(3), 1f);
            c.addView(ex);
            if (!cur.word.exCn.isEmpty()) {
                TextView exCn = new TextView(act);
                exCn.setText(cur.word.exCn);
                exCn.setTextSize(12);
                exCn.setTextColor(C_FG3);
                c.addView(exCn);
            }
        }
        cardBody.addView(c);

        TextView nxt = new TextView(act);
        nxt.setText(idx + 1 >= queue.size() ? "看总结" : "下一题 ›");
        nxt.setTextSize(14);
        nxt.setTextColor(C_BG);
        nxt.setTypeface(Typeface.DEFAULT_BOLD);
        nxt.setGravity(Gravity.CENTER);
        nxt.setPadding(0, dp(12), 0, dp(12));
        GradientDrawable gg = new GradientDrawable();
        gg.setCornerRadius(dp(10));
        gg.setColor(C_TEAL);
        nxt.setBackground(gg);
        nxt.setOnClickListener(v -> {
            idx++;
            next();
        });
        cardBody.addView(nxt);
    }

    // ── 结算 ──────────────────────────────────────────────
    private void renderSummary() {
        cardBody.removeAllViews();
        tvProgress.setText("已完成");

        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(C_BG2);
        c.setBackground(g);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(act);
        int total = right + wrong;
        t.setText("这一轮做完了");
        t.setTextSize(18);
        t.setTextColor(C_FG);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);

        TextView s = new TextView(act);
        s.setText("答对 " + right + " 题，答错 " + wrong + " 题"
                + (total > 0 ? "\n正确率 " + (right * 100 / total) + "%" : ""));
        s.setTextSize(14);
        s.setTextColor(C_FG2);
        s.setPadding(0, dp(8), 0, 0);
        s.setLineSpacing(dp(5), 1f);
        c.addView(s);

        if (total > 0 && right * 100 / Math.max(1, total) < 70) {
            TextView tip = new TextView(act);
            tip.setText("低于 70%。建议先回单词卡把这一批词过一遍，再回来做。");
            tip.setTextSize(12.5f);
            tip.setTextColor(C_AMBER);
            tip.setPadding(0, dp(8), 0, 0);
            tip.setLineSpacing(dp(3), 1f);
            c.addView(tip);
        }
        cardBody.addView(c);

        TextView again = new TextView(act);
        again.setText("再来一轮");
        again.setTextSize(14);
        again.setTextColor(C_BG);
        again.setTypeface(Typeface.DEFAULT_BOLD);
        again.setGravity(Gravity.CENTER);
        again.setPadding(0, dp(12), 0, dp(12));
        GradientDrawable gg = new GradientDrawable();
        gg.setCornerRadius(dp(10));
        gg.setColor(C_TEAL);
        again.setBackground(gg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        again.setLayoutParams(lp);
        again.setOnClickListener(v -> {
            idx = 0;
            right = 0;
            wrong = 0;
            next();
        });
        cardBody.addView(again);
    }

    // ── 错题入库 ──────────────────────────────────────────
    /** 错题进错题库，key 用 "eng:<单词>"，和题库的 id 不会撞。 */
    private void recordWrong(EngTrain.Item it) {
        if (it == null || it.word == null) return;
        if (store == null) return;
        try {
            store.addWrong("eng:" + it.word.w, "eng", it.word.w,
                    EngTrain.modeName(it.mode) + "｜" + it.word.cn);
        } catch (Exception ignored) {
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
