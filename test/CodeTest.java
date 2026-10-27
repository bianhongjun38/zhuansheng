package com.zsb.plan;

import java.util.Arrays;

/**
 * 进度码编解码的桌面测试。
 * 不依赖 Android，可直接 java 运行。
 */
public class CodeTest {

    private static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("▶ 进度码");

        // 造一份接近真实的导出数据
        StateLogic s = new StateLogic();
        for (int i = 0; i < 60; i++) {
            s.setDone("2026-10-" + (10 + i % 18), "L-math-M1-" + i, true);
        }
        for (int i = 0; i < 12; i++) s.commitDay("2026-10-" + (10 + i));
        for (int i = 0; i < 20; i++) s.addTiming("2026-10-15", "T" + i, 1000L * (i + 1));
        for (int i = 0; i < 8; i++) s.setNote("2026-10-15", "N" + i, "默写内容第" + i + "条 有点长");
        for (int i = 0; i < 5; i++) { s.bumpHard("H" + i); s.bumpHard("H" + i); }

        String json = s.exportJson();
        ok("导出非空", json != null && json.length() > 100);

        // 编码
        String code = ProgressCode.encode(json);
        ok("编码成功", code != null && code.length() > 20);
        ok("有前缀", code.startsWith("ZSB1-"));
        System.out.println("    原始 " + json.length() + " 字符 → 进度码 " + code.length() + " 字符");
        System.out.println("    " + (code.length() > 100 ? code.substring(0, 100) + "…" : code));

        // 往返
        String back = ProgressCode.decode(code);
        ok("解码成功", back != null);
        ok("往返一致", json.equals(back));

        // 恢复进新实例
        if (back != null) {
            StateLogic s2 = new StateLogic();
            int n = s2.importJson(back);
            ok("导入条数=" + n, n == 60);
            ok("确认天数一致", s2.completedCount() == 12);
            ok("勾选状态可查", s2.isDone("2026-10-10", "L-math-M1-0"));
            ok("计时一致", s2.timingOf("2026-10-15", "T19") == 20000L);
            ok("笔记一致", "默写内容第3条 有点长".equals(s2.noteOf("2026-10-15", "N3")));
            ok("错题星级一致", s2.hardLevel("H4") == 2);
        }

        // 容错：空格 / 换行 / 小写 / 去掉横杠都能解
        String messy = "  " + code.toLowerCase().replace("-", " \n") + "  ";
        ok("容忍空格换行小写", json.equals(ProgressCode.decode(messy)));

        // 抄错一位必须失败，不能悄悄导入错数据
        if (code.length() > 30) {
            char[] a = code.toCharArray();
            int pos = 12;
            a[pos] = (a[pos] == 'A' ? 'B' : 'A');
            String bad = new String(a);
            ok("改一位被拒绝", ProgressCode.decode(bad) == null);
        }

        // 垃圾输入不能崩
        ok("null 安全", ProgressCode.decode(null) == null);
        ok("空串安全", ProgressCode.decode("") == null);
        ok("乱码安全", ProgressCode.decode("HELLO-WORLD") == null);
        ok("前缀对但太短", ProgressCode.decode("ZSB1-AB") == null);

        // 空进度也要能编解码
        StateLogic empty = new StateLogic();
        String ec = ProgressCode.encode(empty.exportJson());
        ok("空进度可编码", ec != null);
        ok("空进度可解码", ProgressCode.decode(ec) != null);

        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        // 纯 ASCII 统计行：CI 的 locale 不一定是 UTF-8，中文会变成 ?，
        // verify.py 就抓不到数字。这一行任何编码下都读得出来。
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }
}
