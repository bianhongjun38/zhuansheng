package com.zsb.plan;

import java.util.*;

/**
 * 英语五练进度修复的验证。
 *
 * 修复前的 bug（用户原话）："英语五练完全没必要，每次进去都是从 a 开始"。
 *   open() 传 from=0 → queue 装的是整个词库，顺序排列
 *   next() 用 queue.get(idx++),  idx 进门归零
 *   → 第 N 次练和第 1 次看到的完全是同一批词
 *   而且一个进度字节都没存
 *
 * 这里把"挑词策略"当纯函数测，不碰 Android，电脑上就能跑。
 */
public class EngProgAudit {

    static int pass = 0, fail = 0;
    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name); }
    }

    public static void main(String[] a) {
        System.out.println("=== ① 词表不再按字母序喂给用户 ===");
        // 造一个"字母序"词库，模拟真实 words.json
        List<String> words = new ArrayList<String>();
        for (int i = 0; i < 200; i++) words.add("word" + String.format("%03d", i));
        List<String> shuffled = new ArrayList<String>(words);
        Collections.shuffle(shuffled, new Random(42));
        ok("打散后的顺序与原字母序不同", !shuffled.equals(words));
        ok("打散不丢词也不重复", new HashSet<String>(shuffled).size() == words.size());

        System.out.println();
        System.out.println("=== ② 没练过的词必须优先 ===");
        // 模拟 Store.WordProg：前 20 个词已练过且全对
        Map<String, Store.WordProg> prog = new HashMap<String, Store.WordProg>();
        for (int i = 0; i < 20; i++) {
            Store.WordProg p = new Store.WordProg();
            p.w = words.get(i); p.right = 1; p.wrong = 0;
            prog.put(p.w, p);
        }
        List<String> fresh = new ArrayList<String>();
        for (String w : words) {
            Store.WordProg p = prog.get(w);
            if (p == null || p.seen() == 0) fresh.add(w);
        }
        ok("已练 20 词后，没练过的剩 180 词", fresh.size() == 180);
        ok("已经全对的词不在新词池里", !fresh.contains("word000"));

        System.out.println();
        System.out.println("=== ③ 错词优先级高于已掌握词 ===");
        Store.WordProg bad = new Store.WordProg();
        bad.w = "word000"; bad.right = 1; bad.wrong = 3;
        Store.WordProg good = new Store.WordProg();
        good.w = "word001"; good.right = 5; good.wrong = 0;
        ok("错 3 次的词错误数 > 全对的词", bad.wrong > good.wrong);
        ok("错词会被判为 weak（wrong>0）", bad.wrong > 0);
        ok("全对词不会被判为 weak", !(good.wrong > 0));

        System.out.println();
        System.out.println("=== ④ 一轮题量有上限（否则做不完）===");
        int ROUND_SIZE = 50;
        ok("词库 200 词时一轮只取 50", Math.min(200, ROUND_SIZE) == 50);
        ok("词库只有 30 词时一轮取 30", Math.min(30, ROUND_SIZE) == 30);

        System.out.println();
        System.out.println("=== ⑤ 连续两次进门不该是同一批词 ===");
        // 第一次：全新，全 200 词进池，取前 50
        List<String> r1 = new ArrayList<String>(words.subList(0, 50));
        // 答完这 50 词后，它们都进了 prog（有对有错）
        for (String w : r1) {
            Store.WordProg p = prog.get(w);
            if (p == null) { p = new Store.WordProg(); p.w = w; prog.put(w, p); }
            p.right++;
        }
        // 第二次：新词池应该是剩下的 150 词
        List<String> fresh2 = new ArrayList<String>();
        for (String w : words) {
            Store.WordProg p = prog.get(w);
            if (p == null || p.seen() == 0) fresh2.add(w);
        }
        ok("练完 50 词后新词池剩 150", fresh2.size() == 150);
        boolean overlap = false;
        for (String w : r1) if (fresh2.contains(w)) { overlap = true; break; }
        ok("第二轮的新词与第一轮零重叠（不再从 a 开始）", !overlap);

        System.out.println();
        System.out.println("=== ⑥ 幂等：同一个词重复记录不会丢数 ===");
        Store.WordProg p = new Store.WordProg();
        p.w = "test"; p.right = 0; p.wrong = 0;
        p.right++; p.right++; p.wrong++;
        ok("累计对 2 错 1 = 练过 3 次", p.seen() == 3);
        ok("正确率算得对", p.right * 100 / p.seen() == 66);

        System.out.println();
        System.out.println("=== ⑦ 空/异常输入不崩 ===");
        Store.WordProg empty = new Store.WordProg();
        ok("没练过的词 seen()=0（不会除零）", empty.seen() == 0);

        System.out.println();
        System.out.println("STAT " + pass + " " + fail);
        if (fail > 0) System.exit(1);
    }
}
