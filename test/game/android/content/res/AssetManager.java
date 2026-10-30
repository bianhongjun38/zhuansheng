package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 桌面测试桩：把 assets 路径映射到磁盘目录。
 * 根目录由系统属性 zsb.assets 指定（默认 "assets"）。
 */
public class AssetManager {

    private static File dir() {
        String root = System.getProperty("zsb.assets", "assets");
        return new File(root);
    }

    public InputStream open(String name) throws IOException {
        File f = new File(dir(), name);
        if (!f.exists()) throw new IOException("asset not found: " + name);
        return new FileInputStream(f);
    }

    public InputStream open(String name, int accessMode) throws IOException {
        return open(name);
    }

    public String[] list(String dir) throws IOException {
        File d = new File(dir(), dir);
        String[] out = d.list();
        return out == null ? new String[0] : out;
    }
}
