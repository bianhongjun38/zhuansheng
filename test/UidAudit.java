package com.zsb.plan;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 稳定 uid 审计。
 *
 * 为什么这件事值得单独一套审计：
 *   Plan.id 是按内容算出来的哈希（Plan.assignId）。我把一句任务文案
 *   改一个字，哈希就变，用户所有相关勾选全部失联。单机时代丢的是
 *   本地一点数据，上了云就是所有人的进度对不上号。
 *
 * 所以 uid 必须写死在 plan.json 里，生成一次永不变。
 * 这里做的就是钉住这件事：谁把 uid 删了、改重了，直接红。
 */
public class UidAudit {

    static int pass = 0, fail = 0;
    static void ok(String w, boolean c, String d) {
        if (c) pass++;
        else { fail++; System.out.println("  ✗ " + w + "  →  " + d); }
    }

    public static void main(String[] a) throws Exception {
        System.out.println("▶ 任务稳定 uid（改文案不能让进度失联）");

        String json = new String(Files.readAllBytes(Paths.get("assets/plan.json")),
                StandardCharsets.UTF_8);
        Plan p = Plan.parse(json);

        List<Plan.Task> all = new ArrayList<>();
        for (Plan.Day d : p.days) all.addAll(d.tasks);

        ok("任务全被打过 uid", all.size() > 100, "只有 " + all.size() + " 条");

        int missing = 0;
        String missSample = "";
        for (Plan.Task t : all) {
            if (t.uid == null || t.uid.isEmpty()) {
                missing++;
                if (missSample.isEmpty()) missSample = t.note;
            }
        }
        ok("没有任务缺 uid", missing == 0,
                missing + " 条缺，例如「" + missSample + "」");

        // id 必须等于 uid —— 否则 Store 存的和云端的对不上
        int mismatch = 0;
        String mmSample = "";
        for (Plan.Task t : all) {
            if (!t.id.equals(t.uid)) {
                mismatch++;
                if (mmSample.isEmpty()) mmSample = t.uid + " != " + t.id;
            }
        }
        ok("存储 id 就是 uid", mismatch == 0, mismatch + " 条不一致，例如 " + mmSample);

        // 同一天内不能重复（跨天重复是故意的螺旋回访）
        int sameDayDup = 0;
        String dupSample = "";
        for (Plan.Day d : p.days) {
            Set<String> seen = new HashSet<>();
            for (Plan.Task t : d.tasks) {
                if (!seen.add(t.uid)) {
                    sameDayDup++;
                    if (dupSample.isEmpty()) dupSample = d.date + " / " + t.uid;
                }
            }
        }
        ok("同一天内 uid 不重复", sameDayDup == 0,
                sameDayDup + " 处，例如 " + dupSample);

        // 跨天重复应该是「同一章回访」，那么这些 uid 必须真的指向同一章
        Map<String, Set<String>> byUid = new LinkedHashMap<>();
        for (Plan.Task t : all) {
            byUid.computeIfAbsent(t.uid, k -> new HashSet<>()).add(t.chapterId);
        }
        int badCross = 0;
        String badSample = "";
        for (Map.Entry<String, Set<String>> e : byUid.entrySet()) {
            if (e.getValue().size() > 1) {
                badCross++;
                if (badSample.isEmpty()) badSample = e.getKey() + " -> " + e.getValue();
            }
        }
        ok("跨天重复的 uid 指向同一章", badCross == 0,
                badCross + " 个 uid 跨了章，例如 " + badSample);

        // ── 核心断言：改文案不能让 uid 漂移 ──────────────
        //
        // 第一版我写死了 "进制转换 10 题" 去替换，结果那条报「找不到文案」——
        // 依赖固定字符串的测试，文案一改自己先碎。改成自己挑一条来变异：
        // 从解析结果里取任一 other 任务的 note，在 JSON 里把它改掉，重新解析，
        // 断言同一个 uid 还在。
        Plan.Task victim = null;
        for (Plan.Task t : all) {
            if ("other".equals(t.kind) && t.note.length() > 4) { victim = t; break; }
        }
        ok("找到可变异的任务", victim != null, "没有 other 任务");

        if (victim != null) {
            String oldUid = victim.uid;
            String oldNote = victim.note;
            String mutatedNote = oldNote + "（文案改了）";
            String tweaked = json.replace(
                    json.substring(json.indexOf(oldNote) - 1,
                                   json.indexOf(oldNote) + oldNote.length() + 1),
                    json.substring(json.indexOf(oldNote) - 1, json.indexOf(oldNote))
                            + mutatedNote + json.substring(json.indexOf(oldNote) + oldNote.length(),
                                                          json.indexOf(oldNote) + oldNote.length() + 1));
            boolean mutated = !tweaked.equals(json);
            ok("变异真的生效", mutated, "JSON 里没找到这条 note");

            if (mutated) {
                Plan p2 = Plan.parse(tweaked);
                String newUid = null;
                int seenMutated = 0;
                for (Plan.Day d : p2.days) {
                    for (Plan.Task t : d.tasks) {
                        if (mutatedNote.equals(t.note)) { newUid = t.uid; seenMutated++; }
                    }
                }
                ok("改文案后仍能找到该任务", seenMutated > 0, "变异后的任务消失了");
                ok("改文案后 uid 完全不变", oldUid.equals(newUid),
                        "「" + oldNote + "」的 uid 从 " + oldUid + " 变成 " + newUid);
            }
        }

        System.out.println();
        System.out.println("  " + byUid.size() + " 个不同 uid 覆盖 " + all.size() + " 条任务");
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }
}