package com.zsb.plan;

/**
 * 版本比较测试。
 *
 * 这里踩过一个真 bug：更新检测把 tag "v1.1.0" 解析成 10100，
 * 却拿去和 versionCode（10、11、12 这种构建计数）比，
 * 结果永远判定「有新版本」，每次启动都弹更新框。
 * 所以解析函数的边界必须在测试里钉死不变量。
 *
 * 注：Update.parseVer 依赖 android.app.Activity 无法在桌面直接反射，
 * 这里放一份同样的实现，用「跟 Update.java 同源」的方式保证一致，
 * 并在 verify.py 里额外断言两份源码的解析函数体相同。
 */
public class VerTest {

    private static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) pass++;
        else { fail++; System.out.println("  ✗ " + name); }
    }

    static void eq(String name, int got, int want) {
        if (got == want) pass++;
        else {
            fail++;
            System.out.println("  ✗ " + name + "：得到 " + got + "，应为 " + want);
        }
    }

    // ── 与 Update.parseVer 完全相同的实现 ──────────────────
    static int parseVer(String s) {
        if (s == null) return 0;
        try {
            String t = s.trim();
            if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
            int cut = t.length();
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (ch != '.' && (ch < '0' || ch > '9')) { cut = i; break; }
            }
            t = t.substring(0, cut);
            if (t.isEmpty()) return 0;
            String[] p = t.split("\\.");
            int maj = p.length > 0 ? Integer.parseInt(p[0]) : 0;
            int min = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            int pat = p.length > 2 ? Integer.parseInt(p[2]) : 0;
            return maj * 10000 + min * 100 + pat;
        } catch (Exception e) {
            return 0;
        }
    }

    public static void main(String[] args) {
        System.out.println("▶ 版本比较");

        // 基本解析
        eq("1.0.0", parseVer("1.0.0"), 10000);
        eq("1.1.0", parseVer("1.1.0"), 10100);
        eq("v1.1.0", parseVer("v1.1.0"), 10100);
        eq("V1.1.0 大写", parseVer("V1.1.0"), 10100);
        eq("1.1.1", parseVer("1.1.1"), 10101);
        eq("1.2.0", parseVer("1.2.0"), 10200);
        eq("2.0.0", parseVer("2.0.0"), 20000);

        // 带空格 / 后缀
        eq("前后空格", parseVer("  v1.1.0  "), 10100);
        eq("beta 后缀", parseVer("1.1.0-beta"), 10100);
        eq("rc 后缀", parseVer("v1.2.0-rc1"), 10200);
        eq("两段版本", parseVer("1.5"), 10500);
        eq("单段版本", parseVer("3"), 30000);

        // 异常输入不能崩，返回 0
        eq("null", parseVer(null), 0);
        eq("空串", parseVer(""), 0);
        eq("只要 v", parseVer("v"), 0);
        eq("乱码", parseVer("abc"), 0);
        eq("中文", parseVer("版本一"), 0);

        // 顺序性：这是更新检测的核心不变量
        ok("1.0.0 < 1.1.0", parseVer("1.0.0") < parseVer("1.1.0"));
        ok("1.1.0 < 1.1.1", parseVer("1.1.0") < parseVer("1.1.1"));
        ok("1.1.1 < 1.2.0", parseVer("1.1.1") < parseVer("1.2.0"));
        ok("1.9.9 < 2.0.0", parseVer("1.9.9") < parseVer("2.0.0"));
        ok("v 前缀不影响顺序", parseVer("v1.1.0") == parseVer("1.1.0"));

        // 相同版本必须判等（否则会反复提示更新）
        ok("1.1.0 == v1.1.0", parseVer("1.1.0") == parseVer("v1.1.0"));
        ok("已是最新不提示", parseVer("v1.1.0") <= parseVer("1.1.0"));

        // 回归：这个 bug 具体长什么样
        // 旧代码：parseCode("v1.1.0")=10100 vs versionCode=11 → 10100 > 11 永远成立
        int oldRemote = 10100, oldLocal = 11;
        ok("旧写法确实会误判（记录 bug）", oldRemote > oldLocal);
        // 新写法：两边同单位
        ok("新写法不会误判", !(parseVer("v1.1.0") > parseVer("1.1.0")));

        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
