package com.zsb.plan;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * Android 侧的持久化壳子。
 *
 * 业务规则全部在 StateLogic 里（纯 Java，可在电脑上测）。
 * 这里只做三件事：
 *   ① 从 SharedPreferences 读字符串 → 交给 StateLogic
 *   ② 把 StateLogic 序列化的字符串写回去
 *   ③ 输入密集时合并写盘（防抖），避免每个字符都整体序列化
 */
public class Store extends StateLogic {

    private static final String PREF = "zsb_state";
    private static final String K_STATE = "state_json";

    private final SharedPreferences sp;

    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable saveTask = new Runnable() {
        @Override public void run() {
            savePending = false;
            writeNow();
        }
    };
    private boolean savePending;

    public Store(Context ctx) {
        this(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE));
    }

    Store(SharedPreferences sp) {
        this.sp = sp;
        deserialize(sp.getString(K_STATE, null));
    }

    // ── 写盘 ──────────────────────────────────────────────
    /** 关键动作（勾选/推进/计时）立即落盘，不能等。 */
    @Override
    public void setDone(String date, String id, boolean v) {
        super.setDone(date, id, v);
        save();
    }

    @Override
    public void commitDay(String date) {
        super.commitDay(date);
        save();
    }

    @Override
    public void uncommitDay(String date) {
        super.uncommitDay(date);
        save();
    }

    @Override
    public void addTiming(String date, String id, long ms) {
        super.addTiming(date, id, ms);
        save();
    }

    @Override
    public void bumpHard(String id) {
        super.bumpHard(id);
        save();
    }

    @Override
    public void clearHard(String id) {
        super.clearHard(id);
        save();
    }

    @Override
    public void resetAll() {
        super.resetAll();
        save();
    }

    /** 默写框每敲一个字符都会走这里 —— 改成 700ms 合并一次写盘。 */
    @Override
    public void setNote(String date, String id, String text) {
        super.setNote(date, id, text);
        saveSoon();
    }

    private void saveSoon() {
        if (savePending) return;
        savePending = true;
        saveHandler.postDelayed(saveTask, 700);
    }

    /** 立即写盘。切后台 / 退出前必须调，否则最后 700ms 的输入会丢。 */
    public void flush() {
        if (savePending) {
            saveHandler.removeCallbacks(saveTask);
            savePending = false;
        }
        writeNow();
    }

    /** 立即写盘（若已有挂起的写则合并，不重复写）。 */
    public void save() {
        if (savePending) return;
        writeNow();
    }

    private void writeNow() {
        String s = serialize();
        if (s != null) sp.edit().putString(K_STATE, s).apply();
    }

    /** 导出后立刻需要一份最新快照时用。 */
    public String exportSnapshot() {
        flush();
        return exportJson();
    }
}
