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
     * 中途把 Worker 绑到自有域名 dl.xxjcloudns.kdns.fr 绕过这个问题，
     * 但 2026-10-28 按用户决定把 Worker 整套撤掉了：Pages 与 Workers 的
     * 单文件上限、免费额度完全一致，没必要同时维护两套部署。
     * 现在书 PDF 与 APK 都在 Pages，一个域名管完。
     */
    private static final String PRIMARY_JSON =
            "https://app.xxjcloudns.kdns.fr/update.json";

    // 2026-10-28：Worker 那条 dl.xxjcloudns.kdns.fr 兜底通道按用户决定整条撤掉。
    // Worker 运行时本身没毛病，坏的是 workers.dev 后缀的路由（DNS 污染），
    // 后来绑自有域名 dl 绕过 —— 但既然书 PDF 和 APK 都已经统一放在 Pages，
    // 再维持第二套部署只是多一个会失联的源。现在只剩三个通道：
    //   app.xxjcloudns.kdns.fr（自有域名，主）→ zsb-update.pages.dev（兜底）
    //   → api.github.com（本地 Pages 全挂时才试）
    // 老机器（v1.5.7 及更早）读 dl 会 404，但 fetchLatestAny 是「取最高版本、
    // 单源失败即忽略」，不是「读到即返回」，所以缺这一条不影响它们升级 ——
    // 它们读自己的 dl 清单拿到的是旧版，而主通道给的是新版，取最大值仍然是新版。

    /**
     * 教材 PDF 的下载基地址。
     *
     * 三本教材共 11.7 MB，占安装包 60%，而 Cloudflare Pages 和
     * Workers Static Assets 的**单文件上限都是 25 MiB**（2026-10-28 查文档确认），
     * APK 20.13 MB 看着还够，但再塞两本练习册就顶死了。
     * 所以 PDF 从安装包里拿掉，改成第一次看原图时按需拉这一本。
     * 路径与 Worker 静态资源一致：/<bookId>.pdf
     */
    // 2026-10-28：Worker 已按用户决定撤掉（Pages 与 Workers 单文件上限、免费额度一致，
    // 没必要跑两套）。书 PDF 与 APK 统一放在 Pages，这条路一个域名管完。
    public static final String BOOK_BASE = "https://app.xxjcloudns.kdns.fr/books/";

    private static final String PAGES_JSON =
            "https://zsb-update.pages.dev/update.json";

    /** 兜底：Pages 挂了时退到 GitHub API（注意是 api 域名，不是 github.com）。 */
    private static final String API =
            "https://api.github.com/repos/bianhongjun38/zhuansheng/releases/latest";

    /**
     * GitHub 侧的兜底下载地址，由 findApk() 在探测版本时顺手记下。
     *
     * 2026-10-28 多轮实测（每条约 1MB，各连 5 次，间隔 1s）：
     *   Pages 自有域名      成功 5/5   中位 536 KB/s  区间 504~550
     *   GitHub release 资产 成功 1/5   中位 789 KB/s  四次 RemoteDisconnected
     *   GitHub api 资产端点 成功 5/5   中位   7 KB/s  等于不通
     *
     * 结论：GitHub 在国内是「时通时断」，不能用单一实测的漂亮数字当结论
     * （同一台机器 10 分钟内从 6.6 MB/s 掉到 1/5 成功率）。
     * 所以顺序必须是 Pages 优先，GitHub 只在本机 Pages 全失败时才试。
     */
    private volatile String fallbackGithubUrl;
    /** GitHub 那个 Release 的版本号，用来确认它和 Pages 清单是不是同一版。 */
    private volatile int fallbackGithubCode = 0;

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
                        public void run() { askUpdate(lt.ver, lt.notes, buildSources(lt)); }
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
                fetchManifest(PRIMARY_JSON),        // app.xxjcloudns.kdns.fr（Pages，主通道）
                fetchManifest(PAGES_JSON),          // zsb-update.pages.dev（Pages 兜底）
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
        // 记下 GitHub 这一版的版本号。GitHub Release 靠打 tag 才产生，
        // 和 Pages 部署之间有天然时间差（实测 tag 曾长期停在 v1.5.24）。
        // 只有两边版本号一致时，这个地址才能当兜底源用。
        fallbackGithubCode = code;
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
            // 每段按 100 进制挤压成整数。**前提是每段都 < 100** ——
            // 一旦 patch 段到 100，1.5.100 算出来是 10600，和 1.6.0 完全
            // 相等；再到 1.5.101（10601），切到 1.6.0 反而"变旧"，
            // 发版脚本会拒绝发布、客户端会显示"已是最新版本"。
            //
            // build_apk.py --bump 每次只 +1，按 1.5.39 的速度再 build 61 次
            // 就会撞上。所以进位归 build_apk.py 管（99 → 进位到下一段），
            // 这里只做兜底：真碰上越界段就按大端拼接，至少不会碰撞。
            if (maj >= 100 || min >= 100 || pat >= 100) {
                // 越界了，100 进制已经表达不了。退回大端拼接：
                // 1.5.100 → 1*1000000 + 5*10000 + 100*1 = 1050100
                // 恒大于任何合法的 1.5.99（10599），不会被判成"更旧"。
                return maj * 1000000L > Integer.MAX_VALUE
                        ? Integer.MAX_VALUE
                        : (int) (maj * 1000000L + min * 10000L + pat);
            }
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
                if (api.startsWith("https://api.github.com/")) {
                    fallbackGithubUrl = api;
                    return api;
                }
                fallbackGithubUrl = a.optString("browser_download_url", null);
                // 兜底：万一副本接口没给 url，退回老地址（可能很慢但不至于不可用）
                return a.optString("browser_download_url", null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 组装下载源列表，**顺序即优先级**。
     *
     * 第一档：本次探测到的清单里的 apk.url —— 也就是 Pages 自有域名，
     *         实测 5/5 成功、504~550 KB/s、波动极小。
     * 后两档：GitHub 的两个端点，只在前者失败时才会走到。
     */
    private java.util.List<String> buildSources(Latest lt) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (lt != null && lt.apkUrl != null) {
            // 同一个 Pages 站点有两个域名，互为镜像。
            // 以前清单里只写 zsb-update.pages.dev，自有域名从来没被用过 ——
            // 部分运营商网络连不上 pages.dev 时就直接掉到 GitHub 源。
            // 这里把两个域名都排上，先自有域名再 pages.dev。
            String url = lt.apkUrl;
            String appHost = "app.xxjcloudns.kdns.fr/";
            String pagesHost = "zsb-update.pages.dev/";
            if (url.contains(pagesHost)) {
                out.add(url.replace(pagesHost, appHost));
                out.add(url);
            } else if (url.contains(appHost)) {
                out.add(url);
                out.add(url.replace(appHost, pagesHost));
            } else {
                out.add(url);
            }
        }
        // GitHub 兜底源**只在版本号完全一致时**才用。
        //
        // 以前无条件排在第二位。但 GitHub Release 是靠打 tag 才产生的，
        // 而 Pages 一部署就生效 —— 两者之间存在时间差，实测 tag 曾长期
        // 停在 v1.5.24。于是"提示升级 1.5.39、pages.dev 连不上"时，
        // 客户端会从 GitHub 拉下 1.5.24 并装上去（如果本机更旧），
        // 或者被新加的版本校验拦下、报一句莫名其妙的版本错误。
        if (fallbackGithubUrl != null && fallbackGithubCode == ltCode(lt)) {
            out.add(fallbackGithubUrl);
        }
        return out;
    }

    private int ltCode(Latest lt) {
        return lt == null ? 0 : parseVer(lt.ver);
    }

    private void askUpdate(String ver, String notes, final java.util.List<String> urls) {
        // 三个源串行探测最长能拖到二十多秒（api.github.com 在国内经常超时）。
        // 这期间用户很可能已经返回、甚至把 Activity 关了。
        // 不拦一下就会 BadTokenException / "View not attached to window manager"。
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        final java.util.List<String> url = urls;
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

    /**
     * 按顺序尝试多个下载源，任一成功即返回。
     *
     * 为什么要多源：本机 2026-10-28 实测——
     *   github.com（release 资产，302 到 release-assets）完整 20.12 MB / 3.1s / 6.6 MB/s
     *   app.xxjcloudns.kdns.fr（Pages，APK 3.65 MB / 2.9s / 1.3 MB/s）
     * 两条都通，但国内线路质量会变（同一天不同时段、不同运营商差别很大）。
     * 单一源一旦抽风，用户就只能干等或失败；多源按序退避最稳。
     *
     * 每次断线后不重下整个包：用 Range 从已下载的字节处续传。
     * 20 MB 的包在国内掉一次线就全废，这个成本太高。
     */
    private void download(final java.util.List<String> urls) {
        final java.util.List<String> sources = new java.util.ArrayList<>();
        for (String u : urls) if (u != null && !sources.contains(u)) sources.add(u);

        final ProgressDialog pd = new ProgressDialog(act);
        pd.setTitle("正在下载更新");
        pd.setMessage("0%");
        pd.setCancelable(false);
        pd.show();

        new Thread(new Runnable() {
            public void run() {
                final File out = new File(act.getCacheDir(), "update.apk");

                // 关键：每次下载前必须清掉上一次的残留。
                //
                // 以前这里不删。download() 成功路径从不 delete(out)，只有
                // "某个源失败"才删。于是用户装过一次后 /cache/update.apk
                // 永久保留旧安装包，下一次更新会拿它做 Range 续传：
                // 服务器回 416 → 旧代码把 416 当成功 → 安装旧包。
                // 现象就是"能收到更新提示，但装出来还是旧版本"，且永久复现。
                if (out.exists()) out.delete();

                Exception last = null;

                outer:
                for (String src : sources) {
                    HttpURLConnection c = null;
                    try {
                        long got = fetchResumable(src, out, pd);
                        if (got < 1024 * 1024) {
                            throw new Exception("文件不完整（" + got + " 字节）");
                        }
                        // 必须是真正的 APK（zip 头 PK\x03\x04）。
                        // 只有长度检查不够：断流时长度可能过线但内容残缺，
                        // 那种文件系统装到一半会报"解析包出现问题"。
                        if (!looksLikeApk(out)) {
                            throw new Exception("下载内容不是有效安装包（" + got + " 字节）");
                        }
                        // 第三道校验：把包里真正的 versionName 读出来，
                        // 必须**严格大于**本机版本，否则绝不给安装器。
                        //
                        // 2026-10-28 线上事故：Pages 的 _routes.json 是 SPA 回退，
                        // 请求一个已下架的 apk 会返回 200 + index.html；
                        // 更隐蔽的是换包/发错包 —— 下载链路全绿、zip 头正常，
                        // 但里面是**旧版本**，系统安装器于是弹
                        // "已安装相同版本"，用户永远升不上去。
                        // 这个检查让那种情况变成一句明确的错误，而不是装完才发现。
                        String apkVer = apkVersionName(out);
                        if (apkVer == null) {
                            throw new Exception("安装包读取失败（可能不是完整 APK）");
                        }
                        int apkCode = parseVer(apkVer);
                        int myCode = parseVer(localVersionName());
                        if (apkCode <= myCode) {
                            throw new Exception("下载到的版本 v" + apkVer
                                    + " 不高于本机 v" + localVersionName()
                                    + "，已拒绝安装");
                        }
                        ui.post(new Runnable() {
                            public void run() {
                                if (act == null || act.isFinishing() || act.isDestroyed()) {
                                    return;   // 下载完时界面已经没了，不必再弹
                                }
                                pd.dismiss();
                                install(out);
                            }
                        });
                        return;   // 成功，收工
                    } catch (Exception e) {
                        last = e;
                        // 这个源不行，清掉半截文件换下一个 —— 不同源的包可能不同版本，
                        // 混着续传会拼出一个既不是 A 也不是 B 的坏文件。
                        out.delete();
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }

                final String msg = last == null ? "没有可用下载源"
                        : ("下载失败：" + last.getMessage());
                ui.post(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        toastOnUi(msg + "\n可在浏览器打开 app.xxjcloudns.kdns.fr 手动下载。");
                    }
                });
            }
        }).start();
    }

    /**
     * 从一个源把 APK 拉到 out，支持断点续传，返回最终字节数。
     *
     * 关键点：
     *   ① 手动跟 302（api.github.com 的资产端点必须这样，否则拿到的是 JSON 描述）
     *   ② api.github.com 必须带 Accept: octet-stream，同上
     *   ③ 若 out 已有部分内容且源支持 Range，就带 Range 续传，不让用户白等
     */
    private long fetchResumable(String start, File out, ProgressDialog pd) throws Exception {
        long have = out.exists() ? out.length() : 0;
        String target = start;
        HttpURLConnection c = null;

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
            if (have > 0) {
                c.setRequestProperty("Range", "bytes=" + have + "-");
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

        int sc = c.getResponseCode();
        if (sc == 416) {
            // 416 = 请求的区间超出文件总长度。
            //
            // 以前这里直接 return out.length()，把它当成"已经下完了"。
            // 但真实场景是：本地留着**旧版本**的 update.apk，它的长度
            // 大于服务端这个新文件的长度，于是永远 416，永远返回旧文件。
            // 这是"更新后版本没变"的根因，必须重下而不是当成功。
            out.delete();
            throw new Exception("续传区间失效，已清除重试");
        }
        if (sc != 200 && sc != 206) throw new Exception("HTTP " + sc);

        // 206 = 续传成功；200 = 服务端不支持 Range，得从头写
        boolean append = (sc == 206);
        if (!append) have = 0;

        long total = have + c.getContentLength();
        InputStream in = c.getInputStream();
        FileOutputStream fo = new FileOutputStream(out, append);
        byte[] buf = new byte[64 * 1024];
        int n;
        long done = have;
        int lastPct = -1;
        try {
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
        } finally {
            try { fo.close(); } catch (Exception ignored) { }
            try { in.close(); } catch (Exception ignored) { }
            c.disconnect();
        }
        return out.length();
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

    /**
     * 头 4 字节是不是 zip 魔数 PK\x03\x04。
     *
     * APK 就是 zip。Cloudflare 出错时会返回 HTML 错误页（200 + 文本），
     * 断流会留下半截文件 —— 两者长度都可能过 1MB 门槛，
     * 但只有真正的 zip 开头才能被系统安装器接受。
     */
    /**
     * 读 APK 里真实的 versionName。读不到返回 null。
     *
     * 用 PackageManager.getPackageArchiveInfo —— 这是系统自己解析
     * AndroidManifest 的结果，跟安装器看到的是同一份数据，
     * 不会被文件名或清单里的字段骗过去。
     */
    private String apkVersionName(File f) {
        try {
            android.content.pm.PackageManager pm = act.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(
                    f.getAbsolutePath(), 0);
            if (pi == null || pi.versionName == null) return null;
            return pi.versionName;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean looksLikeApk(File f) {
        if (f == null || !f.exists() || f.length() < 4) return false;
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] h = new byte[4];
            int n = 0;
            while (n < 4) {
                int r = in.read(h, n, 4 - n);
                if (r <= 0) break;
                n += r;
            }
            return n == 4 && h[0] == 0x50 && h[1] == 0x4B
                    && h[2] == 0x03 && h[3] == 0x04;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }
}