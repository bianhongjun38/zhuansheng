package com.zsb.plan;

import java.util.*;

/**
 * 端到端贯通验证：真实 localStorage JSON → Java 解析 → 界面数字。
 *
 * 输入是 Chrome 里真跑 AtlasStats.snippet() 得到的 JSON
 * （原样抄过来，不是手写的），验证两件事：
 *   ① Java 侧认得页面真实吐出的格式
 *   ② 断签点（4 天前故意空着）能被正确识别
 */
public class E2EAudit {
    static int pass = 0, fail = 0;
    static void ok(String n, boolean c) { ok(n, c, null); }

    static void ok(String n, boolean c, String d) {
        if (c) { pass++; System.out.println("  PASS  " + n + (d != null ? "  [" + d + "]" : "")); }
        else { fail++; System.out.println("  FAIL  " + n + (d != null ? "  [" + d + "]" : "")); }
    }
    static String key(int back) {
        Calendar c = Calendar.getInstance();
        c.setTime(NOW); c.add(Calendar.DATE, -back);
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH)+1, c.get(Calendar.DAY_OF_MONTH));
    }
    static Date NOW = new Date();

    public static void main(String[] a) {
        // 页面真实输出（结构照抄 Chrome 结果，日期用运行时算）
        String json = "{\"days\":{"
            + "\"" + key(0) + "\":{\"n\":10,\"r\":12,\"c\":19,\"t\":640},"
            + "\"" + key(1) + "\":{\"n\":10,\"r\":8,\"c\":16,\"t\":500},"
            + "\"" + key(2) + "\":{\"n\":8,\"r\":5,\"c\":12,\"t\":420},"
            + "\"" + key(3) + "\":{\"n\":10,\"r\":3,\"c\":11,\"t\":380},"
            + "\"" + key(5) + "\":{\"n\":6,\"r\":2,\"c\":7,\"t\":200},"
            + "\"" + key(6) + "\":{\"n\":9,\"r\":4,\"c\":12,\"t\":300}"
            + "},\"cards\":{\"abandon\":{},\"ability\":{},\"absorb\":{},\"abstract\":{},\"absurd\":{}}}";

        AtlasStats.Checkin c = AtlasStats.parseAt(json, NOW);
        System.out.println("=== 真实页面 JSON → 首页要显示的数字 ===");
        ok("解析成功", c.ok);
        ok("今天已打卡", c.checkedToday());
        ok("今天新词 10", c.todayNew == 10, String.valueOf(c.todayNew));
        ok("今天复习 12", c.todayReview == 12, String.valueOf(c.todayReview));
        ok("今天共 22 词", c.todayTotal() == 22, String.valueOf(c.todayTotal()));
        ok("连续 4 天（4 天前空着）", c.streak == 4, String.valueOf(c.streak));
        ok("累计学过 5 词", c.learned == 5, String.valueOf(c.learned));
        // 柱高口径是「当天动过的总词数」= 新词 n + 复习 r，
        // 和上面今日数字里的「共 22 个词」保持同一口径。
        //  6天前 9+4=13   5天前 6+2=8   4天前 断签 0
        //  3天前 10+3=13  前天 8+5=13   昨天 10+8=18   今天 10+12=22
        ok("一周 = [13,8,0,13,13,18,22]",
           Arrays.equals(c.week, new int[]{13,8,0,13,13,18,22}),
           Arrays.toString(c.week));
        ok("柱高基准 = 22（今天最高）", c.weekMax() == 22, String.valueOf(c.weekMax()));

        System.out.println();
        System.out.println("=== 界面文案预演 ===");
        System.out.println("  状态行  : " + (c.checkedToday() ? "● 今天已打卡" : "○ 今天还没打卡")
                + "    🔥 连续 " + c.streak + " 天");
        System.out.println("  今日数字: 新词 " + c.todayNew + " 个　复习 " + c.todayReview
                + " 个　共 " + c.todayTotal() + " 个词");
        System.out.println("  柱状图  : " + Arrays.toString(c.week) + "  (max=" + c.weekMax() + ")");
        System.out.println("  累计    : 累计学过 " + c.learned + " 个词");

        System.out.println();
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }
}