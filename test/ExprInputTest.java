package com.zsb.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * 函数输入模板审计。
 *
 * 这块代码的用户体验后果特别明显：光标插错位置，用户按一下"sin"就得
 * 手动左移一次；退格退不干净，sin( 要按四遍。所以这里逐条钉死。
 */
public class ExprInputTest {

    static int pass = 0;
    static List<String> fails = new ArrayList<String>();

    static void ok(String name, boolean cond) { ok(name, cond, ""); }

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; return; }
        fails.add(name + (detail.isEmpty() ? "" : "  →  " + detail));
    }

    public static void main(String[] args) {
        // --probe 模式：给 verify.py 用，批量验一串表达式能否被引擎接受。
        // 单独开这个模式是为了避免 verify.py 为每个按键起一次 JVM（20×200ms）。
        if (args.length > 1 && "--probe".equals(args[0])) {
            int bad = 0;
            for (int i = 1; i < args.length; i++) {
                try {
                    Expr.Node n = Expr.parse(args[i]);
                    Expr.eval(n, 1.3);
                } catch (Exception e) {
                    System.out.println("BAD " + args[i] + "  " + e.getMessage());
                    bad++;
                }
            }
            System.exit(bad == 0 ? 0 : 1);
        }
        System.exit(run() == 0 ? 0 : 1);
    }

    public static int run() {
        pass = 0;
        fails.clear();
        try { insertTests(); } catch (Exception e) { fails.add("插入异常：" + e); }
        try { caretTests(); } catch (Exception e) { fails.add("光标异常：" + e); }
        try { backspaceTests(); } catch (Exception e) { fails.add("退格异常：" + e); }
        try { parenTests(); } catch (Exception e) { fails.add("括号异常：" + e); }
        try { templateTests(); } catch (Exception e) { fails.add("模板异常：" + e); }

        System.out.println("  函数输入模板：" + pass + " 项通过，"
                + fails.size() + " 项失败");
        // 纯 ASCII 统计行：CI 的 locale 不一定是 UTF-8，中文会变成 ?，
        // verify.py 就抓不到数字。这一行任何编码下都读得出来。
        System.out.println("STAT " + pass + " " + fails.size());
        for (String f : fails) System.out.println("     ✗ " + f);
        return fails.size();
    }

    // ── ① 插入到光标处 ───────────────────────────────────
    static void insertTests() {
        // 空框里插
        ExprInput.R r = ExprInput.insert("", 0, 0, "x");
        ok("空框插入", "x".equals(r.text) && r.cursor == 1,
                r.text + " @" + r.cursor);

        // 光标在末尾
        r = ExprInput.insert("x+", 2, 2, "1");
        ok("末尾插入", "x+1".equals(r.text) && r.cursor == 3,
                r.text + " @" + r.cursor);

        // 光标在中间。"x+1" 光标在 1 处插 "^2"：
        // head="x"，tail="+1" → "x^2+1"，光标 = 1 + 2 = 3（紧接 "^2" 之后）
        r = ExprInput.insert("x+1", 1, 1, "^2");
        ok("中间插入", "x^2+1".equals(r.text) && r.cursor == 3,
                r.text + " @" + r.cursor);

        // 有选区 → 替换，不是插在前面
        r = ExprInput.insert("x+1", 0, 3, "2x");
        ok("选区被替换", "2x".equals(r.text) && r.cursor == 2,
                r.text + " @" + r.cursor);

        // 选区反向（用户从右往左拖）
        r = ExprInput.insert("abcdef", 4, 2, "X");
        ok("反向选区也替换", "abXef".equals(r.text) && r.cursor == 3,
                r.text + " @" + r.cursor);

        // 越界的光标不能崩（Android 的 getSelectionStart 可能返回 -1）
        r = ExprInput.insert("x", -1, -1, "2");
        ok("负光标不崩", "2x".equals(r.text) && r.cursor == 1,
                r.text + " @" + r.cursor);
        r = ExprInput.insert("x", 99, 99, "2");
        ok("超界光标不崩", "x2".equals(r.text) && r.cursor == 2,
                r.text + " @" + r.cursor);
        r = ExprInput.insert(null, 0, 0, "x");
        ok("null 文本不崩", "x".equals(r.text) && r.cursor == 1, r.text);

        // 空片段：不动
        r = ExprInput.insert("x+1", 1, 1, "");
        ok("空片段不动文本", "x+1".equals(r.text) && r.cursor == 1);
        r = ExprInput.insert("x+1", 1, 1, null);
        ok("null 片段不动文本", "x+1".equals(r.text) && r.cursor == 1);
    }

    // ── ② 光标落点 ───────────────────────────────────────
    static void caretTests() {
        // sin( 插完光标要在括号里面 —— 这是最关键的体验点
        ExprInput.R r = ExprInput.insert("", 0, 0, "sin(@)");
        ok("sin 插完光标在括号内", "sin()".equals(r.text) && r.cursor == 4,
                r.text + " @" + r.cursor);

        r = ExprInput.insert("+2", 0, 0, "sin(@)");
        ok("sin 插入后后面内容保留", "sin()+2".equals(r.text) && r.cursor == 4,
                r.text + " @" + r.cursor);

        // sqrt
        r = ExprInput.insert("", 0, 0, "sqrt(@)");
        ok("sqrt 光标在括号内", "sqrt()".equals(r.text) && r.cursor == 5,
                r.text + " @" + r.cursor);

        // exp / abs / ln
        for (String f : new String[]{"exp", "abs", "ln", "cos", "tan"}) {
            ExprInput.R rr = ExprInput.insert("", 0, 0, f + "(@)");
            ok(f + " 光标在括号内",
               (f + "()").equals(rr.text) && rr.cursor == f.length() + 1,
               rr.text + " @" + rr.cursor);
        }

        // @ 本身必须从文本里消失
        r = ExprInput.insert("", 0, 0, "sin(@)");
        ok("@ 不残留", r.text.indexOf('@') < 0, r.text);

        // 没有 @ 的片段：光标落在末尾
        r = ExprInput.insert("", 0, 0, "x^2");
        ok("无 @ 时光标在末尾", "x^2".equals(r.text) && r.cursor == 3,
                r.text + " @" + r.cursor);
    }

    // ── ③ 退格 ───────────────────────────────────────────
    static void backspaceTests() {
        // 普通退格
        ExprInput.R r = ExprInput.backspace("x+1", 3, 3);
        ok("普通退格删一个字符", "x+".equals(r.text) && r.cursor == 2,
                r.text + " @" + r.cursor);

        // 空串退格不崩
        r = ExprInput.backspace("", 0, 0);
        ok("空串退格不崩", "".equals(r.text) && r.cursor == 0);

        // 光标在开头退格：不动
        r = ExprInput.backspace("x+1", 0, 0);
        ok("开头退格不动", "x+1".equals(r.text) && r.cursor == 0);

        // 有选区退格：删掉 [1,4) 这三个字符 "+1+"，剩下 "x"+"2"
        r = ExprInput.backspace("x+1+2", 1, 4);
        ok("选区退格", "x2".equals(r.text) && r.cursor == 1,
                r.text + " @" + r.cursor);

        // 关键：sin( 一次退干净
        r = ExprInput.backspace("sin()", 4, 4);
        ok("sin( 一次删整段", ")".equals(r.text) && r.cursor == 0,
                r.text + " @" + r.cursor);

        // sqrt( 一次删整段（5 个字符）
        r = ExprInput.backspace("sqrt()", 5, 5);
        ok("sqrt( 一次删整段", ")".equals(r.text) && r.cursor == 0,
                r.text + " @" + r.cursor);

        // arctan( —— 不能被 tan( 抢走，必须整段 7 个字符
        r = ExprInput.backspace("arctan()", 7, 7);
        ok("arctan( 一次删整段", ")".equals(r.text) && r.cursor == 0,
                r.text + " @" + r.cursor);

        // arcsin 不能被 sin 抢走
        r = ExprInput.backspace("arcsin()", 7, 7);
        ok("arcsin( 一次删整段", ")".equals(r.text) && r.cursor == 0,
                r.text + " @" + r.cursor);

        // 前缀不是完整函数名时不能误删
        // "asin(" 前面多一个字母，确实等于 asin，仍应整段删
        r = ExprInput.backspace("2sin()", 5, 5);
        ok("2sin( 删掉 sin(", "2)".equals(r.text) && r.cursor == 1,
                r.text + " @" + r.cursor);

        // 普通括号不能连带前面的字母一起删
        r = ExprInput.backspace("x(", 2, 2);
        ok("x( 只删一个字符", "x".equals(r.text) && r.cursor == 1,
                r.text + " @" + r.cursor);

        // 数字括号
        r = ExprInput.backspace("2(", 2, 2);
        ok("2( 只删一个字符", "2".equals(r.text) && r.cursor == 1,
                r.text + " @" + r.cursor);

        // 多字节字符不能劈成半个
        r = ExprInput.backspace("x+π", 3, 3);
        ok("π 退格完整", "x+".equals(r.text) && r.cursor == 2,
                r.text + " @" + r.cursor);

        // 中文括号也该被 x( 规则放过（不是函数名）
        r = ExprInput.backspace("x（", 2, 2);
        ok("中文括号退格", r.text.length() == 1, r.text);
    }

    // ── ④ 括号平衡 ───────────────────────────────────────
    static void parenTests() {
        ok("平衡返回 0", ExprInput.unclosedParens("sin(x)") == 0,
                String.valueOf(ExprInput.unclosedParens("sin(x)")));
        ok("缺一个返回 1", ExprInput.unclosedParens("sin(x") == 1,
                String.valueOf(ExprInput.unclosedParens("sin(x")));
        ok("缺两个返回 2", ExprInput.unclosedParens("sin(sqrt(x") == 2,
                String.valueOf(ExprInput.unclosedParens("sin(sqrt(x")));
        ok("多一个返回负", ExprInput.unclosedParens("x)") == -1,
                String.valueOf(ExprInput.unclosedParens("x)")));
        ok("中文括号也算", ExprInput.unclosedParens("sin（x") == 1,
                String.valueOf(ExprInput.unclosedParens("sin（x")));

        ok("补括号", "sin(x)".equals(ExprInput.closeParens("sin(x")),
                ExprInput.closeParens("sin(x"));
        ok("补两个括号", "sin(sqrt(x))".equals(ExprInput.closeParens("sin(sqrt(x")),
                ExprInput.closeParens("sin(sqrt(x"));
        ok("已平衡不改", "sin(x)".equals(ExprInput.closeParens("sin(x)")));
        ok("右括号多时不补", "x)".equals(ExprInput.closeParens("x)")));
        ok("空串不崩", "".equals(ExprInput.closeParens("")));
        ok("null 不崩", "".equals(ExprInput.closeParens(null)));
    }

    // ── ⑤ 模板必须全都画得出来 ───────────────────────────
    static void templateTests() {
        ok("模板与名字数量一致",
           ExprInput.TEMPLATES.length == ExprInput.TEMPLATE_NAMES.length,
           ExprInput.TEMPLATES.length + " vs " + ExprInput.TEMPLATE_NAMES.length);

        int bad = 0;
        for (String t : ExprInput.TEMPLATES) {
            try {
                Expr.Node n = Expr.parse(t);
                // 还要真的能求值（不只是能解析）
                double v = Expr.eval(n, 1.7);
                if (Double.isNaN(v) && t.indexOf("sqrt") < 0) bad++;
            } catch (Exception e) {
                bad++;
                fails.add("模板无法解析：" + t + " → " + e.getMessage());
            }
        }
        ok("16 个模板全部可解析可求值", bad == 0, bad + " 个坏模板");

        // 按键表：两页都要查，别只查当前页
        for (int pg = 0; pg < 2; pg++) {
            String[][] keys = ExprInput.page(pg);
            String name = pg == 0 ? "数字页" : "函数页";
            ok(name + " 键数非空", keys.length > 0, "" + keys.length);
            int emptyKey = 0;
            for (String[] k : keys) {
                if (k.length != 2 || k[0].isEmpty() || k[1].isEmpty()) emptyKey++;
            }
            ok(name + " 无空项", emptyKey == 0, emptyKey + " 个");

            int caretOk = 0, caretTotal = 0;
            for (String[] k : keys) {
                if ("\b".equals(k[1]) || "\f".equals(k[1]) || "\n".equals(k[1])) continue;
                caretTotal++;
                ExprInput.R r = ExprInput.insert("", 0, 0, k[1]);
                if (r.cursor >= 0 && r.cursor <= r.text.length()
                        && r.text.indexOf('@') < 0) caretOk++;
            }
            ok(name + " 每个按键光标落点合法", caretOk == caretTotal,
                    caretOk + "/" + caretTotal);
        }

        // 数字键必须真的是数字 —— 这是这轮改版的核心目的：
        // 打 x^2-4 不该再弹系统输入法
        int digits = 0;
        for (String[] k : ExprInput.page(0)) {
            if (k[0].length() == 1 && k[0].charAt(0) >= '0' && k[0].charAt(0) <= '9') {
                digits++;
                ok("数字键「" + k[0] + "」插入的是它自己",
                   k[0].equals(k[1]), k[1]);
            }
        }
        ok("数字页含 0-9 共 10 个数字键", digits == 10, "实际 " + digits);

        // 只靠数字页就能敲出 x^2-4（不弹输入法）。
        // 按用户真实顺序逐键点：x → x² → − → 4
        String s = "";
        int cur = 0;
        // 按键显示的是 x²（上标），插入的片段才是 x^2。
        // x² 键本身已经带了 x，不用再单点 x。
        String[] seq = {"x²", "−", "4"};
        for (String label : seq) {
            String frag = null;
            for (String[] k : ExprInput.page(0)) {
                if (label.equals(k[0])) { frag = k[1]; break; }
            }
            ok("数字页上有「" + label + "」键", frag != null, "没找到");
            if (frag == null) break;
            ExprInput.R rr = ExprInput.insert(s, cur, cur, frag);
            s = rr.text;
            cur = rr.cursor;
        }
        ok("纯点按能敲出 x^2-4", "x^2-4".equals(s), "实际 " + s);
        ok("x^2-4 可求值", Math.abs(Expr.eval(Expr.parse(s), 3) - 5) < 1e-9, "");

        // 翻页键不改变任何文本
        ExprInput.R sw = ExprInput.insert("x+1", 3, 3, ExprInput.SWITCH);
        ok("翻页键不改文本", "x+1".equals(sw.text) && sw.cursor == 3,
           sw.text + " @" + sw.cursor);
        ExprInput.R sw2 = ExprInput.insert("x+1", 3, 3, ExprInput.BACK_TO_NUM);
        ok("切回数字键不改文本", "x+1".equals(sw2.text) && sw2.cursor == 3,
           sw2.text + " @" + sw2.cursor);

        // x² 键插完应是 x^2，且能被引擎认出
        ExprInput.R r = ExprInput.insert("", 0, 0, "x^2");
        ok("x² 键插入 x^2", "x^2".equals(r.text), r.text);
        ok("x^2 可解析", Expr.eval(Expr.parse(r.text), 3) == 9,
                String.valueOf(Expr.eval(Expr.parse(r.text), 3)));

        // π 键插入后能求值
        r = ExprInput.insert("", 0, 0, "pi");
        ok("π 键可求值", Math.abs(Expr.eval(Expr.parse(r.text), 0)
                - Math.PI) < 1e-9);

        // eˣ 键
        r = ExprInput.insert("", 0, 0, "exp(@)");
        ok("eˣ 键结构正确", "exp()".equals(r.text) && r.cursor == 4, r.text);
    }
}
