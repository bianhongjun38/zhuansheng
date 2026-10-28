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
            // 每段按 100 进制挤压若越界会与更高版本碰撞（1.5.100 == 1.6.0），
            // 越界时退回大端拼接，保证严格大于任何合法的小版本号。
            if (maj >= 100 || min >= 100 || pat >= 100) {
                return maj * 1000000L > Integer.MAX_VALUE
                        ? Integer.MAX_VALUE
                        : (int) (maj * 1000000L + min * 10000L + pat);
            }
            return maj * 10000 + min * 100 + pat;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 从若干来源里挑最新的那一份 —— 与 Update.fetchLatestAny 同一策略。
     * 这里用 int 版本号代替 Latest 对象，逻辑一一对应：
     *   · 跳过解析失败（0）
     *   · 版本更高就替换
     *   · 同版本但当前没下载地址、新的有，就补上地址
     */
    static int pickNewest(int bestCode, boolean bestHasUrl,
                          int code, boolean hasUrl, boolean[] bestHasUrlOut) {
        if (code == 0) { bestHasUrlOut[0] = bestHasUrl; return bestCode; }
        if (bestCode == 0) { bestHasUrlOut[0] = hasUrl; return code; }
        if (code > bestCode) { bestHasUrlOut[0] = hasUrl; return code; }
        if (code == bestCode && !bestHasUrl && hasUrl) {
            bestHasUrlOut[0] = true;
            return bestCode;
        }
        bestHasUrlOut[0] = bestHasUrl;
        return bestCode;
    }

    /**
     * 现场故障必须被这次修复挡住。
     *
     * 真实情形（2026-10-27 用户报告）：
     *   手机上装的是旧版（1.2.3 这类），GitHub 上已经有 1.4.1，
     *   但 App 每次点「检查更新」都回「已是最新版本」。
     * 因为 update.json 停在 1.2.3，而旧实现拿它当第一顺位且读到即返回。
     */
    static void regressionStaleManifestBlocksGithub() {
        // 三份来源的真实数值（2026-10-27 实测拉到的）
        int u1 = parseVer("1.2.3");   // 线上清单里的旧版本（过期）
        int u2 = parseVer("1.2.3");   // zsb-update.pages.dev/update.json（同样过期）
        int gh = parseVer("v1.4.1");  // api.github.com releases/latest（正确）
        int local = parseVer("1.2.3"); // 用户手机上实际装的旧版本

        boolean[] hasUrl = new boolean[1];
        int best = 0;
        best = pickNewest(best, hasUrl[0], u1, true, hasUrl);
        best = pickNewest(best, hasUrl[0], u2, true, hasUrl);
        best = pickNewest(best, hasUrl[0], gh, true, hasUrl);

        eq("过期清单不再压住 GitHub", best, 10401);
        ok("修复后能识别出有新版（本机 1.2.3）", best > local);
        // 旧实现拿到的是 u1，且直接 return —— 复现用户看到的错误结论
        ok("修复前会误判为最新（记录故障）", u1 <= local);

        // 本机已是最新时不能误报（本机 1.4.1、线上也是 1.4.1）
        ok("本机已最新则不提示", !(best > parseVer("1.4.1")));

        // 只有 GitHub 通、两份清单都拿不到时也要能用
        boolean[] h2 = new boolean[1];
        int onlyGh = pickNewest(0, false, gh, true, h2);
        eq("只剩 GitHub 通道也能拿到版本", onlyGh, 10401);
        ok("只剩 GitHub 通道时有下载地址", h2[0]);

        // 同版本、Pages 那份因超限把 apk 置 null 的情况：
        // 版本号补齐后必须把 GitHub 的下载地址补进来
        boolean[] h3 = new boolean[1];
        int v1 = pickNewest(0, false, parseVer("1.4.1"), false, h3);
        ok("同版本无地址时初态正确", v1 == 10401 && !h3[0]);
        int v2 = pickNewest(v1, h3[0], parseVer("v1.4.1"), true, h3);
        eq("同版本不降级", v2, 10401);
        ok("同版本补上了下载地址", h3[0]);

        // 更老的一份不能顶掉更新的
        boolean[] h4 = new boolean[1];
        int a = pickNewest(0, false, gh, true, h4);
        int b = pickNewest(a, h4[0], u1, true, h4);
        eq("更老的来源不顶掉更新的", b, 10401);
        ok("老来源不改变地址", h4[0]);
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

        // ── 2026-10-27 真实故障回归 ─────────────────────────
        // 现场：update.json 停在 1.2.3，GitHub 上已经是 1.4.1，
        // 本机装 1.4.1，App 却报「已是最新版本」。
        // 原因不是网络、不是签名，是取值策略：旧实现三源降级
        // 「第一个能读到就返回」，过期的那份排在第一位，
        // 于是把后面正确的来源整个挡住。
        regressionStaleManifestBlocksGithub();

        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        // 纯 ASCII 统计行：CI 的 locale 不一定是 UTF-8，中文会变成 ?，
        // verify.py 就抓不到数字。这一行任何编码下都读得出来。
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }
}