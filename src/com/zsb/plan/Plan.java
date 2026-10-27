package com.zsb.plan;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划数据模型。对应 assets/plan.json。
 *
 * 设计原则：只解析用得到的字段，不认识的字段一律忽略——
 * 这样以后 plan.json 加东西，这个 App 不会崩。
 */
public class Plan {

    public static class Meta {
        public String examDate = "";
        public String startDate = "";
        public int daysToExam;
        public int vocabTotal;
    }

    public static class Subject {
        public String id = "";
        public String name = "";
        public String color = "#888888";
        public int bookPages;
        public Integer pdfOffset;   // 可能为 null（英语没有偏移）
    }

    public static class Chapter {
        public String id = "";
        public String subject = "";
        public String name = "";
        public String tier = "";
        public int startPage;
        public int endPage;
        public int estMin;
        public Integer pdfStart;
        public Integer pdfEnd;
    }

    public static class Task {
        public String subject = "";
        public String chapterId = "";
        public String kind = "learn";     // learn | other | pract
        public String note = "";
        public int startPage;
        public int endPage;
        public int min;
        public Integer pdfStart;
        public Integer pdfEnd;
        /**
         * 最终存储 id。在 parse 阶段一次性分配，之后全部复用。
         * 保证：① 同一份 plan.json 无论解析多少次都一致 ② 同一天内绝不重复
         */
        public String id = "";

        /**
         * 题库落点。不为 null 时说明这个任务有真题目可做。
         * 目前用到两个键：
         *   subject  学科 math|comp|eng
         *   limit    最多抽几道
         *   year     指定年份（真题任务）
         */
        public String bankSubject = "";
        public int bankLimit;
        public int bankYear;
    }

    public static class Review {
        public String id = "";
        public String chapterId = "";
        public String subject = "";
        public String kind = "";          // R1..R4
        public String due = "";
        public int min;
    }

    public static class Day {
        public String date = "";
        public String weekday = "";
        public String phase = "";
        public String round = "";
        public String reviewHint = "";
        public int daysToExam;
        public String vocab;              // 原始 JSON 片段，可能为 null
        // 结构化的词汇区间（v1.2 起）：
        //   vocabFrom..vocabTo  = 当天新词
        //   vocabReview         = 该复习的旧区间 [[from,to], ...]
        public int vocabFrom = -1;
        public int vocabTo = -1;
        public String vocabReviewJson;    // 原始 JSON 数组字符串
        public List<Task> tasks = new ArrayList<>();
        public List<Review> reviews = new ArrayList<>();
    }

    public Meta meta = new Meta();
    public Map<String, Subject> subjects = new LinkedHashMap<>();
    public Map<String, Chapter> chapters = new LinkedHashMap<>();
    public List<Day> days = new ArrayList<>();

    // ── 解析 ────────────────────────────────────────────
    public static Plan parse(String raw) {
        Plan p = new Plan();
        try {
            JSONObject root = new JSONObject(raw);

            JSONObject m = root.optJSONObject("meta");
            if (m != null) {
                p.meta.examDate = m.optString("examDate", "");
                p.meta.startDate = m.optString("startDate", "");
                p.meta.daysToExam = m.optInt("daysToExam", 0);
                p.meta.vocabTotal = m.optInt("vocabTotal", 0);
            }

            JSONArray subs = root.optJSONArray("subjects");
            if (subs != null) {
                for (int i = 0; i < subs.length(); i++) {
                    JSONObject o = subs.getJSONObject(i);
                    Subject s = new Subject();
                    s.id = o.optString("id");
                    s.name = o.optString("name");
                    s.color = o.optString("color", "#888888");
                    s.bookPages = o.optInt("bookPages");
                    s.pdfOffset = o.isNull("pdfOffset") ? null : o.optInt("pdfOffset");
                    if (!s.id.isEmpty()) p.subjects.put(s.id, s);
                }
            }

            JSONArray chs = root.optJSONArray("chapters");
            if (chs != null) {
                for (int i = 0; i < chs.length(); i++) {
                    JSONObject o = chs.getJSONObject(i);
                    Chapter c = new Chapter();
                    c.id = o.optString("id");
                    c.subject = o.optString("subject");
                    c.name = o.optString("name");
                    c.tier = o.optString("tier");
                    c.startPage = o.optInt("startPage");
                    c.endPage = o.optInt("endPage");
                    c.estMin = o.optInt("estMin");
                    c.pdfStart = o.isNull("pdfStart") ? null : o.optInt("pdfStart");
                    c.pdfEnd = o.isNull("pdfEnd") ? null : o.optInt("pdfEnd");
                    if (!c.id.isEmpty()) p.chapters.put(c.id, c);
                }
            }

            JSONArray ds = root.optJSONArray("days");
            if (ds != null) {
                for (int i = 0; i < ds.length(); i++) {
                    JSONObject o = ds.getJSONObject(i);
                    Day d = new Day();
                    d.date = o.optString("date");
                    d.weekday = o.optString("weekday");
                    d.phase = o.optString("phase");
                    d.round = o.optString("round");
                    d.reviewHint = o.optString("reviewHint", "");
                    d.daysToExam = o.optInt("daysToExam");
                    d.vocab = o.isNull("vocab") ? null : String.valueOf(o.opt("vocab"));
                    // 结构化词汇区间
                    JSONObject vo = o.optJSONObject("vocab");
                    if (vo != null) {
                        d.vocabFrom = vo.optInt("from", -1);
                        d.vocabTo = vo.optInt("to", -1);
                    }
                    JSONArray vr = o.optJSONArray("vocabReview");
                    if (vr != null && vr.length() > 0) {
                        d.vocabReviewJson = vr.toString();
                    }

                    JSONArray ts = o.optJSONArray("tasks");
                    if (ts != null) {
                        // 同一天内去重：learn 用章节+起始页，other 用 note 全量哈希
                        Set<String> usedIds = new HashSet<>();
                        for (int j = 0; j < ts.length(); j++) {
                            JSONObject t = ts.getJSONObject(j);
                            Task k = new Task();
                            k.subject = t.optString("subject");
                            k.chapterId = t.optString("chapterId");
                            k.kind = t.optString("kind", "learn");
                            k.note = t.optString("note", "");
                            k.startPage = t.optInt("startPage");
                            k.endPage = t.optInt("endPage");
                            k.min = t.optInt("min");
                            k.pdfStart = t.isNull("pdfStart") ? null : t.optInt("pdfStart");
                            k.pdfEnd = t.isNull("pdfEnd") ? null : t.optInt("pdfEnd");
                            // 题库落点（可为 null：不是做题类任务）
                            JSONObject bk = t.optJSONObject("bank");
                            if (bk != null) {
                                k.bankSubject = bk.optString("subject", k.subject);
                                k.bankLimit = bk.optInt("limit", 10);
                                k.bankYear = bk.optInt("year", 0);
                            }
                            k.id = assignId(k, usedIds, d.date);
                            d.tasks.add(k);
                        }
                    }

                    JSONArray rs = o.optJSONArray("reviews");
                    if (rs != null) {
                        for (int j = 0; j < rs.length(); j++) {
                            d.reviews.add(parseReview(rs.getJSONObject(j)));
                        }
                    }
                    p.days.add(d);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("plan.json 解析失败：" + e.getMessage(), e);
        }
        return p;
    }

    static Review parseReview(JSONObject o) {
        Review r = new Review();
        r.id = o.optString("id");
        r.chapterId = o.optString("chapterId");
        r.subject = o.optString("subject");
        r.kind = o.optString("kind");
        r.due = o.optString("due");
        r.min = o.optInt("min");
        return r;
    }

    // ── 派生逻辑 ─────────────────────────────────────────
    public Day dayByDate(String date) {
        for (Day d : days) if (d.date.equals(date)) return d;
        return null;
    }

    public Chapter chapter(String id) {
        return chapters.get(id);
    }

    public Subject subject(String id) {
        Subject s = subjects.get(id);
        if (s != null) return s;
        Subject x = new Subject();
        x.id = id;
        x.name = id;
        return x;
    }

    /**
     * 分配任务 id。
     *
     * learn：L-科目-章节-起始页。跨天会重复，这是「三轮螺旋」故意的同章节回访——
     *        存储键是 date|id，所以不会串。
     * other：O-科目-<note 的稳定哈希>。原来只取 note 前 12 字，
     *        而差异恰好出现在后半段（"练习册《连续与间断》p21" vs "p22-26"），
     *        导致同一天两个不同任务撞成同一个 id → 勾一个另一个也变勾上。
     *        改用全量哈希 + 重名计数兜底。
     */
    static String assignId(Task t, Set<String> usedInDay, String date) {
        String base;
        if ("learn".equals(t.kind) && t.startPage > 0) {
            base = "L-" + t.subject + "-" + t.chapterId + "-" + t.startPage;
        } else {
            base = "O-" + t.subject + "-" + fnv(t.note + "|" + t.startPage + "-" + t.endPage);
        }
        // 万一还是撞（note 完全相同的两条），加序号，绝不静默合并
        String id = base;
        int n = 2;
        while (usedInDay.contains(id)) {
            id = base + "#" + n;
            n++;
        }
        usedInDay.add(id);
        return id;
    }

    /** FNV-1a 32 位，输出 8 位十六进制。用来给长文本生成短而稳定的 id 片段。 */
    static String fnv(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return String.format("%08x", h);
    }

    /** 取任务 id（已在 parse 时分配）。 */
    public static String taskId(Task t) {
        return t.id;
    }

    /** 某天全部需要勾选的 id（用于判断“是否全勾完”才能推进）。 */
    public static List<String> dayTaskIds(Day d) {
        List<String> ids = new ArrayList<>();
        for (Task t : d.tasks) ids.add(taskId(t));
        if (d.vocab != null) ids.add("V-" + d.date);
        for (Review r : d.reviews) ids.add("R-" + r.id);
        return ids;
    }
}
