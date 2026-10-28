package com.zsb.plan;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 游戏存档。用 SharedPreferences 存一份 JSON，和项目其它模块一致。
 *
 * 存的东西刻意很少：金币、已获得的特殊词、打的第几层、累计答对/答错。
 * 牌库不存 —— 每场战斗现抽，避免存档膨胀，也让「这局拿到什么牌」
 * 变成纯粹的临场决策而不是读档优化。
 */
public class GStore {

    private static final String SP = "zsb_game";
    private final SharedPreferences sp;

    public GStore(Context c) {
        sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    // ── 基础数值 ─────────────────────────────────────────

    public int gold()      { return sp.getInt("gold", 0); }
    public void addGold(int g) { sp.edit().putInt("gold", Math.max(0, gold() + g)).apply(); }
    public boolean spend(int g) {
        if (gold() < g) return false;
        sp.edit().putInt("gold", gold() - g).apply();
        return true;
    }

    public int layer()     { return sp.getInt("layer", 0); }
    public void setLayer(int l) { sp.edit().putInt("layer", l).apply(); }

    public int right()     { return sp.getInt("right", 0); }
    public int wrong()     { return sp.getInt("wrong", 0); }
    public void markRight() { sp.edit().putInt("right", right() + 1).apply(); }
    public void markWrong() { sp.edit().putInt("wrong", wrong() + 1).apply(); }

    public int bestFloor() { return sp.getInt("bestFloor", 0); }
    public void raiseFloor(int f) {
        if (f > bestFloor()) sp.edit().putInt("bestFloor", f).apply();
    }

    // ── 特殊词 ───────────────────────────────────────────

    public JSONArray specials() {
        try { return new JSONArray(sp.getString("specials", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public boolean hasSpecial(String id) {
        JSONArray a = specials();
        for (int i = 0; i < a.length(); i++) {
            if (id.equals(a.optString(i))) return true;
        }
        return false;
    }

    public void addSpecial(String id) {
        if (hasSpecial(id)) return;
        JSONArray a = specials();
        a.put(id);
        sp.edit().putString("specials", a.toString()).apply();
    }

    public int specialCount() { return specials().length(); }

    // ── 一次性道具 ───────────────────────────────────────
    //
    // 存在 "items" 这个 key 里，元素是 cards.json 的 item id。
    // 和 specials 分开：特殊词是永久被动、买了就不删；
    // 道具用掉必须删掉，否则下次进战斗还能再按一次。

    public org.json.JSONArray items() {
        try { return new org.json.JSONArray(sp.getString("items", "[]")); }
        catch (org.json.JSONException e) { return new org.json.JSONArray(); }
    }

    public boolean hasItem(String id) {
        if (id == null) return false;
        org.json.JSONArray a = items();
        for (int i = 0; i < a.length(); i++) {
            if (id.equals(a.optString(i))) return true;
        }
        return false;
    }

    public void addItem(String id) {
        if (id == null || id.isEmpty() || hasItem(id)) return;
        org.json.JSONArray a = items();
        a.put(id);
        sp.edit().putString("items", a.toString()).apply();
    }

    /** 用掉一个道具：从列表里删掉第一个匹配项。 */
    public void removeItem(String id) {
        if (id == null) return;
        org.json.JSONArray a = items();
        org.json.JSONArray out = new org.json.JSONArray();
        boolean dropped = false;
        for (int i = 0; i < a.length(); i++) {
            String v = a.optString(i);
            if (!dropped && id.equals(v)) { dropped = true; continue; }
            out.put(v);
        }
        if (dropped) sp.edit().putString("items", out.toString()).apply();
    }

    public int itemCount() { return items().length(); }

    // ── 地图进度 ─────────────────────────────────────────

    /**
     * 地图存档。
     *
     * 存的是一个二维数组：map[row][col] = 节点类型（int）。
     * 每层节点数不固定（1~3），所以是变长二维数组，不是矩形。
     * 邻接关系（谁能走到谁）由 GameData.Map 现场重建 —— 它只依赖每层节点数，
     * 存下来会让存档体积翻几倍，而且一旦和类型不同步就是灾难。
     *
     * done 用一串 "row,col" 记录，已走过的格子画成暗色。
     */
    public String mapRaw() { return sp.getString("map", ""); }

    public void setMap(String s) { sp.edit().putString("map", s).apply(); }

    /** 玩家当前站在哪一层哪一列。走到哪由玩家上一步的选择决定。 */
    public int curRow() { return sp.getInt("curRow", 0); }
    public int curCol() { return sp.getInt("curCol", 0); }

    public void setPos(int row, int col) {
        sp.edit().putInt("curRow", row).putInt("curCol", col).apply();
    }

    /** 已走过的格子，"row,col" 逗号分隔。 */
    public String visitedRaw() { return sp.getString("visited", ""); }

    public void markVisited(int row, int col) {
        String v = visitedRaw();
        String key = row + "," + col;
        if (v.isEmpty()) { setVisited(key); return; }
        String[] parts = v.split(";");
        for (String s : parts) {
            if (s.equals(key)) return;
        }
        setVisited(v + ";" + key);
    }

    private void setVisited(String v) { sp.edit().putString("visited", v).apply(); }

    public boolean isVisited(int row, int col) {
        String v = visitedRaw();
        if (v.isEmpty()) return false;
        String key = row + "," + col;
        for (String s : v.split(";")) {
            if (s.equals(key)) return true;
        }
        return false;
    }

    /**
     * 当前生命。跨战斗保留 —— 篝火和商店治疗才有意义。
     * 0 或负数表示「还没开始过」，战斗里按满血处理。
     */
    public int curHp() { return sp.getInt("curHp", -1); }

    public void setCurHp(int hp) { sp.edit().putInt("curHp", Math.max(0, hp)).apply(); }

    /** 清空已访问记录（新地图 / 新一层时用）。 */
    public void setVisitedClear() { sp.edit().putString("visited", "").apply(); }

    public void unmarkVisited(int row, int col) {
        String v = visitedRaw();
        if (v.isEmpty()) return;
        String key = row + "," + col;
        StringBuilder sb = new StringBuilder();
        for (String s : v.split(";")) {
            if (s.isEmpty() || s.equals(key)) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(s);
        }
        setVisited(sb.toString());
    }

    /**
     * 进行中的战斗节点 "row,col"；空串 = 没有进行中的战斗。
     *
     * 为什么必须落盘：战斗状态（血量/手牌/回合）只活在 GBattleView 实例里，
     * 进程一被系统回收就没了。而 store.setPos() 早就在 enterNode() 里
     * 把玩家挪到了这一格并标成已访问 —— 重进时程序看到的是「这一格走过了、
     * 人站在下一层」，于是上一场怪被当成打赢了，玩家白拿一个节点（自动过关）。
     * 把这个节点写进 SharedPreferences，重进时就能把位置退回去重新打。
     */
    public String battleNode() { return sp.getString("battleNode", ""); }

    public void setBattleNode(int row, int col) {
        sp.edit().putString("battleNode", row + "," + col).apply();
    }

    public void clearBattleNode() { sp.edit().putString("battleNode", "").apply(); }

    /**
     * 篝火 / 商店买的治疗量。
     *
     * 生命值存在 GBattle 实例里，每场战斗开始时才创建 —— 所以非战斗节点
     * 的加血没法直接改「当前血量」，只能挂账，等下一场战斗开始时兑现。
     * 兑现后清零，避免一路攒着变成白嫖满血。
     */
    public int campHeal() { return sp.getInt("campHeal", 0); }

    public void addCampHeal(int h) {
        sp.edit().putInt("campHeal", Math.max(0, campHeal() + h)).apply();
    }

    public void clearCampHeal() { sp.edit().putInt("campHeal", 0).apply(); }

    /** 兼容旧存档：旧版存的是 4 条平行路线，清掉即可（新版重新生成地图）。 */
    public JSONArray route() {
        try { return new JSONArray(sp.getString("route", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public void setRoute(JSONArray a) {
        sp.edit().putString("route", a.toString()).apply();
    }

    // ── 每日战绩（排行榜用）───────────────────────────────
    // 为什么要在 SharedPreferences 里再做一份按天分桶，而不是复用 Store 的
    // eng_round 表：那是学习进度库，进度码导出/导入会把整库打包带走。
    // 游戏战绩是「玩」的数据，混进去会让进度包多背一份无关内容，
    // 也会让「换手机导入进度」把别人的游戏记录一起搬过去。
    // 所以只在本机 zsb_game 命名空间里另存两份 JSON。

    /** 待上报的战斗记录。上限 200 条，超了丢最老的（宁可漏报也不让存档无限长）。 */
    private static final String K_PENDING = "g_pending";
    /** 按天聚合的本地战绩，保留最近 14 天。 */
    private static final String K_DAILY = "g_daily";
    private static final int PENDING_CAP = 200;
    private static final int DAILY_KEEP = 14;

    public static class Battle {
        public String day = "";
        public int floor;
        public int node;
        public boolean win;
        public int gold;
        public int right;
        public int wrong;
        public long ms;
        public long started;
        public String rev = "";
        public int total() { return right + wrong; }
    }

    private JSONArray arr(String key) {
        try { return new JSONArray(sp.getString(key, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private JSONObject obj(String key) {
        try { return new JSONObject(sp.getString(key, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    /**
     * 记一场战斗。
     *
     * 幂等：同一个 rev 重复调用不会记两次（rev 由调用方在战斗开始时生成一次，
     * 补传时一直用同一个，所以「传成功了但没收到响应、用户又重进」不会算两场）。
     */
    public void addBattle(Battle b) {
        if (b == null || b.rev == null || b.rev.isEmpty()) return;

        // 1) 待上报队列
        JSONArray pend = arr(K_PENDING);
        for (int i = 0; i < pend.length(); i++) {
            JSONObject o = pend.optJSONObject(i);
            if (o != null && b.rev.equals(o.optString("rev"))) return;   // 已经记过
        }
        JSONArray next = new JSONArray();
        int from = Math.max(0, pend.length() - (PENDING_CAP - 1));
        for (int i = from; i < pend.length(); i++) next.put(pend.opt(i));
        try {
            JSONObject o = new JSONObject();
            o.put("day", b.day);
            o.put("floor", b.floor);
            o.put("node", b.node);
            o.put("win", b.win ? 1 : 0);
            o.put("gold", b.gold);
            o.put("r", b.right);
            o.put("w", b.wrong);
            o.put("ms", b.ms);
            o.put("started", b.started);
            o.put("rev", b.rev);
            next.put(o);
        } catch (Exception e) {
            return;
        }
        sp.edit().putString(K_PENDING, next.toString()).apply();

        // 2) 当日聚合桶
        JSONObject daily = obj(K_DAILY);
        JSONObject d = daily.optJSONObject(b.day);
        if (d == null) d = new JSONObject();
        try {
            d.put("nodes", d.optInt("nodes", 0) + (b.win ? 1 : 0));
            d.put("gold", d.optInt("gold", 0) + Math.max(0, b.gold));
            d.put("right", d.optInt("right", 0) + b.right);
            d.put("wrong", d.optInt("wrong", 0) + b.wrong);
            d.put("battles", d.optInt("battles", 0) + 1);
            if (b.floor > d.optInt("floor", 0)) d.put("floor", b.floor);
            daily.put(b.day, d);
            // 只留最近 14 天：按 key 排序后裁掉最老的
            if (daily.length() > DAILY_KEEP) {
                java.util.List<String> keys = new java.util.ArrayList<>();
                java.util.Iterator<String> it = daily.keys();
                while (it.hasNext()) keys.add(it.next());
                java.util.Collections.sort(keys);
                int drop = keys.size() - DAILY_KEEP;
                for (int i = 0; i < drop; i++) daily.remove(keys.get(i));
            }
        } catch (Exception e) {
            return;
        }
        sp.edit().putString(K_DAILY, daily.toString()).apply();
    }

    /** 还没传上去的战斗。 */
    public JSONArray pendingBattles() { return arr(K_PENDING); }

    /** 标记已上传。按 rev 定位。 */
    public void markBattleSent(String rev) {
        if (rev == null || rev.isEmpty()) return;
        JSONArray pend = arr(K_PENDING);
        JSONArray next = new JSONArray();
        for (int i = 0; i < pend.length(); i++) {
            JSONObject o = pend.optJSONObject(i);
            if (o == null) continue;
            if (!rev.equals(o.optString("rev"))) next.put(o);
        }
        sp.edit().putString(K_PENDING, next.toString()).apply();
    }

    /** 某一天的本地汇总（离线也能显示的「我的今日战绩」）。 */
    public JSONObject dayStat(String day) {
        JSONObject d = obj(K_DAILY).optJSONObject(day);
        return d == null ? new JSONObject() : d;
    }

    // ── 重置 ─────────────────────────────────────────────

    public void resetRun() {
        sp.edit()
          .putInt("gold", 0)
          .putInt("layer", 0)
          .putInt("node", 0)
          .putString("route", "[]")
          .putString("specials", "[]")
          .putString("items", "[]")
          .putString("battleNode", "")   // 重置这一局就不能留下"打到一半"的记录
          .putString("visited", "")
          .remove("curRow")
          .remove("curCol")
          .apply();
    }

    public void resetAll() { sp.edit().clear().apply(); }
}
