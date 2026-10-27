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

    public static class Node {
        public int type;
        public int row;
        public int col;
        public boolean done;
        public Enemy enemy;
        public Node(int t, int r, int c) { type = t; row = r; col = c; }
    }

    /**
     * 生成 4 条路线。规则来自平衡表：战场 50%、商店 12%、篝火 15%、
     * 宝箱 10%、精英 13%；并保证至少一条路线完全没有精英（但商店更少）。
     */
    public static List<List<Node>> makeMap(Random rnd) {
        List<List<Node>> routes = new ArrayList<>();
        for (int r = 0; r < 4; r++) routes.add(buildRoute(rnd, r == 3));
        return routes;
    }

    private static List<Node> buildRoute(Random rnd, boolean noElite) {
        int len = 10 + rnd.nextInt(3);          // 10~12 节点
        List<Node> route = new ArrayList<>();
        for (int i = 0; i < len; i++) {
            int t;
            if (i == 0) {
                t = N_BATTLE;                    // 第 1 个必是战斗
            } else if (i == len - 1) {
                t = N_BATTLE;                    // 最后一个必是战斗
            } else {
                t = pickType(rnd, noElite);
            }
            route.add(new Node(t, 0, i));
        }
        // 每 4 个节点内不许连续两个同类非战斗节点，防止整段没有战斗
        for (int i = 1; i < route.size(); i++) {
            if (route.get(i).type != N_BATTLE && route.get(i - 1).type == route.get(i).type) {
                route.get(i).type = N_BATTLE;
            }
        }
        return route;
    }

    private static int pickType(Random rnd, boolean noElite) {
        int roll = rnd.nextInt(100);
        if (roll < 50) return N_BATTLE;
        if (roll < 62) return N_SHOP;
        if (roll < 77) return N_CAMP;
        if (roll < 87) return N_CHEST;
        return noElite ? N_BATTLE : N_ELITE;
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