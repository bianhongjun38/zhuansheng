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
    private static final int DB_VER = 4;

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

            // 英语闯关成绩。之前 right/wrong 只是 EngView 里的两个内存变量，
            // 退出界面就归零，一行都没落过盘 —— 排行榜要用的数据以前根本不存在。
            // client_rev 是幂等键（uid+startedAt+mode 的哈希），防止重复上报。
            d.execSQL("CREATE TABLE IF NOT EXISTS eng_round ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "d TEXT NOT NULL, mode INTEGER NOT NULL,"
                    + " r INTEGER NOT NULL, w INTEGER NOT NULL, ms INTEGER NOT NULL,"
                    + " started INTEGER NOT NULL, rev TEXT UNIQUE, up INTEGER NOT NULL DEFAULT 0)");

            // 英语五练的逐词进度（v4 新增）。
            //
            // 以前每进一次五练都从词库第 1 个字开始 —— open() 传 from=0，
            // next() 用 queue.get(idx++)，idx 进门就归零，一个进度字节都没存。
            // 练第 20 次和第 1 次看到的完全是同一批词，等于每次都在做无用功。
            //
            // 记的是「每个词最后一次被练到的时间 + 累计对错」，
            // 不是「学到第几个」—— 因为五练是抽题型、会跳词，
            // 用下标记录会在跳词时错位。用 w（单词）做主键天然幂等。
            d.execSQL("CREATE TABLE IF NOT EXISTS eng_word ("
                    + "w TEXT NOT NULL PRIMARY KEY,"
                    + " r INTEGER NOT NULL DEFAULT 0, wc INTEGER NOT NULL DEFAULT 0,"
                    + " last INTEGER NOT NULL DEFAULT 0)");

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
            // v2 → v3：加英语闯关成绩表。只加表，不动已有进度。
            if (o < 3) {
                d.execSQL("CREATE TABLE IF NOT EXISTS eng_round ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "d TEXT NOT NULL, mode INTEGER NOT NULL,"
                        + " r INTEGER NOT NULL, w INTEGER NOT NULL, ms INTEGER NOT NULL,"
                        + " started INTEGER NOT NULL, rev TEXT UNIQUE, up INTEGER NOT NULL DEFAULT 0)");
            }
            // v3 → v4：加五练逐词进度表。只加表，不动已有进度。
            // 老用户升级后这张表是空的，第一次练会从头开始 —— 那正好是
            // 修复前的行为，不会比现在更差，之后才开始真正记住练到哪。
            if (o < 4) {
                d.execSQL("CREATE TABLE IF NOT EXISTS eng_word ("
                        + "w TEXT NOT NULL PRIMARY KEY,"
                        + " r INTEGER NOT NULL DEFAULT 0, wc INTEGER NOT NULL DEFAULT 0,"
                        + " last INTEGER NOT NULL DEFAULT 0)");
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
            // 游标必须显式关闭。链式 .getCount() 用完就丢，
            // 每次冷启动都泄漏一个 Cursor + 文件描述符，
            // GC 时打 "Finalizing a Cursor that has not been deactivated or closed"。
            android.database.Cursor c0 = db.getReadableDatabase().rawQuery(
                    "SELECT v FROM kv WHERE k='migrated'", null);
            boolean migrated;
            try {
                migrated = c0.getCount() > 0;
            } finally {
                c0.close();
            }
            if (migrated) return;   // 已经迁过
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

    // ── 通用键值（更新忽略版本、下载偏好等）──────────────────
    // 放 kv 表而不是另开 SharedPreferences：偏好已经分散在
    // zsb_state / 各自 SP 里，再开一个新的只会让"这设置存哪了"更难查。

    /** 读一个字符串设置，没有就返回 def。 */
    public String kvGet(String k, String def) {
        try {
            android.database.Cursor c = db.getReadableDatabase().rawQuery(
                    "SELECT v FROM kv WHERE k=?", new String[]{k});
            String v = null;
            if (c.moveToFirst()) v = c.getString(0);
            c.close();
            return v == null ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    /** 写一个字符串设置。value 为 null 表示删除。 */
    public void kvSet(String k, String v) {
        try {
            if (v == null) {
                db.getWritableDatabase().execSQL(
                        "DELETE FROM kv WHERE k=?", new Object[]{k});
            } else {
                db.getWritableDatabase().execSQL(
                        "INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)", new Object[]{k, v});
            }
        } catch (Exception ignored) { }
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
    /**
     * 覆写 StateLogic 的导入：**导入后必须立刻落库**。
     *
     * 基类只把数据填进内存集合（done / completed / 笔记），不碰 SQLite。
     * 而 UI 上勾单个任务走的是 setDone() 这种单行 INSERT/DELETE，
     * 也不会触发全量 writeNow()。于是「导入进度码」之后只要进程被杀、
     * 或者用户先勾了一个任务的再重启，导入的内容就整批丢了 ——
     * 但当时 Toast 明明写着"已导入 N 条勾选"。
     */
    @Override
    public int importJson(String raw) throws Exception {
        int n = super.importJson(raw);
        writeNow();
        return n;
    }

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

    // ── 英语闯关成绩 ──────────────────────────────────────

    /** 一轮成绩。对应 eng_round 一行。 */
    public static class EngRound {
        public long id;
        public String day = "";
        public int mode;
        public int right;
        public int wrong;
        public long ms;
        public long started;
        public String rev = "";
        public boolean uploaded;
        public int total() { return right + wrong; }
        public int rate() { return total() == 0 ? 0 : right * 100 / total(); }
    }

    /**
     * 记一轮成绩。
     *
     * 幂等：rev 有 UNIQUE 约束，同一个 rev 重复调用不会写第二条。
     * rev 由调用方用 uid+started+mode 算，这样「上报成功了但没收到响应、
     * 用户又重进了一次」不会在榜上算两次。
     */
    public void addEngRound(EngRound r) {
        if (r == null) return;
        ContentValues cv = new ContentValues();
        cv.put("d", r.day);
        cv.put("mode", r.mode);
        cv.put("r", r.right);
        cv.put("w", r.wrong);
        cv.put("ms", r.ms);
        cv.put("started", r.started);
        cv.put("rev", r.rev);
        cv.put("up", r.uploaded ? 1 : 0);
        db.getWritableDatabase().insertWithOnConflict(
                "eng_round", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** 读成绩。sinceDay 非空时只取那天及以后（按字符串比较，ISO 日期正好可比）。 */
    public List<EngRound> engRounds(String sinceDay) {
        List<EngRound> out = new ArrayList<>();
        Cursor c = sinceDay == null || sinceDay.isEmpty()
                ? db.getReadableDatabase().rawQuery(
                        "SELECT id,d,mode,r,w,ms,started,rev,up FROM eng_round ORDER BY started DESC", null)
                : db.getReadableDatabase().rawQuery(
                        "SELECT id,d,mode,r,w,ms,started,rev,up FROM eng_round WHERE d>=? ORDER BY started DESC",
                        new String[]{sinceDay});
        while (c.moveToNext()) {
            EngRound r = new EngRound();
            r.id = c.getLong(0);
            r.day = c.getString(1);
            r.mode = c.getInt(2);
            r.right = c.getInt(3);
            r.wrong = c.getInt(4);
            r.ms = c.getLong(5);
            r.started = c.getLong(6);
            r.rev = c.getString(7) == null ? "" : c.getString(7);
            r.uploaded = c.getInt(8) != 0;
            out.add(r);
        }
        c.close();
        return out;
    }

    /** 还没传上去的成绩。 */
    public List<EngRound> pendingEngRounds() {
        List<EngRound> out = new ArrayList<>();
        for (EngRound r : engRounds(null)) if (!r.uploaded) out.add(r);
        return out;
    }

    /** 标记成已上传。按 rev 定位，不依赖自增 id。 */
    public void markEngUploaded(String rev) {
        if (rev == null || rev.isEmpty()) return;
        ContentValues cv = new ContentValues();
        cv.put("up", 1);
        db.getWritableDatabase().update("eng_round", cv, "rev=?", new String[]{rev});
    }

    /** 今日（或指定日）汇总，给「我的正确率」用。 */
    public int[] engDaily(String day) {
        // [轮数, 对题, 错题, 毫秒]
        int[] out = new int[4];
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(*), SUM(r), SUM(w), SUM(ms) FROM eng_round WHERE d=?", new String[]{day});
        if (c.moveToNext()) {
            out[0] = c.getInt(0);
            out[1] = c.getInt(1);
            out[2] = c.getInt(2);
            out[3] = (int) Math.min(Integer.MAX_VALUE, c.getLong(3));
        }
        c.close();
        return out;
    }

    // ══ 英语五练的逐词进度（v4）═════════════════════════════
    //
    // 为什么要有这一块：
    //   修复前每进一次五练都从词库第 1 个字开始，练第 N 次和第 1 次
    //   看到的完全是同一批词。用户说的是"完全没必要，每次进去都是从 a 开始"。
    //
    // 为什么按单词记而不是记下标：
    //   五练是抽题型、会跳词（某个词出不了题就 idx++ 跳过）。
    //   记下标会在跳词时错位，记单词本身永远对得上。

    /** 单词进度：练过几次、对几次、最后一次是什么时候。 */
    public static class WordProg {
        public String w = "";
        public int right;      // 累计答对次数
        public int wrong;      // 累计答错次数
        public long last;      // 最后一次练到的时间戳（毫秒）
        public int seen() { return right + wrong; }
    }

    /**
     * 读出全部单词进度。
     *
     * 一次全读（词库几千条，SQLite 全表扫描是毫秒级），
     * 比每道题查一次数据库快得多，也不用管游标泄漏。
     */
    public java.util.Map<String, WordProg> engWordProgress() {
        java.util.Map<String, WordProg> out = new java.util.HashMap<>();
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT w,r,wc,last FROM eng_word", null);
        while (c.moveToNext()) {
            WordProg p = new WordProg();
            p.w = c.getString(0);
            p.right = c.getInt(1);
            p.wrong = c.getInt(2);
            p.last = c.getLong(3);
            if (p.w != null) out.put(p.w, p);
        }
        c.close();
        return out;
    }

    /**
     * 记一道题的结果。
     *
     * 用「先查再写」而不是 SQL 里的 r=r+1：
     * 后者一条语句就够，但要求 API 24+ 的 upsert 语法，
     * 本项目 minSdk 23，得走兼容路径。这里并发压力为 0（一个人在答题），
     * 读改写完全够用，代码也更好读。
     */
    public void recordEngWord(String word, boolean correct, long when) {
        if (word == null || word.isEmpty()) return;
        SQLiteDatabase w = db.getWritableDatabase();
        Cursor c = w.rawQuery("SELECT r,wc FROM eng_word WHERE w=?", new String[]{word});
        int r = 0, wc = 0;
        boolean exists = c.moveToNext();
        if (exists) { r = c.getInt(0); wc = c.getInt(1); }
        c.close();
        if (correct) r++; else wc++;
        ContentValues cv = new ContentValues();
        cv.put("w", word);
        cv.put("r", r);
        cv.put("wc", wc);
        cv.put("last", when);
        w.insertWithOnConflict("eng_word", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * 进度总览：[已练词数, 总词数]。
     *
     * 首页要显示"五练进度 128/3500"，没有这个数字用户根本不知道
     * 自己练到哪了 —— 这也是"每次都从头"最让人恼火的地方：
     * 看不到进度，就没有推进感。
     */
    public int engWordTouched() {
        Cursor c = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM eng_word", null);
        int n = 0;
        if (c.moveToNext()) n = c.getInt(0);
        c.close();
        return n;
    }

    /** 清空五练进度（"重新开始"用）。 */
    public void resetEngWordProgress() {
        db.getWritableDatabase().execSQL("DELETE FROM eng_word");
    }
}