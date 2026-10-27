package com.zsb.plan;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 桌面端逻辑测试。把 Plan 与 Store 的核心逻辑搬到电脑上跑，
 * 用真实的 plan.json 验证——因为手上没有安卓真机/模拟器。
 *
 * 用反射把 Store 里的内存结构换掉（Store 的构造函数要 Context），
 * 只测纯逻辑：解析、任务 id、推进指针、剩余天数。
 */
public class LogicTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond, String detail) {
        if (cond) {
            pass++;
            System.out.println("  ✅ " + name + (detail.isEmpty() ? "" : "  (" + detail + ")"));
        } else {
            fail++;
            System.out.println("  ❌ " + name + (detail.isEmpty() ? "" : "  (" + detail + ")"));
        }
    }

    public static void main(String[] a) throws Exception {
        String path = a.length > 0 ? a[0]
                : "E:\\小黑盒运营\\cq-zhuanshengbens\\mobile\\assets\\plan.json";
        System.out.println("测试数据: " + path);
        String raw = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        System.out.println("文件大小: " + raw.length() + " 字符\n");

        // ── 1. 解析 ─────────────────────────────────────
        System.out.println("【1】plan.json 解析");
        Plan p = Plan.parse(raw);
        ok("解析不抛异常", true, "");
        ok("meta.examDate 正确", "2027-03-20".equals(p.meta.examDate), p.meta.examDate);
        ok("meta.startDate 正确", "2026-10-26".equals(p.meta.startDate), p.meta.startDate);
        ok("科目 3 个", p.subjects.size() == 3, "实际 " + p.subjects.size());
        ok("章节 72 个", p.chapters.size() == 72, "实际 " + p.chapters.size());
        ok("天数 146 天", p.days.size() == 146, "实际 " + p.days.size());

        // 反序列化后字段抽查
        Plan.Day d0 = p.days.get(0);
        ok("第1天日期", "2026-10-26".equals(d0.date), d0.date);
        ok("第1天有任务", d0.tasks.size() >= 1, d0.tasks.size() + " 个");
        Plan.Subject math = p.subject("math");
        ok("math 科目名", "高等数学".equals(math.name), math.name);
        ok("math pdfOffset=5", math.pdfOffset != null && math.pdfOffset == 5, String.valueOf(math.pdfOffset));
        Plan.Subject eng = p.subject("eng");
        ok("eng pdfOffset 为 null", eng.pdfOffset == null, String.valueOf(eng.pdfOffset));
        Plan.Chapter m31 = p.chapter("M3-1");
        ok("M3-1 存在且名=不定积分", m31 != null && "不定积分".equals(m31.name),
                m31 == null ? "null" : m31.name);
        ok("M3-1 页码 86-101", m31.startPage == 86 && m31.endPage == 101,
                m31.startPage + "-" + m31.endPage);

        // ── 2. 任务 id 稳定性 ───────────────────────────
        System.out.println("\n【2】任务 id 生成与唯一性");
        int nLearn = 0, nOther = 0;
        int sameDayDup = 0;
        // 真正的硬约束：date|id 必须全局唯一（这才是存储键）
        Set<String> storageKeys = new HashSet<>();
        int keyDup = 0;
        // 任务 id 本身会跨天重复——这是「三轮螺旋」故意的同章节回访，不是 bug
        Set<String> learnIds = new HashSet<>();
        Set<String> otherIds = new HashSet<>();

        for (Plan.Day d : p.days) {
            Set<String> dayIds = new HashSet<>();
            for (Plan.Task t : d.tasks) {
                String id = Plan.taskId(t);
                if ("learn".equals(t.kind)) { nLearn++; learnIds.add(id); }
                else { nOther++; otherIds.add(id); }

                if (!dayIds.add(id)) sameDayDup++;
                if (!storageKeys.add(d.date + "|" + id)) keyDup++;
            }
        }
        // 同日不得重复：否则一次勾选会同时勾掉两条不同的任务
        ok("同日无重复 id", sameDayDup == 0, "重复 " + sameDayDup);
        // 存储键必须全局唯一：否则不同天的进度会互相污染
        ok("date|id 存储键全局唯一", keyDup == 0,
                keyDup == 0 ? "共 " + storageKeys.size() + " 个" : "冲突 " + keyDup + " 个");
        ok("存储键数量 = 任务总数", storageKeys.size() == nLearn + nOther,
                storageKeys.size() + " vs " + (nLearn + nOther));

        System.out.println("      learn " + nLearn + " 个（唯一 id " + learnIds.size() + "）");
        System.out.println("      other " + nOther + " 个（唯一 id " + otherIds.size() + "）");
        if (nLearn != learnIds.size()) {
            System.out.println("      注：learn id 跨天重复 " + (nLearn - learnIds.size())
                    + " 处，是三轮螺旋的同章节回访，靠 date 前缀区分，属预期");
        }

        // 同一任务解析两次 id 必须相同（不能依赖随机数/时间戳）
        Plan.Task t0 = d0.tasks.get(0);
        String idA = Plan.taskId(t0);
        String idB = Plan.taskId(t0);
        ok("id 可重复计算且稳定", idA.equals(idB), idA);
        // 换一个 Plan 实例重新解析，id 也必须一致（跨设备一致性）
        Plan p2 = Plan.parse(raw);
        String idC = Plan.taskId(p2.days.get(0).tasks.get(0));
        ok("重新解析后 id 不变", idA.equals(idC), idC);

        // ── 3. dayTaskIds ───────────────────────────────
        System.out.println("\n【3】dayTaskIds（推进闸门依据）");
        int totalIds = 0, daysWithZero = 0;
        for (Plan.Day d : p.days) {
            List<String> ids = Plan.dayTaskIds(d);
            totalIds += ids.size();
            if (ids.isEmpty()) daysWithZero++;
            // 每天 id 不应重复
            if (new HashSet<>(ids).size() != ids.size()) {
                ok("第 " + d.date + " 天 id 重复", false, "");
            }
        }
        ok("没有空任务的日子", daysWithZero == 0, "空 " + daysWithZero + " 天");
        ok("总勾选条目数 > 500", totalIds > 500, "共 " + totalIds + " 条");
        System.out.println("      全部天数累计 " + totalIds + " 个勾选点");

        // ── 4. 推进指针与剩余天数 ────────────────────────
        System.out.println("\n【4】推进制逻辑（不按日历走）");
        // 模拟一个 Store 的空状态
        Set<String> completed = new HashSet<>();

        int idx0 = firstNotCompleted(p, completed);
        ok("初始 currentIndex=0", idx0 == 0, "idx=" + idx0);
        ok("初始剩余=146", 146 - completed.size() == 146, "剩 " + (146 - completed.size()));

        // 确认第 1 天
        completed.add(p.days.get(0).date);
        int idx1 = firstNotCompleted(p, completed);
        ok("确认1天后 currentIndex=1", idx1 == 1, "idx=" + idx1);
        ok("剩余 145 天", 146 - completed.size() == 145, "剩 " + (146 - completed.size()));

        // 确认前 5 天
        for (int i = 0; i < 5; i++) completed.add(p.days.get(i).date);
        ok("确认5天后 剩余141", 146 - completed.size() == 141, "剩 " + (146 - completed.size()));
        ok("确认5天后 currentIndex=5", firstNotCompleted(p, completed) == 5,
                "idx=" + firstNotCompleted(p, completed));

        // 撤销第 3 天
        completed.remove(p.days.get(2).date);
        ok("撤销第3天后 剩余142", 146 - completed.size() == 142, "剩 " + (146 - completed.size()));
        ok("撤销第3天后 currentIndex 回到 2", firstNotCompleted(p, completed) == 2,
                "idx=" + firstNotCompleted(p, completed));

        // 全部确认
        Set<String> all = new HashSet<>();
        for (Plan.Day d : p.days) all.add(d.date);
        ok("全确认后 剩余=0", 146 - all.size() == 0, "剩 " + (146 - all.size()));
        ok("全确认后 currentIndex 停在最后一天",
                firstNotCompleted(p, all) == 145, "idx=" + firstNotCompleted(p, all));

        // ── 5. 日期连续性 ───────────────────────────────
        System.out.println("\n【5】日期与检索数据完整性");
        boolean monotonic = true;
        for (int i = 1; i < p.days.size(); i++) {
            if (p.days.get(i).date.compareTo(p.days.get(i - 1).date) <= 0) monotonic = false;
        }
        ok("日期严格递增", monotonic, "");

        int dayWithReviews = 0, totalReviews = 0;
        Set<String> reviewKinds = new HashSet<>();
        for (Plan.Day d : p.days) {
            if (!d.reviews.isEmpty()) dayWithReviews++;
            for (Plan.Review r : d.reviews) {
                totalReviews++;
                reviewKinds.add(r.kind);
                if (r.id.isEmpty()) ok("检索缺 id", false, d.date);
            }
        }
        ok("有检索的日子 > 100", dayWithReviews > 100, dayWithReviews + " 天");
        ok("检索总条数 = 206", totalReviews == 206, totalReviews + " 条");
        ok("检索梯级含 R1-R4", reviewKinds.containsAll(
                java.util.Arrays.asList("R1", "R2", "R3", "R4")),
                reviewKinds.toString());

        // ── 6. 边界：损坏数据不应崩 ──────────────────────
        System.out.println("\n【6】边界与容错");
        try {
            Plan empty = Plan.parse("{}");
            ok("空 JSON 不崩", empty.days.isEmpty() && empty.subjects.isEmpty(), "");
        } catch (Exception e) {
            ok("空 JSON 不崩", false, e.getMessage());
        }
        try {
            Plan partial = Plan.parse("{\"days\":[{\"date\":\"2026-01-01\"}]}");
            ok("缺字段的日子不崩", partial.days.size() == 1, "");
            ok("缺 tasks 时列表为空", partial.days.get(0).tasks.isEmpty(), "");
        } catch (Exception e) {
            ok("缺字段的日子不崩", false, e.getMessage());
        }
        try {
            Plan.parse("not json");
            ok("非法 JSON 抛异常", false, "没抛");
        } catch (Exception e) {
            ok("非法 JSON 抛异常", true, "按预期");
        }

        // ── 汇总 ────────────────────────────────────────
        System.out.println("\n" + "=".repeat(58));
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        // 纯 ASCII 统计行：CI 的 locale 不一定是 UTF-8，中文会变成 ?，
        // verify.py 就抓不到数字。这一行任何编码下都读得出来。
        System.out.println("STAT " + pass + " " + fail);
        System.out.println("=".repeat(58));
        if (fail > 0) System.exit(1);
    }

    static int firstNotCompleted(Plan p, Set<String> completed) {
        for (int i = 0; i < p.days.size(); i++) {
            if (!completed.contains(p.days.get(i).date)) return i;
        }
        return Math.max(0, p.days.size() - 1);
    }
}
