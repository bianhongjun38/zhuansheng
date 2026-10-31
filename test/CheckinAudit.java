package com.zsb.plan;

import java.util.*;

/**
 * 雅思打卡解析的验证。
 *
 * 被测的是 AtlasStats.parseAt —— 它把 localStorage 里的 progress JSON
 * 变成首页要显示的数字。这段逻辑错一个边界，用户就会看到错的打卡状态：
 *   · 昨天背了、今天还没开始 → 不能显示"断签"
 *   · 今天刚开始背 1 个词  → 应该立刻显示"已打卡"
 *   · 数据损坏 → 显示"还没开始"，不能崩、不能显示 0 天已打卡
 *
 * 测的是纯函数，不需要 Android 环境。
 */
public class CheckinAudit {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) { ok(name, cond, null); }

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  PASS  " + name + (detail != null ? "  [" + detail + "]" : "")); }
        else { fail++; System.out.println("  FAIL  " + name + (detail != null ? "  [" + detail + "]" : "")); }
    }

    /** 构造某一天的 key */
    static String key(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static Calendar cal(int backDays) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.OCTOBER, 31, 12, 0, 0);   // 固定"今天"，测试才可复现
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.DATE, -backDays);
        return c;
    }

    static Date now() { return cal(0).getTime(); }

    public static void main(String[] a) {
        System.out.println("=== ① 正常数据：今天背了 10 个新词 ===");
        {
            String json = "{\"days\":{\"" + key(cal(0)) + "\":{\"n\":10,\"r\":0}},"
                    + "\"cards\":{\"a\":1,\"b\":1,\"c\":1}}";
            AtlasStats.Checkin c = AtlasStats.parseAt(json, now());
            ok("读到数据", c.ok);
            ok("今天新词 10", c.todayNew == 10, String.valueOf(c.todayNew));
            ok("复习 0", c.todayReview == 0);
            ok("算作已打卡", c.checkedToday());
            ok("连续 1 天", c.streak == 1, String.valueOf(c.streak));
            ok("累计学过 3 词", c.learned == 3, String.valueOf(c.learned));
        }

        System.out.println();
        System.out.println("=== ② 关键边界：昨天背了，今天还没开始 ===");
        {
            String json = "{\"days\":{\"" + key(cal(1)) + "\":{\"n\":8,\"r\":2}}}";
            AtlasStats.Checkin c = AtlasStats.parseAt(json, now());
            ok("今天没打卡", !c.checkedToday());
            ok("但连续天数仍算 1（不能显示断签）", c.streak == 1, String.valueOf(c.streak));
            ok("今天数字为 0", c.todayNew == 0 && c.todayReview == 0);
        }

        System.out.println();
        System.out.println("=== ③ 连续 5 天，中间断一天 ===");
        {
            // 今天、-1、-2、-3 有；-4 空；-5、-6 有
            StringBuilder sb = new StringBuilder("{\"days\":{");
            int[] back = {0, 1, 2, 3, 5, 6};
            for (int i = 0; i < back.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(key(cal(back[i]))).append("\":{\"n\":5,\"r\":0}");
            }
            sb.append("}}");
            AtlasStats.Checkin c = AtlasStats.parseAt(sb.toString(), now());
            ok("连续 4 天（-4 断了就停）", c.streak == 4, String.valueOf(c.streak));
        }

        System.out.println();
        System.out.println("=== ④ 复习也算打卡（n=0 但 r>0）===");
        {
            String json = "{\"days\":{\"" + key(cal(0)) + "\":{\"n\":0,\"r\":7}}}";
            AtlasStats.Checkin c = AtlasStats.parseAt(json, now());
            ok("今天复习 7 个", c.todayReview == 7);
            ok("算作已打卡", c.checkedToday());
        }

        System.out.println();
        System.out.println("=== ⑤ 空 / 损坏数据一律当「没数据」 ===");
        {
            String[] bad = {null, "", "null", "{}", "不是JSON", "{\"days\":null}",
                            "[1,2,3]", "\"\""};
            boolean allOk = true;
            StringBuilder why = new StringBuilder();
            for (String s : bad) {
                AtlasStats.Checkin c = AtlasStats.parseAt(s, now());
                // 要么明确 ok=false，要么 ok=true 但数字全 0（空 days 对象）
                boolean safe = !c.ok || (c.streak == 0 && c.todayTotal() == 0);
                if (!safe) { allOk = false; why.append("[").append(s).append("] "); }
            }
            ok("八种异常输入都不会伪造出打卡", allOk, why.length() > 0 ? why.toString() : "全部安全");
            ok("null 输入不崩", AtlasStats.parseAt(null, now()) != null);
        }

        System.out.println();
        System.out.println("=== ⑥ evaluateJavascript 的转义外层 ===");
        {
            String inner = "{\"days\":{\"" + key(cal(0)) + "\":{\"n\":3,\"r\":1}}}";
            // WebView 有时会返回 \"...\" 这种再包一层的字符串
            String wrapped = "\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            AtlasStats.Checkin c = AtlasStats.parseAt(wrapped, now());
            ok("剥掉外层引号后能解析", c.ok);
            ok("新词 3", c.todayNew == 3, String.valueOf(c.todayNew));
        }

        System.out.println();
        System.out.println("=== ⑦ 一周柱状图的取值 ===");
        {
            StringBuilder sb = new StringBuilder("{\"days\":{");
            int[] vals = {2, 0, 5, 9, 3, 0, 7};   // 6天前 … 今天
            for (int i = 0; i < 7; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(key(cal(6 - i))).append("\":{\"n\":").append(vals[i]).append(",\"r\":0}");
            }
            sb.append("}}");
            AtlasStats.Checkin c = AtlasStats.parseAt(sb.toString(), now());
            boolean same = true;
            for (int i = 0; i < 7; i++) if (c.week[i] != vals[i]) same = false;
            ok("7 天数值按时间顺序对上", same,
                    Arrays.toString(c.week) + " vs " + Arrays.toString(vals));
            ok("最大值 9（柱高基准）", c.weekMax() == 9, String.valueOf(c.weekMax()));
            ok("今天在最后一格", c.week[6] == 7, String.valueOf(c.week[6]));
        }

        System.out.println();
        System.out.println("=== ⑧ weekMax 不会返回 0（防除零）===");
        {
            AtlasStats.Checkin empty = new AtlasStats.Checkin();
            ok("全 0 时 weekMax=1", empty.weekMax() == 1, String.valueOf(empty.weekMax()));
        }

        System.out.println();
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }
}
