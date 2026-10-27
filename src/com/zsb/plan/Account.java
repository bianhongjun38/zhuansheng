package com.zsb.plan;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本机账号凭证。
 *
 * 只存 token、uid、用户名三样。密码不存 ——
 * token 已经是长期凭证，再存一份密码只是多一个泄漏面。
 * 密码丢了走「找管理员重置」，20 个同学这个流程最实际。
 */
public class Account {

    private static final String SP = "zsb_account";

    public String uid = "";
    public String username = "";
    public String token = "";

    public boolean loggedIn() {
        return token != null && !token.isEmpty() && uid != null && !uid.isEmpty();
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static Account load(Context c) {
        Account a = new Account();
        SharedPreferences p = sp(c);
        a.uid = p.getString("uid", "");
        a.username = p.getString("username", "");
        a.token = p.getString("token", "");
        return a;
    }

    public void save(Context c) {
        sp(c).edit()
                .putString("uid", uid)
                .putString("username", username)
                .putString("token", token)
                .apply();
    }

    /** 退出登录。本地进度不清 —— 那是用户的学习数据，不是账号数据。 */
    public static void clear(Context c) {
        sp(c).edit().remove("uid").remove("username").remove("token").apply();
    }

    // ── 同步状态（最后一次成功推送的时间，用于冲突判断）────

    public static long lastSyncedAt(Context c) {
        return sp(c).getLong("syncedAt", 0);
    }

    public static void setLastSyncedAt(Context c, long v) {
        sp(c).edit().putLong("syncedAt", v).apply();
    }
}