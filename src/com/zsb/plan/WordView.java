package com.zsb.plan;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 背单词界面。
 *
 * 用户原话："英语只需要单词就行了"
 * 所以英语这一科在 App 里就是这个界面，没有语法章节。
 *
 * 交互（刻意做得极简，单手能操作）：
 *   · 一张卡：正面单词 + 音标，点一下翻到背面看释义和例句
 *   · 两张按钮：认识 / 不认识
 *   · 不认识的进"再背一遍"队列，本组结束前必须再过一次
 *
 * 数据分层：
 *   words.json  3370 词（word / 音标 / 词性 / 中文 / 例句 / 短语）
 *   plan.json   每天 vocab{from,to} + vocabReview[[from,to],...]
 */
public class WordView {

    // 与 BookView / MainActivity 同一套配色
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
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Dialog dlg;

    // 词库（懒加载一次，常驻）
    private static List<JSONObject> allWords = null;

    // 本轮队列
    private final List<JSONObject> queue = new ArrayList<>();
    private final List<JSONObject> again = new ArrayList<>();
    private int idx = 0;
    private int known = 0;
    private int unknown = 0;

    private TextView tvProgress;
    private TextView tvWord;
    private TextView tvPhon;
    private TextView tvFace;     // 背面释义区
    private TextView tvExample;
    private LinearLayout bottomBar;
    private LinearLayout answerBar;
    private boolean flipped = false;

    public WordView(Activity a) { this.act = a; }

    // ── 词库加载 ────────────────────────────────────────────
    private List<JSONObject> loadAll() {
        if (allWords != null) return allWords;
        List<JSONObject> out = new ArrayList<>();
        try {
            InputStream in = act.getAssets().open("words.json");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject root = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            JSONArray arr = root.getJSONArray("words");
            for (int i = 0; i < arr.length(); i++) out.add(arr.getJSONObject(i));
        } catch (Exception e) {
            // 词库读不出来就返回空，界面显示提示而不是崩溃
        }
        allWords = out;
        return out;
    }

    // ── 打开一组词 ──────────────────────────────────────────
    /**
     * @param from 起始下标（含）
     * @param to   结束下标（不含）
     * @param title 标题，如"今天的单词"
     */
    public void open(int from, int to, String title) {
        List<JSONObject> all = loadAll();
        if (all.isEmpty()) {
            Toast("词库未加载");
            return;
        }
        queue.clear();
        again.clear();
        idx = 0; known = 0; unknown = 0;

        int a = Math.max(0, Math.min(from, all.size()));
        int b = Math.max(a, Math.min(to, all.size()));
        for (int i = a; i < b; i++) queue.add(all.get(i));
        if (queue.isEmpty()) {
            Toast("这组没有单词");
            return;
        }
        buildDialog(title);
        render();
        dlg.show();
    }

    /** 复习队列：[{from,to}, ...] 依次加入。 */
    public void openReview(JSONArray ranges, String title) {
        List<JSONObject> all = loadAll();
        if (all.isEmpty()) { Toast("词库未加载"); return; }
        queue.clear(); again.clear(); idx = 0; known = 0; unknown = 0;
        try {
            for (int r = 0; r < ranges.length(); r++) {
                JSONArray rg = ranges.getJSONArray(r);
                int a = Math.max(0, rg.getInt(0));
                int b = Math.min(all.size(), rg.getInt(1));
                for (int i = a; i < b; i++) queue.add(all.get(i));
            }
        } catch (Exception e) { /* 区间坏了就忽略 */ }
        if (queue.isEmpty()) { Toast("没有需要复习的单词"); return; }
        // 复习时打乱，避免按顺序背造成的假记忆
        Collections.shuffle(queue);
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

        // 顶栏：关闭 + 进度
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
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        top.addView(t, tlp);

        tvProgress = new TextView(act);
        tvProgress.setTextSize(13);
        tvProgress.setTextColor(C_FG3);
        top.addView(tvProgress);
        root.addView(top);

        // 卡片区
        FrameLayout cardWrap = new FrameLayout(act);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.setMargins(dp(16), dp(6), dp(16), dp(6));
        root.addView(cardWrap, clp);

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(C_BG2);
        cbg.setCornerRadius(dp(20));
        cbg.setStroke(dp(1), C_BG3);
        card.setBackground(cbg);
        cardWrap.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        tvWord = new TextView(act);
        tvWord.setTextSize(40);
        tvWord.setTextColor(C_FG);
        tvWord.setTypeface(Typeface.DEFAULT_BOLD);
        tvWord.setGravity(Gravity.CENTER);
        card.addView(tvWord);

        tvPhon = new TextView(act);
        tvPhon.setTextSize(15);
        tvPhon.setTextColor(C_FG3);
        tvPhon.setGravity(Gravity.CENTER);
        tvPhon.setPadding(0, dp(8), 0, 0);
        card.addView(tvPhon);

        tvFace = new TextView(act);
        tvFace.setTextSize(19);
        tvFace.setTextColor(C_FG);
        tvFace.setGravity(Gravity.CENTER);
        tvFace.setPadding(0, dp(22), 0, 0);
        card.addView(tvFace);

        tvExample = new TextView(act);
        tvExample.setTextSize(14);
        tvExample.setTextColor(C_FG2);
        tvExample.setGravity(Gravity.CENTER);
        tvExample.setPadding(dp(6), dp(16), dp(6), 0);
        tvExample.setLineSpacing(dp(4), 1f);
        card.addView(tvExample);

        // 点卡片翻面
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { flip(); }
        });

        // 底部按钮区
        bottomBar = new LinearLayout(act);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(16), dp(6), dp(16), dp(6));
        answerBar = new LinearLayout(act);
        answerBar.setOrientation(LinearLayout.HORIZONTAL);
        answerBar.setGravity(Gravity.CENTER);
        answerBar.setPadding(dp(16), dp(6), dp(16), dp(6));

        LinearLayout barWrap = new LinearLayout(act);
        barWrap.setOrientation(LinearLayout.VERTICAL);
        barWrap.addView(answerBar);
        barWrap.addView(bottomBar);
        root.addView(barWrap);

        TextView hint = new TextView(act);
        hint.setText("点卡片翻面 · 认识就不用再背");
        hint.setTextSize(12);
        hint.setTextColor(0xFF4A5060);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(14));
        root.addView(hint);

        dlg.setContentView(root);
    }

    private TextView mkBtn(String text, int color, View.OnClickListener l) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextSize(16);
        b.setTextColor(color);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(15), 0, dp(15));
        GradientDrawable g = new GradientDrawable();
        g.setColor(C_BG3);
        g.setCornerRadius(dp(13));
        b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    private void render() {
        if (idx >= queue.size()) { finish(); return; }
        final JSONObject w = queue.get(idx);
        flipped = false;

        tvProgress.setText((idx + 1) + " / " + queue.size()
                + "   ✓" + known + "  ✕" + unknown);
        tvWord.setText(w.optString("w", ""));
        String ph = w.optString("ph", "");
        tvPhon.setText(ph);
        tvPhon.setVisibility(ph.isEmpty() ? View.GONE : View.VISIBLE);
        // 背面先藏起来，逼自己先回忆
        tvFace.setText("…");
        tvFace.setTextColor(C_FG3);
        tvFace.setTextSize(15);
        tvExample.setText("");

        // 未翻面：只有一个"看答案"按钮
        answerBar.removeAllViews();
        bottomBar.removeAllViews();
        TextView see = mkBtn("看释义", C_TEAL, new View.OnClickListener() {
            @Override public void onClick(View v) { flip(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        answerBar.addView(see, lp);
    }

    private void flip() {
        if (idx >= queue.size()) return;
        JSONObject w = queue.get(idx);
        flipped = true;
        tvFace.setTextColor(C_FG);
        tvFace.setTextSize(19);
        String pos = w.optString("pos", "");
        String cn = w.optString("cn", "");
        tvFace.setText(pos.isEmpty() ? cn : (pos + "  " + cn));

        String ex = w.optString("ex", "");
        String excn = w.optString("exCn", "");
        if (!ex.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(ex);
            if (!excn.isEmpty()) sb.append("\n").append(excn);
            tvExample.setText(sb.toString());
        } else {
            String phr = w.optString("phr", "");
            tvExample.setText(phr);
        }

        // 翻面后换成两个判断按钮：认识 / 不认识
        answerBar.removeAllViews();
        bottomBar.removeAllViews();

        TextView no = mkBtn("不认识", C_RED, new View.OnClickListener() {
            @Override public void onClick(View v) { mark(false); }
        });
        TextView yes = mkBtn("认识", C_TEAL, new View.OnClickListener() {
            @Override public void onClick(View v) { mark(true); }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.setMargins(0, 0, dp(5), 0);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.setMargins(dp(5), 0, 0, 0);
        bottomBar.addView(no, lp1);
        bottomBar.addView(yes, lp2);
    }

    private void mark(boolean ok) {
        if (idx >= queue.size()) return;
        JSONObject w = queue.get(idx);
        if (ok) {
            known++;
        } else {
            unknown++;
            again.add(w);      // 进"再背一遍"，本组结束前必须再过
        }
        idx++;
        if (idx >= queue.size()) {
            if (!again.isEmpty()) {
                // 把不认识的放回队列尾部，清空 again 重新计
                int n = again.size();
                queue.addAll(again);
                again.clear();
                render();
                Toast("还有 " + n + " 个要再过一遍");
                return;
            }
            finish();
            return;
        }
        render();
    }

    private void finish() {
        LinearLayout done = new LinearLayout(act);
        done.setOrientation(LinearLayout.VERTICAL);
        done.setGravity(Gravity.CENTER);
        done.setBackgroundColor(C_BG);

        TextView big = new TextView(act);
        big.setText("这一组背完了");
        big.setTextSize(22);
        big.setTextColor(C_TEAL);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setGravity(Gravity.CENTER);
        done.addView(big);

        TextView sub = new TextView(act);
        sub.setText("认识 " + known + " 个 · 不认识 " + unknown + " 个");
        sub.setTextSize(15);
        sub.setTextColor(C_FG2);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(14), 0, dp(28));
        done.addView(sub);

        TextView close = mkBtn("完成", C_FG, new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(180), ViewGroup.LayoutParams.WRAP_CONTENT);
        done.addView(close, lp);

        FrameLayout root = new FrameLayout(act);
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

    public boolean isShowing() {
        return dlg != null && dlg.isShowing();
    }
}
