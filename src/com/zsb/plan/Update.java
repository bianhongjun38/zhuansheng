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

    /**
     * 更新**主通道**：用户自有域名（Cloudflare Pages 托管，已绑自定义域名）。
     *
     * 2026-10-27 直连实测（不带代理，模拟手机）：
     *   app.xxjcloudns.kdns.fr/update.json   HTTP 200
     *   app.xxjcloudns.kdns.fr/*.apk          HTTP 206，可断点续传，速度 288KB/s~4.9MB/s
     *   zsb-update.pages.dev                 HTTP 200 / 810ms（兜底）
     *   github.com                           HTTP 000 / 超时 12s   ← 被阻断
     *   api.github.com                       HTTP 200 / 321ms     ← 通，二级兜底
     *
     * ── 2026-10-28 更正（之前的「账号级 1101 故障」是误判）─────────
     *
     * 实测结果：
     *   *.workers.dev                        → DNS 污染，每次解析出不同 IP
     *                                          （202.160.129.6 / 103.73.161.52
     *                                            / 74.86.142.55），TCP 443 全部超时
     *   cloudflare.com / pages.dev / 自定义域名 → 全部正常
     *   github.com                           → 仍被阻断
     *
     * 也就是说 Worker 运行时一直是好的，坏的是 workers.dev 这个后缀的路由。
     * 所以现在把 Worker 绑到自有域名 dl.xxjcloudns.kdns.fr 上，
     * APK 本体作为 Worker 静态资源直出（不再 302 去 github.com，那条路下不动）。
     * 这样也顺手绕开了 Pages 单文件 25 MiB 的上限 —— APK 已经 20.13 MB 了。
     *
     * 实测：dl 域名下 21,109,913 B 的包 8.5 秒下完，2.37 MB/s，文件头 PK。
     */
    private static final String PRIMARY_JSON =
            "https://dl.xxjcloudns.kdns.fr/update.json";

    /** 旧主通道（Cloudflare Pages），保留做第二兜底。 */
    private static final String PAGES_PRIMARY_JSON =
            "https://app.xxjcloudns.kdns.fr/update.json";

    /**
     * 教材 PDF 的下载基地址。
     *
     * 三本教材共 11.7 MB，占安装包 60%，而 Cloudflare Pages 和
     * Workers Static Assets 的**单文件上限都是 25 MiB**（2026-10-28 查文档确认），
     * APK 20.13 MB 看着还够，但再塞两本练习册就顶死了。
     * 所以 PDF 从安装包里拿掉，改成第一次看原图时按需拉这一本。
     * 路径与 Worker 静态资源一致：/<bookId>.pdf
     */
    public static final String BOOK_BASE = "https://dl.xxjcloudns.kdns.fr/books/";

    private static final String PAGES_JSON =
            "https://zsb-update.pages.dev/update.json";

    /** 兜底：Pages 挂了时退到 GitHub API（注意是 api 域名，不是 github.com）。 */
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
                    final Latest lt = fetchLatestAny();
                    if (lt == null) {
                        if (!silent) toastOnUi("检查失败：网络不通");
                        return;
                    }
                    final int remoteCode = lt.code;
                    final int localCode = parseVer(localVersionName());
                    if (remoteCode == 0) {
                        if (!silent) toastOnUi("已是最新版本 v" + localVersionName());
                        return;
                    }
                    if (remoteCode <= localCode) {
                        if (!silent) toastOnUi("已是最新版本 v" + localVersionName());
                        return;
                    }
                    if (lt.apkUrl == null) {
                        if (!silent) toastOnUi("新版没有找到安装包");
                        return;
                    }
                    ui.post(new Runnable() {
                        public void run() { askUpdate(lt.ver, lt.notes, lt.apkUrl); }
                    });
                } catch (Exception e) {
                    if (!silent) toastOnUi("检查失败：" + e.getClass().getSimpleName());
                }
            }
        }).start();
    }

    /** 统一后的「最新版」信息，两个来源都归一到这个结构。 */
    static class Latest {
        String ver;
        int code;
        String notes;
        String apkUrl;
    }

    /**
     * 三个来源都查一遍，取**版本号最高**的那个，而不是"第一个能读到就用"。
     *
     * 为什么必须改成取最大值：
     * 前两条通道读的是我们自己域名上的 update.json —— 一份要手动跑
     * 发版pages.py 才会更新的静态文件。忘了跑，它就永远停在旧版本。
     * 旧实现里它排在第一位且"读到即返回"，于是：
     *   update.json 停在 1.2.3，GitHub 上已经是 1.4.1，
     *   本机 1.4.1 → 10203 <= 10401 → 判定「已是最新版本」。
     * 明明线上有新版、明明 GitHub 通道完全正常，却被一份过期静态文件
     * 整个挡住。这不是"某个源挂了"，是取值策略错了。
     *
     * 现在任何一份过期都只是被忽略，不会压住更高版本。
     *
     * 同版本时优先保留带下载地址的那份：update.json 可能在 APK 超过
     * Pages 单文件上限时把 apk 置为 null，那时要能补上 GitHub 的地址。
     */
    private Latest fetchLatestAny() {
        // 探测顺序保持"自有域名优先"——只是不再提前返回，
        // 三份都要拿到手才能比出最高的那个。
        Latest[] all = {
                fetchManifest(PRIMARY_JSON),        // dl.xxjcloudns.kdns.fr（Worker 直出）
                fetchManifest(PAGES_PRIMARY_JSON),  // app.xxjcloudns.kdns.fr（Pages）
                fetchManifest(PAGES_JSON),          // zsb-update.pages.dev
                fetchFromGithub(),                  // api.github.com
        };

        Latest best = null;
        for (Latest lt : all) {
            if (lt == null || lt.code == 0) continue;
            if (best == null) { best = lt; continue; }
            if (lt.code > best.code) {
                best = lt;
            } else if (lt.code == best.code
                    && best.apkUrl == null && lt.apkUrl != null) {
                // 版本一样但这份能下载，补上地址
                best.apkUrl = lt.apkUrl;
            }
        }
        return best;
    }

    /** 读一份 update.json 清单。 */
    private Latest fetchManifest(String src) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(src).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "zsb-plan");
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return null;
            String s = readAll(c.getInputStream());
            JSONObject o = new JSONObject(s);
            if (!o.optBoolean("ok", false)) return null;
            String v = o.optString("versionName", "");
            int code = o.optInt("versionInt", parseVer(v));
            if (code == 0) return null;
            Latest lt = new Latest();
            lt.ver = v;
            lt.code = code;
            lt.notes = o.optString("notes", "");
            JSONObject apk = o.optJSONObject("apk");
            if (apk != null) lt.apkUrl = apk.optString("url", null);
            return lt;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 兜底通道：GitHub Release。 */
    private Latest fetchFromGithub() {
        JSONObject rel = fetchLatest();
        if (rel == null) return null;
        String tag = rel.optString("tag_name", "");
        int code = parseVer(tag);
        if (code == 0) return null;
        Latest lt = new Latest();
        lt.ver = tag.replaceFirst("^[vV]", "");
        lt.code = code;
        lt.notes = rel.optString("body", "");
        lt.apkUrl = findApk(rel);
        return lt;
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String ln;
        while ((ln = r.readLine()) != null) sb.append(ln);
        r.close();
        return sb.toString();
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
                final Latest lt = fetchLatestAny();
                final String tag = lt == null ? null : lt.ver;
                final int code = lt == null ? 0 : lt.code;
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

    /**
     * 取安装包的下载地址。
     *
     * **必须用 api.github.com 的资产端点，不能用 browser_download_url。**
     *
     * 2026-10-27 实测（不带代理，模拟手机处境）：
     *   api.github.com                       HTTP 200   321ms
     *   github.com                           HTTP 000   超时 12s   ← 卡死在这里
     *   release-assets.githubusercontent.com HTTP 404   388ms（域名可达）
     *
     * browser_download_url 是 https://github.com/.../releases/download/...，
     * 也就是走被阻断的那个域名 —— 版本能查到、但一按下载就永远转圈。
     *
     * api.github.com/repos/.../releases/assets/{id} 带 Accept: octet-stream
     * 会 302 到 release-assets.githubusercontent.com（那个域名直连是通的），
     * 实测完整下载 745 KB/s、1.4 秒拿 1 MB。
     */
    private String findApk(JSONObject rel) {
        try {
            JSONArray arr = rel.getJSONArray("assets");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                String name = a.optString("name", "");
                if (!name.endsWith(".apk")) continue;
                String api = a.optString("url", "");
                if (api.startsWith("https://api.github.com/")) return api;
                // 兜底：万一副本接口没给 url，退回老地址（可能很慢但不至于不可用）
                return a.optString("browser_download_url", null);
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
                    // 下载链路说明见 findApk()：起点是 api.github.com 的资产端点，
                    // 带 Accept: octet-stream 才会 302 到 release-assets.githubusercontent.com。
                    // 不带这个头，API 会返回该资产的 JSON 描述（几 KB），
                    // 表现为「下载飞快完成，然后提示文件不完整」。
                    String target = url;
                    for (int hop = 0; hop < 6; hop++) {
                        c = (HttpURLConnection) new URL(target).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(60000);
                        c.setInstanceFollowRedirects(false);
                        c.setRequestProperty("User-Agent", "zsb-plan");
                        if (target.contains("api.github.com")) {
                            c.setRequestProperty("Accept", "application/octet-stream");
                            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                        }
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
