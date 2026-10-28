package com.zsb.plan;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 做题界面 —— 单选，当场判分，做错进错题本。
 *
 * 用户原话："根本没有题目"
 * 之前 pract 任务写的是"练习册《进制转换》p5-6"，
 * 那只是"去哪儿做"，不是"做哪道题"。手机上翻不到、也没判分。
 *
 * 现在：题目来自 assets/bank/*.json（真题解析出来的），
 * 每道题有题干、四个选项、答案、解析。
 *
 * 判分规则：
 *   · 选了立刻反馈，对的绿、错的红并显示正确项
 *   · 做错的题记进 Store（错题本），下次可以只刷错题
 *   · 没答案的题（a<0）只让思考不判分，如实显示"暂无答案"
 */
public class QuizView {

    private static final int C_BG = 0xFF14161C;
    private static final int C_BG2 = 0xFF1C1F27;
    private static final int C_BG3 = 0xFF262A34;
    private static final int C_FG = 0xFFE8EAF0;
    private static final int C_FG2 = 0xFFC3C9D6;
    private static final int C_FG3 = 0xFF6B7282;
    private static final int C_TEAL = 0xFF3ECFAF;
    private static final int C_RED = 0xFFE05A54;
    private static final int C_AMBER = 0xFFE0A94A;

    private final Activity act;
    private final Store store;
    private Dialog dlg;

    private static final Map<String, List<JSONObject>> cache = new HashMap<>();

    private final List<JSONObject> queue = new ArrayList<>();
    private final List<JSONObject> wrong = new ArrayList<>();
    private int idx = 0;
    private int right = 0;
    private boolean answered = false;

    private TextView tvProgress;
    private TextView tvStem;
    private LinearLayout optBox;
    private TextView tvSol;
    private LinearLayout navBar;
    private ScrollView scroller;

    public QuizView(Activity a, Store s) {
        this.act = a;
        this.store = s;
    }

    // ── 题库加载 ────────────────────────────────────────────
    private List<JSONObject> bank(String subject) {
        if (cache.containsKey(subject)) return cache.get(subject);
        List<JSONObject> out = new ArrayList<>();
        try {
            InputStream in = act.getAssets().open("bank/" + subject + ".json");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            JSONArray arr = root.getJSONArray("q");
            for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        } catch (Exception e) { /* 读不到就空 */ }
        cache.put(subject, out);
        return out;
    }

    /** 某科某年的题。 */
    public void openYear(String subject, int year, String title) {
        List<JSONObject> all = bank(subject);
        queue.clear();
        for (JSONObject q : all) {
            if (q.optInt("y", 0) == year) queue.add(q);
        }
        if (queue.isEmpty()) {
            Toast("题库里没有 " + year + " 年的题");
            return;
        }
        start(title);
    }

    /**
     * 按学科抽题。
     * @param limit  最多几道
     * @param onlyWrong  true = 只做错题本里的
     */
    public void openSubject(String subject, int limit, boolean onlyWrong, String title) {
        List<JSONObject> all = bank(subject);
        if (all.isEmpty()) {
            Toast("题库未加载");
            return;
        }
        queue.clear();
        if (onlyWrong) {
            List<String> ids = store.wrongIds(subject);
            for (JSONObject q : all) {
                if (ids.contains(q.optString("id"))) queue.add(q);
            }
            if (queue.isEmpty()) {
                Toast("错题本是空的，先去做题");
                return;
            }
        } else {
            // 优先给有答案的题：能被判分才有练习价值
            List<JSONObject> withA = new ArrayList<>();
            List<JSONObject> noA = new ArrayList<>();
            for (JSONObject q : all) {
                if (q.optInt("a", -1) >= 0) withA.add(q); else noA.add(q);
            }
            Collections.shuffle(withA);
            Collections.shuffle(noA);
            for (int i = 0; i < withA.size() && queue.size() < limit; i++) queue.add(withA.get(i));
            for (int i = 0; i < noA.size() && queue.size() < limit; i++) queue.add(noA.get(i));
        }
        if (queue.isEmpty()) { Toast("没有题"); return; }
        start(title);
    }

    private void start(String title) {
        idx = 0; right = 0; answered = false;
        wrong.clear();
        buildDialog(title);
        render();
        dlg.show();
    }

    // ── 界面 ────────────────────────────────────────────────
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics());
    }

    private void buildDialog(String title) {
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
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        top.addView(close);

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(C_FG2);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvProgress = new TextView(act);
        tvProgress.setTextSize(13);
        tvProgress.setTextColor(C_FG3);
        top.addView(tvProgress);
        root.addView(top);

        // 可滚动内容区
        scroller = new ScrollView(act);
        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(12));

        tvStem = new TextView(act);
        tvStem.setTextSize(17);
        tvStem.setTextColor(C_FG);
        tvStem.setLineSpacing(dp(6), 1f);
        body.addView(tvStem);

        optBox = new LinearLayout(act);
        optBox.setOrientation(LinearLayout.VERTICAL);
        optBox.setPadding(0, dp(16), 0, 0);
        body.addView(optBox);

        tvSol = new TextView(act);
        tvSol.setTextSize(14);
        tvSol.setTextColor(C_FG2);
        tvSol.setLineSpacing(dp(5), 1f);
        tvSol.setPadding(dp(2), dp(16), dp(2), 0);
        tvSol.setVisibility(View.GONE);
        body.addView(tvSol);

        scroller.addView(body);
        root.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        navBar = new LinearLayout(act);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setGravity(Gravity.CENTER);
        navBar.setPadding(dp(16), dp(6), dp(16), dp(16));
        root.addView(navBar);

        dlg.setContentView(root);
    }

    private void render() {
        if (idx >= queue.size()) { finish(); return; }
        answered = false;
        JSONObject q = queue.get(idx);

        tvProgress.setText((idx + 1) + " / " + queue.size() + "   ✓" + right);
        tvStem.setText((idx + 1) + ". " + q.optString("q", ""));
        tvSol.setVisibility(View.GONE);
        tvSol.setText("");

        optBox.removeAllViews();
        navBar.removeAllViews();
        scroller.scrollTo(0, 0);

        JSONArray opts = q.optJSONArray("o");
        int n = opts == null ? 0 : opts.length();
        for (int i = 0; i < n; i++) {
            final int fi = i;
            final String text = opts.optString(i, "");
            final TextView row = new TextView(act);
            row.setText("ABCD".charAt(Math.min(i, 3)) + ".  " + text);
            row.setTextSize(16);
            row.setTextColor(C_FG);
            row.setLineSpacing(dp(4), 1f);
            row.setPadding(dp(15), dp(14), dp(15), dp(14));
            GradientDrawable g = new GradientDrawable();
            g.setColor(C_BG2);
            g.setCornerRadius(dp(12));
            g.setStroke(dp(1), C_BG3);
            row.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(5), 0, dp(5));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (answered) return;
                    choose(fi, row);
                }
            });
            optBox.addView(row, lp);
        }
    }

    private void choose(int pick, TextView row) {
        if (idx >= queue.size()) return;
        answered = true;
        JSONObject q = queue.get(idx);
        int ans = q.optInt("a", -1);
        JSONArray opts = q.optJSONArray("o");
        int n = opts == null ? 0 : opts.length();

        // 解析可信度护栏，见 trustedSol()
        String sol = trustedSol(q);

        if (ans < 0) {
            // 没答案：只标记"已选"，不判对错 —— 不编造答案
            paint(row, C_AMBER);
            tvSol.setVisibility(View.VISIBLE);
            tvSol.setText("这道题暂未收录答案，先自己判断。"
                    + (sol.isEmpty() ? "" : "\n\n" + sol));
        } else {
            // 把所有选项该上的色都上齐
            for (int i = 0; i < n; i++) {
                View cv = optBox.getChildAt(i);
                if (!(cv instanceof TextView)) continue;
                if (i == ans) paint((TextView) cv, C_TEAL);
                else if (i == pick) paint((TextView) cv, C_RED);
            }
            if (pick == ans) {
                right++;
            } else {
                wrong.add(q);
                store.addWrong(q.optString("id"), q.optString("subjectSafe", ""), q.optString("q"), "");
            }
            tvSol.setVisibility(View.VISIBLE);
            tvSol.setText(pick == ans
                    ? ("答对了。" + (sol.isEmpty() ? "" : "\n\n" + sol))
                    : ("答错了，正确答案是 " + "ABCD".charAt(ans) + "。"
                       + (sol.isEmpty() ? "" : "\n\n" + sol)));
        }

        // 下一题按钮
        navBar.removeAllViews();
        TextView next = new TextView(act);
        boolean last = idx + 1 >= queue.size();
        next.setText(last ? "看结果" : "下一题");
        next.setTextSize(16);
        next.setTextColor(C_FG);
        next.setTypeface(Typeface.DEFAULT_BOLD);
        next.setGravity(Gravity.CENTER);
        next.setPadding(0, dp(15), 0, dp(15));
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_BG3);
        g.setCornerRadius(dp(13));
        next.setBackground(g);
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                idx++;
                if (idx >= queue.size()) finish(); else render();
            }
        });
        navBar.addView(next, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void paint(TextView v, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_BG3);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(2), color);
        v.setBackground(g);
        v.setTextColor(color);
    }

    private void finish() {
        int total = queue.size();
        int pct = total == 0 ? 0 : (right * 100 / total);

        LinearLayout done = new LinearLayout(act);
        done.setOrientation(LinearLayout.VERTICAL);
        done.setGravity(Gravity.CENTER);
        done.setBackgroundColor(C_BG);

        TextView big = new TextView(act);
        big.setText(total + " 题做完");
        big.setTextSize(22);
        big.setTextColor(C_TEAL);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        done.addView(big);

        TextView sub = new TextView(act);
        sub.setText("答对 " + right + " 题 · 正确率 " + pct + "%"
                + (wrong.isEmpty() ? "" : "\n错的 " + wrong.size() + " 题已进错题本"));
        sub.setTextSize(15);
        sub.setTextColor(C_FG2);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(dp(6), 1f);
        sub.setPadding(dp(20), dp(14), dp(20), dp(28));
        done.addView(sub);

        TextView close = new TextView(act);
        close.setText("完成");
        close.setTextSize(16);
        close.setTextColor(C_FG);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(15), 0, dp(15));
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_BG3);
        g.setCornerRadius(dp(13));
        close.setBackground(g);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        done.addView(close, new LinearLayout.LayoutParams(dp(180), dp(50)));

        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(C_BG);
        root.addView(done, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dlg.setContentView(root);
    }

    private void Toast(String s) {
        android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void dismiss() {
        if (dlg != null && dlg.isShowing()) dlg.dismiss();
        dlg = null;
    }

    public boolean isShowing() { return dlg != null && dlg.isShowing(); }

    /**
     * 解析可信度护栏 —— 2026-10-28 事故的直接防线。
     *
     * 当时的现象：题干问"500 个 32*32 点阵需要多少容量"，下面挂的解析却在讲
     * "中英文切换的快捷键是 Ctrl+Space"。根因是 PDF 转题库时题号与解析块错位，
     * 解析被成段挂到了别的题上（comp.json 里 2007 年的解析有 89% 实际是 2013 年的）。
     * 离线已清洗掉 129 条错配，这里再加一道运行时护栏：
     * 解析必须和本题有实质重合才显示，否则宁可不显示 ——
     * 空解析只是少点信息，错解析直接教错。
     *
     * 判定（任一成立才算可信）：
     *   A. 解析含题干 >=8 字的连续原文（是连续片段，不是零散词）
     *   B. 解析含正确选项的完整文本（>=4 字）
     */
    private static String trustedSol(JSONObject q) {
        String sol = q.optString("sol", "");
        if (sol.trim().isEmpty()) return "";
        String stem = normText(q.optString("q", ""));
        String solN = normText(sol);
        if (stem.isEmpty() || solN.isEmpty()) return sol;

        if (longestRun(stem, solN) >= 8) return sol;

        int ai = q.optInt("a", -1);
        JSONArray os = q.optJSONArray("o");
        if (os != null && ai >= 0 && ai < os.length()) {
            String on = normText(os.optString(ai, ""));
            if (on.length() >= 4 && solN.contains(on)) return sol;
        }

        // C. 弱条件：题干里有 >=6 字与解析重合（实际数据里只有两条靠这条放行，
        //    都是"下图所示"这类题干不含答案线索的正确解析）。门槛刻意留高，
        //    错配样本的这条分数都 <0.10，拦得住。
        if (longestRun(solN, stem) >= 6 && solN.length() >= 15) {
            int hit = 0;
            for (int i = 0; i + 6 <= stem.length(); i++) {
                if (solN.contains(stem.substring(i, i + 6))) { hit++; }
            }
            if (hit * 100 / Math.max(1, stem.length()) >= 12) return sol;
        }
        return "";
    }

    /** 去掉空白与标点，只留实义字符，用于比对。 */
    private static String normText(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if ("\uFF0C\u3002\u3001\uFF1B\uFF1A\uFF1F\uFF01\uFF08\uFF09\u201C\u201D\u2018\u2019\u00B7\u2026\u2014,.;:?!()[]\"'*=_".indexOf(c) >= 0) continue;
            b.append(c);
        }
        return b.toString();
    }

    /** a 的最长连续子串有多长出现在 b 里。 */
    private static int longestRun(String a, String b) {
        int best = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.length() - i <= best) break;
            for (int L = a.length() - i; L > best; L--) {
                if (b.contains(a.substring(i, i + L))) { best = L; break; }
            }
        }
        return best;
    }
}
