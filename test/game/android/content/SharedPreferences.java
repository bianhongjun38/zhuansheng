package android.content;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 桌面测试桩：内存版 SharedPreferences。
 *
 * ── 为什么这里必须是 interface ────────────────────────────────
 *
 * 主源码编译时挂在 android.jar 上，而 android.jar 里
 * android.content.SharedPreferences 是 **interface**。
 * GStore 的字段声明是 `private final SharedPreferences sp;`，
 * JVM 在 setMap() 这类方法里做 invokeinterface。
 *
 * 早期这个桩写成了 `public class SharedPreferences`，于是运行时
 * 加载到的是 class，而字节码要求 interface，直接抛：
 *
 *   IncompatibleClassChangeError:
 *     Found class android.content.SharedPreferences, but interface was expected
 *
 * 症状是测试跑到 S5（战斗引擎）就挂，前面几组全绿 —— 极容易
 * 被误判成"战斗逻辑有 bug"。现在桩本身是 interface，
 * 真实现放在 Impl 里，由 Context 桩返回，与真机结构一致。
 */
public interface SharedPreferences {

    Map<String, Object> ALL = new HashMap<>();
    Map<String, Map<String, Object>> STORAGE = new HashMap<>();

    /** 清空所有命名空间（测试隔离用）。 */
    static void resetAll() {
        synchronized (STORAGE) {
            ALL.clear();
            STORAGE.clear();
        }
    }

    int getInt(String key, int def);
    String getString(String key, String def);
    long getLong(String key, long def);
    boolean getBoolean(String key, boolean def);
    float getFloat(String key, float def);
    boolean contains(String key);
    Map<String, ?> getAll();
    Editor edit();
    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);
    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l);

    interface OnSharedPreferenceChangeListener {
        void onSharedPreferenceChanged(SharedPreferences sp, String key);
    }

    interface Editor {
        Editor putInt(String k, int v);
        Editor putLong(String k, long v);
        Editor putFloat(String k, float v);
        Editor putBoolean(String k, boolean v);
        Editor putString(String k, String v);
        Editor putStringSet(String k, Set<String> v);
        Editor remove(String k);
        Editor clear();
        void apply();
        boolean commit();
    }

    /** 内存实现。按 name 单例，与真机语义一致。 */
    final class Impl implements SharedPreferences {

        private final String name;

        private Impl(String name) { this.name = name; }

        public static SharedPreferences forName(String name) {
            synchronized (STORAGE) {
                Map<String, Object> ns = STORAGE.get(name);
                if (ns == null) {
                    ns = new HashMap<String, Object>();
                    STORAGE.put(name, ns);
                }
                return new Impl(name);
            }
        }

        private Map<String, Object> map() { return STORAGE.get(name); }

        public int getInt(String k, int def) {
            Object v = map().get(k);
            return v instanceof Integer ? (Integer) v : def;
        }
        public String getString(String k, String def) {
            Object v = map().get(k);
            return v instanceof String ? (String) v : def;
        }
        public long getLong(String k, long def) {
            Object v = map().get(k);
            return v instanceof Long ? (Long) v : def;
        }
        public boolean getBoolean(String k, boolean def) {
            Object v = map().get(k);
            return v instanceof Boolean ? (Boolean) v : def;
        }
        public float getFloat(String k, float def) {
            Object v = map().get(k);
            return v instanceof Float ? (Float) v : def;
        }
        public boolean contains(String k) { return map().containsKey(k); }
        public Map<String, ?> getAll() { return new HashMap<String, Object>(map()); }
        public Editor edit() { return new Ed(map()); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    }

    /** 内存版 Editor。 */
    final class Ed implements Editor {
        private final Map<String, Object> m;
        Ed(Map<String, Object> m) { this.m = m; }
        public Editor putInt(String k, int v) { m.put(k, v); return this; }
        public Editor putLong(String k, long v) { m.put(k, v); return this; }
        public Editor putFloat(String k, float v) { m.put(k, v); return this; }
        public Editor putBoolean(String k, boolean v) { m.put(k, v); return this; }
        public Editor putString(String k, String v) { m.put(k, v); return this; }
        public Editor putStringSet(String k, Set<String> v) { m.put(k, v); return this; }
        public Editor remove(String k) { m.remove(k); return this; }
        public Editor clear() { m.clear(); return this; }
        public void apply() { }
        public boolean commit() { return true; }
    }
}