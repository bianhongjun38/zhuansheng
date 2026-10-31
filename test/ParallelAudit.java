package com.zsb.plan;

import java.util.Arrays;

/**
 * ParallelFetch 的分段计划审计。
 *
 * 段计划错了会怎样：段与段重叠 → 同一字节被两个线程写到同一偏移，
 * 文件内容取决于谁后写（下载"成功"但装不上）；
 * 段与段之间有缝 → 那一段永远是 0，APK 解析失败。
 * 这两种都不会抛异常，只会静默产出坏文件，所以必须逐字节验完。
 */
public class ParallelAudit {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) { ok(name, cond, null); }

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("    PASS  " + name); }
        else {
            fail++;
            System.out.println("    FAIL  " + name
                    + (detail == null ? "" : "  [" + detail + "]"));
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 分段计划 ===");

        // 447 MB 真实包
        long real = 469467782L;
        long[][] s8 = ParallelFetch.plan(real, 8);
        check("447MB / 8 并发", s8, real);

        // 各种边界
        check("1 字节", ParallelFetch.plan(1, 8), 1);
        check("刚好 2MB", ParallelFetch.plan(2L * 1024 * 1024, 8), 2L * 1024 * 1024);
        check("2MB-1", ParallelFetch.plan(2L * 1024 * 1024 - 1, 8), 2L * 1024 * 1024 - 1);
        check("10MB", ParallelFetch.plan(10L * 1024 * 1024, 4), 10L * 1024 * 1024);
        check("100MB / 1 并发", ParallelFetch.plan(100L * 1024 * 1024, 1), 100L * 1024 * 1024);

        System.out.println("\n=== 异常入参不崩 ===");
        ok("total=0 得到空计划", ParallelFetch.plan(0, 8).length == 0);
        ok("total 负数得到空计划", ParallelFetch.plan(-5, 8).length == 0);
        long[][] cz = ParallelFetch.plan(real, 0);
        check("并发 0 当 1 处理", cz, real);
        long[][] cbig = ParallelFetch.plan(real, 999);
        ok("并发 999 被夹到上限内", cbig.length <= ParallelFetch.MAX_CONNS * 2,
                "段数=" + cbig.length);

        System.out.println("\n=== 并发上限 ===");
        ok("MAX_CONNS = 8（实测 12 路会触发限流）", ParallelFetch.MAX_CONNS == 8,
                String.valueOf(ParallelFetch.MAX_CONNS));
        long[][] sc = ParallelFetch.plan(real, ParallelFetch.MAX_CONNS);
        ok("8 并发产生 16 段（2 倍，让先完成的线程能领下一段）",
                sc.length == 16, "实际 " + sc.length);

        System.out.println("\n=== Content-Range 解析 ===");
        ok("bytes 0-0/469467782",
                ParallelFetch.totalOf("bytes 0-0/469467782") == 469467782L);
        ok("bytes 100-200/1024", ParallelFetch.totalOf("bytes 100-200/1024") == 1024L);
        ok("null 返回 -1", ParallelFetch.totalOf(null) == -1);
        ok("无斜杠返回 -1", ParallelFetch.totalOf("bytes 0-0") == -1);
        ok("* 返回 -1", ParallelFetch.totalOf("bytes 0-0/*") == -1);
        ok("乱码不抛异常", ParallelFetch.totalOf("garbage") == -1);
        ok("总长 0 视为无效",
                ParallelFetch.totalOf("bytes 0-0/0") == -1);

        System.out.println();
        System.out.println("  STAT " + pass + " " + fail);
    }

    /** 逐字节验证：覆盖完整、无重叠、无缝隙、单调递增。 */
    static void check(String name, long[][] segs, long total) {
        if (segs.length == 0) { ok(name, false, "空计划"); return; }
        long expect = 0;
        for (int i = 0; i < segs.length; i++) {
            long a = segs[i][0], b = segs[i][1];
            if (a != expect) {
                ok(name, false, "第" + i + "段起点 " + a + " 应为 " + expect
                        + (a > expect ? "（有缝隙）" : "（有重叠）"));
                return;
            }
            if (b < a) { ok(name, false, "第" + i + "段终点 " + b + " < 起点 " + a); return; }
            expect = b + 1;
        }
        if (expect != total) {
            ok(name, false, "覆盖到 " + expect + " 但总长 " + total);
            return;
        }
        ok(name + "（" + segs.length + " 段，逐字节覆盖）", true);
    }
}