package com.zsb.plan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 主界面。全部用代码构建布局，不依赖 XML——
 * 这样构建链只有 aapt2 + javac + d8，不上 Gradle 也能出包。
 */
public class MainActivity extends Activity {

    // ── 配色 ──────────────────────────────────────────────
    static final int C_BG      = 0xFF14161C;
    static final int C_BG2     = 0xFF1C1F27;
    static final int C_BG3     = 0xFF262A34;
    static final int C_LINE    = 0xFF2C313C;
    static final int C_FG      = 0xFFE8EAF0;
    static final int C_FG2     = 0xFFA8AEBC;
    static final int C_FG3     = 0xFF6B7282;
    static final int C_PURPLE  = 0xFF7C6CF0;
    static final int C_TEAL    = 0xFF2FA8A0;
    static final int C_ORANGE  = 0xFFE0873F;
    static final int C_PINK    = 0xFFE89A9D;
    static final int C_GREEN   = 0xFF4BBF8A;
    static final int C_AMBER   = 0xFFE0A94A;

    private Plan plan;
    private Store store;
    private BookView book;
    private QuizView quizView;
    private WordView wordView;
    private MathView mathView;
    private EngView engView;
    private LinalgView linalgView;
    private Update updater;

    private LinearLayout content;
    private TextView tvDays, tvLeftLabel;
    private LinearLayout navBar;
    private final TextView[] navBtn = new TextView[TAB_NAMES.length];
    private int tab = 0;
    private int idx = 0;

    // 计时
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private Runnable tickRun;
    private String timingKey;      // "yyyy-MM-dd|taskId"
    private long timingStart;

    /**
     * 导航只有四个。
     *
     * 原来有「检索」这一栏，但它渲染的是 d.reviews —— 和「今日」页里
     * 那张「到期检索」卡片是同一批数据（同一份 plan.json、同一个
     * reviewRow）。用户看到的"还是以前的东西"，因为那确实是同一样东西
     * 换了个位置重复显示。
     *
     * 而且检索本身不该单独成页：它属于当天的动作，逾期会累积到当天，
     * 所以「今日」就是它唯一正确的位置。删掉不丢功能。
     */
    private static final String[] TAB_NAMES = {"今日", "错题库", "全表", "数据"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        plan = Plan.parse(readAsset("plan.json"));
        store = new Store(this);
        book = new BookView(this);
        quizView = new QuizView(this, store);
        wordView = new WordView(this);
        mathView = new MathView(this);
        engView = new EngView(this, store);
        linalgView = new LinalgView(this);
        idx = store.todayIndex(plan);

        Window w = getWindow();
        w.setStatusBarColor(C_BG);
        w.setNavigationBarColor(C_BG);

        setContentView(buildRoot());
        render();

        // 启动后静默查一次更新（有新版才弹窗，没网也不打扰）
        updater = new Update(this);
        updater.check(true);
    }

    // ── 根布局 ────────────────────────────────────────────
    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);

        // 顶栏
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackgroundColor(C_BG);
        head.setPadding(dp(14), dp(10), dp(14), dp(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView t1 = new TextView(this);
        t1.setText("剩余 ");
        t1.setTextColor(C_FG2);
        t1.setTextSize(13);
        row.addView(t1);

        tvDays = new TextView(this);
        tvDays.setTextColor(C_FG);
        tvDays.setTextSize(22);
        tvDays.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(tvDays);

        TextView t2 = new TextView(this);
        t2.setText(" 天");
        t2.setTextColor(C_FG2);
        t2.setTextSize(13);
        row.addView(t2);

        tvLeftLabel = new TextView(this);
        tvLeftLabel.setTextColor(C_FG3);
        tvLeftLabel.setTextSize(11);
        tvLeftLabel.setPadding(dp(8), dp(3), 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvLeftLabel.setLayoutParams(lp);
        row.addView(tvLeftLabel);

        head.addView(row);
        root.addView(head);

        // 导航
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(C_BG);
        navBar.setPadding(dp(4), 0, dp(4), dp(2));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int k = i;
            TextView tv = new TextView(this);
            tv.setText(TAB_NAMES[i]);
            tv.setTextSize(14);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, dp(11), 0, dp(11));
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tv.setOnClickListener(v -> { tab = k; render(); });
            navBtn[i] = tv;
            navBar.addView(tv);
        }
        root.addView(navBar);

        View line = new View(this);
        line.setBackgroundColor(C_LINE);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // 内容
        ScrollView sc = new ScrollView(this);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        sc.setFillViewport(true);
        sc.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(C_BG);
        content.setPadding(dp(12), dp(12), dp(12), dp(28));
        sc.addView(content);
        root.addView(sc);

        return root;
    }

    // ── 渲染分发 ──────────────────────────────────────────
    private void render() {
        tvDays.setText(String.valueOf(store.daysLeft(plan)));
        tvLeftLabel.setText("已确认 " + store.completed.size() + " / " + plan.days.size() + " 天");

        for (int i = 0; i < navBtn.length; i++) {
            navBtn[i].setTextColor(i == tab ? C_FG : C_FG3);
            navBtn[i].setTypeface(i == tab ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }

        content.removeAllViews();
        switch (tab) {
            case 0: renderToday(); break;
            case 1: renderHard(); break;
            case 2: renderPlanList(); break;
            default: renderStats(); break;
        }
    }

    // ══ 今日 ══════════════════════════════════════════════
    private void renderToday() {
        if (plan.days.isEmpty()) { content.addView(hint("计划数据为空")); return; }
        idx = Math.max(0, Math.min(idx, plan.days.size() - 1));
        final Plan.Day d = plan.days.get(idx);

        content.addView(dayHeader(d));

        // 任务
        if (!d.tasks.isEmpty()) {
            LinearLayout card = card("今日任务", null);
            for (Plan.Task t : d.tasks) card.addView(taskRow(d.date, t));
            content.addView(card);
        }

        // 检索
        //
        // 原来这里还有个独立的「检索」tab，渲染的是同一批 d.reviews ——
        // 明明是同一份数据换个位置重复显示，用户自然会问"怎么还是以前的东西"。
        // 检索本来就属于当天：逾期会累积到当天，不单独成页。已删。
        //
        // 但"还有多少项没做、会不会累积"这件事要让人看得见，
        // 所以把逾期统计补在这一张卡上，而不是靠翻页去数。
        if (!d.reviews.isEmpty()) {
            int done = 0;
            for (Plan.Review r : d.reviews) {
                if (store.completed.contains("R-" + r.id)) done++;
            }
            int left = d.reviews.size() - done;
            String sub = left == 0
                    ? "全部做完了，可以确认这一天"
                    : "还剩 " + left + " 项没做 · 合上书默写，再翻页核对";
            LinearLayout card = card("到期检索 · " + d.reviews.size() + " 项", sub);
            for (Plan.Review r : d.reviews) card.addView(reviewRow(d.date, r));
            content.addView(card);
        }

        // 词汇 —— 英语这一科在 App 里就是这块
        // 用户原话："英语只需要单词就行了"
        // 原来这里只把 json 片段当文字打印出来（{"from":20,"to":40}），
        // 既不能背也不能点。现在做成真入口。
        if (d.vocabFrom >= 0 && d.vocabTo > d.vocabFrom) {
            final int vf = d.vocabFrom, vt = d.vocabTo;
            int nNew = vt - vf;

            LinearLayout vcard = card("英语 · 背单词", null);

            TextView big = new TextView(this);
            big.setText("今天 " + nNew + " 个新词");
            big.setTextColor(C_FG);
            big.setTextSize(17);
            big.setTypeface(Typeface.DEFAULT_BOLD);
            vcard.addView(big);

            TextView sub = new TextView(this);
            sub.setText("第 " + (vf + 1) + " – " + vt + " 个"
                    + (d.vocabReviewJson != null ? " · 另有旧词要复习" : ""));
            sub.setTextColor(C_FG3);
            sub.setTextSize(12);
            sub.setPadding(0, dp(3), 0, 0);
            vcard.addView(sub);

            // 主按钮：背今天的词
            TextView go = new TextView(this);
            go.setText("开始背这 " + nNew + " 个 →");
            go.setTextColor(C_TEAL);
            go.setTextSize(13.5f);
            go.setTypeface(Typeface.DEFAULT_BOLD);
            go.setPadding(0, dp(10), 0, dp(2));
            go.setOnClickListener(v -> wordView.open(vf, vt, "今天的单词"));
            vcard.addView(go);

            // 练一练：翻卡只测"见过没"，测不出版面真实能力。
            // 五种题型分别查读、写、语境、词性 —— 这是"丰富教法"的落点。
            TextView drill = new TextView(this);
            drill.setText("✎ 练一练这 " + nNew + " 个词（五种题型）→");
            drill.setTextColor(C_PURPLE);
            drill.setTextSize(13.5f);
            drill.setTypeface(Typeface.DEFAULT_BOLD);
            drill.setPadding(0, dp(9), 0, dp(2));
            drill.setOnClickListener(v -> engView.open(vf, vt, "练今天这批词"));
            vcard.addView(drill);

            // 复习按钮：只在有旧词时出现
            final String rvJson = d.vocabReviewJson;
            if (rvJson != null) {
                TextView rev = new TextView(this);
                rev.setText("复习旧词 →");
                rev.setTextColor(C_AMBER);
                rev.setTextSize(13.5f);
                rev.setTypeface(Typeface.DEFAULT_BOLD);
                rev.setPadding(0, dp(6), 0, dp(2));
                rev.setOnClickListener(v -> {
                    try {
                        wordView.openReview(new org.json.JSONArray(rvJson), "复习旧词");
                    } catch (Exception e) {
                        android.widget.Toast.makeText(MainActivity.this,
                                "复习数据读取失败", android.widget.Toast.LENGTH_SHORT).show();
                    }
                });
                vcard.addView(rev);

                TextView drillOld = new TextView(this);
                drillOld.setText("✎ 练这批旧词 →");
                drillOld.setTextColor(C_PURPLE);
                drillOld.setTextSize(13.5f);
                drillOld.setTypeface(Typeface.DEFAULT_BOLD);
                drillOld.setPadding(0, dp(6), 0, dp(2));
                drillOld.setOnClickListener(v -> {
                    try {
                        org.json.JSONArray arr = new org.json.JSONArray(rvJson);
                        List<String> ws = new ArrayList<String>();
                        for (int i = 0; i < arr.length(); i++) {
                            org.json.JSONObject o = arr.optJSONObject(i);
                            if (o != null) {
                                String w = o.optString("w", "");
                                if (!w.isEmpty()) ws.add(w);
                            }
                        }
                        if (ws.isEmpty()) {
                            engView.open();
                        } else {
                            engView.openList(ws, "练旧词");
                        }
                    } catch (Exception e) {
                        engView.open();
                    }
                });
                vcard.addView(drillOld);
            }

            vcard.addView(checkRow(d.date, "V-" + d.date, "今天的单词过完了"));
            content.addView(vcard);
        }

        // 推进闸门
        content.addView(commitBlock(d));
    }

    private View dayHeader(Plan.Day d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 0, 0, dp(10));

        boolean isToday = Store.todayIso().equals(d.date);

        TextView title = new TextView(this);
        title.setText(d.date + "  周" + d.weekday + (isToday ? "   · 今天" : ""));
        title.setTextColor(isToday ? C_TEAL : C_FG);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView sub = new TextView(this);
        String s = "第 " + (idx + 1) + " / " + plan.days.size() + " 学习日";
        if (!TextUtils.isEmpty(d.phase)) s += "   ·   " + d.phase;
        int examLeft = store.realDaysToExam(plan);
        if (examLeft > 0) s += "   ·   距考 " + examLeft + " 天";
        sub.setText(s);
        sub.setTextColor(C_FG3);
        sub.setTextSize(12);
        sub.setPadding(0, dp(4), 0, 0);
        box.addView(sub);

        // 前后翻页
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(0, dp(9), 0, 0);

        nav.addView(smallBtn("‹ 上一天", () -> {
            if (idx > 0) { idx--; stopTimer(); render(); }
        }, idx > 0));

        nav.addView(smallBtn("下一天 ›", () -> {
            if (idx < plan.days.size() - 1) { idx++; stopTimer(); render(); }
        }, idx < plan.days.size() - 1));

        if (idx != store.todayIndex(plan)) {
            nav.addView(smallBtn("回到今天", () -> {
                idx = store.todayIndex(plan); stopTimer(); render();
            }, true));
        }
        box.addView(nav);
        return box;
    }

    // ── 任务 → 教材页 ─────────────────────────────────────
    // 内置了 6 本书：三本教材 + 三本练习册。
    // learn 指向教材，pract 指向练习册，页码由 plan.json 的 pdfStart 给出
    // （生成时已按各书的印刷页→PDF 页偏移换算好）。
    static class BookRef {
        String id, title, shortName;
        int page;
    }

    /** 学科代码 → 中文名。题库按钮和页面标题都用它。 */
    static String subjectName(String s) {
        if ("math".equals(s)) return "数学";
        if ("comp".equals(s)) return "计算机";
        if ("eng".equals(s)) return "英语";
        return s == null ? "" : s;
    }

    private BookRef bookRefOf(Plan.Task t) {
        if (t == null) return null;

        String subj = t.subject == null ? "" : t.subject;
        String bookId, title, shortName;
        if ("math".equals(subj)) {
            bookId = "math_book"; title = "大学数学基础教材"; shortName = "数学教材";
        } else if ("comp".equals(subj)) {
            bookId = "comp_book"; title = "计算机基础教材"; shortName = "计算机教材";
        } else if ("eng".equals(subj)) {
            bookId = "eng_book"; title = "大学英语基础教材"; shortName = "英语教材";
        } else {
            return null;
        }

        int page = 0;
        if ("pract".equals(t.kind)) {
            // 练习册：任务里的 startPage 是练习册印刷页，偏移 +2（实测校验过）
            bookId = bookId.replace("_book", "_pract");
            title = shortName.replace("教材", "") + "同步练习册";
            shortName = shortName.replace("教材", "") + "练习册";
            if (t.startPage > 0) page = t.startPage + 2;
        } else {
            if (t.pdfStart != null) page = t.pdfStart;
            else if (t.startPage > 0) page = t.startPage + 1;
        }

        if (page <= 0) return null;
        BookRef r = new BookRef();
        r.id = bookId;
        r.title = title;
        r.shortName = shortName;
        r.page = page;
        return r;
    }

    private View taskRow(final String date, final Plan.Task t) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setGravity(Gravity.TOP);

        final String tid = Plan.taskId(t);
        final boolean done = store.isDone(date, tid);

        // 勾选框
        final TextView chk = new TextView(this);
        chk.setText(done ? "✓" : "");
        chk.setTextSize(13);
        chk.setGravity(Gravity.CENTER);
        chk.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        g.setStroke(dp(2), done ? C_PURPLE : C_FG3);
        g.setColor(done ? C_PURPLE : Color.TRANSPARENT);
        chk.setBackground(g);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(24), dp(24));
        clp.topMargin = dp(1);
        chk.setLayoutParams(clp);
        chk.setOnClickListener(v -> {
            boolean nv = store.toggleDone(date, tid);
            chk.setText(nv ? "✓" : "");
            GradientDrawable g2 = new GradientDrawable();
            g2.setCornerRadius(dp(6));
            g2.setStroke(dp(2), nv ? C_PURPLE : C_FG3);
            g2.setColor(nv ? C_PURPLE : Color.TRANSPARENT);
            chk.setBackground(g2);
            renderHeaderOnly();
            // 勾选状态变化可能影响推进按钮，重画整页
            render();
        });
        row.addView(chk);

        // 文字区
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colp.leftMargin = dp(11);
        col.setLayoutParams(colp);

        Plan.Subject sj = plan.subject(t.subject);
        Plan.Chapter ch = plan.chapter(t.chapterId);

        TextView name = new TextView(this);
        String line1;
        if ("learn".equals(t.kind) && ch != null) {
            line1 = sj.name + " · " + ch.name;
        } else {
            line1 = TextUtils.isEmpty(t.note) ? sj.name : t.note;
        }
        name.setText(line1);
        name.setTextColor(done ? C_FG3 : C_FG);
        name.setTextSize(14.5f);
        if (!done) name.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(name);

        // 页码 + 时长
        List<String> meta = new ArrayList<>();
        if (t.startPage > 0 && t.endPage >= t.startPage) {
            meta.add("p" + t.startPage + "-" + t.endPage);
        }
        if (t.min > 0) meta.add(t.min + " 分钟");
        long el = store.timingOf(date, tid);
        if (el > 1000) meta.add("已计时 " + fmtDur(el));

        TextView m = new TextView(this);
        m.setText(TextUtils.join("   ", meta));
        m.setTextColor(C_FG3);
        m.setTextSize(11.5f);
        m.setPadding(0, dp(3), 0, 0);
        col.addView(m);
        if (!TextUtils.isEmpty(t.note) && "learn".equals(t.kind)) {
            TextView note = new TextView(this);
            note.setText(t.note);
            note.setTextColor(0xFF5C6373);
            note.setTextSize(11);
            note.setPadding(0, dp(2), 0, 0);
            col.addView(note);
        }

        // 打开教材 —— 点这行文字直接跳对应页，不用去翻 PDF
        final BookRef ref = bookRefOf(t);
        if (ref != null) {
            TextView open = new TextView(this);
            open.setText("📖 打开" + ref.shortName + " p" + ref.page);
            open.setTextColor(C_TEAL);
            open.setTextSize(12.5f);
            open.setTypeface(Typeface.DEFAULT_BOLD);
            open.setPadding(0, dp(7), 0, dp(1));
            open.setOnClickListener(v -> book.open(ref.id, ref.title, ref.page));
            col.addView(open);
        }

        // 做题 —— 任务本身带题库时才有这个按钮
        // 用户原话："根本没有题目"：光写"练习册 p5-6"没法在手机上做
        if (!TextUtils.isEmpty(t.bankSubject)) {
            TextView quiz = new TextView(this);
            int n = t.bankLimit > 0 ? t.bankLimit : 10;

            // 数学不走题库：用户明确说数学题库不要了，改成工具。
            // 题库测的是"记没记住"，而数学考的是"会不会推"。
            boolean isMath = "math".equals(t.bankSubject);
            String label;
            if (isMath) {
                label = "📈 函数图像与求导";
            } else if (t.bankYear > 0) {
                label = "✎ 做 " + t.bankYear + " 年真题";
            } else {
                label = "✎ 做题（" + n + " 道）";
            }
            quiz.setText(label);
            quiz.setTextColor(C_AMBER);
            quiz.setTextSize(12.5f);
            quiz.setTypeface(Typeface.DEFAULT_BOLD);
            quiz.setPadding(0, dp(6), 0, dp(1));
            quiz.setOnClickListener(v -> {
                if (isMath) {
                    mathView.open(0);
                } else if (t.bankYear > 0) {
                    quizView.openYear(t.bankSubject, t.bankYear,
                            t.bankYear + " 年真题");
                } else {
                    quizView.openSubject(t.bankSubject, n, false,
                            subjectName(t.bankSubject) + " 练习");
                }
            });
            col.addView(quiz);

            // 数学再给一个直达公式表的入口：查公式比做题更常用
            if (isMath) {
                TextView tbl = new TextView(this);
                tbl.setText("📋 求导公式对照表");
                tbl.setTextColor(C_TEAL);
                tbl.setTextSize(12.5f);
                tbl.setTypeface(Typeface.DEFAULT_BOLD);
                tbl.setPadding(0, dp(4), 0, dp(1));
                tbl.setOnClickListener(v -> mathView.open(1));
                col.addView(tbl);

                TextView la = new TextView(this);
                la.setText("🔄 线性变换（矩阵拉扯平面）");
                la.setTextColor(C_PURPLE);
                la.setTextSize(12.5f);
                la.setTypeface(Typeface.DEFAULT_BOLD);
                la.setPadding(0, dp(4), 0, dp(1));
                la.setOnClickListener(v -> linalgView.open());
                col.addView(la);
            }
        }

        row.addView(col);

        // 计时按钮
        final TextView timer = new TextView(this);
        final String tkey = Store.key(date, tid);
        boolean running = tkey.equals(timingKey);
        timer.setText(running ? "■" : "▶");
        timer.setTextSize(13);
        timer.setTextColor(running ? C_PINK : C_FG3);
        timer.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(C_BG3);
        timer.setBackground(bg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(34), dp(30));
        tlp.leftMargin = dp(6);
        timer.setLayoutParams(tlp);
        timer.setOnClickListener(v -> {
            if (tkey.equals(timingKey)) stopTimer();
            else startTimer(tkey, m);
            render();
        });
        row.addView(timer);

        return row;
    }

    private View reviewRow(final String date, final Plan.Review r) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(9), 0, dp(9));

        final String rid = "R-" + r.id;
        final boolean done = store.isDone(date, rid);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView chk = new TextView(this);
        chk.setText(done ? "✓" : "");
        chk.setTextSize(13);
        chk.setGravity(Gravity.CENTER);
        chk.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        g.setStroke(dp(2), done ? C_PURPLE : C_FG3);
        g.setColor(done ? C_PURPLE : Color.TRANSPARENT);
        chk.setBackground(g);
        chk.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        chk.setOnClickListener(v -> store.toggleDone(date, rid));
        row.addView(chk);

        Plan.Subject sj = plan.subject(r.subject);
        Plan.Chapter ch = plan.chapter(r.chapterId);

        TextView tv = new TextView(this);
        tv.setText(r.kind + "  " + sj.name + (ch != null ? " · " + ch.name : "")
                + (r.min > 0 ? "  (" + r.min + " 分钟)" : ""));
        tv.setTextColor(done ? C_FG3 : C_FG);
        tv.setTextSize(13.5f);
        tv.setPadding(dp(11), 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(lp);
        row.addView(tv);

        box.addView(row);

        // 默写框
        final EditText et = new EditText(this);
        et.setHint("默写公式 / 定义（写不出就空着）");
        et.setHintTextColor(0xFF4E5565);
        et.setTextColor(C_FG2);
        et.setTextSize(14);
        et.setBackgroundColor(C_BG3);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setMinLines(1);
        et.setText(store.noteOf(date, rid));
        et.setTag(Store.key(date, rid));
        // 边输入边存：靠失焦保存会丢——用户可能直接点「推进」或切页签，
        // 那些动作会 render() 重建视图，还没失焦的文本就没了。
        et.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                store.setNote(date, rid, s.toString());
            }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(7);
        elp.leftMargin = dp(35);
        et.setLayoutParams(elp);
        box.addView(et);

        // 三档自评
        LinearLayout grades = new LinearLayout(this);
        grades.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(6);
        glp.leftMargin = dp(35);
        grades.setLayoutParams(glp);

        grades.addView(gradeBtn("写不出", () -> {
            store.bumpHard(r.chapterId.isEmpty() ? r.id : r.chapterId);
            store.setDone(date, rid, false);
            toast("已进错题库，明天再来");
            render();
        }));
        grades.addView(gradeBtn("写一半", () -> {
            store.setDone(date, rid, true);
            store.setNote(date, rid, et.getText().toString());
            toast("记下了");
            render();
        }));
        grades.addView(gradeBtn("写出来了", () -> {
            store.setDone(date, rid, true);
            store.setNote(date, rid, et.getText().toString());
            toast("过关");
            render();
        }));
        box.addView(grades);

        View sep = new View(this);
        sep.setBackgroundColor(C_LINE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        slp.topMargin = dp(9);
        sep.setLayoutParams(slp);
        box.addView(sep);

        return box;
    }

    private View commitBlock(final Plan.Day d) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, 0);

        List<String> ids = Plan.dayTaskIds(d);
        int doneN = 0;
        for (String id : ids) if (store.isDone(d.date, id)) doneN++;
        final boolean all = !ids.isEmpty() && doneN == ids.size();
        final boolean passed = store.completed.contains(d.date);

        TextView label = new TextView(this);
        label.setText("完成度 " + doneN + " / " + ids.size()
                + (all ? "   全部完成" : "   还有 " + (ids.size() - doneN) + " 项"));
        label.setTextColor(all ? C_GREEN : C_FG3);
        label.setTextSize(12.5f);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(6), 0, dp(8));
        box.addView(label);

        Button btn = new Button(this);
        if (passed) {
            btn.setText("已确认完成 · 点此撤销");
            btn.setTextColor(C_FG2);
            btn.setBackgroundColor(C_BG3);
            btn.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("把 " + d.date + " 退回未完成？剩余天数会增加 1。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("撤销", (x, y) -> {
                        store.uncommitDay(d.date);
                        idx = store.todayIndex(plan);
                        render();
                        toast("已撤销");
                    }).show());
        } else {
            btn.setText(all ? "今日完成 · 推进到下一天" : "把上面全部勾完才能推进");
            btn.setTextColor(all ? Color.WHITE : C_FG3);
            btn.setBackgroundColor(all ? C_PURPLE : C_BG3);
            btn.setEnabled(all);
            btn.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("确认 " + d.date + " 已完成？\n\n这会推进计划进度，剩余天数减少 1。")
                    .setNegativeButton("再想想", null)
                    .setPositiveButton("确认完成", (x, y) -> {
                        store.commitDay(d.date);
                        idx = store.todayIndex(plan);
                        render();
                        toast("已推进 · 剩余 " + store.daysLeft(plan) + " 天");
                    }).show());
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(2);
        btn.setLayoutParams(lp);
        btn.setTextSize(14);
        box.addView(btn);

        return box;
    }

    // ══ 错题库 ════════════════════════════════════════════
    private void renderHardMap() { renderHard(); }

    private void renderHard() {
        if (store.hard.isEmpty()) {
            content.addView(hint("错题库是空的。\n\n检索时选「写不出」，那一节就会进来。"
                    + "\n英语训练答错的单词也会自动进来。"));
            content.addView(wideBtn("✎ 练英语单词（五种题型）", () -> engView.open()));
            return;
        }
        LinearLayout card = card("错题库 · " + store.hard.size() + " 项",
                "等级越高越该回头看。清掉后就从列表移除。");
        for (final String id : new ArrayList<>(store.hard.keySet())) {
            Plan.Chapter ch = plan.chapter(id);
            Integer lv = store.hard.get(id);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(9), 0, dp(9));

            TextView tv = new TextView(this);
            // 英语错题的 key 是 "eng:单词"，不是章节 id，plan.chapter() 找不到。
            // 不特判的话这里会把 "eng:apple" 原样打出来，看着像脏数据。
            boolean isEngWord = id.startsWith("eng:");
            String nm;
            if (isEngWord) {
                nm = "英语 · 单词 " + id.substring(4);
            } else if (ch != null) {
                nm = plan.subject(ch.subject).name + " · " + ch.name;
            } else {
                nm = id;
            }
            tv.setText(nm + "    等级 " + (lv == null ? 1 : lv) + " / 3");
            tv.setTextColor(C_FG);
            tv.setTextSize(13.5f);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (isEngWord) {
                final String word = id.substring(4);
                tv.setOnClickListener(v -> {
                    List<String> one = new ArrayList<String>();
                    one.add(word);
                    engView.openList(one, "重练 " + word);
                });
            }
            row.addView(tv);

            row.addView(smallBtn("降级", () -> {
                Integer c = store.hard.get(id);
                if (c == null || c <= 1) { store.clearHard(id); toast("移出错题库"); }
                else { store.hard.put(id, c - 1); store.save(); toast("降一级"); }
                render();
            }, true));

            card.addView(row);
        }
        content.addView(card);
    }

    // ══ 全表 ══════════════════════════════════════════════
    private void renderPlanList() {
        LinearLayout card = card("全部 " + plan.days.size() + " 个学习日", "点任意一天查看并补做");
        final int cur = store.todayIndex(plan);
        for (int i = 0; i < plan.days.size(); i++) {
            final Plan.Day d = plan.days.get(i);
            final int pos = i;
            boolean done = store.completed.contains(d.date);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(11), 0, dp(11));

            View bar = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(2));
            bg.setColor(done ? C_GREEN : (i == cur ? C_PURPLE : C_BG3));
            bar.setBackground(bg);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(3), dp(30));
            blp.rightMargin = dp(11);
            bar.setLayoutParams(blp);
            row.addView(bar);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView a = new TextView(this);
            a.setText(d.date + "  周" + d.weekday);
            a.setTextColor(i == cur ? C_FG : C_FG2);
            a.setTextSize(13.5f);
            if (i == cur) a.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(a);

            TextView bx = new TextView(this);
            String st = "第 " + (i + 1) + " 天";
            if (!TextUtils.isEmpty(d.phase)) st += " · " + d.phase;
            st += " · " + d.tasks.size() + " 任务";
            if (!d.reviews.isEmpty()) st += " · " + d.reviews.size() + " 检索";
            if (done) st += "   ✓ 已确认";
            else if (i == cur) st += "   ← 当前";
            bx.setText(st);
            bx.setTextColor(done ? C_GREEN : C_FG3);
            bx.setTextSize(11.5f);
            bx.setPadding(0, dp(2), 0, 0);
            col.addView(bx);

            row.addView(col);

            row.setOnClickListener(v -> { idx = pos; tab = 0; stopTimer(); render(); });
            card.addView(row);

            if (i < plan.days.size() - 1) {
                View sep = new View(this);
                sep.setBackgroundColor(C_LINE);
                card.addView(sep, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
        content.addView(card);
    }

    // ══ 数据 ══════════════════════════════════════════════
    private void renderStats() {
        int total = plan.days.size();
        int conf = store.completed.size();
        int taskDone = store.done.size();

        LinearLayout c1 = card("总览", null);
        c1.addView(statRow("学习日总数", total + " 天"));
        c1.addView(statRow("已确认完成", conf + " 天"));
        c1.addView(statRow("剩余", store.daysLeft(plan) + " 天"));
        c1.addView(statRow("已勾选条目", taskDone + " 项"));
        c1.addView(statRow("错题库", store.hard.size() + " 项"));
        c1.addView(statRow("考试日", plan.meta.examDate));
        content.addView(c1);

        // 常驻工具入口：数学和英语的能力不该只在某一天的任务里出现。
        // 数学题库已经删了（用户不要），换成能画图、能查公式表的工具。
        LinearLayout tools = card("学习工具", "随时可用，不用等到某一天的任务");
        tools.addView(wideBtn("📈 函数图像（可叠导数曲线、看切线）", () -> mathView.open(0)));
        tools.addView(wideBtn("📋 求导公式对照表（40 条，点一条就画图）", () -> mathView.open(1)));
        tools.addView(wideBtn("✍ 求导练习（写答案，程序判等价）", () -> mathView.open(2)));
        tools.addView(wideBtn("🔤 英语五练（英译中/中译英/拼写/填空/词性）", () -> engView.open()));
        tools.addView(wideBtn("🔄 线性变换（2×2 矩阵怎么拉扯平面、特征方向）",
                () -> linalgView.open()));
        content.addView(tools);

        LinearLayout c2 = card("数据管理", "全部数据只存在这台手机上，不上传任何服务器");
        c2.addView(wideBtn("生成进度码", () -> {
            final String code = ProgressCode.encode(store.exportSnapshot());
            final String json = store.exportJson();
            if (code == null) { toast("生成失败"); return; }
            new AlertDialog.Builder(this)
                    .setTitle("进度码")
                    .setMessage("这是一串可以带走的进度。换手机或重装后，"
                            + "在新手机上点「导入进度码」粘进去，进度全部回来。\n\n"
                            + "长度 " + code.length() + " 字符，含校验位，抄错会直接报错。")
                    .setPositiveButton("复制进度码", (x, y) -> {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("zsb-progress", code));
                        toast("进度码已复制");
                    })
                    .setNeutralButton("复制完整备份", (x, y) -> {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("zsb-backup", json));
                        toast("完整 JSON 已复制（" + json.length() + " 字符）");
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        }));

        c2.addView(wideBtn("导入进度码", () -> {
            final EditText et = new EditText(this);
            et.setHint("粘贴进度码（ZSB1-…）或完整备份 JSON");
            et.setHintTextColor(C_FG3);
            et.setTextColor(C_FG);
            et.setTextSize(13);
            et.setMinLines(4);
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            new AlertDialog.Builder(this)
                    .setTitle("导入进度")
                    .setMessage("会覆盖当前全部进度。空白和换行不影响，小写也行。")
                    .setView(et)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("导入", (x, y) -> {
                        String in = et.getText().toString().trim();
                        if (in.isEmpty()) { toast("没有内容"); return; }
                        // 先当进度码解，不是码就按原始 JSON 处理
                        String json = ProgressCode.decode(in);
                        if (json == null) json = in;
                        try {
                            int n = store.importJson(json);
                            idx = store.todayIndex(plan);
                            render();
                            toast("已导入 " + n + " 条勾选");
                        } catch (Exception e) {
                            toast("导入失败：内容无效（进度码可能抄错）");
                        }
                    }).show();
        }));

        // 检查更新：按钮文案直接带版本号，省得用户不知道自己装的是哪一版。
        // 首次渲染显示本机版本，异步查到线上版本后追加「最新 vX.Y.Z」或
        // 「可升级 vX.Y.Z」。点一下重新查并弹窗（check(false)）。
        final Button btnUpd = wideBtn("检查更新 · 当前 v" + updater.currentVersion(),
                () -> updater.check(false));
        c2.addView(btnUpd);
        updater.queryLatest(true, (tag, code) -> {
            String local = updater.currentVersion();
            if (tag == null) {
                btnUpd.setText("检查更新 · 当前 v" + local + "（线上查询失败，点击重试）");
            } else if (code > Update.parseVer(local)) {
                btnUpd.setText("检查更新 · 当前 v" + local + " → 可升级 v" + tag);
            } else {
                btnUpd.setText("检查更新 · 当前 v" + local + "（已是最新）");
            }
        });
        c2.addView(wideBtn("清空全部进度", () -> new AlertDialog.Builder(this)
                .setMessage("清空全部勾选、默写和计时？无法撤销。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (x, y) -> {
                    store.resetAll();
                    idx = store.todayIndex(plan);
                    render();
                    toast("已清空");
                }).show()));
        content.addView(c2);

        LinearLayout c3 = card("这套计划的依据", null);
        TextView tv = new TextView(this);
        tv.setText("· 检索阶梯 R1 +4天 / R2 +18天 / R3 +53天 / R4 +133天，"
                + "按 Cepeda 2008 的最优间隔比例（考试延迟的 8.5%–17%）换算。\n"
                + "· 第二轮第三轮都是做题而不是重读，依据 Dunlosky 2013 对 10 种学习法的评级。\n"
                + "· 日期跟真实日历走：今天是哪天就显示哪天的任务。没学完的会一直留着，"
                + "勾选完成后这部分天数才从「剩余」里减掉。\n"
                + "· 每页耗时是估算值，跑一周后要用真实数据重算整张表。");
        tv.setTextColor(C_FG3);
        tv.setTextSize(12.5f);
        tv.setLineSpacing(dp(3), 1f);
        c3.addView(tv);
        content.addView(c3);
    }

    // ── 小组件 ────────────────────────────────────────────
    private LinearLayout card(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(roundRect(C_BG2, dp(14), C_LINE));
        box.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(11);
        box.setLayoutParams(lp);

        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextColor(C_FG);
            t.setTextSize(15.5f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            box.addView(t);
        }
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(C_FG3);
            s.setTextSize(12);
            s.setPadding(0, dp(3), 0, dp(9));
            box.addView(s);
        }
        return box;
    }

    private GradientDrawable roundRect(int fill, float radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private TextView hint(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(C_FG3);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(40), dp(10), dp(40));
        return tv;
    }

    private View checkRow(String date, String id, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, 0);

        final boolean done = store.isDone(date, id);
        final TextView chk = new TextView(this);
        chk.setText(done ? "✓" : "");
        chk.setTextSize(13);
        chk.setGravity(Gravity.CENTER);
        chk.setTextColor(Color.WHITE);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(6));
        g.setStroke(dp(2), done ? C_PURPLE : C_FG3);
        g.setColor(done ? C_PURPLE : Color.TRANSPARENT);
        chk.setBackground(g);
        chk.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        chk.setOnClickListener(v -> { store.toggleDone(date, id); render(); });
        row.addView(chk);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(C_FG2);
        tv.setTextSize(13.5f);
        tv.setPadding(dp(11), 0, 0, 0);
        row.addView(tv);
        return row;
    }

    private TextView smallBtn(String text, Runnable onClick, boolean enabled) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(enabled ? C_FG2 : 0xFF3C4250);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(9));
        g.setColor(C_BG3);
        tv.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(7);
        tv.setLayoutParams(lp);
        if (enabled) tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private TextView gradeBtn(String text, Runnable onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(C_FG2);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(7), 0, dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(C_BG3);
        tv.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(5);
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private Button wideBtn(String text, Runnable onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13.5f);
        b.setTextColor(C_FG);
        b.setBackgroundColor(C_BG3);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(7);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private View statRow(String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView a = new TextView(this);
        a.setText(k);
        a.setTextColor(C_FG3);
        a.setTextSize(13);
        a.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(a);

        TextView b = new TextView(this);
        b.setText(v);
        b.setTextColor(C_FG);
        b.setTextSize(13.5f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(b);
        return row;
    }

    // ── 计时 ──────────────────────────────────────────────
    /** 计时中那个任务的文字标签，每秒刷新它显示已计时长 */
    private TextView timingLabel;

    private void startTimer(String key, TextView label) {
        stopTimer();
        timingKey = key;
        timingStart = System.currentTimeMillis();
        timingLabel = label;
        tickRun = new Runnable() {
            @Override public void run() {
                if (timingKey == null) return;
                // 每秒只刷新那一行文字，不重画整页（重画会丢焦点、闪屏）
                if (timingLabel != null) {
                    long el = System.currentTimeMillis() - timingStart
                            + store.timingOf(dateOfKey(timingKey), idOfKey(timingKey));
                    timingLabel.setText("已计时 " + fmtDur(el));
                }
                tickHandler.postDelayed(this, 1000);
            }
        };
        tickHandler.postDelayed(tickRun, 1000);
    }

    private static String dateOfKey(String k) {
        int i = k.indexOf('|');
        return i < 0 ? "" : k.substring(0, i);
    }

    private static String idOfKey(String k) {
        int i = k.indexOf('|');
        return i < 0 ? "" : k.substring(i + 1);
    }

    private void stopTimer() {
        if (timingKey != null) {
            long el = System.currentTimeMillis() - timingStart;
            if (el > 1000) {
                store.addTiming(dateOfKey(timingKey), idOfKey(timingKey), el);
            }
        }
        timingKey = null;
        timingStart = 0;
        timingLabel = null;
        if (tickRun != null) tickHandler.removeCallbacks(tickRun);
        tickRun = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 切后台先把计时结掉，避免手机锁屏后时间白跑。
        // 这里不调 render()——Activity 已经不可见了，重建视图纯属浪费，
        // 而且会把输入框里的未提交文本冲掉。回到前台时 onResume 会重画。
        stopTimer();
        store.flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从后台回来：刷新一次，把后台期间不该累计的计时清掉并重画
        if (plan != null && store != null) render();
    }

    private void renderHeaderOnly() {
        tvDays.setText(String.valueOf(store.daysLeft(plan)));
        tvLeftLabel.setText("已确认 " + store.completed.size() + " / " + plan.days.size() + " 天");
    }

    // ── 工具 ──────────────────────────────────────────────
    private String readAsset(String name) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    getAssets().open(name), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("读不到 assets/" + name + "：" + e.getMessage(), e);
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private String fmtDur(long ms) {
        if (ms < 1000) return "";
        long s = Math.round(ms / 1000.0);
        long m = s / 60;
        if (m < 1) return s + " 秒";
        if (m < 60) return m + " 分";
        return (m / 60) + " 小时 " + (m % 60) + " 分";
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
