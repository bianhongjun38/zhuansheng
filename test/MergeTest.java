package com.zsb.plan;

import org.json.JSONObject;

/** 桌面验证 merge()：只跑静态方法，不碰 Android UI。 */
public class MergeTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[OK]   " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] a) throws Exception {
        // ── 1. 勾选并集：本地勾了 a、云端勾了 b，结果两个都在 ──
        String local = "{\"state\":{\"done\":{\"2026-01-01|t1\":true},"
                + "\"completed\":{\"2026-01-01\":true}}}}";
        String remote = "{\"state\":{\"done\":{\"2026-01-02|t2\":true},"
                + "\"completed\":{}}}}";
        JSONObject m = new JSONObject(GistSync.merge(local, remote));
        JSONObject st = m.getJSONObject("state");
        check("done 并集保留本地项", st.getJSONObject("done").optBoolean("2026-01-01|t1"));
        check("done 并集保留云端项", st.getJSONObject("done").optBoolean("2026-01-02|t2"));
        check("completed 并集", st.getJSONObject("completed").optBoolean("2026-01-01"));

        // ── 2. 任一端 false / 缺失，另一端 true 即完成 ──
        String l2 = "{\"state\":{\"done\":{\"k\":false}}}";
        String r2 = "{\"state\":{\"done\":{\"k\":true}}}";
        JSONObject st2 = new JSONObject(GistSync.merge(l2, r2)).getJSONObject("state");
        check("一端 false 一端 true => true", st2.getJSONObject("done").optBoolean("k"));

        // ── 3. 数值取大：hard / timing ──
        String l3 = "{\"state\":{\"hard\":{\"q1\":2,\"q2\":3},\"timing\":{\"d|t\":1000}}}";
        String r3 = "{\"state\":{\"hard\":{\"q1\":3,\"q3\":1},\"timing\":{\"d|t\":500}}}";
        JSONObject st3 = new JSONObject(GistSync.merge(l3, r3)).getJSONObject("state");
        check("hard 取大 q1=3", st3.getJSONObject("hard").optLong("q1") == 3L);
        check("hard 保留本地独有 q2=3", st3.getJSONObject("hard").optLong("q2") == 3L);
        check("hard 保留云端独有 q3=1", st3.getJSONObject("hard").optLong("q3") == 1L);
        check("timing 取大 1000", st3.getJSONObject("timing").optLong("d|t") == 1000L);

        // ── 4. notes：本地非空优先；本地空则用云端 ──
        String l4 = "{\"state\":{\"notes\":{\"a\":\"本地写的\",\"b\":\"\"}}}";
        String r4 = "{\"state\":{\"notes\":{\"a\":\"云端旧稿\",\"b\":\"云端补的\"}}}";
        JSONObject st4 = new JSONObject(GistSync.merge(l4, r4)).getJSONObject("state");
        check("notes 冲突用本地", "本地写的".equals(st4.getJSONObject("notes").optString("a")));
        check("notes 本地空用云端", "云端补的".equals(st4.getJSONObject("notes").optString("b")));

        // ── 5. wrong 错题本：n 取大、ts 取新 ──
        String l5 = "{\"state\":{\"wrong\":{\"w1\":{\"n\":1,\"ts\":100,\"s\":\"数学\",\"q\":\"题A\"}}}}";
        String r5 = "{\"state\":{\"wrong\":{\"w1\":{\"n\":4,\"ts\":900,\"s\":\"\",\"q\":\"题A\"},"
                + "\"w2\":{\"n\":2,\"ts\":500,\"s\":\"英语\",\"q\":\"题B\"}}}}";
        JSONObject w1 = new JSONObject(GistSync.merge(l5, r5))
                .getJSONObject("state").getJSONObject("wrong");
        check("wrong n 取大 4", w1.getJSONObject("w1").optLong("n") == 4L);
        check("wrong ts 取新 900", w1.getJSONObject("w1").optLong("ts") == 900L);
        check("wrong 学科本地非空优先", "数学".equals(w1.getJSONObject("w1").optString("s")));
        check("wrong 云端独有条目保留", w1.has("w2") && "英语".equals(w1.getJSONObject("w2").optString("s")));

        // ── 6. 空/坏输入不炸 ──
        check("本地 null 不炸", GistSync.merge(null, remote).contains("2026-01-02|t2"));
        check("远端 null 不炸", GistSync.merge(local, null).contains("2026-01-01|t1"));
        check("两端 null 不炸", GistSync.merge(null, null) != null);
        check("远端坏 JSON 不炸", GistSync.merge(local, "{oops").contains("2026-01-01|t1"));
        check("空串不炸", GistSync.merge("", "").equals("{}"));

        // ── 7. 裸状态（无 state 包装）也能合，且输出形态跟随输入 ──
        String bareL = "{\"done\":{\"x\":true}}";
        String bareR = "{\"done\":{\"y\":true},\"hard\":{\"q\":2}}";
        JSONObject bare = new JSONObject(GistSync.merge(bareL, bareR));
        check("裸状态不套 state 包装", !bare.has("state"));
        check("裸状态并集", bare.getJSONObject("done").optBoolean("x")
                && bare.getJSONObject("done").optBoolean("y"));
        check("裸状态数值取大", bare.getJSONObject("hard").optLong("q") == 2L);

        // ── 8. 包装/裸混合：有包装就输出包装 ──
        JSONObject mixed = new JSONObject(GistSync.merge(local, "{\"done\":{\"z\":true}}"));
        check("混合时输出带包装", mixed.has("state"));
        check("混合时裸端数据不丢", mixed.getJSONObject("state")
                .getJSONObject("done").optBoolean("z"));

        // ── 9. updatedAt 被改写，且真实 exportJson 形态可用 ──
        JSONObject st9 = new JSONObject(GistSync.merge(local, remote)).getJSONObject("state");
        check("updatedAt 已写入", st9.has("updatedAt") && !st9.optString("updatedAt").isEmpty());
        check("外层带 _app", "zsb-plan".equals(m.optString("_app")));

        // ── 10. 幂等：合两次结果一致（除时间戳） ──
        String once = GistSync.merge(local, remote);
        String twice = GistSync.merge(once, remote);
        JSONObject o1 = new JSONObject(once).getJSONObject("state");
        JSONObject o2 = new JSONObject(twice).getJSONObject("state");
        o1.remove("updatedAt"); o2.remove("updatedAt");
        check("合并幂等", o1.toString().equals(o2.toString()));

        // ── 11. HTTP 状态码翻译 ──
        check("401 有中文提示", GistSync.httpMsg(401, "{\"message\":\"Bad credentials\"}")
                .contains("Token 无效"));
        check("401 带 GitHub 原文", GistSync.httpMsg(401, "{\"message\":\"Bad credentials\"}")
                .contains("Bad credentials"));
        check("403 提示权限/限流", GistSync.httpMsg(403, "").contains("权限"));
        check("404 提示 Gist ID", GistSync.httpMsg(404, "").contains("Gist ID"));
        check("503 提示稍后再试", GistSync.httpMsg(503, "").contains("再试"));

        System.out.println("\n==== pass=" + pass + " fail=" + fail + " ====");
        if (fail > 0) System.exit(1);
    }
}
