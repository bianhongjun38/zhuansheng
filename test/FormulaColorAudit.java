package com.zsb.plan;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/**
 * 公式颜色审计（源码级）。
 *
 * 起因：实机上 eˣ 只剩下一个 e，指数 x 是黑的看不见 ——
 * FormulaView 给上下标单独取了一支 Paint，只设了字号和字体，
 * 没设颜色，于是退回 Android Paint 的默认黑色。
 *
 * 这类问题跑公式几何审计是抓不到的（位置全对、只是看不见），
 * 所以这里直接从源码上钉住：缩放画笔必须显式继承颜色。
 */
public class FormulaColorAudit {

    static int pass = 0, fail = 0;

    static void ok(String w, boolean c, String d) {
        if (c) pass++;
        else { fail++; System.out.println("  ✗ " + w + "  →  " + d); }
    }

    public static void main(String[] a) throws Exception {
        System.out.println("▶ 公式颜色（上下标不能是黑的）");

        String src = read("src/com/zsb/plan/FormulaView.java");
        ok("读到 FormulaView.java", !src.isEmpty(), "文件为空");

        // 找到 paintFor 方法体
        int i = src.indexOf("private Paint paintFor(float scale)");
        ok("找到 paintFor", i >= 0, "方法被改名了？");
        if (i < 0) { summary(); return; }

        int end = src.indexOf("\n    }", i);
        String body = end > i ? src.substring(i, end) : src.substring(i);

        // 必须先去掉注释行再判断。
        // 第一次写这个审计时只做了 contains("setColor(")，结果把
        // "// scaled.setColor(...)" 也算成通过 —— 故意注释掉那一行去试，
        // 审计照样报绿。查字符串而不查语义，就是这种下场。
        String code = stripComments(body);

        ok("缩放画笔设了颜色",
                code.contains("setColor("),
                "只设字号/字体时 Paint 默认黑色，上下标会消失在黑底上");
        ok("缩放画笔继承 paint 的颜色",
                code.contains("setColor(paint.getColor())"),
                "要继承主画笔颜色，不能写死");

        // 主画笔的颜色必须来自 setTextColor
        ok("主画笔颜色可由外部设置",
                src.contains("setTextColor"),
                "没有 setTextColor，颜色无从继承");

        summary();
    }

    /** 去掉整行注释和行尾注释，只留可执行代码。 */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) continue;
            int c = t.indexOf("//");
            if (c > 0) t = t.substring(0, c);
            out.append(t).append('\n');
        }
        return out.toString();
    }

    static String read(String p) throws Exception {
        try {
            return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static void summary() {
        System.out.println();
        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }
}