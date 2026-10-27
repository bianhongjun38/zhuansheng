package com.zsb.plan;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 游戏静态数据。全部来自 assets/game/ 下的 JSON，只在首次访问时读一次。
 *
 * 为什么同义关系要现查而不能写进牌里：
 *   一个词可能同时属于多个同义组（regulate 属 4 组、rough 属 4 组），
 *   牌只有一张，组有多重归属，提前拍平必然丢信息。所以牌只存词形，
 *   同义判定在 GameData 里按 wordKeys 求交集。
 */
public class GameData {

    /** 存档引用，由界面层注入。战斗需要它来读写跨战斗状态（血量、篝火挂账）。
     *  为 null 时一切按「无存档」的默认行为跑，不会崩。 */
    public GStore storeRef;


    private static GameData I;

    public static synchronized GameData get(Context c) {
        if (I == null) I = new GameData(c.getApplicationContext());
        return I;
    }

    // ── 词库 ─────────────────────────────────────────────
    public final List<GCard> words = new ArrayList<>();
    public final List<GCard> adverbs = new ArrayList<>();
    public final List<Special> specials = new ArrayList<>();
    public final List<Enemy> enemies = new ArrayList<>();
    public final List<Item> items = new ArrayList<>();

    /** 词形 → 它所属的全部同义组键（pos|cn）。 */
    public final Map<String, Set<String>> wordKeys = new HashMap<>();
    /** 同义组键 → 该组全部词形。 */
    public final Map<String, List<String>> groupWords = new HashMap<>();
    private final Map<String, GCard> byId = new HashMap<>();

    public int playerHp = 80;
    public int handSize = 5;
    public int baseDamage = 12;
    public double chainMultiplier = 1.5;
    public int keepLimit = 2;
    public double critMultiplier = 2.0;
    public int wrongChargeUp = 2;
    public double wrongDamageMultiplier = 2.0;
    public int battleGoldMin = 15, battleGoldMax = 25;
    public int eliteGold = 50, chestGoldMin = 20, chestGoldMax = 60;
    public int cardPrice = 60, itemPrice = 90;

    private GameData(Context c) {
        loadVocab(c);
        loadCards(c);
    }

    // ── 读词库 ───────────────────────────────────────────
    private void loadVocab(Context c) {
        try {
            JSONObject root = new JSONObject(read(c, "game/vocab.json"));
            JSONArray ws = root.optJSONArray("words");
            if (ws != null) {
                for (int i = 0; i < ws.length(); i++) {
                    JSONObject o = ws.getJSONObject(i);
                    String w = o.optString("w", "");
                    if (w.isEmpty()) continue;
                    String pos = firstPos(o.optJSONArray("pos"));
                    String cn  = firstCn(o.optString("cn", ""));
                    GCard g = new GCard(w.toLowerCase(), w, cn, pos,
                                        GCard.KIND_WORD, null,
                                        o.optString("ex", ""), o.optString("exCn", ""));
                    words.add(g);
                    byId.put(g.id, g);
                }
            }
            JSONArray syn = root.optJSONArray("synonyms");
            if (syn != null) {
                for (int i = 0; i < syn.length(); i++) {
                    JSONObject g = syn.getJSONObject(i);
                    String pos = g.optString("pos", "");
                    String cn  = g.optString("cn", "");
                    if (pos.isEmpty() || cn.isEmpty()) continue;
                    String key = pos + "|" + cn;
                    JSONArray arr = g.optJSONArray("words");
                    if (arr == null) continue;
                    List<String> list = new ArrayList<>();
                    for (int j = 0; j < arr.length(); j++) {
                        String w = arr.optString(j, "").toLowerCase();
                        if (w.isEmpty()) continue;
                        list.add(w);
                        Set<String> ks = wordKeys.get(w);
                        if (ks == null) { ks = new HashSet<>(); wordKeys.put(w, ks); }
                        ks.add(key);
                    }
                    if (list.size() >= 2) groupWords.put(key, list);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("GameData", "vocab.json 读取失败", e);
        }
    }

    private void loadCards(Context c) {
        try {
            JSONObject root = new JSONObject(read(c, "game/cards.json"));

            JSONArray ad = root.optJSONArray("adverbs");
            if (ad != null) for (int i = 0; i < ad.length(); i++) {
                JSONObject o = ad.getJSONObject(i);
                GCard g = new GCard(o.optString("id"),
                        o.optString("en"), o.optString("cn"), "adv",
                        GCard.KIND_ADVERB, o.optString("desc"));
                adverbs.add(g); byId.put(g.id, g);
            }

            JSONArray sp = root.optJSONArray("specials");
            if (sp != null) for (int i = 0; i < sp.length(); i++) {
                JSONObject o = sp.getJSONObject(i);
                specials.add(new Special(o.optString("id"),
                        o.optString("cn"), o.optString("effect")));
            }

            JSONArray en = root.optJSONArray("enemies");
            if (en != null) for (int i = 0; i < en.length(); i++) {
                JSONObject o = en.getJSONObject(i);
                enemies.add(new Enemy(o.optString("id"), o.optString("name"),
                        o.optString("nameEn"), o.optInt("hp"), o.optInt("atk"),
                        o.optInt("charge", 1), o.optString("tier"),
                        o.optString("sprite")));
            }

            JSONArray it = root.optJSONArray("items");
            if (it != null) for (int i = 0; i < it.length(); i++) {
                JSONObject o = it.getJSONObject(i);
                items.add(new Item(o.optString("id"), o.optString("en"),
                        o.optString("cn"), o.optString("desc"), o.optInt("price", 90)));
            }

            JSONObject b = root.optJSONObject("balance");
            if (b != null) {
                playerHp = b.optInt("playerHp", playerHp);
                handSize = b.optInt("handSize", handSize);
                baseDamage = b.optInt("baseDamage", baseDamage);
                chainMultiplier = b.optDouble("chainMultiplier", chainMultiplier);
                keepLimit = b.optInt("keepLimit", keepLimit);
                critMultiplier = b.optDouble("critMultiplier", critMultiplier);
                wrongChargeUp = b.optInt("wrongChargeUp", wrongChargeUp);
                wrongDamageMultiplier = b.optDouble("wrongDamageMultiplier", wrongDamageMultiplier);
                JSONObject ec = b.optJSONObject("economy");
                if (ec != null) {
                    battleGoldMin = ec.optInt("battleMin", battleGoldMin);
                    battleGoldMax = ec.optInt("battleMax", battleGoldMax);
                    eliteGold = ec.optInt("elite", eliteGold);
                    chestGoldMin = ec.optInt("chestMin", chestGoldMin);
                    chestGoldMax = ec.optInt("chestMax", chestGoldMax);
                    cardPrice = ec.optInt("cardPrice", cardPrice);
                    itemPrice = ec.optInt("itemPrice", itemPrice);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("GameData", "cards.json 读取失败", e);
        }
    }

    // ── 同义判定 ─────────────────────────────────────────

    /** 两个词是否同义（至少共享一个组）。同形词视为同义。 */
    public boolean synonym(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        Set<String> ka = wordKeys.get(a), kb = wordKeys.get(b);
        if (ka == null || kb == null) return false;
        for (String k : ka) if (kb.contains(k)) return true;
        return false;
    }

    /** 与 w 同义的其它词（用于「同义连锁」提示与干扰项生成）。 */
    public List<String> synonymsOf(String w) {
        Set<String> ks = wordKeys.get(w);
        List<String> out = new ArrayList<>();
        if (ks == null) return out;
        for (String k : ks) {
            List<String> g = groupWords.get(k);
            if (g == null) continue;
            for (String x : g) if (!x.equals(w) && !out.contains(x)) out.add(x);
        }
        return out;
    }

    // ── 抽牌 ─────────────────────────────────────────────

    /** 按种子从全词库抽 n 张不重复的牌。 */
    public List<GCard> drawCards(int n, Random rnd) {
        List<GCard> out = new ArrayList<>();
        if (words.isEmpty() || n <= 0) return out;
        Set<String> used = new HashSet<>();
        int guard = 0;
        while (out.size() < n && guard++ < n * 20) {
            GCard g = words.get(rnd.nextInt(words.size()));
            if (used.add(g.id)) out.add(g);
        }
        return out;
    }

    public GCard byId(String id) { return byId.get(id); }

    // ── 地图 ─────────────────────────────────────────────

    public static final int N_BATTLE = 0, N_SHOP = 1, N_CAMP = 2, N_CHEST = 3, N_ELITE = 4;

    /**
     * 地图节点。
     *
     * row = 第几层（自上而下 0..N-1），col = 该层从左到右第几个（0..2）。
     * 同一层最多 3 个节点，nodes[row] 是一个变长数组 —— 有的层只有 2 个甚至 1 个，
     * 这样才能画出真正会分叉、会合并的树枝，而不是固定几列。
     */
    public static class Node {
        public int type;
        public int row;
        public int col;
        public boolean done;
        public Enemy enemy;
        /** 下一层可达节点的下标（在 nodes[row+1] 里的位置）。空数组 = 终点。 */
        public final List<Integer> next = new ArrayList<>();
        public Node(int t, int r, int c) { type = t; row = r; col = c; }
        public Node(int t) { this(t, 0, 0); }
    }

    /**
     * 生成树枝状分叉地图（Slay the Spire 式）。
     *
     * ── 为什么要重写 ──────────────────────────────────────
     * 旧版是「4 条平行的直线，每条 10~12 节点」，而且 routeCard 给**每条路线**
     * 当前节点都绑了点击 —— 玩家可以第 1 步走路线 1、第 2 步跳到路线 3，
     * 根本没有「选择一条路」的感觉，只是在 4 个并排的进度条之间乱点。
     *
     * 现在：一层 1~3 个节点，上一层任意节点连到下一层相邻的 1~2 个节点，
     * 玩家**当前能去的格子**由「你上一步站在哪」决定，走不通的地方点了没用。
     *
     * ── 结构保证 ─────────────────────────────────────────
     *   · 第 0 层固定 1 个节点（起点，普通战斗）
     *   · 最后一层固定 1 个节点（Boss 前的关底，普通战斗）
     *   · 每层节点数 1~3，层数 12~14
     *   · 每个非末层节点至少连出 1 条边，不会出现死路
     *   · 至少一条通往终点的路径上完全没有精英（给不想赌的玩家留活路）
     *   · 相邻两层不出现「同类型硬串」—— 连着的两个篝火没意义
     */
    public static final class RouteMap {
        /** nodes.get(row) = 该层所有节点。 */
        public final List<List<Node>> nodes = new ArrayList<>();
        public int rows() { return nodes.size(); }
        public List<Node> row(int r) { return nodes.get(r); }
        public Node start() { return nodes.get(0).get(0); }
    }

    /** 生成一张分叉地图。 */
    public static RouteMap makeMap(Random rnd) {
        RouteMap m = new RouteMap();
        int rows = 12 + rnd.nextInt(3);          // 12~14 层

        // ① 先定每层的节点数：首末层各 1 个，中间 1~3 个（多数是 2~3）
        List<Integer> counts = new ArrayList<>();
        counts.add(1);
        for (int r = 1; r < rows - 1; r++) {
            int roll = rnd.nextInt(100);
            if (roll < 18) counts.add(1);
            else if (roll < 62) counts.add(2);
            else counts.add(3);
        }
        counts.add(1);

        // ② 建节点
        for (int r = 0; r < rows; r++) {
            List<Node> layer = new ArrayList<>();
            for (int c = 0; c < counts.get(r); c++) layer.add(new Node(N_BATTLE, r, c));
            m.nodes.add(layer);
        }

        // ③ 连边：每个节点连到下一层相邻 1~2 个节点。
        //    先保证「下一层每个节点都有人连」，否则那一层是孤岛、永远走不到。
        for (int r = 0; r < rows - 1; r++) {
            List<Node> cur = m.nodes.get(r);
            List<Node> nxt = m.nodes.get(r + 1);
            int nc = nxt.size();

            // 反推：下一层每个节点至少被一个上层节点连到
            List<List<Integer>> inFrom = new ArrayList<>();
            for (int i = 0; i < nc; i++) inFrom.add(new ArrayList<>());
            for (int i = 0; i < cur.size(); i++) {
                // 按比例映射到下一层，保证覆盖
                int target = cur.size() == 1
                        ? rnd.nextInt(nc)
                        : (int) Math.round((double) i * (nc - 1) / Math.max(1, cur.size() - 1));
                target = Math.max(0, Math.min(nc - 1, target));
                cur.get(i).next.add(target);
                inFrom.get(target).add(i);
            }
            // 把漏掉的下一层节点补一条边（从最近的上一层节点连过来）
            for (int j = 0; j < nc; j++) {
                if (!inFrom.get(j).isEmpty()) continue;
                int src = cur.size() == 1 ? 0
                        : Math.max(0, Math.min(cur.size() - 1,
                            (int) Math.round((double) j * (cur.size() - 1) / Math.max(1, nc - 1))));
                if (!cur.get(src).next.contains(j)) cur.get(src).next.add(j);
            }
            // ⚠ 这里**不能**加随机交叉边。
            // 存档只存「每层有几个节点、各是什么类型」，读档时 rebuildEdges()
            // 按同样的比例规则确定性重建 —— 它复现不了 rnd 加的随机边。
            // 一旦这里用了随机，玩家看到的地图（含随机边）和存档还原后的
            // 地图（无随机边）就是两张不同的图：岔路消失，而且下面
            // clearOneElitePath() 基于随机拓扑算出的「无精英路径」
            // 在真正游玩的那个拓扑上可能根本不存在。
            for (int i = 0; i < cur.size(); i++) {
                java.util.Collections.sort(cur.get(i).next);
            }
        }

        // ④ 分配节点类型
        assignTypes(m, rnd);

        // ⑤ 保证至少有一条路完全没有精英
        clearOneElitePath(m, rnd);

        // ⑥ 去掉相邻两层的同类非战斗节点（连着的两个篝火 / 两家商店没意义）
        smoothAdjacent(m);

        return m;
    }

    /**
     * 类型分配。
     *
     * 按层的阶段给不同权重 —— 越往后越危险，篝火则集中在中后段当补给点。
     * 起点和终点固定普通战斗。
     */
    private static void assignTypes(RouteMap m, Random rnd) {
        int rows = m.rows();
        for (int r = 0; r < rows; r++) {
            for (Node n : m.nodes.get(r)) {
                if (r == 0 || r == rows - 1) { n.type = N_BATTLE; continue; }

                // 进度 0..1：越靠后精英概率越高
                double p = rows <= 1 ? 0 : (double) r / (rows - 1);
                int roll = rnd.nextInt(100);

                if (p < 0.25) {
                    // 前段：基本只有战斗和宝箱
                    if (roll < 72) n.type = N_BATTLE;
                    else if (roll < 88) n.type = N_CHEST;
                    else if (roll < 96) n.type = N_SHOP;
                    else n.type = N_CAMP;
                } else if (p < 0.6) {
                    // 中段：商店和篝火开始出现
                    if (roll < 52) n.type = N_BATTLE;
                    else if (roll < 68) n.type = N_CAMP;
                    else if (roll < 80) n.type = N_SHOP;
                    else if (roll < 90) n.type = N_CHEST;
                    else n.type = N_ELITE;
                } else {
                    // 后段：精英变多，篝火是决战前的补给
                    if (roll < 44) n.type = N_BATTLE;
                    else if (roll < 60) n.type = N_CAMP;
                    else if (roll < 72) n.type = N_SHOP;
                    else if (roll < 80) n.type = N_CHEST;
                    else n.type = N_ELITE;
                }
            }
        }
        // 最后一层前面必须能补一次血：把倒数第二层至少一个节点改成篝火
        if (rows >= 3) {
            List<Node> pre = m.nodes.get(rows - 2);
            boolean hasCamp = false;
            for (Node n : pre) if (n.type == N_CAMP) hasCamp = true;
            if (!hasCamp) pre.get(rnd.nextInt(pre.size())).type = N_CAMP;
        }
    }

    /**
     * 保证存在一条从起点到终点、全程没有精英的路径。
     *
     * 做法：从起点按「优先选非精英」贪心走到底；沿途如果全是精英，
     * 就把其中那个精英改成普通战斗。这样不想赌的玩家永远有稳路可走。
     */
    private static void clearOneElitePath(RouteMap m, Random rnd) {
        int rows = m.rows();
        Node cur = m.start();
        List<Node> path = new ArrayList<>();
        path.add(cur);
        for (int r = 0; r < rows - 1; r++) {
            List<Node> nxt = m.nodes.get(r + 1);
            int pick = -1;
            for (int idx : cur.next) {
                if (idx >= 0 && idx < nxt.size() && nxt.get(idx).type != N_ELITE) { pick = idx; break; }
            }
            if (pick < 0) {
                // 全是精英：随机选一个，稍后降级
                pick = cur.next.isEmpty() ? 0 : cur.next.get(0);
                if (pick >= 0 && pick < nxt.size()) {
                    Node bad = nxt.get(pick);
                    bad.type = rnd.nextInt(100) < 55 ? N_BATTLE : N_CAMP;
                }
            }
            if (pick < 0 || pick >= nxt.size()) break;
            cur = nxt.get(pick);
            path.add(cur);
        }
    }

    /** 同一条边上的两个节点不都是同类非战斗节点。 */
    private static void smoothAdjacent(RouteMap m) {
        for (int r = 0; r < m.rows() - 1; r++) {
            for (Node a : m.nodes.get(r)) {
                for (int idx : a.next) {
                    List<Node> nxt = m.nodes.get(r + 1);
                    if (idx < 0 || idx >= nxt.size()) continue;
                    Node b = nxt.get(idx);
                    if (a.type == b.type && a.type != N_BATTLE && b.type != N_BATTLE) {
                        // 把下层那个换成战斗，避免「篝火→篝火」「商店→商店」
                        b.type = N_BATTLE;
                    }
                }
            }
        }
    }

    /** 按节点类型与层数挑敌人。 */
    public Enemy pickEnemy(int nodeType, int layer, Random rnd) {
        String want;
        if (nodeType == N_ELITE) want = "elite";
        else if (layer >= 2 && rnd.nextInt(100) < 14) want = "boss";
        else want = "mob" + (1 + rnd.nextInt(3));
        List<Enemy> pool = new ArrayList<>();
        for (Enemy e : enemies) {
            if (e.tier.startsWith(want) || (want.startsWith("mob") && e.tier.equals(want)))
                pool.add(e);
        }
        if (pool.isEmpty()) {
            for (Enemy e : enemies) if (e.tier.startsWith("mob")) pool.add(e);
        }
        return pool.isEmpty() ? null : pool.get(rnd.nextInt(pool.size()));
    }

    // ── 结构体 ───────────────────────────────────────────

    public static class Enemy {
        public final String id, name, nameEn, tier, sprite;
        public final int hp, atk, charge;
        public Enemy(String id, String name, String nameEn, int hp, int atk,
                     int charge, String tier, String sprite) {
            this.id = id; this.name = name; this.nameEn = nameEn;
            this.hp = hp; this.atk = atk; this.charge = charge;
            this.tier = tier == null ? "" : tier;
            this.sprite = sprite == null ? "" : sprite;
        }
    }

    public static class Special {
        public final String id, cn, effect;
        public Special(String id, String cn, String effect) {
            this.id = id; this.cn = cn; this.effect = effect;
        }
    }

    public static class Item {
        public final String id, en, cn, desc;
        public final int price;
        public Item(String id, String en, String cn, String desc, int price) {
            this.id = id; this.en = en; this.cn = cn;
            this.desc = desc; this.price = price;
        }
    }

    /** 随机挑一个玩家还没有的特殊词。全都有了返回 null。 */
    public Special randomUnownedSpecial(GStore store, java.util.Random rnd) {
        List<Special> pool = new ArrayList<>();
        for (Special s : specials) {
            if (s == null || s.id == null || s.id.isEmpty()) continue;
            if (!store.hasSpecial(s.id)) pool.add(s);
        }
        if (pool.isEmpty()) return null;
        return pool.get(rnd.nextInt(pool.size()));
    }

    // ── 小工具 ───────────────────────────────────────────

    private static String firstPos(JSONArray a) {
        if (a == null || a.length() == 0) return "";
        return a.optString(0, "");
    }

    private static String firstCn(String s) {
        if (s == null) return "";
        int i = s.indexOf(';');
        if (i < 0) i = s.indexOf('；');
        String t = i < 0 ? s : s.substring(0, i);
        return t.trim();
    }

    static String read(Context c, String name) {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    c.getAssets().open(name), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("读不到 assets/" + name + "：" + e.getMessage(), e);
        }
    }
}