package com.zsb.plan;

/**
 * 「忽略此版本」的版本号比较逻辑。
 *
 * Update.isSkipped() 用的是 parseVer 比较，不是字符串比较。
 * 这两者差别很大，写错会静默出错：
 *
 *   "1.5.9"  和 "1.5.10"  字符串比较 "1.5.9" > "1.5.10"（因为 '9' > '1'），
 *                         于是忽略 1.5.9 会连带把 1.5.10 也吞掉。
 *   多了空格、少了前导零，字符串比较全错。
 *
 * 这里把 parseVer 的比较规则逐条钉死，确保「忽略」只影响它该影响的版本。
 */
public class SkipAudit {

    static int pass = 0, fail = 0;

    static void ok(String n, boolean c) { ok(n, c, null); }

    static void ok(String n, boolean c, String d) {
        if (c) { pass++; System.out.println("    PASS  " + n); }
        else { fail++; System.out.println("    FAIL  " + n + (d == null ? "" : "  [" + d + "]")); }
    }

    /** 模拟 Update.isSkipped：等价于 parseVer(skip) == parseVer(ver)。 */
    static boolean skipped(String skipVer, String curVer) {
        if (skipVer == null || curVer == null) return false;
        return Update.parseVer(skipVer) == Update.parseVer(curVer);
    }

    public static void main(String[] args) {
        System.out.println("=== 忽略只影响它自己那一版 ===");
        ok("忽略 1.5.47，当前 1.5.47 → 忽略",
                skipped("1.5.47", "1.5.47"));
        ok("忽略 1.5.47，当前 1.5.48 → 不忽略（新版本要提示）",
                !skipped("1.5.47", "1.5.48"));
        ok("忽略 1.5.47，当前 1.5.46 → 不忽略（旧版本与本机无关）",
                !skipped("1.5.47", "1.5.46"));

        System.out.println("\n=== 字符串比较会错的那些用例 ===");
        ok("忽略 1.5.9，当前 1.5.10 → 不忽略",
                !skipped("1.5.9", "1.5.10"),
                "字符串比较这里会误判");
        ok("忽略 1.5.10，当前 1.5.9 → 不忽略",
                !skipped("1.5.10", "1.5.9"));
        ok("忽略 1.10.0，当前 1.9.0 → 不忽略",
                !skipped("1.10.0", "1.9.0"));

        System.out.println("\n=== 写法差异不该改变判定 ===");
        ok("1.5.47 与 1.5.47 完全一致", skipped("1.5.47", "1.5.47"));
        ok("前导零 01.05.47 等同 1.5.47", skipped("01.05.47", "1.5.47"));
        ok("三位 1.5.47 与四位 1.5.47.0 视为同版",
                skipped("1.5.47", "1.5.47.0"));

        System.out.println("\n=== 脏数据不能误伤 ===");
        ok("空串不忽略", !skipped("", "1.5.47"));
        ok("null 不忽略", !skipped(null, "1.5.47"));
        ok("当前为 null 不忽略", !skipped("1.5.47", null));
        ok("乱码不忽略", !skipped("abc", "1.5.47"));
        ok("当前乱码不忽略", !skipped("1.5.47", "abc"));
        ok("全 0 视为 0，不忽略 1.5.47", !skipped("0.0.0", "1.5.47"));

        System.out.println("\n=== 大版本跳变 ===");
        ok("忽略 1.5.47，当前 2.0.0 → 不忽略", !skipped("1.5.47", "2.0.0"));
        ok("忽略 1.5.47，当前 1.6.0 → 不忽略", !skipped("1.5.47", "1.6.0"));

        System.out.println();
        System.out.println("  STAT " + pass + " " + fail);
    }
}
