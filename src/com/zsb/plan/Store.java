package com.zsb.plan;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * 持久化层 —— 数据库版。
 *
 * 为什么从 SharedPreferences 换成 SQLite：
 *  · 原来把整个进度序列化成一个 JSON 字符串，每次勾一个框都要重写全量
 *  · 一天勾一二十个任务、每个任务还有计时和默写，很快就是几十 KB 反复整写
 *  · 单条记录更新改成一行 UPDATE，代价与总进度无关
 *
 * 业务规则仍然全在 StateLogic（纯 Java，可在电脑上测）。
 * 这里只负责「读库 → 填进 StateLogic」和「StateLogic 变了 → 写回库」。
 *
 * 兼容老数据：首次打开时如果发现旧的 zsb_state 里还有进度，
 * 自动迁移过来，之后不再读它（但不清空，留作后悔药）。
 */
public class Store extends StateLogic {

    private static final String DB = "zsb.db";
    private static final int DB_VER = 2;

    private final Db db;
    private final SharedPreferences legacy;

    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable saveTask = new Runnable() {
        @Override public void run() {
            savePending = false;
            writeNow();
        }
    };
    private boolean savePending;

    public Store(Context ctx) {
        this.legacy = ctx.getSharedPreferences("zsb_state", Context.MODE_PRIVATE);
        this.db = new Db(ctx.getApplicationContext());
        loadAll();
        migrateLegacy();
    }

    // ── 建表 ──────────────────────────────────────────────
    static class Db extends SQLiteOpenHelper {

        Db(Context c) {
            super(c, DB, null, DB_VER);
        }

        @Override
        public void onCreate(SQLiteDatabase d) {
            // 勾选：一天一个任务一行
            d.execSQL("CREATE TABLE IF NOT EXISTS done ("
                    + "d TEXT NOT NULL, i TEXT NOT NULL, v INTEGER NOT NULL DEFAULT 1,"
                    + " PRIMARY KEY (d, i))");

            // 推进：哪天已确认
            d.execSQL("CREATE TABLE IF NOT EXISTS committed ("
                    + "d TEXT NOT NULL PRIMARY KEY)");

            // 计时：累积毫秒
            d.execSQL("CREATE TABLE IF NOT EXISTS timing ("
                    + "d TEXT NOT NULL, i TEXT NOT NULL, ms INTEGER NOT NULL DEFAULT 0,"
                    + " PRIMARY KEY (d, i))");

            // 默写/笔记
            d.execSQL("CREATE TABLE IF NOT EXISTS notes ("
                    + "t TEXT, d TEXT NOT NULL, i TEXT NOT NULL,"
                    + " PRIMARY KEY (d, i))");

            // 错题星级 0..3
            d.execSQL("CREATE TABLE IF NOT EXISTS hard ("
                    + "i TEXT NOT NULL PRIMARY KEY, lv INTEGER NOT NULL DEFAULT 0)");

            // 错题本：做题做错的题（v2 新增）
            // q 存题干前 80 字做摘要，避免依赖题库版本
            d.execSQL("CREATE TABLE IF NOT EXISTS wrong ("
                    + "i TEXT NOT NULL PRIMARY KEY, s TEXT, q TEXT,"
                    + " ts INTEGER NOT NULL DEFAULT 0, n INTEGER NOT NULL DEFAULT 1)");

            // 关键值（schema 版本、最后同步时间等）
            d.execSQL("CREATE TABLE IF NOT EXISTS kv ("
                    + "k TEXT NOT NULL PRIMARY KEY, v TEXT)");

            d.execSQL("CREATE INDEX IF NOT EXISTS idx_done_d ON done(d)");
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_timing_d ON timing(d)");
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_wrong_s ON wrong(s)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase d, int o, int n) {
            // v1 → v2：加错题本表。只加表，不动已有进度。
            if (o < 2) {
                d.execSQL("CREATE TABLE IF NOT EXISTS wrong ("
                        + "i TEXT NOT NULL PRIMARY KEY, s TEXT, q TEXT,"
                        + " ts INTEGER NOT NULL DEFAULT 0, n INTEGER NOT NULL DEFAULT 1)");
                d.execSQL("CREATE INDEX IF NOT EXISTS idx_wrong_s ON wrong(s)");
            }
        }
    }

    // ── 载入 ──────────────────────────────────────────────
    private void loadAll() {
        SQLiteDatabase r = db.getReadableDatabase();

        Cursor c = r.rawQuery("SELECT d, i FROM done WHERE v=1", null);
        while (c.moveToNext()) done.add(key(c.getString(0), c.getString(1)));
        c.close();

        c = r.rawQuery("SELECT d FROM committed", null);
        while (c.moveToNext()) completed.add(c.getString(0));
        c.close();

        c = r.rawQuery("SELECT d, i, ms FROM timing", null);
        while (c.moveToNext()) timing.put(key(c.getString(0), c.getString(1)), c.getLong(2));
        c.close();

        c = r.rawQuery("SELECT d, i, t FROM notes", null);
        while (c.moveToNext()) {
            String t = c.getString(2);
            notes.put(key(c.getString(0), c.getString(1)), t == null ? "" : t);
        }
        c.close();

        c = r.rawQuery("SELECT i, lv FROM hard WHERE lv>0", null);
        while (c.moveToNext()) hard.put(c.getString(0), c.getInt(1));
        c.close();

        r.close();
    }

    /** 老版本把整份 JSON 存在 SharedPreferences；有就迁进来。 */
    private void migrateLegacy() {
        try {
            if (db.getReadableDatabase().rawQuery(
                    "SELECT v FROM kv WHERE k='migrated'", null).getCount() > 0) {
                return;   // 已经迁过
            }
            String old = legacy.getString("state_json", null);
            if (old == null || old.length() < 10) return;
            deserialize(old);        // 填进内存
            writeNow();              // 立刻落库
            db.getWritableDatabase().execSQL(
                    "INSERT OR REPLACE INTO kv(k,v) VALUES('migrated',?)",
                    new Object[]{nowIso()});
        } catch (Exception ignored) {
            // 迁移失败就当新用户，不让 App 起不来
        }
    }

    // ── 写库（关键动作立即写）──────────────────────────────
    @Override
    public void setDone(String date, String id, boolean v) {
        super.setDone(date, id, v);
        SQLiteDatabase w = db.getWritableDatabase();
        if (v) {
            ContentValues cv = new ContentValues();
            cv.put("d", date);
            cv.put("i", id);
            cv.put("v", 1);
            w.insertWithOnConflict("done", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } else {
            w.delete("done", "d=? AND i=?", new String[]{date, id});
        }
    }

    @Override
    public void commitDay(String date) {
        super.commitDay(date);
        ContentValues cv = new ContentValues();
        cv.put("d", date);
        db.getWritableDatabase().insertWithOnConflict(
                "committed", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void uncommitDay(String date) {
        super.uncommitDay(date);
        db.getWritableDatabase().delete("committed", "d=?", new String[]{date});
    }

    @Override
    public void addTiming(String date, String id, long ms) {
        super.addTiming(date, id, ms);
        ContentValues cv = new ContentValues();
        cv.put("d", date);
        cv.put("i", id);
        cv.put("ms", timingOf(date, id));
        db.getWritableDatabase().insertWithOnConflict(
                "timing", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void bumpHard(String id) {
        super.bumpHard(id);
        saveHard(id);
    }

    @Override
    public void clearHard(String id) {
        super.clearHard(id);
        saveHard(id);
    }

    private void saveHard(String id) {
        SQLiteDatabase w = db.getWritableDatabase();
        int lv = hardLevel(id);
        if (lv <= 0) {
            w.delete("hard", "i=?", new String[]{id});
        } else {
            ContentValues cv = new ContentValues();
            cv.put("i", id);
            cv.put("lv", lv);
            w.insertWithOnConflict("hard", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    // ── 错题本 ────────────────────────────────────────────
    /**
     * 记录一道做错的题。同一题重复做错只累加次数，不重复占行。
     * 只存题号 + 学科 + 题干摘要，不存答案 —— 题库更新后仍能对上。
     */
    public void addWrong(String qid, String subject, String stem, String extra) {
        if (qid == null || qid.isEmpty()) return;
        String s = stem == null ? "" : stem;
        if (s.length() > 80) s = s.substring(0, 80);
        SQLiteDatabase w = db.getWritableDatabase();
        // 先查是否已存在，存在就 n+1
        Cursor c = w.rawQuery("SELECT n FROM wrong WHERE i=?", new String[]{qid});
        int n = 0;
        while (c.moveToNext()) n = c.getInt(0);
        c.close();
        ContentValues cv = new ContentValues();
        cv.put("i", qid);
        cv.put("s", subject == null ? "" : subject);
        cv.put("q", s);
        cv.put("ts", System.currentTimeMillis());
        cv.put("n", n + 1);
        w.insertWithOnConflict("wrong", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 取某科错题 id 列表；subject 传 null 或空串则取全部。 */
    public List<String> wrongIds(String subject) {
        List<String> out = new ArrayList<>();
        SQLiteDatabase r = db.getReadableDatabase();
        Cursor c;
        if (subject == null || subject.isEmpty()) {
            c = r.rawQuery("SELECT i FROM wrong ORDER BY ts DESC", null);
        } else {
            c = r.rawQuery("SELECT i FROM wrong WHERE s=? ORDER BY ts DESC",
                    new String[]{subject});
        }
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    /** 错题本总数。 */
    public int wrongCount() {
        SQLiteDatabase r = db.getReadableDatabase();
        Cursor c = r.rawQuery("SELECT COUNT(*) FROM wrong", null);
        int n = 0;
        while (c.moveToNext()) n = c.getInt(0);
        c.close();
        return n;
    }

    /** 答对后从错题本移除。 */
    public void removeWrong(String qid) {
        db.getWritableDatabase().delete("wrong", "i=?", new String[]{qid});
    }

    @Override
    public void resetAll() {
        super.resetAll();
        SQLiteDatabase w = db.getWritableDatabase();
        w.delete("done", null, null);
        w.delete("committed", null, null);
        w.delete("timing", null, null);
        w.delete("notes", null, null);
        w.delete("hard", null, null);
    }

    /** 默写框每敲一个字都走这里 —— 700ms 合并一次写盘。 */
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

    public void save() {
        if (savePending) return;
        writeNow();
    }

    /** 把内存里的全部状态整体刷进库（导入/迁移后用）。 */
    private void writeNow() {
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            w.delete("done", null, null);
            w.delete("committed", null, null);
            w.delete("timing", null, null);
            w.delete("notes", null, null);
            w.delete("hard", null, null);

            for (String k : done) {
                int p = k.indexOf('|');
                if (p <= 0) continue;
                ContentValues cv = new ContentValues();
                cv.put("d", k.substring(0, p));
                cv.put("i", k.substring(p + 1));
                cv.put("v", 1);
                w.insertWithOnConflict("done", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            for (String d : completed) {
                ContentValues cv = new ContentValues();
                cv.put("d", d);
                w.insertWithOnConflict("committed", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            for (java.util.Map.Entry<String, Long> e : timing.entrySet()) {
                int p = e.getKey().indexOf('|');
                if (p <= 0) continue;
                ContentValues cv = new ContentValues();
                cv.put("d", e.getKey().substring(0, p));
                cv.put("i", e.getKey().substring(p + 1));
                cv.put("ms", e.getValue());
                w.insertWithOnConflict("timing", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            for (java.util.Map.Entry<String, String> e : notes.entrySet()) {
                int p = e.getKey().indexOf('|');
                if (p <= 0) continue;
                ContentValues cv = new ContentValues();
                cv.put("d", e.getKey().substring(0, p));
                cv.put("i", e.getKey().substring(p + 1));
                cv.put("t", e.getValue());
                w.insertWithOnConflict("notes", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            for (java.util.Map.Entry<String, Integer> e : hard.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                ContentValues cv = new ContentValues();
                cv.put("i", e.getKey());
                cv.put("lv", e.getValue());
                w.insertWithOnConflict("hard", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    /** 导出 / 生成进度码前需要一份最新快照。 */
    public String exportSnapshot() {
        flush();
        return exportJson();
    }
}
