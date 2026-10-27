package com.zsb.plan;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 「今日必背」置顶卡的审计。
 *
 * 起因：用户说"不用默写毕竟手机上怎么默写，把答案写出来直接提醒用户
 * 答案是这个记住就行了，做成每日置顶，显眼每次打开看一遍更好"。
 *
 * 这个审计守三件事：
 *   ① 每一天都必须有卡（练习日靠回溯最近章兜底），不能有空白日；
 *   ② 每天行数不能多到"打开要滑三屏"，超了就是没做减法；
 *   ③ 真的按手机的宽度和字号把卡画出来，肉眼看文字有没有出界。
 */
public class RecallAudit {

    static int pass = 0, fail = 0;

    static void ok(String w, boolean c, String d) {
        if (c) pass++;
        else { fail++; System.out.println("  ✗ " + w + "  →  " + d); }
    }

    // 手机视口：360dp 宽、左右各 24dp 页边 → 卡片可用 312dp
    static final int W = 360, PAD = 24, CARD_W = W - PAD * 2;
    static final float SIZE_PT = 13.5f, SIZE_H = 14.5f, SIZE_K = 13f;

    static Plan plan;

    public static void main(String[] a) throws Exception {
        System.out.println("▶ 今日必背置顶卡");

        String raw = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("assets/plan.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        plan = Plan.parse(raw);

        // ── 1. 数据完整性 ──────────────────────────────
        ok("recall 卡数 = 章节数",
                plan.recall.size() == plan.chapters.size(),
                plan.recall.size() + " vs " + plan.chapters.size());

        int miss = 0;
        for (String cid : plan.chapters.keySet()) {
            if (!plan.recall.containsKey(cid)) miss++;
        }
        ok("每章都有卡", miss == 0, "缺 " + miss + " 章");

        int noExam = 0, thin = 0;
        for (Plan.Recall r : plan.recall.values()) {
            if (r.exam.isEmpty()) noExam++;
            if (r.points.size() < 3) thin++;
        }
        ok("每章都有考点", noExam == 0, noExam + " 章没有");
        ok("每章至少 3 条结论", thin == 0, thin + " 章不足");

        // ── 2. 每天都要有卡 ────────────────────────────
        int emptyDay = 0, maxLines = 0, maxChaps = 0;
        StringBuilder worst = new StringBuilder();
        for (int i = 0; i < plan.days.size(); i++) {
            List<String> chaps = recallChaps(i);
            if (chaps.isEmpty()) { emptyDay++; continue; }
            int lines = 0;
            for (String cid : chaps) {
                Plan.Recall r = plan.recall.get(cid);
                lines += r.points.size() + (r.exam.isEmpty() ? 0 : 1);
            }
            if (lines > maxLines) { maxLines = lines; worst.setLength(0); worst.append(plan.days.get(i).date); }
            maxChaps = Math.max(maxChaps, chaps.size());
        }
        ok("没有空白日", emptyDay == 0, emptyDay + " 天空着");
        // 28 行 × 约 20dp = 560dp，约 0.8 屏，可以接受；超过 40 行就该拆
        ok("单日条目 ≤ 30", maxLines <= 30, "最多 " + maxLines + " 条（" + worst + "）");
        ok("单日章数 ≤ 6", maxChaps <= 6, "最多 " + maxChaps + " 章");

        // ── 3. 文字不出界 ──────────────────────────────
        Font fTitle = new Font("Microsoft YaHei", Font.BOLD, 15);
        Font fHead = new Font("Microsoft YaHei", Font.BOLD, 15);
        Font fBody = new Font("Microsoft YaHei", Font.PLAIN, 14);
        FontMetrics fmBody = canvasMetrics(fBody);

        // 单行宽度不是重点 —— TextView 会自动折行，能不能"一整行"塞下
        // 并不影响可读性（英语句型公式折两行反而清楚）。真正要守的是
        // 折行后这张卡会不会长到"打开要滑好几屏"。所以这里按折行行数算。
        int wrapped = 0, worstWrap = 0;
        String wrapSample = "";
        for (Plan.Recall r : plan.recall.values()) {
            for (String p : r.points) {
                int lines = wrapLines("· " + p, fmBody, CARD_W - 8);
                wrapped += lines;
                if (lines > worstWrap) { worstWrap = lines; wrapSample = p; }
            }
            if (!r.exam.isEmpty()) {
                int lines = wrapLines("考点： " + r.exam, fmBody, CARD_W - 8);
                wrapped += lines;
                if (lines > worstWrap) { worstWrap = lines; wrapSample = r.exam; }
            }
        }
        // 单条折成 3 行以上说明这条根本没压缩，读起来是灾难
        ok("单条最多折 2 行", worstWrap <= 2,
                "有 " + worstWrap + " 行，例如「" + wrapSample + "」");
        System.out.println("    全部 " + plan.recall.size() + " 章共 " + wrapped + " 个显示行");

        // ── 4. 画出来看 ────────────────────────────────
        render();

        System.out.println();
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 与 MainActivity.addRecallTop 完全一致的选择逻辑。 */
    static List<String> recallChaps(int i) {
        Plan.Day d = plan.days.get(i);
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Plan.Review r : d.reviews) {
            if (plan.recall.containsKey(r.chapterId)) seen.add(r.chapterId);
        }
        if (seen.isEmpty()) {
            for (Plan.Task t : d.tasks) {
                if (plan.recall.containsKey(t.chapterId)) seen.add(t.chapterId);
            }
        }
        if (seen.isEmpty()) {
            for (int k = i; k >= 0 && seen.isEmpty(); k--) {
                for (Plan.Task t : plan.days.get(k).tasks) {
                    if (plan.recall.containsKey(t.chapterId)) seen.add(t.chapterId);
                }
            }
        }
        return new ArrayList<>(seen);
    }

    /**
     * 按可用宽度把一行拆成多行再画 —— 手机上的 TextView 就是这么做的。
     * 早先这里直接 drawString 一整行，结果右侧被裁掉，看起来像布局出错，
     * 其实只是审计的画笔不会折行。中文按字断，英文按词断。
     */
    static int drawWrapped(Graphics2D g, String s, FontMetrics fm, int x, int y,
                           int avail, int lineH) {
        StringBuilder cur = new StringBuilder();
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            String test = cur.toString() + ch;
            if (fm.stringWidth(test) > avail && cur.length() > 0) {
                g.drawString(cur.toString(), x, y);
                y += lineH;
                lines++;
                cur.setLength(0);
            }
            cur.append(ch);
        }
        if (cur.length() > 0) g.drawString(cur.toString(), x, y);
        return lines;
    }

    /** 按字宽估算折行数。TextView 的断行规则更聪明，这里取保守估计。 */
    static int wrapLines(String s, FontMetrics fm, int avail) {
        int w = fm.stringWidth(s);
        if (w <= avail) return 1;
        return (int) Math.ceil((double) w / avail);
    }

    static FontMetrics canvasMetrics(Font f) {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setFont(f);
        FontMetrics m = g.getFontMetrics();
        g.dispose();
        return m;
    }

    static void render() throws Exception {
        // 画"最挤的一天"和"最早的一天"，看极值
        int busiest = 0, bl = -1;
        for (int i = 0; i < plan.days.size(); i++) {
            List<String> cs = recallChaps(i);
            int l = 0;
            for (String c : cs) l += plan.recall.get(c).points.size() + 1;
            if (l > bl) { bl = l; busiest = i; }
        }
        drawDay(2, busiest, "recall-day-early.png");
        drawDay(busiest, busiest, "recall-day-busy.png");
        System.out.println("    已渲染 recall-day-early.png / recall-day-busy.png");
    }

    static void drawDay(int earlyIdx, int busyIdx, String out) throws Exception {
        Plan.Day d = plan.days.get(busyIdx == -1 ? earlyIdx : busyIdx);
        List<String> chaps = recallChaps(busyIdx == -1 ? earlyIdx : busyIdx);

        Font fTitle = new Font("Microsoft YaHei", Font.BOLD, 17);
        Font fSub = new Font("Microsoft YaHei", Font.PLAIN, 12);
        Font fHead = new Font("Microsoft YaHei", Font.BOLD, 15);
        Font fBody = new Font("Microsoft YaHei", Font.PLAIN, 14);
        Font fKey = new Font("Microsoft YaHei", Font.BOLD, 13);
        FontMetrics mSub = canvasMetrics(fSub);
        FontMetrics mBody = canvasMetrics(fBody);

        int lineH = 22, padIn = 12;
        int avail = CARD_W - padIn * 2 - 4;
        int h = 18 + 66 + 34;
        for (String c : chaps) {
            h += 26 + 22;
            Plan.Recall r = plan.recall.get(c);
            for (String p : r.points) h += lineH * wrapLines("· " + p, mBody, avail);
            if (!r.exam.isEmpty()) h += lineH * wrapLines("考点： " + r.exam, mBody, avail);
        }
        h += 40;

        BufferedImage img = new BufferedImage(W, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 背景
        g.setColor(new Color(0x14161C));
        g.fillRect(0, 0, W, h);

        // 顶端日记条（占位，说明这是今日页顶部）
        g.setColor(new Color(0xE0A94A));
        g.setFont(fTitle);
        g.drawString("今天 · " + d.date, PAD, 34);

        // 卡片
        int cy = 56;
        int cardH = h - cy - 16;
        g.setColor(new Color(0x1B1E26));
        g.fillRoundRect(PAD, cy, CARD_W, cardH, 24, 24);
        g.setColor(new Color(0x3ECFAD));
        g.setStroke(new BasicStroke(1.2f));

        int y = cy + 26;
        g.setColor(new Color(0xE8EAF0));
        g.setFont(fTitle);
        g.drawString("今日必背 · " + chaps.size() + " 章", PAD + padIn, y);

        y += 20;
        g.setColor(new Color(0x6B7282));
        g.setFont(fSub);
        String sub = chaps.isEmpty() ? "" : "答案直接摊开，不用默写";
        g.drawString(sub, PAD + padIn, y);

        y += 14;
        int rank = 0;
        for (String cid : chaps) {
            Plan.Chapter ch = plan.chapter(cid);
            Plan.Recall r = plan.recall.get(cid);
            y += 26;
            g.setColor(new Color(0xE0A94A));
            g.setFont(fHead);
            g.drawString((++rank) + ".  " + (ch != null ? ch.name : cid), PAD + padIn, y);

            for (String p : r.points) {
                y += lineH;
                g.setColor(new Color(0xC3C9D6));
                g.setFont(fBody);
                int n = drawWrapped(g, "· " + p, mBody, PAD + padIn + 2, y,
                        CARD_W - padIn * 2 - 4, lineH);
                y += (n - 1) * lineH;
            }
            if (!r.exam.isEmpty()) {
                y += lineH;
                g.setColor(new Color(0x3ECFAD));
                g.setFont(fKey);
                int n = drawWrapped(g, "考点： " + r.exam, mBody, PAD + padIn + 2, y,
                        CARD_W - padIn * 2 - 4, lineH);
                y += (n - 1) * lineH;
            }
            y += 12;
        }
        g.dispose();
        ImageIO.write(img, "png", new File(out));
    }
}