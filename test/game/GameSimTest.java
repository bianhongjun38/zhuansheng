package com.zsb.plan;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 词汇塔纯逻辑专项测试（JVM 直跑，不碰 Android）。
 *
 * 编译：见 run.bat（只编 GCard/GameData/GQuiz/GBattle/GStore + android 桩 + 本文件）。
 * 运行：java -Dzsb.assets=assets -cp <json jar>;build\gameout com.zsb.plan.GameSimTest
 *
 * 数据源是仓库里的真 vocab.json / cards.json，真值（1654 词 / 324 多义 / 396 组）
 * 已由独立 Python 脚本算过一遍，两侧对得上才说明 org.json 语义和加载逻辑都对。
 */
public class GameSimTest {

    static int pass = 0, fail = 0;
    static final List<String> FAILS = new ArrayList<>();

    static void ok(boolean cond, String name) { ok(cond, name, ""); }

    static void ok(boolean cond, String name, String detail) {
        if (cond) { pass++; return; }
        fail++;
        String line = "✗ " + name + (detail == null || detail.isEmpty() ? "" : " ｜ " + detail);
        FAILS.add(line);
        System.out.println(line);
    }

    // ════════════════════════════════════════════════════

    public static void main(String[] args) throws Exception {
        if (args.length > 0) System.setProperty("zsb.assets", args[0]);

        GameData G = GameData.get(new android.content.Context());

        suiteData(G);
        suiteMap(G);
        suiteDraw(G);
        suiteQuiz(G);
        suiteOverload(G);
        suiteBattleEngine(G);
        suiteBattleSim(G);
        suiteStore(G);
        suiteText(G);

        System.out.println();
        System.out.println("════════════════════════════════════════");
        System.out.println("词汇塔专项测试：通过 " + pass + " 项，失败 " + fail + " 项");
        System.out.println("STAT " + pass + " " + fail);
        if (!FAILS.isEmpty()) {
            System.out.println("失败明细：");
            for (String f : FAILS) System.out.println("  " + f);
        }
        System.exit(fail == 0 ? 0 : 1);
    }

    // ── S0 数据完整性 ────────────────────────────────────
    static void suiteData(GameData G) {
        System.out.println("\n▶ S0 数据完整性");
        ok(G.words.size() == 1654, "词库 1654 词", "实际 " + G.words.size());
        ok(G.adverbs.size() == 25, "副词牌 25 张", "实际 " + G.adverbs.size());
        ok(G.specials.size() == 23, "特殊词 23 个", "实际 " + G.specials.size());
        ok(G.items.size() == 8, "道具 8 个", "实际 " + G.items.size());
        ok(G.enemies.size() == 11, "敌人 11 个", "实际 " + G.enemies.size());
        ok(G.handSize == 5 && G.baseDamage == 12 && G.playerHp == 80,
                "balance 数值载入", "handSize=" + G.handSize + " baseDamage=" + G.baseDamage);

        int multi = 0, grouped = 0;
        for (GCard w : G.words) if (w.canOverload()) multi++;
        grouped = G.wordKeys.size();
        ok(multi == 324, "可过载（≥2 义项）324 词", "实际 " + multi);
        ok(grouped == 652, "属同义组的词形 652", "实际 " + grouped);

        // 组的完整性：每个组 ≥2 词，且组内词形都能查到组键
        int badGroup = 0;
        for (Map.Entry<String, List<String>> e : G.groupWords.entrySet()) {
            if (e.getValue().size() < 2) badGroup++;
            for (String w : e.getValue()) {
                Set<String> ks = G.wordKeys.get(w);
                if (ks == null || !ks.contains(e.getKey())) badGroup++;
            }
        }
        ok(badGroup == 0, "同义组双向一致", badGroup + " 处不一致");

        // 敌人池：mimic 永不进常规池；elite 池只出 elite
        Random r = new Random(7);
        boolean mimicLeaked = false, eliteBad = false, bossTooEarly = false;
        for (int i = 0; i < 4000; i++) {
            GameData.Enemy e = G.pickEnemy(GameData.N_BATTLE, i % 10, r);
            if (e == null) { mimicLeaked = true; break; }
            if (e.tier.equals("mimic")) mimicLeaked = true;
            if (i % 10 < 2 && e.tier.startsWith("boss")) bossTooEarly = true;
            GameData.Enemy el = G.pickEnemy(GameData.N_ELITE, 9, r);
            if (el == null || !el.tier.startsWith("elite")) eliteBad = true;
        }
        ok(!mimicLeaked, "宝箱怪不进常规战斗池");
        ok(!eliteBad, "精英节点只出精英");
        ok(!bossTooEarly, "前 2 层不出 boss");

        // 每张词牌至少有可用中文义（否则出不了题）
        int noCn = 0;
        for (GCard w : G.words) if (GQuiz.firstSensePublic(w.cn).isEmpty()) noCn++;
        System.out.println("  · 无中文义的词牌数：" + noCn + "（出题器会自动跳过）");
    }

    // ── S1 地图 ──────────────────────────────────────────
    static void suiteMap(GameData G) {
        System.out.println("\n▶ S1 地图生成与存档还原");
        Random r = new Random(42);
        int badShape = 0, badEdge = 0, noElitePath = 0, badCamp = 0, adjSame = 0, badRebuild = 0;

        for (int seed = 0; seed < 600; seed++) {
            GameData.RouteMap m = GameData.makeMap(r);
            int rows = m.rows();
            if (rows < 12 || rows > 14) badShape++;
            if (m.row(0).size() != 1 || m.row(rows - 1).size() != 1) badShape++;
            if (m.start().type != GameData.N_BATTLE
                    || m.row(rows - 1).get(0).type != GameData.N_BATTLE) badShape++;

            // 连通性：边合法 + 下一层全覆盖 + 全图从起点可达
            Set<Long> reach = new HashSet<>();
            ArrayDeque<Long> dq = new ArrayDeque<>();
            dq.add(key(0, 0));
            reach.add(key(0, 0));
            while (!dq.isEmpty()) {
                long k = dq.removeFirst();
                int rr = (int) (k >> 20), cc = (int) (k & 0xFFFFF);
                for (int idx : m.row(rr).get(cc).next) {
                    if (rr + 1 >= rows || idx < 0 || idx >= m.row(rr + 1).size()) { badEdge++; continue; }
                    long nk = key(rr + 1, idx);
                    if (reach.add(nk)) dq.add(nk);
                }
            }
            for (int rr = 0; rr < rows; rr++)
                for (int cc = 0; cc < m.row(rr).size(); cc++)
                    if (!reach.contains(key(rr, cc))) badEdge++;
            if (reach.size() != totalNodes(m)) badEdge++;

            // 无精英路径
            boolean eliteFree = false;
            // DFS 所有可能路径太贵，用贪心（与 clearOneElitePath 相同方向）+ 全路径枚举上限保护
            if (existsEliteFreePath(m)) eliteFree = true;
            if (!eliteFree) noElitePath++;

            // 倒数第二层必须有篝火
            boolean camp = false;
            for (GameData.Node n : m.row(rows - 2)) if (n.type == GameData.N_CAMP) camp = true;
            if (!camp) badCamp++;

            // 相邻层同类非战斗
            for (int rr = 0; rr < rows - 1; rr++)
                for (GameData.Node a : m.row(rr))
                    for (int idx : a.next) {
                        GameData.Node b = m.row(rr + 1).get(idx);
                        if (a.type == b.type && a.type != GameData.N_BATTLE) adjSame++;
                    }

            // 存档还原：序列化 → 按确定性规则重建 → 可达集必须一致
            String raw = mapToRawCopy(m);
            GameData.RouteMap reb = rebuildFromRawCopy(raw);
            if (reb == null || reb.rows() != rows) { badRebuild++; continue; }
            Set<Long> reach2 = bfs(reb);
            if (!reach.equals(reach2)) badRebuild++;
        }
        ok(badShape == 0, "地图形状（12-14 层/首末单节点/战斗）", badShape + " 张违规");
        ok(badEdge == 0, "地图连通（边合法/无孤岛/全图可达）", badEdge + " 处违规");
        ok(noElitePath == 0, "永远存在无精英路径", noElitePath + " 张违规");
        ok(badCamp == 0, "倒数第二层必有篝火", badCamp + " 张违规");
        ok(adjSame == 0, "无相邻同类非战斗节点", adjSame + " 处违规");
        ok(badRebuild == 0, "存档序列化→重建后可达集一致", badRebuild + " 张不一致");
    }

    static long key(int r, int c) { return ((long) r << 20) | (long) c; }

    static int totalNodes(GameData.RouteMap m) {
        int n = 0;
        for (List<GameData.Node> l : m.nodes) n += l.size();
        return n;
    }

    /** 枚举全部「无精英路径」是否存在（层数 ≤14、每层 ≤3，路径数上限 3^13≈1.6M，可接受）。 */
    static boolean existsEliteFreePath(GameData.RouteMap m) {
        return eliteFreeDfs(m, 0, 0);
    }

    static boolean eliteFreeDfs(GameData.RouteMap m, int r, int c) {
        if (m.row(r).get(c).type == GameData.N_ELITE) return false;
        if (r == m.rows() - 1) return true;
        for (int idx : m.row(r).get(c).next) {
            if (eliteFreeDfs(m, r + 1, idx)) return true;
        }
        return false;
    }

    static Set<Long> bfs(GameData.RouteMap m) {
        Set<Long> reach = new HashSet<>();
        ArrayDeque<Long> dq = new ArrayDeque<>();
        reach.add(key(0, 0));
        dq.add(key(0, 0));
        while (!dq.isEmpty()) {
            long k = dq.removeFirst();
            int r = (int) (k >> 20), c = (int) (k & 0xFFFFF);
            if (r + 1 >= m.rows()) continue;
            for (int idx : m.row(r).get(c).next) {
                if (idx >= 0 && idx < m.row(r + 1).size() && reach.add(key(r + 1, idx)))
                    dq.add(key(r + 1, idx));
            }
        }
        return reach;
    }

    /** 与 GModeView.mapToRaw 相同的序列化（复制件，防 UI 类牵入 Android）。 */
    static String mapToRawCopy(GameData.RouteMap m) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < m.rows(); r++) {
            if (r > 0) sb.append("|");
            List<GameData.Node> layer = m.row(r);
            for (int c = 0; c < layer.size(); c++) {
                if (c > 0) sb.append(",");
                sb.append(layer.get(c).type);
            }
        }
        return sb.toString();
    }

    /** 与 GModeView.loadOrMakeMap + rebuildEdges 相同的还原逻辑（复制件）。 */
    static GameData.RouteMap rebuildFromRawCopy(String raw) {
        try {
            GameData.RouteMap m = new GameData.RouteMap();
            String[] layerStrs = raw.split("\\|");
            for (int r = 0; r < layerStrs.length; r++) {
                List<GameData.Node> layer = new ArrayList<>();
                if (!layerStrs[r].isEmpty()) {
                    String[] cols = layerStrs[r].split(",");
                    for (int c = 0; c < cols.length; c++)
                        layer.add(new GameData.Node(Integer.parseInt(cols[c]), r, c));
                }
                m.nodes.add(layer);
            }
            for (int r = 0; r < m.rows() - 1; r++) {
                List<GameData.Node> cur = m.row(r);
                List<GameData.Node> nxt = m.row(r + 1);
                int nc = nxt.size();
                for (GameData.Node n : cur) n.next.clear();
                for (int i = 0; i < cur.size(); i++) {
                    int target = cur.size() == 1 ? 0
                            : (int) Math.round((double) i * (nc - 1) / Math.max(1, cur.size() - 1));
                    target = Math.max(0, Math.min(nc - 1, target));
                    if (!cur.get(i).next.contains(target)) cur.get(i).next.add(target);
                }
                for (int j = 0; j < nc; j++) {
                    boolean has = false;
                    for (GameData.Node n : cur) if (n.next.contains(j)) has = true;
                    if (has) continue;
                    int src = cur.size() == 1 ? 0
                            : Math.max(0, Math.min(cur.size() - 1,
                                (int) Math.round((double) j * (cur.size() - 1) / Math.max(1, nc - 1))));
                    cur.get(src).next.add(j);
                }
                for (GameData.Node n : cur) java.util.Collections.sort(n.next);
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // ── S2 抽牌 ──────────────────────────────────────────
    static void suiteDraw(GameData G) {
        System.out.println("\n▶ S2 抽牌与同义保底");
        Random r = new Random(99);

        int dup = 0, wrongSize = 0, synHands = 0, leak = 0;
        int N = 20000;
        for (int i = 0; i < N; i++) {
            List<GCard> hand = G.drawCardsWithSyn(5, r, null, 0.55, null);
            if (hand.size() != 5) wrongSize++;
            Set<String> ids = new HashSet<>();
            for (GCard c : hand) if (!ids.add(c.id)) dup++;
            boolean pair = false;
            for (int a = 0; a < hand.size() && !pair; a++)
                for (int b2 = a + 1; b2 < hand.size(); b2++)
                    if (G.synonym(hand.get(a).id, hand.get(b2).id)) { pair = true; break; }
            if (pair) synHands++;
        }
        ok(dup == 0, "抽牌无重复", dup + " 次重复");
        ok(wrongSize == 0, "手牌张数恒为 5", wrongSize + " 次不足");
        double rate = synHands / (double) N;
        System.out.println("  · 同义保底后手牌同义对率：" + String.format("%.2f%%", rate * 100)
                + "（纯随机基线 0.39%）");
        ok(rate > 0.05, "同义保底机制真实生效（>5%）", String.format("%.2f%%", rate * 100));

        // exclude 必须被尊重（保牌锁卡）
        for (int i = 0; i < 2000; i++) {
            Set<String> ex = new HashSet<>();
            for (int k = 0; k < 3; k++) ex.add(G.words.get(r.nextInt(G.words.size())).id);
            List<GCard> hand = G.drawCardsWithSyn(5, r, null, 0.55, ex);
            for (GCard c : hand) if (ex.contains(c.id)) leak++;
        }
        ok(leak == 0, "exclude 集合绝不回抽", leak + " 张泄漏");

        // focus 焦点：给一个有同义词的焦点，定向补牌应是它的同义词
        GCard focus = null;
        for (GCard w : G.words) if (G.synonymsOf(w.id).size() >= 2) { focus = w; break; }
        int hit = 0;
        for (int i = 0; i < 2000; i++) {
            List<GCard> hand = G.drawCardsWithSyn(5, r, focus, 1.0, null);
            for (GCard c : hand) if (!c.id.equals(focus.id) && G.synonym(c.id, focus.id)) { hit++; break; }
        }
        System.out.println("  · seedP=1.0 时焦点同义词入手率：" + String.format("%.1f%%", hit / 20.0));
        ok(hit > 1600, "焦点定向补牌（seedP=1.0 应 ≥80%）", hit + "/2000");
    }

    // ── S3 出题四型 ──────────────────────────────────────
    static void suiteQuiz(GameData G) {
        System.out.println("\n▶ S3 出题器（题型 0/1/2/3）");
        Random r = new Random(1234);
        GQuiz quiz = new GQuiz(G, r);
        int N = 30000;

        int ambig02 = 0, mode02Count = 0, mode1 = 0, mode1Fallback = 0, mode3 = 0;
        int mode3AmbigGroup = 0, mode3AmbigSense = 0, mode3BadPos = 0, mode3InHand = 0;
        int mode3NoOptions = 0;
        List<String> ambigSamples = new ArrayList<>();
        List<String> m3Samples = new ArrayList<>();

        for (int i = 0; i < N; i++) {
            List<GCard> hand = G.drawCardsWithSyn(5, r, null, 0.55, null);
            int mode = quiz.randomMode();
            GQuiz.Q q = quiz.make(hand, mode);
            if (q == null) { mode1Fallback++; continue; }

            if (q.mode == 0 || q.mode == 2) {
                mode02Count++;
                String prompt = q.prompt;
                ok(prompt != null && !prompt.isEmpty(), "mode0/2 题面非空");
                // 歧义检测：手牌里另一张词牌的 firstSense 与题面「同一个意思」
                for (GCard c2 : hand) {
                    if (c2 == q.card || !c2.isWord()) continue;
                    if (GQuiz.sameSensePublic(GQuiz.firstSensePublic(c2.cn), prompt)) {
                        ambig02++;
                        if (ambigSamples.size() < 8)
                            ambigSamples.add(q.card.en + " / " + c2.en + " ←「" + prompt + "」");
                        break;
                    }
                }
            } else if (q.mode == 1) {
                mode1++;
                ok(q.prompt.contains("＿"), "mode1 例句挖空", q.prompt);
            } else if (q.mode == 3) {
                mode3++;
                if (q.options == null || q.options.length < 2) { mode3NoOptions++; continue; }
                ok(q.answer >= 0 && q.answer < q.options.length, "mode3 答案下标有效");
                ok(q.options[q.answer].equalsIgnoreCase(q.card.en), "mode3 答案=目标词");
                // 干扰项不得是手牌里的牌（否则两个都对）
                for (String o : q.options) {
                    if (o.equalsIgnoreCase(q.card.en)) continue;
                    boolean inHand = false;
                    for (GCard h : hand) if (h.en.equalsIgnoreCase(o)) inHand = true;
                    if (inHand) mode3InHand++;
                    // 干扰项与正确词同义/同义写法 → 冤枉错
                    GCard oc = G.byId(o.toLowerCase());
                    if (oc != null) {
                        if (!oc.pos.equals(q.card.pos)) mode3BadPos++;
                        if (G.synonym(oc.id, q.card.id)) {
                            mode3AmbigGroup++;
                            if (m3Samples.size() < 8)
                                m3Samples.add(q.card.en + "（" + GQuiz.firstSensePublic(q.card.cn)
                                        + "） ← 干扰项 " + oc.en + "（同义组）");
                        } else if (GQuiz.sameSensePublic(oc.cn, q.card.cn)) {
                            mode3AmbigSense++;
                            if (m3Samples.size() < 8)
                                m3Samples.add(q.card.en + "（" + GQuiz.firstSensePublic(q.card.cn)
                                        + "） ← 干扰项 " + oc.en + "（释义互含）");
                        }
                    }
                }
            }
        }
        System.out.println("  · mode0/2 释义题 " + mode02Count + " 道，题面在手上存在「同义歧义」"
                + ambig02 + " 道（" + String.format("%.3f%%", ambig02 * 100.0 / Math.max(1, mode02Count)) + "）");
        for (String s : ambigSamples) System.out.println("      歧义样本：" + s);
        System.out.println("  · mode1 例句题 " + mode1 + " 道（退化 " + mode1Fallback + " 次）");
        System.out.println("  · mode3 辨析题 " + mode3 + " 道：同义组干扰 " + mode3AmbigGroup
                + "、释义互含干扰 " + mode3AmbigSense + "、词性不符 " + mode3BadPos
                + "、干扰项∈手牌 " + mode3InHand);
        for (String s : m3Samples) System.out.println("      冤枉样本：" + s);

        ok(mode02Count + mode1 + mode3 >= N * 0.9, "出题成功率 ≥90%");
        ok(mode3InHand == 0, "mode3 干扰项绝不与手牌牌面相同");
        ok(mode3BadPos == 0, "mode3 干扰项词性与答案一致");
        // 这两条是「已知设计缺口」的量化：不为 0 就是冤枉题的真实来源
        System.out.println("  ⚠ mode3 同义干扰（出题侧未过滤，判题侧无容差）："
                + (mode3AmbigGroup + mode3AmbigSense) + " 道");
    }

    // ── S4 僻义过载 ──────────────────────────────────────
    static void suiteOverload(GameData G) {
        System.out.println("\n▶ S4 僻义过载（mode 4：真词库计时 + 缩减词库跑不变量）");
        Random r = new Random(555);
        GQuiz quiz = new GQuiz(G, r);

        List<GCard> overloadable = new ArrayList<>();
        for (GCard c : G.words) if (c.canOverload()) overloadable.add(c);

        // ── A. 真词库单次计时（性能证据，跑 4 张不同卡）──
        double totalMs = 0;
        int timed = 0;
        for (int i = 0; i < overloadable.size() && timed < 4; i += overloadable.size() / 4) {
            GCard c = overloadable.get(i);
            long t = System.nanoTime();
            GQuiz.Q q = quiz.makeOverload(c);
            long ms = (System.nanoTime() - t) / 1000000L;
            totalMs += ms;
            timed++;
            System.out.println("  · makeOverload(" + c.en + ", senses=" + c.senseCount()
                    + ") = " + ms + " ms, 出题 " + (q == null ? "失败" : "成功"));
        }
        double avgMs = totalMs / Math.max(1, timed);
        System.out.println("  · 全词库单次平均 " + String.format("%.0f", avgMs)
                + " ms（桌面 JVM；真机 UI 线程按 3-10× 估算）");
        ok(avgMs < 3000, "单次过载出题桌面均值 <3s（真机卡顿红线）",
                String.format("%.0f ms", avgMs));

        // 对照组：自身义项就能凑满 4 个选项的卡不走全词库扫描（step ② 跳过），
        // 应当毫秒级 —— 以此证明热点就是 step ② 的全词库 O(N²) 释义判重
        GCard fastCard = null;
        for (GCard c : overloadable) {
            int parts = 0;
            for (int i = 0; i < c.senseCount(); i++)
                parts += c.senseText(i).split("[;；]").length;
            if (parts >= 5) { fastCard = c; break; }
        }
        if (fastCard != null) {
            long t = System.nanoTime();
            GQuiz.Q q = quiz.makeOverload(fastCard);
            long ms = (System.nanoTime() - t) / 1000000L;
            System.out.println("  · 对照 makeOverload(" + fastCard.en + ", 自带 "
                    + fastCard.senseCount() + " 义项) = " + ms + " ms, 出题 "
                    + (q == null ? "失败" : "成功"));
            ok(ms < 200, "自带义项充足的卡出题 <200ms（证明热点在补干扰项扫描）",
                    ms + " ms");
        }

        // ── B. 缩减词库跑正确性不变量（词库大小不影响过滤逻辑本身）──
        List<GCard> backup = new ArrayList<>(G.words);
        List<GCard> keep = new ArrayList<>();
        Random pick = new Random(5);
        for (GCard c : backup) if (pick.nextDouble() < 0.1) keep.add(c);
        // 保证样例里有足够多可过载卡
        for (int i = 0; keep.size() < 80 && i < overloadable.size(); i += 3) {
            if (!keep.contains(overloadable.get(i))) keep.add(overloadable.get(i));
        }
        G.words.clear();
        G.words.addAll(keep);

        int produced = 0, skipped = 0, badOptCount = 0, badAnswerIdx = 0;
        int dupSense = 0, commonLeak = 0, badPrompt = 0, badSenseIdx = 0;
        for (GCard c : overloadable) {
            for (int rep = 0; rep < 12; rep++) {
                GQuiz.Q q = quiz.makeOverload(c);
                if (q == null) { skipped++; continue; }
                produced++;
                if (q.options == null || q.options.length < 3 || q.options.length > 4) { badOptCount++; continue; }
                if (q.answer < 0 || q.answer >= q.options.length) { badAnswerIdx++; continue; }
                if (q.senseIndex < 1 || q.senseIndex >= c.senseCount()) badSenseIdx++;
                if (!q.prompt.contains("常见义")) badPrompt++;
                String right = q.options[q.answer];
                int same = 0;
                for (String o : q.options) if (GQuiz.sameSensePublic(o, right)) same++;
                if (same != 1) dupSense++;
                String s0 = c.senseText(0);
                for (String part : s0.split("[;；]")) {
                    if (!part.trim().isEmpty() && GQuiz.sameSensePublic(part.trim(), right)) commonLeak++;
                }
            }
        }
        System.out.println("  · 缩减词库（" + G.words.size() + " 词）× 全部 " + overloadable.size()
                + " 张可过载卡 × 12 = " + (overloadable.size() * 12)
                + " 次：出题 " + produced + "，跳过 " + skipped);

        // 还原词库，后续套件用全量数据
        G.words.clear();
        G.words.addAll(backup);

        ok(badOptCount == 0, "过载选项数 3-4", badOptCount + " 次违规");
        ok(badAnswerIdx == 0, "过载答案下标有效", badAnswerIdx + " 次违规");
        ok(dupSense == 0, "过载选项无「两个都对」（sameSense 恰好 1 个）", dupSense + " 次违规");
        ok(commonLeak == 0, "过载正确答案绝不与常见义同义", commonLeak + " 次泄漏");
        ok(badPrompt == 0, "过载题面带常见义", badPrompt + " 次缺失");
        ok(badSenseIdx == 0, "过载只考第 2+ 义项", badSenseIdx + " 次违规");
        ok(produced > (overloadable.size() * 12) / 2, "过载出题成功率 >50%",
                produced + "/" + overloadable.size() * 12);

        // sameSense 语义抽测
        ok(GQuiz.sameSensePublic("收费", "收费;控告"), "sameSense 精确相等");
        ok(GQuiz.sameSensePublic("信用", "信用贷款"), "sameSense 互包含判同");
        ok(!GQuiz.sameSensePublic("危险", "安全"), "sameSense 反义不判同");
    }

    // ── S5 战斗引擎（确定性数值）─────────────────────────
    static void suiteBattleEngine(GameData G) {
        System.out.println("\n▶ S5 战斗引擎数值（确定性）");
        Random r = new Random(2026);

        // isReWord：动态找 re- 词与一个非 re- 词
        {
            GCard reW = null, notRe = null;
            for (GCard w : G.words) {
                if (reW == null && w.en.length() > 3 && w.en.toLowerCase().startsWith("re")) reW = w;
                if (notRe == null && w.en.toLowerCase().startsWith("ab")) notRe = w;
                if (reW != null && notRe != null) break;
            }
            ok(reW != null && GBattle.isReWord(reW), "isReWord 认 re- 词",
                    reW == null ? "词库无 re- 词" : reW.en);
            ok(notRe == null || !GBattle.isReWord(notRe), "isReWord 不误判非 re- 词",
                    notRe == null ? "" : notRe.en);
        }

        // 连锁与共鸣的确定序列：找一组 ≥3 词、无 re- 开头的同义组
        String[] trio = null;
        for (Map.Entry<String, List<String>> e : G.groupWords.entrySet()) {
            List<String> ws = e.getValue();
            if (ws.size() >= 3) {
                boolean re = false;
                for (String w : ws) if (w.startsWith("re")) re = true;
                if (!re) { trio = ws.toArray(new String[0]); break; }
            }
        }
        ok(trio != null, "找到 ≥3 词且无 re- 的同义组");
        if (trio != null) {
            GameData.Enemy boss = G.enemyById("devourer");   // hp 130，扛得住 3 连
            GBattle b = new GBattle(G, boss, new Random(1), null);
            // 直接把同义组建进手牌（手牌是公开列表，引擎允许注入以做确定性验证）
            b.hand.clear();
            for (String w : trio) b.hand.add(G.byId(w));
            b.chooseAttack();
            int d1 = b.playCard(G.byId(trio[0]));
            ok(d1 == 12, "首张无加成 = baseDamage 12", "实际 " + d1);
            int d2 = b.playCard(G.byId(trio[1]));
            ok(d2 == Math.round(12 * 1.8), "同义共鸣 = ×1.8 → 22", "实际 " + d2);
            ok(b.lastWasResonance, "共鸣标记置位");
            int d3 = b.playCard(G.byId(trio[2]));
            int expect3 = (int) Math.round(12 * Math.pow(1.5, 2));
            ok(d3 == expect3, "共鸣耗尽后本回合连锁 ×2.25 → " + expect3, "实际 " + d3);
            // 槽被触发牌自身回填（playCard 末尾 resonance==null 时重新入槽）
            ok(b.resonance == G.byId(trio[1]), "共鸣触发后槽由打出牌回填",
                    "实际 " + (b.resonance == null ? "空" : b.resonance.en));
        }

        // 能量与保牌
        {
            GameData.Enemy e = G.enemyById("mists");
            GBattle b = new GBattle(G, e, new Random(2), null);
            ok(b.energy == 3 && b.energyMax == 3, "Lv1 能量上限 3");
            ok(b.playerMaxHp == 80 && b.playerHp == 80, "无存档满血 80");
            b.chooseAttack();
            ok(b.chooseKeep(), "3 能量可保牌");
            ok(b.energy == 1, "保牌扣 2 能量", "实际 " + b.energy);
            GCard k = b.hand.get(0);
            ok(b.keep(k), "保牌移出一张");
            ok(!b.hand.contains(k), "保的牌离开手牌");
            b.endKeep();
            ok(b.kept.size() == 1, "保牌区 1 张");
            ok(b.hand.size() == G.handSize - 1, "补牌 = 上限 - 已保张数",
                    "实际 " + b.hand.size());
            ok(!b.hand.contains(k), "补牌不把已保的牌抽回来");
        }

        // 直接攻击
        {
            GBattle b = new GBattle(G, G.enemyById("mists"), new Random(3), null);
            int hp0 = b.enemyHp;
            int dmg = b.attackDirect();
            ok(dmg == Math.max(4, G.baseDamage / 3), "直接攻击 = max(4, base/3)", "实际 " + dmg);
            ok(b.enemyHp == hp0 - dmg, "直接攻击扣敌血");
        }

        // 答错惩罚
        {
            GBattle b = new GBattle(G, G.enemyById("mists"), new Random(4), null);
            b.chooseAttack();
            int charge0 = b.enemyCharge;
            b.onWrong(b.hand.get(0));
            ok(b.enemyCharge == charge0 + G.wrongChargeUp, "答错充能 +2", "实际 " + b.enemyCharge);
            ok(b.wrongDoubled, "答错置伤害翻倍标记");
        }

        // 过载伤害
        {
            GBattle b = new GBattle(G, G.enemyById("devourer"), new Random(5), null);
            b.chooseAttack();
            GCard c = b.hand.get(0);
            int hp0 = b.enemyHp;
            int dmg = b.playCardOverload(c, 0);
            ok(dmg == (int) Math.round(12 * 2.2), "过载 = ×2.2 → 26", "实际 " + dmg);
            ok(b.enemyHp == hp0 - dmg, "过载直接扣敌血");
            ok(!b.hand.contains(c), "过载用掉的牌进弃牌堆");
        }

        // 等级封顶：floorIndex 9 → ×1.72，floorIndex 20 仍 ×1.72
        {
            android.content.SharedPreferences.resetAll();
            GStore st = new GStore(new android.content.Context());
            st.setMap("0|0|0|0|0|0|0|0|0|0");   // 10 行
            st.setPos(9, 0);
            st.setLayer(0);
            GBattle b = new GBattle(G, G.enemyById("devourer"), new Random(6), st);
            ok(b.level() == 10, "floorIndex 9 → Lv.10", "实际 " + b.level());
            ok(Math.abs(b.levelDamageMul() - 1.72) < 1e-9, "Lv.10 伤害 ×1.72",
                    "实际 " + b.levelDamageMul());
            ok(b.energyMax == 5, "Lv.10 能量上限 5", "实际 " + b.energyMax);
            b.chooseAttack();
            GCard c = b.hand.get(0);
            int d = b.playCard(c);
            ok(d == (int) Math.round(12 * 1.72), "首张伤害 = 12×1.72 → 21", "实际 " + d);

            st.setLayer(2);   // floorIndex = 2*10+9 = 29 → 仍封顶
            GBattle b2 = new GBattle(G, G.enemyById("devourer"), new Random(7), st);
            ok(Math.abs(b2.levelDamageMul() - 1.72) < 1e-9, "Lv.29 伤害仍封顶 ×1.72",
                    "实际 " + b2.levelDamageMul());
        }

        // 宝箱怪惩罚
        {
            android.content.SharedPreferences.resetAll();
            GStore st = new GStore(new android.content.Context());
            GBattle b = new GBattle(G, G.enemyById("mimic"), new Random(8), st);
            b.playerHp = 70;
            b.onMimicDefeat();
            ok(b.playerMaxHp == 40, "上限 80 → 减半 40", "实际 " + b.playerMaxHp);
            ok(b.playerHp == 40, "当前血压到新上限", "实际 " + b.playerHp);
            ok(st.curMaxHp() == 40 && st.curHp() == 40, "惩罚写入存档");
            b.onMimicDefeat();
            ok(b.playerMaxHp == 20, "再次战败继续减半", "实际 " + b.playerMaxHp);
        }

        // 三连打断（注意：第 3 拍答对时能量打空，引擎会在 playCard 内部自动
        // 结束回合并重置本回合标记 —— 所以只能用「敌人没打你」来验证兑现）
        {
            GBattle b = new GBattle(G, G.enemyById("devourer"), new Random(9), null);
            b.chooseAttack();
            List<GCard> hand = new ArrayList<>(b.hand);
            b.playCard(hand.get(0));
            b.playCard(hand.get(1));
            int hpBefore3 = b.playerHp;
            int turnBefore = b.turn;
            b.playCard(hand.get(2));
            ok(b.turn == turnBefore + 1, "第 3 拍后引擎已自动进入下一回合",
                    "turn=" + b.turn);
            ok(b.playerHp == hpBefore3, "打断兑现：敌人本次未攻击",
                    "掉了 " + (hpBefore3 - b.playerHp));
        }

        // 金币区间
        {
            GBattle b = new GBattle(G, G.enemyById("colossus"), new Random(10), null);
            ok(b.goldReward() == G.eliteGold, "精英金币固定 50", "实际 " + b.goldReward());
            GBattle b2 = new GBattle(G, G.enemyById("mists"), new Random(11), null);
            for (int i = 0; i < 200; i++) {
                int g = b2.goldReward();
                ok(g >= G.battleGoldMin && g <= G.battleGoldMax,
                        "普通金币在 [" + G.battleGoldMin + "," + G.battleGoldMax + "]",
                        "实际 " + g);
                if (g < G.battleGoldMin || g > G.battleGoldMax) break;
            }
        }
    }

    // ── S6 战斗模拟（随机策略 + 全程不变量）─────────────
    static void suiteBattleSim(GameData G) {
        System.out.println("\n▶ S6 战斗模拟（2000 场随机策略）");
        Random r = new Random(31415);
        GQuiz quiz = new GQuiz(G, r);
        final double KNOW = 0.75;

        int wins = 0, losses = 0, turnMax = 0;
        long turnsTotal = 0, plays = 0, reWords = 0, reforges = 0;
        int resonance = 0, interrupts = 0;
        int badHp = 0, badEnergy = 0, badDup = 0, badDmg = 0;
        int goldBad = 0;

        for (int sim = 0; sim < 2000; sim++) {
            GameData.Enemy e = G.pickEnemy(sim % 5 == 0 ? GameData.N_ELITE : GameData.N_BATTLE,
                    r.nextInt(12), r);
            if (e == null) continue;
            GBattle b = new GBattle(G, e, r, null);
            int guard = 0;
            while (!b.over() && guard++ < 400) {
                // ── 不变量（分类计数）──
                if (b.enemyHp < 0 || b.playerHp < 0) badHp++;
                if (b.energy < 0 || b.energy > b.energyMax) badEnergy++;
                Set<String> ids = new HashSet<>();
                for (GCard c : b.hand) if (!ids.add(c.id)) badDup++;
                for (GCard c : b.kept) if (!ids.add(c.id)) badDup++;

                switch (b.phase) {
                    case GBattle.PHASE_CHOOSE:
                        b.chooseAttack();
                        break;
                    case GBattle.PHASE_PLAY: {
                        GQuiz.Q q = quiz.make(b.hand, quiz.randomMode());
                        if (q == null) {
                            // 手上只剩副词：10% 用副词，否则结束回合
                            boolean used = false;
                            for (GCard c : b.hand) {
                                if (c.isAdverb() && r.nextDouble() < 0.3) {
                                    b.hand.remove(c);
                                    b.discard.add(c);
                                    b.energy--;
                                    used = true;
                                    break;
                                }
                            }
                            if (b.energy <= 0 || b.hand.isEmpty()) b.endTurn(false);
                            else if (!used) b.endTurn(false);
                            break;
                        }
                        boolean knows = r.nextDouble() < KNOW;
                        if (q.options != null) {
                            int pick;
                            if (knows) pick = q.answer;
                            else {
                                pick = r.nextInt(q.options.length);
                                if (pick == q.answer && q.options.length > 1)
                                    pick = (pick + 1) % q.options.length;
                            }
                            boolean right = pick == q.answer;
                            // 与 GBattleView.answer 一致：只有 mode 4 有同义容差
                            if (!right && q.mode == 4) {
                                right = GQuiz.sameSensePublic(q.options[pick], q.options[q.answer]);
                            }
                            if (right) {
                                int hp0 = b.enemyHp;
                                int dmg;
                                if (q.mode == 4) dmg = b.playCardOverload(q.card, q.senseIndex);
                                else dmg = b.playCard(q.card);
                                // 致命一击会被引擎截到 0，用 max(0, hp0-dmg) 对账
                                if (b.enemyHp != Math.max(0, hp0 - dmg)) badDmg++;
                                plays++;
                                if (b.lastWasResonance) resonance++;
                                if (b.lastMsg != null && b.lastMsg.contains("三连打断")) interrupts++;
                                if (GBattle.isReWord(q.card)) {
                                    // 只统计「本回合还在继续」的打出：回合末自动结算
                                    // 会清手牌，重铸的牌本来就会被弃，不算机制失败
                                    boolean turnContinued = b.phase == GBattle.PHASE_PLAY
                                            && !b.hand.isEmpty() && b.energy > 0;
                                    if (turnContinued) {
                                        reWords++;
                                        if (b.hand.contains(q.card)) reforges++;
                                    }
                                }
                            } else {
                                if (q.mode == 4) b.onOverloadWrong(q.card);
                                else b.onWrong(q.card);
                            }
                        } else {
                            GCard target = q.card;
                            GCard pick = target;
                            if (!knows) {
                                List<GCard> others = new ArrayList<>();
                                for (GCard c : b.hand) if (c != target && c.isWord()) others.add(c);
                                if (!others.isEmpty()) pick = others.get(r.nextInt(others.size()));
                            }
                            if (pick == target) {
                                int hp0 = b.enemyHp;
                                int dmg = b.playCard(target);
                                if (b.enemyHp != Math.max(0, hp0 - dmg)) badDmg++;
                                plays++;
                                if (b.lastWasResonance) resonance++;
                                if (b.lastMsg != null && b.lastMsg.contains("三连打断")) interrupts++;
                                if (GBattle.isReWord(target)) {
                                    boolean turnContinued = b.phase == GBattle.PHASE_PLAY
                                            && !b.hand.isEmpty() && b.energy > 0;
                                    if (turnContinued) {
                                        reWords++;
                                        if (b.hand.contains(target)) reforges++;
                                    }
                                }
                            } else {
                                b.onWrong(pick);
                            }
                        }
                        break;
                    }
                    case GBattle.PHASE_KEEP: {
                        int k = 0;
                        while (b.phase == GBattle.PHASE_KEEP && k < G.keepLimit && !b.hand.isEmpty()) {
                            b.keep(b.hand.get(r.nextInt(b.hand.size())));
                            k++;
                        }
                        if (b.phase == GBattle.PHASE_KEEP) b.endKeep();
                        break;
                    }
                    default:
                        b.endTurn(false);
                }
            }
            ok(guard < 400 || b.over(), "战斗 400 步内结束", e.name);
            if (b.won()) {
                wins++;
                int g = b.goldReward();
                boolean elite = e.tier.startsWith("elite");
                if (elite ? (g != G.eliteGold)
                          : (g < G.battleGoldMin || g > G.battleGoldMax)) goldBad++;
            } else losses++;
            turnsTotal += b.turn;
            if (b.turn > turnMax) turnMax = b.turn;
        }
        System.out.println("  · 胜 " + wins + " / 负 " + losses
                + "（KNOW=" + KNOW + "）　平均回合 " + String.format("%.1f", turnsTotal / 2000.0)
                + "　最长 " + turnMax);
        System.out.println("  · 出牌 " + plays + "　共鸣 " + resonance + "　打断 " + interrupts
                + "　re- 词 " + reWords + " 中重铸 " + reforges
                + "（" + (reWords == 0 ? 0 : String.format("%.1f%%", reforges * 100.0 / reWords)) + "）");
        int badInv = badHp + badEnergy + badDup + badDmg;
        System.out.println("  · 违规分布：血量 " + badHp + "　能量 " + badEnergy
                + "　手牌重复 " + badDup + "　伤害对账 " + badDmg);
        ok(badInv == 0, "全程不变量（血量/能量/手牌去重/伤害对账）",
                "血量 " + badHp + " 能量 " + badEnergy + " 重复 " + badDup + " 伤害 " + badDmg);
        ok(goldBad == 0, "胜利金币区间正确", goldBad + " 次越界");
        double rr = reWords == 0 ? 0 : reforges / (double) reWords;
        ok(rr > 0.35 && rr < 0.65, "重铸概率 ≈50%", String.format("%.1f%%", rr * 100));
        ok(resonance > 200, "共鸣在实战中频繁触发", resonance + " 次");
        ok(interrupts > 100, "三连打断在实战中可达成", interrupts + " 次");
    }

    // ── S7 存档 ──────────────────────────────────────────
    static void suiteStore(GameData G) {
        System.out.println("\n▶ S7 GStore 存档");
        android.content.SharedPreferences.resetAll();
        GStore st = new GStore(new android.content.Context());

        // floorIndex 口径
        st.setMap("0,1|2|0,1,2|0");   // 4 行
        st.setLayer(2);
        st.setPos(1, 0);
        ok(st.floorIndex() == 2 * 4 + 1, "floorIndex = layer×rows+curRow",
                "实际 " + st.floorIndex());
        ok(st.floorNo() == st.floorIndex() + 1, "floorNo 从 1 起");

        // 战报幂等
        GStore.Battle b1 = new GStore.Battle();
        b1.day = "2026-10-29"; b1.floor = 3; b1.node = 0; b1.win = true;
        b1.gold = 20; b1.right = 7; b1.wrong = 2; b1.ms = 60000; b1.started = 1000;
        b1.rev = "rev-a";
        st.addBattle(b1);
        st.addBattle(b1);   // 同 rev 重复
        ok(st.pendingBattles().length() == 1, "同 rev 战报只记一次",
                "实际 " + st.pendingBattles().length());
        ok(st.dayStat("2026-10-29").optInt("battles") == 1, "同 rev 不重复计入日聚合");

        // 待上报队列上限 200（丢最老）
        for (int i = 0; i < 250; i++) {
            GStore.Battle b = new GStore.Battle();
            b.day = "2026-10-29"; b.win = true; b.gold = 1; b.rev = "r" + i;
            st.addBattle(b);
        }
        ok(st.pendingBattles().length() <= 200, "待上报队列 ≤200",
                "实际 " + st.pendingBattles().length());

        // 日聚合只留 14 天
        for (int d = 1; d <= 20; d++) {
            GStore.Battle b = new GStore.Battle();
            b.day = String.format("2026-09-%02d", d); b.win = true; b.rev = "d" + d;
            st.addBattle(b);
        }
        // 日聚合只留 14 天：本轮共写了 21 个不同的天（10-29 + 09-01..20），
        // 窗口按 key 排序丢最老，10-29 最新必留，09-01..07 被裁 → 恰好 14 天
        String[] knownDays = new String[21];
        knownDays[0] = "2026-10-29";
        for (int d = 1; d <= 20; d++) knownDays[d] = String.format("2026-09-%02d", d);
        int days = 0;
        for (String day : knownDays) {
            if (st.dayStat(day).length() > 0) days++;
        }
        ok(days == 14, "日聚合只留 14 天", "实际 " + days + " 天");
        ok(st.dayStat("2026-10-29").length() > 0, "最新一天必在窗口内");
        ok(st.dayStat("2026-09-01").length() == 0, "最老一天已被裁剪");

        // 道具去重删除
        st.addItem("anyhow"); st.addItem("anyhow"); st.addItem("anyway");
        ok(st.itemCount() == 2, "道具去重添加");
        st.removeItem("anyhow");
        ok(st.itemCount() == 1 && st.hasItem("anyway"), "removeItem 删第一个匹配");
        st.removeItem("anyway");
        st.removeItem("anyway");   // 空列表再删不崩
        ok(st.itemCount() == 0, "道具清空");

        // resetRun：清了什么、留了什么
        st.setMap("0|0|0");
        st.setPos(2, 1);
        st.setBattleNode(2, 1);
        st.addGold(55);
        st.addSpecial("crucial");
        st.addItem("anyhow");
        st.markRight(); st.markWrong();
        st.resetRun();
        ok(st.gold() == 0, "resetRun 清金币");
        ok(!st.hasSpecial("crucial"), "resetRun 清特殊词");
        ok(!st.hasItem("anyhow"), "resetRun 清道具");
        ok(st.battleNode().isEmpty(), "resetRun 清进行中战斗");
        ok(st.curHp() == -1 && st.curMaxHp() == 0, "resetRun 清血量（回到满血口径）");
        ok(st.right() == 1 && st.wrong() == 1, "resetRun 保留累计答对/答错（设计如此）");
        ok(!st.mapRaw().isEmpty(), "⚠ resetRun 不清地图（战败重开沿用旧地图的根因）",
                "mapRaw=" + st.mapRaw());

        // 验证战败重开的完整链路：resetRun 后 advanceStep 落点
        // （GModeView.advanceStep 的等价逻辑）
        String raw = st.mapRaw();
        int rows = raw.isEmpty() ? 6 : raw.split("\\|", -1).length;
        int curRow = 0;   // resetRun 移除了 curRow
        int nextRow = curRow + 1;
        ok(nextRow < rows, "战败重开后 advanceStep 落在旧地图第 2 行（而非新地图）",
                "rows=" + rows + " nextRow=" + nextRow);
    }

    // ── S8 文本处理 ──────────────────────────────────────
    static void suiteText(GameData G) throws Exception {
        System.out.println("\n▶ S8 卡面文本与例句挖空");
        Random r = new Random(777);

        // firstSentence 基准
        GCard t = new GCard("t", "test", "义", "n", GCard.KIND_WORD, null, "ex",
                "我需要一个解释。 I have a dog. 我有一只狗。");
        ok(t.exCnFirst.equals("我需要一个解释"), "firstSentence 切第一句",
                "实际「" + t.exCnFirst + "」");
        GCard t2 = new GCard("t2", "test", "义", "n", GCard.KIND_WORD, null, "ex",
                "生活是一串串的快乐时光， He started a fast food chain");
        System.out.println("  · 混排行「" + t2.exCnFirst + "」");
        ok(t2.exCnFirst.isEmpty() || !hasTailEnglish(t2.exCnFirst), "混排行不残留英文尾巴");

        // 全量数据：exCnFirst 非空则 ≥6 字
        int withFirst = 0, tooShort = 0;
        for (GCard w : G.words) {
            if (w.exCnFirst.isEmpty()) continue;
            withFirst++;
            if (w.exCnFirst.length() < 6) tooShort++;
        }
        System.out.println("  · " + withFirst + " 词有可念译文，过短 " + tooShort);
        ok(tooShort == 0, "译文首句 ≥6 字（不足则回退空串）", tooShort + " 条违规");

        // blankOut 行为
        Method blank = GQuiz.class.getDeclaredMethod("blankOut", String.class, String.class);
        blank.setAccessible(true);
        ok(blank.invoke(null, "in a short period of time", "time") != null, "挖空：原形匹配");
        ok(blank.invoke(null, "abilities of the hero", "able") == null, "挖空：整词匹配不误切");
        ok(blank.invoke(null, "a completely different thing", "different") != null, "挖空：变形词干");

        // 粗词干误匹配扫描：matched form 是「去尾词干」且词干本身是词库词 → 例句被别的词污染
        Method idxW = GQuiz.class.getDeclaredMethod("indexWord", String.class, String.class);
        idxW.setAccessible(true);
        Set<String> vocabLower = new HashSet<>();
        for (GCard w : G.words) vocabLower.add(w.id);
        int stemFalse = 0;
        List<String> stemSamples = new ArrayList<>();
        for (GCard w : G.words) {
            String ex = w.ex == null ? "" : w.ex.trim();
            if (ex.isEmpty()) continue;
            String form = matchedForm(ex, w.en, idxW);
            if (form == null) continue;
            if (form.length() == w.en.length() - 1 && vocabLower.contains(form)) {
                stemFalse++;
                if (stemSamples.size() < 10)
                    stemSamples.add(w.en + " → 例句里的「" + form + "」：" + ex);
            }
        }
        System.out.println("  · 粗词干撞库（挖的是别的词）：" + stemFalse + " 词");
        for (String s : stemSamples) System.out.println("      " + s);
        // 不设硬断言：这是数据质量项，先量化给结论

        // 例句可用率（决定 mode1 供给）
        int exOK = 0;
        for (GCard w : G.words) {
            String ex = w.ex == null ? "" : w.ex.trim();
            if (!ex.isEmpty() && blank.invoke(null, ex, w.en) != null) exOK++;
        }
        System.out.println("  · 例句可出题 " + exOK + " / 1654 词"
                + "（注释称 79% 含本词，实测 " + String.format("%.1f%%", exOK * 100.0 / 1654) + "）");
    }

    static boolean hasTailEnglish(String s) {
        int i = s.length();
        while (i > 0) {
            char ch = s.charAt(i - 1);
            boolean eng = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || ch == ' ' || ch == '\'';
            if (!eng) break;
            i--;
        }
        int letters = 0;
        for (int k = i; k < s.length(); k++) {
            char ch = s.charAt(k);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) letters++;
        }
        return letters >= 2;
    }

    /** 复制 blankOut 的候选词形顺序，返回实际命中的词形（用于词干误配检测）。 */
    static String matchedForm(String ex, String word, Method indexWord) {
        try {
            String lower = ex.toLowerCase();
            String w = word.toLowerCase();
            List<String> forms = new ArrayList<>(Arrays.asList(
                    w, w + "s", w + "es", w + "ed", w + "d", w + "ing"));
            if (w.endsWith("e")) forms.add(w.substring(0, w.length() - 1) + "ing");
            if (w.endsWith("y")) forms.add(w.substring(0, w.length() - 1) + "ies");
            if (w.length() > 3) forms.add(w.substring(0, w.length() - 1));
            for (String f : forms) {
                if (((Integer) indexWord.invoke(null, lower, f)) >= 0) return f;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // 小工具：避免 import 冲突的简版 ArrayDeque 引用
    static class ArrayDeque<T> {
        private final java.util.ArrayDeque<T> d = new java.util.ArrayDeque<>();
        void add(T t) { d.add(t); }
        T removeFirst() { return d.removeFirst(); }
        boolean isEmpty() { return d.isEmpty(); }
    }
}
