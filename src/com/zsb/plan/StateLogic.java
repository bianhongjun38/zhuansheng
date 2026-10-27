package com.zsb.plan;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 纯逻辑状态容器。**不依赖任何 Android 类**，所以能在电脑上直接跑测试。
 *
 * 分工：
 *   StateLogic —— 只管数据在内存里怎么变、怎么序列化成 JSON 字符串
 *   Store      —— 只管这份字符串怎么落到 SharedPreferences（Android 专属）
 *
 * 这样拆开的好处是真实存在的：所有业务规则（勾选、推进、剩余天数、计时累计、
 * 导出导入）都能在桌面上用真实数据验证，不必依赖真机或模拟器。
 */
public class StateLogic {

    protected final Set<String> done = new HashSet<>();
    protected final Set<String> completed = new HashSet<>();
    protected final Map<String, String> notes = new HashMap<>();
    protected final Map<String, Long> timing = new HashMap<>();
    protected final Set<String> star = new HashSet<>();
    protected final Map<String, Integer> hard = new HashMap<>();

    public static String key(String date, String id) {
        return date + "|" + id;
    }

    // ── 勾选 ──────────────────────────────────────────────
    public boolean isDone(String date, String id) { return done.contains(key(date, id)); }

    public void setDone(String date, String id, boolean v) {
        String k = key(date, id);
        if (v) done.add(k); else done.remove(k);
    }

    public boolean toggleDone(String date, String id) {
        boolean nv = !isDone(date, id);
        setDone(date, id, nv);
        return nv;
    }

    public int doneCount() { return done.size(); }

    // ── 推进 ──────────────────────────────────────────────
    public int currentIndex(Plan plan) {
        for (int i = 0; i < plan.days.size(); i++) {
            if (!completed.contains(plan.days.get(i).date)) return i;
        }
        return Math.max(0, plan.days.size() - 1);
    }

    public int daysLeft(Plan plan) {
        int n = 0;
        for (Plan.Day d : plan.days) if (!completed.contains(d.date)) n++;
        return n;
    }

    public boolean isDayCommitted(String date) { return completed.contains(date); }

    public void commitDay(String date) { completed.add(date); }

    public void uncommitDay(String date) { completed.remove(date); }

    public int completedCount() { return completed.size(); }

    // ── 计时 ──────────────────────────────────────────────
    public long timingOf(String date, String id) {
        Long v = timing.get(key(date, id));
        return v == null ? 0L : v;
    }

    public void addTiming(String date, String id, long ms) {
        String k = key(date, id);
        timing.put(k, timingOf(date, id) + ms);
    }

    // ── 默写 ──────────────────────────────────────────────
    public String noteOf(String date, String id) {
        String v = notes.get(key(date, id));
        return v == null ? "" : v;
    }

    public void setNote(String date, String id, String text) {
        String k = key(date, id);
        if (text == null || text.isEmpty()) notes.remove(k);
        else notes.put(k, text);
    }

    // ── 错题 ──────────────────────────────────────────────
    public void bumpHard(String id) {
        Integer cur = hard.get(id);
        hard.put(id, cur == null ? 1 : Math.min(3, cur + 1));
    }

    public void clearHard(String id) { hard.remove(id); }

    public int hardCount() { return hard.size(); }

    public Integer hardLevel(String id) { return hard.get(id); }

    public Set<String> hardIds() { return new HashSet<>(hard.keySet()); }

    // ── 序列化 ────────────────────────────────────────────
    /** 序列化成 JSON 字符串。返回 null 表示没变化（理论上不会）。 */
    public String serialize() {
        try {
            JSONObject o = new JSONObject();
            o.put("done", toObj(done));
            o.put("completed", toObj(completed));
            o.put("star", toObj(star));
            o.put("notes", new JSONObject(notes));
            o.put("hard", new JSONObject(hard));
            JSONObject tm = new JSONObject();
            for (Map.Entry<String, Long> e : timing.entrySet()) tm.put(e.getKey(), e.getValue());
            o.put("timing", tm);
            o.put("updatedAt", nowIso());
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 JSON 字符串恢复。损坏内容一律当空进度，绝不抛到调用方。 */
    public void deserialize(String raw) {
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(raw);
            readSet(o.optJSONObject("done"), done);
            readSet(o.optJSONObject("completed"), completed);
            readSet(o.optJSONObject("star"), star);
            readMap(o.optJSONObject("notes"), notes);
            readIntMap(o.optJSONObject("hard"), hard);
            JSONObject tm = o.optJSONObject("timing");
            if (tm != null) {
                for (Iterator<String> it = tm.keys(); it.hasNext(); ) {
                    String k = it.next();
                    timing.put(k, tm.optLong(k));
                }
            }
        } catch (Exception ignored) {
            // 存档坏了就当空进度，不让 App 起不来
        }
    }

    private static void readSet(JSONObject o, Set<String> out) {
        if (o == null) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            if (o.optBoolean(k, false)) out.add(k);
        }
    }

    private static void readMap(JSONObject o, Map<String, String> out) {
        if (o == null) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            out.put(k, o.optString(k, ""));
        }
    }

    private static void readIntMap(JSONObject o, Map<String, Integer> out) {
        if (o == null) return;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            out.put(k, o.optInt(k, 1));
        }
    }

    private static JSONObject toObj(Set<String> s) throws Exception {
        JSONObject o = new JSONObject();
        for (String k : s) o.put(k, true);
        return o;
    }

    // ── 导出 / 导入 ────────────────────────────────────────
    public String exportJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("_app", "zsb-plan");
            o.put("_version", 1);
            o.put("_exportedAt", nowIso());
            o.put("state", new JSONObject(serialize()));
            return o.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 返回导入后的勾选条数。内容不合法时抛异常，且不改动已有数据。 */
    public int importJson(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONObject st = root.optJSONObject("state");
        if (st == null) st = root;

        // 先解析到临时容器，全部成功后再替换——避免半截导入把数据清掉
        Set<String> nd = new HashSet<>(), nc = new HashSet<>(), ns = new HashSet<>();
        Map<String, String> nn = new HashMap<>();
        Map<String, Integer> nh = new HashMap<>();
        Map<String, Long> nt = new HashMap<>();
        readSet(st.optJSONObject("done"), nd);
        readSet(st.optJSONObject("completed"), nc);
        readSet(st.optJSONObject("star"), ns);
        readMap(st.optJSONObject("notes"), nn);
        readIntMap(st.optJSONObject("hard"), nh);
        JSONObject tm = st.optJSONObject("timing");
        if (tm != null) for (Iterator<String> it = tm.keys(); it.hasNext(); ) {
            String k = it.next(); nt.put(k, tm.optLong(k));
        }

        done.clear(); done.addAll(nd);
        completed.clear(); completed.addAll(nc);
        star.clear(); star.addAll(ns);
        notes.clear(); notes.putAll(nn);
        hard.clear(); hard.putAll(nh);
        timing.clear(); timing.putAll(nt);
        return done.size();
    }

    public void resetAll() {
        done.clear(); completed.clear(); star.clear();
        notes.clear(); timing.clear(); hard.clear();
    }

    // ── 日期 ──────────────────────────────────────────────
    public static String todayIso() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date());
    }

    static String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(new Date());
    }
}
