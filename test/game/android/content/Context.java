package android.content;

import java.io.InputStream;

/**
 * 桌面测试桩：只实现词汇塔纯逻辑用到的两个方法。
 * getAssets() 返回的 AssetManager 用系统属性 zsb.assets 指向真实 assets 目录。
 */
public class Context {

    public static final int MODE_PRIVATE = 0;
    public static final int MODE_WORLD_READABLE = 1;

    public Context getApplicationContext() {
        return this;
    }

    public android.content.res.AssetManager getAssets() {
        return new android.content.res.AssetManager();
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return SharedPreferences.Impl.forName(name);
    }

    /** 兼容个别代码路径直接 open 流。 */
    public InputStream open(String assetPath) throws java.io.IOException {
        return new android.content.res.AssetManager().open(assetPath);
    }
}
