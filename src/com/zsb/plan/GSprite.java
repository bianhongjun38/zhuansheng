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
 *   · actions 里给每个动作的起始帧与帧数，idle/move 循环，其它播一次
 *
 * 为什么不逐帧存 PNG：810 张碎图在 APK 里会产生大量 zip 条目，
 * 安装时解压开销大；打成图集后只有 12 个文件，2.35MB。
 */
public class GSprite extends View {

    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    /** sprites.json 解析结果缓存。原实现每次 load 都重新读文件并解析，
     *  进战斗时会有一次明显卡顿（JSON 5.5KB + 两次 IO）。 */
    private static org.json.JSONObject INDEX;

    private Bitmap sheet;
    private int canvas = 128, cols = 10, frame = 0, total = 0;
    private JSONObject actions;
    private String action = "idle";
    private int actionStart = 0, actionCount = 0;
    private boolean loop = true;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect srcR = new Rect(), dstR = new Rect();
    private final int fps;

    public GSprite(Context c) {
        super(c);
        fps = 12;
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

    /** 播放某个动作。 */
    public void play(String a) {
        action = a;
        frame = 0;
        actionStart = 0; actionCount = 0; loop = true;
        if (actions != null) {
            JSONObject o = actions.optJSONObject(a);
            if (o != null) {
                actionStart = o.optInt("start", 0);
                actionCount = o.optInt("count", 0);
                loop = o.optBoolean("loop", false);
            }
        }
        if (actionCount <= 0) { actionStart = 0; actionCount = total; }
        invalidate();
        tick();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (actionCount <= 0) return;
            frame++;
            if (frame >= actionCount) {
                if (loop) frame = 0;
                else { frame = actionCount - 1; invalidate(); return; }
            }
            invalidate();
            h.postDelayed(this, 1000L / fps);
        }
    };

    private void tick() {
        h.removeCallbacks(ticker);
        if (actionCount > 1) h.postDelayed(ticker, 1000L / fps);
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