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

    /**
     * 玩家这一题**实际敲进去的内容**（原样，未归一化）。
     *
     * 为什么不能回显标准答案：原来 answered != null 时输入框直接 setText(cur.answerText)，
     * 屏幕上显示的就是正确答案。于是「差一个字母」判错时，玩家看到的输入框里
     * 明明写着和答案一模一样的词 —— 反馈说我错，界面说我全对，这是最伤信任的一种 bug。
     * 用户截图里 accusation 显示在框里、判定却说「差一点」，就是这个原因。
     */
    private String typedText = "";
    private int picked = -1;              // 选择题选了哪个
    private int verdict = -1;
    private String feedback = "";
    private int right = 0, wrong = 0;
    private final Random rnd = new Random();

    /** 这一轮的开始时间（毫秒）。做题过程中不变，一轮结束拿去算用时。 */
    private long roundStart = 0;
    /**
     * 本轮指纹，用于幂等写入。
     * 组成：起始时间 + 题型 + 题量。同一轮无论写几次都是同一条，
     * 避免「上报成功了但没收到响应、用户又点了一遍」在榜上算两次。
     */
    private String roundRev = "";

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
        roundStart = System.currentTimeMillis();
        roundRev = "";
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
        roundStart = System.currentTimeMillis();
        roundRev = "";
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
            // 词性题：英文是主体，中文释义只做小字提示。
            // 原来 prompt 里 "apple  苹果" 整串用 22pt，中文和英文一样大，
            // 视觉上中文成了主角 —— 用户直接指出来了。
            s.setTextSize(cur.mode == EngTrain.M_POS ? 13f : 12.5f);
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
            String opt = cur.options[i];
            // 词性题：光给 "art." 等于在考缩写，补上中文才是在考词性
            if (cur.mode == EngTrain.M_POS) {
                String nm = EngTrain.posName(opt);
                if (!nm.isEmpty()) opt = opt + "　" + nm;
            }
            b.setText(((char) ('A' + i)) + "． " + opt);
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
        // ── 关掉输入法的「自作聪明」 ────────────────────────
        // 默认 TYPE_CLASS_TEXT 在两个地方会替学生改答案：
        //   ① TYPE_TEXT_FLAG_CAP_SENTENCES 会自动把首字母大写；
        //   ② 输入法的自动纠错/联想会悄悄换掉拼错的词。
        // 拼写题要的是「学生自己写的那个词」，被输入法改过就不再是他的答案了。
        // 顺带关掉全角输入：中文输入法开着时敲出来是全角字母（U+FF41 起），
        // 屏幕上几乎看不出差别却必然判定不等 —— 归一化那边已经能兜住，
        // 这里再从源头减少一种可能。
        // 不用 VISIBLE_PASSWORD —— 有些输入法会因此把已输入的字母暂时遮成圆点。
        // NO_SUGGESTIONS 关联想；CAP_CHARACTERS 保证不会被自动改成首字母大写。
        et.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        if (answered != null) {
            // 回显玩家自己敲的东西，不是标准答案。
            // 答对了就显示他写的（本来就等于答案）；答错了显示他错在哪，
            // 再配合下面的「答案：xxx」对照，才知道差在哪个字母。
            et.setText(typedText);
            et.setEnabled(false);
            et.setTextColor(verdict == EngTrain.J_RIGHT ? C_TEAL : C_RED);
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
                typedText = in;                 // 原样留底，用于回显
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
        String ansText = cur.answerText;
        // 词性题的答案也补中文名，和选项口径一致
        if (cur.mode == EngTrain.M_POS) {
            String nm = EngTrain.posName(ansText);
            if (!nm.isEmpty()) ansText = ansText + "（" + nm + "）";
        }
        ans.setText("答案：" + ansText
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

        // 落盘。以前 right/wrong 是两个内存变量，退出就归零，
        // 用户连「这周做了多少题」都看不到 —— 排行榜要的数据以前不存在。
        saveRound();

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
            // 新一轮必须重置计时和指纹，否则第二轮会继承第一轮的用时
            roundStart = System.currentTimeMillis();
            roundRev = "";
            next();
        });
        cardBody.addView(again);
    }

    /**
     * 一轮结束落盘。
     *
     * 之前 right / wrong 是纯内存变量，退出界面就归零，一行都没存过。
     * 排行榜要的数据从这一刻起才有。
     *
     * 幂等：roundRev 由「起始时间+题量」算，同一轮写多次仍是同一条，
     * Store 那边 rev 上有 UNIQUE 约束兜底。
     * 题量为 0 的一轮不记 —— 那是打开看一眼就退出的，不该拉低正确率。
     */
    private void saveRound() {
        if (store == null) return;
        int total = right + wrong;
        if (total <= 0) return;
        if (roundRev.isEmpty()) {
            roundRev = Long.toHexString(roundStart) + "-" + total + "-" + right;
        }
        try {
            Store.EngRound r = new Store.EngRound();
            r.day = StateLogic.todayIso();
            r.mode = modes.length > 0 ? modes[0] : 0;   // 记录本轮起手题型
            r.right = right;
            r.wrong = wrong;
            r.ms = Math.max(0, System.currentTimeMillis() - roundStart);
            r.started = roundStart;
            r.rev = roundRev;
            store.addEngRound(r);
        } catch (Exception ignored) {
            // 存不上不能影响用户继续做题
        }
        uploadPending();
    }

    /**
     * 把没传上去的成绩补传给服务器。
     *
     * 为什么要「补传」而不是「当场传完就算了」：
     *   同学做题的地方经常没网（教室、地铁），当场传必失败。
     *   所以成绩先进本地 eng_round 表（up=0），
     *   每次出成绩单的时候顺手把积压的都试一遍 ——
     *   幂等键是 rev，重复传不会在榜上算两次。
     *
     * 全程后台线程，失败静默：上传失败不该弹窗打断做题的人。
     */
    private void uploadPending() {
        if (store == null) return;
        final Account acc = Account.load(act);
        if (!acc.loggedIn()) return;
        final java.util.List<Store.EngRound> pend = store.pendingEngRounds();
        if (pend.isEmpty()) return;

        new Thread(() -> {
            int sent = 0;
            for (Store.EngRound r : pend) {
                Api.R res = Api.postRound(acc.token, r.right, r.wrong, r.ms, r.started, r.rev);
                // 只有服务器明确收下才算传过。
                // ignored=true（用时太短/超上限）也算「服务器已处理」，
                // 再传一次结果还是一样，不该无限重试。
                if (res.ok || (res.body != null && res.body.optBoolean("ignored"))) {
                    store.markEngUploaded(r.rev);
                    sent++;
                } else {
                    break;   // 网络断了就别硬刷，下次再说
                }
            }
            final int n = sent;
            if (n > 0) act.runOnUiThread(() ->
                    android.widget.Toast.makeText(act, "已上传 " + n + " 轮成绩",
                            android.widget.Toast.LENGTH_SHORT).show());
        }).start();
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
