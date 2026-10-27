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
