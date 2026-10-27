package com.zsb.plan;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 状态逻辑测试。针对 StateLogic（纯 Java，零 Android 依赖），
 * 所以能在电脑上跑真实数据，不需要真机或模拟器。
 *
 * 覆盖：持久化往返、损坏存档容错、导出导入无损、计时累计、等级封顶、推进撤销。
 */
public class StoreTest {

    static int pass = 0, fail = 0;

    static void ok(String n, boolean c, String d) {
        if (c) { pass++; System.out.println("  ✅ " + n + (d.isEmpty() ? "" : "  (" + d + ")")); }
        else { fail++; System.out.println("  ❌ " + n + (d.isEmpty() ? "" : "  (" + d + ")")); }
    }

    public static void main(String[] a) throws Exception {
        String planPath = a.length > 0 ? a[0]
                : "E:\\小黑盒运营\\cq-zhuanshengbens\\mobile\\assets\\plan.json";
        Plan plan = Plan.parse(new String(
                Files.readAllBytes(Paths.get(planPath)), StandardCharsets.UTF_8));
        System.out.println("使用真实计划：" + plan.days.size() + " 天 / "
                + plan.chapters.size() + " 章节\n");

        // ── 1. 空状态 ────────────────────────────────────
        System.out.println("【1】空状态");
        StateLogic s = new StateLogic();
        ok("done 为空", s.doneCount() == 0, "");
        ok("剩余天数 = 146", s.daysLeft(plan) == 146, s.daysLeft(plan) + " 天");
        ok("当前索引 = 0", s.currentIndex(plan) == 0, "");

        // ── 2. 序列化 → 反序列化（持久化核心）──────────────
        System.out.println("\n【2】序列化 → 反序列化（存盘再读回）");
        String d1 = plan.days.get(0).date;
        String t1 = Plan.taskId(plan.days.get(0).tasks.get(0));
        s.setDone(d1, t1, true);
        s.setDone(d1, "R-C1-2-R1", true);
        s.commitDay(d1);
        s.setNote(d1, "R-C1-2-R1", "积分中值定理：∃ξ 使 ∫f = f(ξ)(b-a)");
        s.addTiming(d1, t1, 125_000L);
        s.addTiming(d1, t1, 75_000L);      // 应累加成 200 秒
        s.bumpHard("M1-2");
        s.bumpHard("M1-2");                // 应到 2

        String json = s.serialize();
        ok("序列化非空", json != null && json.length() > 50,
                json == null ? "null" : json.length() + " 字符");

        StateLogic s2 = new StateLogic();
        s2.deserialize(json);
        ok("done 读回 2 条", s2.doneCount() == 2, s2.doneCount() + " 条");
        ok("完成标记读回", s2.isDone(d1, t1), "");
        ok("已确认天数读回", s2.completedCount() == 1, s2.completedCount() + " 天");
        ok("剩余天数 = 145", s2.daysLeft(plan) == 145, s2.daysLeft(plan) + " 天");
        ok("当前索引 = 1", s2.currentIndex(plan) == 1, "idx=" + s2.currentIndex(plan));
        ok("默写读回", s2.noteOf(d1, "R-C1-2-R1").startsWith("积分中值定理"),
                s2.noteOf(d1, "R-C1-2-R1").length() + " 字");
        ok("计时累计 200 秒", s2.timingOf(d1, t1) == 200_000L,
                s2.timingOf(d1, t1) + " ms");
        ok("错题等级 2", Integer.valueOf(2).equals(s2.hardLevel("M1-2")),
                String.valueOf(s2.hardLevel("M1-2")));

        // ── 3. 等级封顶 ──────────────────────────────────
        System.out.println("\n【3】错题库等级上限 = 3");
        s2.bumpHard("M1-2");
        ok("升到 3", Integer.valueOf(3).equals(s2.hardLevel("M1-2")),
                String.valueOf(s2.hardLevel("M1-2")));
        s2.bumpHard("M1-2");
        s2.bumpHard("M1-2");
        ok("继续 bump 封顶在 3", Integer.valueOf(3).equals(s2.hardLevel("M1-2")),
                String.valueOf(s2.hardLevel("M1-2")));
        s2.clearHard("M1-2");
        ok("清除后为 null", s2.hardLevel("M1-2") == null, "");
        ok("清除后计数为 0", s2.hardCount() == 0, "");

        // ── 4. toggle ────────────────────────────────────
        System.out.println("\n【4】勾选切换");
        String d2 = plan.days.get(1).date;
        ok("首次 toggle = true", s2.toggleDone(d2, "T"), "");
        ok("此时是已勾选", s2.isDone(d2, "T"), "");
        ok("再次 toggle = false", !s2.toggleDone(d2, "T"), "");
        ok("此时未勾选", !s2.isDone(d2, "T"), "");

        // ── 5. 推进 / 撤销 ────────────────────────────────
        System.out.println("\n【5】推进与撤销");
        s2.commitDay(d2);
        ok("确认 2 天后 completed=2", s2.completedCount() == 2, s2.completedCount() + " 天");
        ok("剩余 144 天", s2.daysLeft(plan) == 144, s2.daysLeft(plan) + " 天");
        s2.uncommitDay(d1);
        ok("撤销第 1 天后 completed=1", s2.completedCount() == 1, s2.completedCount() + " 天");
        ok("撤销后剩余 145 天", s2.daysLeft(plan) == 145, s2.daysLeft(plan) + " 天");
        ok("撤销后索引回到 0", s2.currentIndex(plan) == 0, "idx=" + s2.currentIndex(plan));

        // ── 6. 导出 / 导入往返 ────────────────────────────
        System.out.println("\n【6】导出 → 导入（换手机）");
        String backup = s2.exportJson();
        ok("导出含应用标识", backup.contains("zsb-plan"), "");
        ok("导出含 state", backup.contains("\"state\""), "");

        StateLogic s3 = new StateLogic();
        ok("新设备初始为空", s3.doneCount() == 0, "");
        int n = s3.importJson(backup);
        ok("导入返回条数", n == s2.doneCount(), n + " 条");
        ok("done 一致", s3.doneCount() == s2.doneCount(),
                s3.doneCount() + " vs " + s2.doneCount());
        ok("completed 一致", s3.completedCount() == s2.completedCount(), "");
        ok("默写一致", s3.noteOf(d1, "R-C1-2-R1").equals(s2.noteOf(d1, "R-C1-2-R1")), "");
        ok("计时一致", s3.timingOf(d1, t1) == s2.timingOf(d1, t1), "");
        ok("导入后可继续推进", s3.daysLeft(plan) == s2.daysLeft(plan), "");

        // ── 7. 损坏存档 ──────────────────────────────────
        System.out.println("\n【7】损坏存档不能崩");
        StateLogic bad = new StateLogic();
        try {
            bad.deserialize("{这不是JSON");
            ok("非法 JSON 不抛异常", true, "按空进度处理");
            ok("非法 JSON 后状态为空", bad.doneCount() == 0, "");
        } catch (Exception e) {
            ok("非法 JSON 不抛异常", false, e.getMessage());
        }

        StateLogic weird = new StateLogic();
        try {
            weird.deserialize("{\"done\":{\"a|b\":false},\"timing\":{\"a|b\":\"abc\"},\"notes\":null}");
            ok("字段类型异常不崩", true, "");
            ok("done=false 不入集合", weird.doneCount() == 0, "");
            ok("timing 非法值当 0", weird.timingOf("a", "b") == 0L,
                    weird.timingOf("a", "b") + "");
        } catch (Exception e) {
            ok("字段类型异常不崩", false, e.getMessage());
        }

        StateLogic empty = new StateLogic();
        try {
            empty.deserialize("");
            empty.deserialize(null);
            ok("空/null 输入不崩", true, "");
        } catch (Exception e) {
            ok("空/null 输入不崩", false, e.getMessage());
        }

        // ── 8. 非法导入必须不清数据 ────────────────────────
        System.out.println("\n【8】非法导入不能毁掉已有数据");
        int before = s3.doneCount();
        try {
            s3.importJson("随便一段话");
            ok("非法备份抛异常", false, "没抛");
        } catch (Exception e) {
            ok("非法备份抛异常", true, "按预期");
        }
        ok("原数据完好", s3.doneCount() == before, s3.doneCount() + " 条未变");

        // 空对象导入（合法 JSON 但无 state）——应视为空状态但不崩
        try {
            s3.importJson("{}");
            ok("空对象导入不崩", true, "清成空状态");
        } catch (Exception e) {
            ok("空对象导入不崩", false, e.getMessage());
        }

        // ── 9. 清空 ──────────────────────────────────────
        System.out.println("\n【9】清空");
        StateLogic s4 = new StateLogic();
        s4.deserialize(s2.serialize());
        ok("清空前有数据", s4.doneCount() > 0, s4.doneCount() + " 条");
        s4.resetAll();
        ok("清空后 done=0", s4.doneCount() == 0, "");
        ok("清空后 completed=0", s4.completedCount() == 0, "");
        ok("清空后剩余 146 天", s4.daysLeft(plan) == 146, s4.daysLeft(plan) + " 天");
        ok("清空后可序列化", s4.serialize() != null, "");

        // ── 10. 全流程推演（模拟用满 146 天）────────────────
        System.out.println("\n【10】全流程推演：走完整个计划");
        StateLogic walk = new StateLogic();
        int commits = 0;
        for (int i = 0; i < plan.days.size(); i++) {
            Plan.Day d = plan.days.get(i);
            // 勾完当天全部
            for (String id : Plan.dayTaskIds(d)) walk.setDone(d.date, id, true);
            walk.commitDay(d.date);
            commits++;
        }
        ok("逐日推进 146 次", commits == 146, commits + " 次");
        ok("走完后剩余 = 0", walk.daysLeft(plan) == 0, walk.daysLeft(plan) + " 天");
        ok("走完后索引停在最后一天", walk.currentIndex(plan) == 145,
                "idx=" + walk.currentIndex(plan));
        // 期望勾选数不能写死：计划条目会随内容调整（v1.2 去掉英语语法章节后
        // 从 792 变 781）。按 dayTaskIds 实际算一遍才是真断言。
        int expectDone = 0;
        for (Plan.Day dd : plan.days) expectDone += Plan.dayTaskIds(dd).size();
        ok("走完后勾选数 = " + expectDone, walk.doneCount() == expectDone,
                walk.doneCount() + " 条");
        String full = walk.serialize();
        ok("全程状态可序列化", full != null && full.length() > 1000,
                full == null ? "null" : full.length() + " 字符");
        StateLogic walkBack = new StateLogic();
        walkBack.deserialize(full);
        ok("全程状态可完整读回", walkBack.doneCount() == walk.doneCount(),
                walkBack.doneCount() + " 条");

        System.out.println("\n" + "=".repeat(54));
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        // 纯 ASCII 统计行：CI 的 locale 不一定是 UTF-8，中文会变成 ?，
        // verify.py 就抓不到数字。这一行任何编码下都读得出来。
        System.out.println("STAT " + pass + " " + fail);
        System.out.println("=".repeat(54));
        if (fail > 0) System.exit(1);
    }
}
