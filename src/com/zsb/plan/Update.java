package com.zsb.plan;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * App 内更新。
 *
 * 流程：启动后静默查 GitHub Release 最新版 → 有新版才弹窗 →
 * 下载 APK 到 cache → 拉起系统安装器覆盖安装。
 *
 * 版本比较用 versionName（1.1.0 这种）换算成同单位整数，
 * 不用 versionCode —— 因为 versionCode 是构建计数（10、11、12），
 * 和 tag 里的 1.1.0 不是一套单位。混着比会永远判定「有新版」，
 * 每次启动都弹框，那比没有更新功能更烦。
 *
 * 签名必须始终用同一把钥匙（指纹 05:24:6E:...），否则系统拒绝覆盖，
 * 只能卸载重装 —— 那样进度就没了。
 */
public class Update {

    private static final String API =
            "https://api.github.com/repos/bianhongjun38/zhuansheng/releases/latest";

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public Update(Activity a) {
        this.act = a;
    }

    /** 启动时调用。silent=true 时只在有新版本才提示。 */
    public void check(final boolean silent) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    final JSONObject rel = fetchLatest();
                    if (rel == null) {
                        if (!silent) toastOnUi("检查失败：网络不通");
                        return;
                    }
                    final String tag = rel.optString("tag_name", "");
                    final int remoteCode = parseVer(tag);
                    final int localCode = parseVer(localVersionName());
                    if (remoteCode == 0) {
                        if (!silent) toastOnUi("已是最新版本 v" + localVersionName());
                        return;
                    }
                    if (remoteCode <= localCode) {
                        if (!silent) toastOnUi("已是最新版本 v" + localVersionName());
                        return;
                    }
                    final String apkUrl = findApk(rel);
                    if (apkUrl == null) {
                        if (!silent) toastOnUi("新版没有找到安装包");
                        return;
                    }
                    final String ver = tag.replaceFirst("^[vV]", "");
                    final String notes = rel.optString("body", "");
                    ui.post(new Runnable() {
                        public void run() { askUpdate(ver, notes, apkUrl); }
                    });
                } catch (Exception e) {
                    if (!silent) toastOnUi("检查失败：" + e.getClass().getSimpleName());
                }
            }
        }).start();
    }

    private JSONObject fetchLatest() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "zsb-plan");
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
            r.close();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** "1.1.0" / "v1.1.0" → 10100。解析不了返回 0。 */
    static int parseVer(String s) {
        if (s == null) return 0;
        try {
            String t = s.trim();
            if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1);
            // 去掉可能的后缀，如 1.1.0-beta
            int cut = t.length();
            for (int i = 0; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (ch != '.' && (ch < '0' || ch > '9')) { cut = i; break; }
            }
            t = t.substring(0, cut);
            if (t.isEmpty()) return 0;
            String[] p = t.split("\\.");
            int maj = p.length > 0 ? Integer.parseInt(p[0]) : 0;
            int min = p.length > 1 ? Integer.parseInt(p[1]) : 0;
            int pat = p.length > 2 ? Integer.parseInt(p[2]) : 0;
            return maj * 10000 + min * 100 + pat;
        } catch (Exception e) {
            return 0;
        }
    }

    private String localVersionName() {
        try {
            String n = act.getPackageManager()
                    .getPackageInfo(act.getPackageName(), 0).versionName;
            return n == null ? "0.0.0" : n;
        } catch (Exception e) {
            return "0.0.0";
        }
    }

    /** 给设置页显示用：本机版本号。 */
    public String currentVersion() {
        return localVersionName();
    }

    /**
     * 查询线上最新版本号，回调在主线程。
     * 失败时给出 null，调用方负责显示「查询失败」。
     *
     * 与 check() 的区别：这个**只查不弹窗**，用于设置页按钮文案，
     * 让用户一眼看到「本机 1.2.0 / 线上 1.2.1」。
     */
    public void queryLatest(final boolean silent, final OnLatest cb) {
        new Thread(new Runnable() {
            public void run() {
                JSONObject rel = fetchLatest();
                final String tag;
                final int code;
                if (rel == null) {
                    tag = null;
                    code = 0;
                } else {
                    tag = rel.optString("tag_name", "").replaceFirst("^[vV]", "");
                    code = parseVer(tag);
                }
                ui.post(new Runnable() {
                    public void run() {
                        if (cb != null) cb.onLatest(tag, code);
                        else if (!silent) {
                            toastOnUi(tag == null ? "网络不通，稍后再试"
                                    : "线上最新 v" + tag);
                        }
                    }
                });
            }
        }).start();
    }

    /** queryLatest 的回调。tag 为 null 表示网络失败。 */
    public interface OnLatest {
        void onLatest(String tag, int code);
    }

    private String findApk(JSONObject rel) {
        try {
            JSONArray arr = rel.getJSONArray("assets");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                String name = a.optString("name", "");
                if (name.endsWith(".apk")) return a.optString("browser_download_url", null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void askUpdate(String ver, String notes, final String url) {
        String body = "当前 v" + localVersionName() + "，最新 v" + ver + "。";
        if (notes != null && notes.trim().length() > 0) {
            String n = notes.trim();
            body += "\n\n" + (n.length() > 500 ? n.substring(0, 500) + "…" : n);
        }
        body += "\n\n覆盖安装不会丢失学习进度。"
                + "\n如果提示「不允许安装」，在系统设置里允许本应用安装未知应用即可。";

        new AlertDialog.Builder(act)
                .setTitle("发现新版本 v" + ver)
                .setMessage(body)
                .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { download(url); }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    private void download(final String url) {
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setTitle("正在下载更新");
        pd.setMessage("0%");
        pd.setCancelable(false);
        pd.show();

        new Thread(new Runnable() {
            public void run() {
                final File out = new File(act.getCacheDir(), "update.apk");
                HttpURLConnection c = null;
                try {
                    // Release 资产会 302 到 objects.githubusercontent.com，
                    // 用 setInstanceFollowRedirects 在部分机型上会丢 redirect，
                    // 这里手动跟一次。
                    String target = url;
                    for (int hop = 0; hop < 5; hop++) {
                        c = (HttpURLConnection) new URL(target).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(60000);
                        c.setInstanceFollowRedirects(false);
                        c.setRequestProperty("User-Agent", "zsb-plan");
                        int sc = c.getResponseCode();
                        if (sc == 301 || sc == 302 || sc == 303 || sc == 307 || sc == 308) {
                            String loc = c.getHeaderField("Location");
                            c.disconnect();
                            if (loc == null) throw new Exception("重定向无地址");
                            target = loc;
                            continue;
                        }
                        break;
                    }
                    if (c == null) throw new Exception("连接失败");
                    if (c.getResponseCode() != 200) {
                        throw new Exception("HTTP " + c.getResponseCode());
                    }
                    int total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    FileOutputStream fo = new FileOutputStream(out);
                    byte[] buf = new byte[64 * 1024];
                    int n, done = 0, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100L / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                ui.post(new Runnable() {
                                    public void run() { pd.setMessage(pct + "%"); }
                                });
                            }
                        }
                    }
                    fo.flush();
                    fo.close();
                    in.close();

                    // 下载完整性：APK 至少几 MB，太小说明拿到的是错误页
                    if (out.length() < 1024 * 1024) {
                        throw new Exception("文件不完整（" + out.length() + " 字节）");
                    }

                    ui.post(new Runnable() {
                        public void run() {
                            pd.dismiss();
                            install(out);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            pd.dismiss();
                            toastOnUi("下载失败：" + e.getMessage());
                        }
                    });
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    private void install(File apk) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            Uri u;
            if (Build.VERSION.SDK_INT >= 24) {
                // 7.0+ 禁止 file:// 跨应用，用自家 ApkProvider 提供 content://
                u = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + ApkProvider.NAME);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                u = Uri.fromFile(apk);
            }
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Exception e) {
            toastOnUi("打开安装器失败：" + e.getClass().getSimpleName()
                    + "\n可在浏览器打开仓库手动下载。");
        }
    }

    private void toastOnUi(final String s) {
        ui.post(new Runnable() {
            public void run() {
                android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }
}
