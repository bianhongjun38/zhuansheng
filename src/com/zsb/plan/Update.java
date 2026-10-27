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
 * 为什么要覆盖安装而不是跳浏览器下载：
 *  · 手机上次次要手动找下载目录、点开、允许未知来源，太麻烦
 *  · 装好后进度数据在 SharedPreferences 里，覆盖安装不会丢
 *
 * 签名必须始终用同一把钥匙（指纹 05:24:6E:...），否则系统拒绝覆盖，
 * 只能卸载重装 —— 那样进度就没了。所以这个类的错误提示里要讲清楚。
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
                    final int remoteCode = parseCode(rel.optString("tag_name", ""));
                    final int localCode = localVersionCode();
                    if (remoteCode <= localCode) {
                        if (!silent) toastOnUi("已是最新版本 v" + localVersionName());
                        return;
                    }
                    final String apkUrl = findApk(rel);
                    if (apkUrl == null) {
                        if (!silent) toastOnUi("新版没有找到安装包");
                        return;
                    }
                    final String notes = rel.optString("body", "");
                    ui.post(new Runnable() {
                        public void run() { askUpdate(remoteCode, notes, apkUrl); }
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

    /** tag 形如 v1.0.3 → 10003；版本 1.0.0 → 10000。 */
    private int parseCode(String tag) {
        try {
            String s = tag.trim();
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
            String[] p = s.split("\\.");
            int maj = p.length > 0 ? Integer.parseInt(p[0].replaceAll("\\D", "0")) : 0;
            int min = p.length > 1 ? Integer.parseInt(p[1].replaceAll("\\D", "0")) : 0;
            int pat = p.length > 2 ? Integer.parseInt(p[2].replaceAll("\\D", "0")) : 0;
            return maj * 10000 + min * 100 + pat;
        } catch (Exception e) {
            return 0;
        }
    }

    private int localVersionCode() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private String localVersionName() {
        try {
            return act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
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

    private void askUpdate(final int code, String notes, final String url) {
        String body = "当前 v" + localVersionName() + "，最新 v" + code / 10000 + "."
                + (code / 100) % 100 + "." + code % 100;
        if (notes != null && notes.trim().length() > 0) {
            body += "\n\n" + (notes.length() > 400 ? notes.substring(0, 400) + "…" : notes);
        }
        body += "\n\n覆盖安装不会丢失学习进度。";

        new AlertDialog.Builder(act)
                .setTitle("发现新版本")
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
                File out = new File(act.getCacheDir(), "update.apk");
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(60000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "zsb-plan");
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
                    fo.close();
                    in.close();

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
                            toastOnUi("下载失败：" + e.getClass().getSimpleName());
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
