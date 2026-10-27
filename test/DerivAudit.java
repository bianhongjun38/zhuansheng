package com.zsb.plan;

import java.util.List;

/**
 * 求导公式表的「显示层」审计。
 *
 * 背景：表里每条都同时有
 *   expr/der     机器写法（sqrt(x)、exp(x)），给 Expr 解析、给 MathTest 求导对照
 *   show/showDer 标准写法（√(x)、e^(x)），给人看
 * 两者必须是同一个数学式子。只要有一行"只改了显示"或"只改了计算"，
 * 用户看到的就不是被验证过的那个式子 —— 这是最难察觉的错。
 *
 * 关键设计：显示串本身**不能**被 Expr 解析（√、e^、arcsin 都不是它认的
 * 语法），所以不能用"解析 show 再比数值"。改用一个更强的不变量：
 *
 *     把 show 做一次可逆还原（√→sqrt、e^→exp、arcsin→asin…）
 *     还原后必须能被 Expr 解析，且在取样点上与原式数值完全一致
 *
 * 这同时钉死了三件事：显示串没写错、还原规则没写错、
 * 还原后与原式是同一个函数。任一处改动都会当场红。
 */
public class DerivAudit {

    static int pass = 0, fail = 0;

    static void ok(String what, boolean cond, String detail) {
        if (cond) pass++;
        else { fail++; System.out.println("  ✗ " + what + "  →  " + detail); }
    }

    /** 机器名清单：显示层出现任何一个都算没改干净。 */
    static final String[] MACHINE = {
            "sqrt(", "exp(", "log2(", "log10(", "asin(", "acos(", "atan(", "abs("};

    /**
     * 显示写法 → 机器写法。与 DerivTable.pretty 严格互逆。
     * 顺序不能乱：arcsin 要先于 asin 不该出现，log₂ 要先于 lg。
     */
    static String depretty(String s) {
        if (s == null) return "";
        return s
                .replace("arcsin(", "asin(")
                .replace("arccos(", "acos(")
                .replace("arctan(", "atan(")
                .replace("log₂(", "log2(")
                .replace("lg(", "log10(")
                .replace("e^(", "exp(")
                .replace("√(", "sqrt(");
    }

    public static void main(String[] a) {
        System.out.println("▶ 公式表显示层");
        List<DerivTable.Row> rows = DerivTable.all();
        int clean = 0;

        for (DerivTable.Row r : rows) {
            String tag = r.name;

            // ① 显示层不许留机器名
            String bad = null;
            for (String m : MACHINE) {
                if (r.show.contains(m) || r.showDer.contains(m)) { bad = m; break; }
            }
            ok("「" + tag + "」显示层无机器名", bad == null,
               "含 " + bad + "  →  " + r.show + " / " + r.showDer);
            if (bad == null) clean++;

            // ② show 必须等于 pretty(expr)：防止只改一边
            ok("「" + tag + "」show 与 pretty(expr) 一致",
               r.show.equals(DerivTable.Row.pretty(r.expr)),
               r.show + " vs " + DerivTable.Row.pretty(r.expr));
            ok("「" + tag + "」showDer 与 pretty(der) 一致",
               r.showDer.equals(DerivTable.Row.pretty(r.der)),
               r.showDer + " vs " + DerivTable.Row.pretty(r.der));

            // ③ 可逆：还原后必须解析得出，且与原式数值一致
            sameValue(tag + "原函数", r.expr, r.show);
            sameValue(tag + "导数", r.der, r.showDer);
        }

        ok("全部 " + rows.size() + " 条显示层已转标准写法",
           clean == rows.size(), clean + " / " + rows.size());

        System.out.println("  通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }

    /** 机器写法与「显示还原」后必须是同一个函数。 */
    static void sameValue(String tag, String machine, String shown) {
        Expr.Node m1, m2;
        try {
            m1 = Expr.parse(machine);
        } catch (Exception e) {
            ok(tag + " 机器写法可解析", false, machine + " : " + e.getMessage());
            return;
        }
        String back = depretty(shown);
        try {
            m2 = Expr.parse(back);
        } catch (Exception e) {
            ok(tag + " 显示还原后可解析", false, shown + " → " + back + " : " + e.getMessage());
            return;
        }
        double[] xs = {0.7, 1.3, 2.1, 3.4};
        for (double x : xs) {
            double v1 = Expr.eval(m1, x);
            double v2 = Expr.eval(m2, x);
            if (Double.isNaN(v1) && Double.isNaN(v2)) continue;
            if (Double.isNaN(v1) != Double.isNaN(v2)) {
                ok(tag + " 在 x=" + x + " 定义域一致", false,
                   m1 + "→" + v1 + "  vs  " + m2 + "→" + v2);
                return;
            }
            double d = Math.abs(v1 - v2);
            double sc = Math.max(1, Math.abs(v1));
            if (d / sc > 1e-9) {
                ok(tag + " 在 x=" + x + " 数值一致", false, v1 + " vs " + v2);
                return;
            }
        }
        ok(tag + " 与原式数值一致", true, "");
    }
}
