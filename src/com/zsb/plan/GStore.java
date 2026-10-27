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

    // ── 地图进度 ─────────────────────────────────────────

    /** 当前层的路线（4 条，每条是节点类型数组）。 */
    public JSONArray route() {
        try { return new JSONArray(sp.getString("route", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    public void setRoute(JSONArray a) {
        sp.edit().putString("route", a.toString()).apply();
    }

    public int node() { return sp.getInt("node", 0); }
    public void setNode(int n) { sp.edit().putInt("node", n).apply(); }

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
          .apply();
    }

    public void resetAll() { sp.edit().clear().apply(); }
}
