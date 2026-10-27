package com.zsb.plan;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 精灵动画视图。直接读 assets/game/sprites/ 下的图集与 JSON。
 *
 * 图集规格（由 pack_sprites_final.py 生成）：
 *   · 每帧 128×128，按 cols 排布，第 i 帧位置 = (i%cols, i/cols)
 *   · actions 里给每个动作的起始帧、帧数与 durationMs
 *
 * 为什么不逐帧存 PNG：810 张碎图在 APK 里会产生大量 zip 条目，
 * 安装时解压开销大；打成图集后只有 12 个文件，2.35MB。
 *
 * 这一版修掉三个实际 bug：
 * 1. 非循环动作播完卡在最后一帧，不回待机 —— 表现就是怪物打一下之后
 *    再也不动，看起来"只有一个待机动作"。
 * 2. 帧间隔写死 12fps，JSON 里的 durationMs 完全没用上。attack 设计
 *    900ms、hurt 设计 420ms，实播都是 83ms/帧，节奏全错。
 * 3. tick() 里 actionCount<=1 就不启动定时器，单帧动作永远停住。
 */
public class GSprite extends View {

    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    /** sprites.json 解析结果缓存。原实现每次 load 都重新读文件并解析，
     *  进战斗时会有一次明显卡顿（JSON 5.5KB + 两次 IO）。 */
    private static org.json.JSONObject INDEX;

    private Bitmap sheet;
    private int canvas = 128, cols = 10, frame = 0, total = 0;
    private JSONObject actions;

    /** 当前动作。 */
    private String action = "idle";
    /** 非循环动作播完后回到哪个动作；null 表示停在最后一帧（死亡）。 */
    private String fallback = "idle";
    private int actionStart = 0, actionCount = 0;
    private boolean loop = true;
    /** 当前动作每帧停留毫秒，由 durationMs / count 算出。 */
    private long frameMs = 1000L / 12;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect srcR = new Rect(), dstR = new Rect();

    public GSprite(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** 加载一个角色。name 是 sprites.json 里的键（hero / mists …）。 */
    public void load(String name) {
        if (name == null || name.isEmpty()) return;
        String key = "sheet:" + name;
        Bitmap b = CACHE.get(key);
        if (b == null) {
            try {
                InputStream is = getContext().getAssets()
                        .open("game/sprites/" + name + ".png");
                b = BitmapFactory.decodeStream(is, null, opts());
                is.close();
                if (b != null) CACHE.put(key, b);
            } catch (Exception e) {
                android.util.Log.e("GSprite", "读不到 " + name, e);
            }
        }
        sheet = b;

        try {
            if (INDEX == null) {
                INDEX = new JSONObject(GameData.read(getContext(), "game/sprites/sprites.json"));
            }
            JSONObject one = INDEX.optJSONObject(name);
            if (one != null) {
                canvas = one.optInt("canvas", 128);
                cols = one.optInt("cols", 10);
                total = one.optInt("total", 1);
                actions = one.optJSONObject("actions");
            }
        } catch (Exception e) {
            android.util.Log.e("GSprite", "sprites.json 读取失败", e);
        }
        play("idle");
    }

    private static BitmapFactory.Options opts() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        // 图集本身已经缩到 128，不需要再降采样；用低内存格式
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inScaled = false;
        return o;
    }

    /** 播放某个动作，播完自动回待机。 */
    public void play(String a) {
        play(a, "idle");
    }

    /**
     * 播放某个动作，非循环动作播完后回到 ret。
     *
     * ret 传 null 表示停在最后一帧 —— 死亡就这么用，不回到待机。
     */
    public void play(String a, String ret) {
        if (a == null || a.isEmpty()) a = "idle";
        action = a;
        fallback = ret;
        frame = 0;
        actionStart = 0;
        actionCount = 0;
        loop = true;
        frameMs = 1000L / 12;

        if (actions != null) {
            JSONObject o = actions.optJSONObject(a);
            if (o != null) {
                actionStart = o.optInt("start", 0);
                actionCount = o.optInt("count", 0);
                loop = o.optBoolean("loop", false);
                int dur = o.optInt("durationMs", 0);
                // durationMs 是整段动作的总时长，不是单帧。
                if (dur > 0 && actionCount > 0) {
                    frameMs = Math.max(16, dur / actionCount);
                }
            }
        }
        if (actionCount <= 0) { actionStart = 0; actionCount = total; }

        invalidate();
        restartTicker();
    }

    /** 当前动作名。 */
    public String current() { return action; }

    /** 是否正在播非循环动作（攻击/受伤/施法/死亡）。 */
    public boolean busy() { return !loop; }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (actionCount <= 0) return;
            frame++;
            if (frame >= actionCount) {
                if (loop) {
                    frame = 0;
                } else {
                    frame = actionCount - 1;
                    invalidate();
                    // 播完回待机；fallback 为 null 就停在最后一帧（死亡）
                    if (fallback != null && !fallback.equals(action)) {
                        play(fallback);
                    }
                    return;
                }
            }
            invalidate();
            h.postDelayed(this, frameMs);
        }
    };

    private void restartTicker() {
        h.removeCallbacks(ticker);
        if (actionCount > 1) h.postDelayed(ticker, frameMs);
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (sheet == null || sheet.isRecycled()) return;
        int idx = actionStart + frame;
        if (idx < 0) idx = 0;
        if (idx >= total && total > 0) idx = total - 1;

        int sx = (idx % cols) * canvas;
        int sy = (idx / cols) * canvas;
        srcR.set(sx, sy, sx + canvas, sy + canvas);

        // 等比铺满控件，底边对齐
        int vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;
        float s = Math.min(vw / (float) canvas, vh / (float) canvas);
        int w = Math.round(canvas * s), hh = Math.round(canvas * s);
        int left = (vw - w) / 2;
        int top = vh - hh;
        dstR.set(left, top, left + w, top + hh);

        cv.drawBitmap(sheet, srcR, dstR, paint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        h.removeCallbacks(ticker);
    }
}
