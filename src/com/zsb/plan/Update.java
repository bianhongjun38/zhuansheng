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
     * 更新**主通道**：Cloudflare Pages 的默认域名。
     *
     * 2026-10-28：自有域名 app.xxjcloudns.kdns.fr 按用户决定**取消**。
     * 它指向的就是同一个 Pages 项目，只是多套了一层 Cloudflare 代理，
     * 而实测会返回 403 "Website Access Blocked"（WAF 拦截页）。
     * 多一个域名 = 多一个会坏的入口，现在只用 pages.dev。
     *
     * 下面保留的历史实测数据，供以后排查时对照：
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
            "https://zsb-update.pages.dev/update.json";

    /**
     * ── 2026-10-31 更新链路大改：GitHub Release 成为唯一源 ──────────────
     *
     * 起因：雅思词图并进 App 后，安装包从 3.7 MB 涨到 447 MB。
     * Cloudflare Pages 的单个文件硬上限是 25 MiB（官方 platform/limits 原文：
     * "The maximum file size for a single Cloudflare Pages site asset is 25 MiB."），
     * 447 MB 是它的 18 倍 —— **Pages 做下载源这条路彻底不可用了**。
     * 教材 PDF 每本 2~5 MB 仍远低于上限，继续留在 Pages，不受影响。
     *
     * 新架构：
     *   ① 版本清单 → GitHub Release（tag 即版本，天然不会过期）
     *   ② APK 下载  → GitHub Release 资产，前面挂多个加速镜像
     *   ③ 教材 PDF  → 仍走 pages.dev（小文件，没受影响）
     *
     * ── 为什么必须"运行时择优"而不是写死顺序 ──────────────────────
     *
     * 2026-10-31 本机直连实测（关闭代理，模拟国内手机）：
     *
     *   github.com 直链       4183 KB/s 完整 3.66MB   ← 快得离谱
     *   同上，20 分钟前        297 KB/s 拉了 512KB    ← 同一台机器
     *   同上，更早一次          拉到 3.66MB 就断流（20.205.x 超时）
     *   api.github.com 资产    1797 KB/s 完整
     *   ghproxy.net            193 KB/s 完整
     *   gh-proxy.com            36 KB/s
     *   ghfast.top / ghp.ci / hub.gitmirror.com / github.moeyy.xyz → DNS 解析失败
     *
     * 同一台机器、同一天、20 分钟内从 297 KB/s 到 4183 KB/s 再到断流。
     * 这就是"国内直连不稳定"的量化样子 —— 它不是"快"或"慢"，
     * 而是**根本没有稳定值**。所以：
     *   · 写死"GitHub 第一" → 抽风时用户干等 20 秒超时
     *   · 写死"镜像第一"     → 镜像实测比直连慢 20 倍（193 vs 4183）
     *   · 只能**下载前先并发探一下，谁快用谁**（见 rankSources()）
     *
     * 镜像列表是"候选"，不是"必用"。每个都可能在某个时刻挂掉，
     * 所以任何一个失败都只记一笔、换下一个，绝不阻塞。
     */

    // 2026-10-28：Worker 那条 dl.xxjcloudns.kdns.fr 兜底通道按用户决定整条撤掉。
    // Worker 运行时本身没毛病，坏的是 workers.dev 后缀的路由（DNS 污染），
    // 后来绑自有域名 dl 绕过 —— 但既然书 PDF 和 APK 都已经统一放在 Pages，
    // 再维持第二套部署只是多一个会失联的源。现在只剩三个通道：
    //   zsb-update.pages.dev（唯一 Pages 域名，主）
    //   → api.github.com（Pages 全挂时才试）
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
    public static final String BOOK_BASE = "https://zsb-update.pages.dev/books/";

    /**
     * 第二个清单源。
     *
     * 这里原本写的是 "https://zsb-update.pages.dev/update.json" ——
     * 和上面的 PRIMARY_JSON **逐字符相同**。它是在 Worker 通道撤掉时
     * 留下的残留：当年这两条确实是两个不同域名（Pages 与 Worker），
     * 合并到一个域名之后就成了同一个地址。
     *
     * 后果：fetchLatestAny() 会在每次检查更新时把同一个 URL 请求两遍。
     * 请求本身很快，但它是**串行**的，网络差的时候白白多等一个超时，
     * 而且与自己项目「不许对同一入口制造重复请求」的红线冲突。
     *
     * 现在统一指向 PRIMARY_JSON：留两个常量是为了让 fetchLatestAny()
     * 的"多源"结构不用改，同时也给将来真的加第二个源留了位置。
     */
    private static final String PAGES_JSON = PRIMARY_JSON;

    /**
     * 版本清单的唯一权威来源：GitHub Release 的 latest 接口。
     *
     * 为什么是 api.github.com 而不是 github.com/.../releases/latest：
     * github.com 主站在国内实测多次整段超时（HTTP 000 / 12s），
     * 而 api.github.com 稳定 200 / 约 320ms。两个是不同的接入点。
     *
     * 为什么不再读 update.json 当主清单：
     * 那份文件要手动跑发版脚本才更新，忘跑就永远停在旧版本 ——
     * 历史上已经因为这个坑让"线上有新版、用户却被告知已是最新"。
     * GitHub 的 tag 是发版动作本身产生的，不存在忘记同步。
     * 现在 update.json 只作为**补充**（教材地址 + 更新说明），
     * 而且读不到就完全忽略，不影响版本判定。
     */
    private static final String API =
            "https://api.github.com/repos/bianhongjun38/zhuansheng/releases/latest";

    /** 仓库全名，拼镜像地址时要用。 */
    private static final String REPO = "bianhongjun38/zhuansheng";

    /**
     * GitHub Release 资产 URL 的加速镜像前缀。
     *
     * 每个条目就是一个「把 https://github.com/... 拼在自己后面」的反向代理。
     * 这些是**第三方公益服务**，随时可能关停或限速 —— 所以：
     *   · 全部只当候选，不写死主力
     *   · 任何一个失败都不报错，静默跳过
     *   · 真正用哪个由 rankSources() 当场测速决定
     *
     * 2026-10-31 本机直连实测（关代理，模拟国内手机，各拉完整 3.66 MB 包）：
     *   github 直链            HTTPS  完整 3747 KB   0.9s  4183 KB/s
     *   api 资产端点           HTTPS  完整 3747 KB   2.1s  1797 KB/s
     *   ghproxy.net            HTTPS  完整 3747 KB  19.5s   193 KB/s
     *   gh-proxy.com           HTTPS  完整 3747 KB   367s    10 KB/s  [剔除]
     *   ghfast.top             -          超时 12s          [不可用]
     *   gh.llkk.cc             -          超时 12s          [不可用]
     *   hub.gitmirror.com      -          DNS 解析失败      [不可用]
     *   github.moeyy.xyz       -          DNS 解析失败      [不可用]
     *   ghp.ci                 -          DNS 解析失败      [不可用]
     *   ghproxy.cc             -          TLS 握手失败      [不可用]
     *   gh.xxooo.cf            -          DNS 解析失败      [不可用]
     *   gh.api.99988866.xyz    -          TLS 握手失败      [不可用]
     *
     * gh-proxy.com 虽然能通，但实测 10 KB/s —— 447 MB 要下 12 小时，
     * 等于不可用；而它排在候选池里还要占掉一个探测名额，直接剔除。
     * 判断标准不是"能不能连上"，而是"能不能在合理时间内下完"。
     *
     * 只保留实测能用的，避免每次更新都白等一堆超时。
     * 以后要加新镜像：先手动拉一个完整包计时，通了且够快再加进来。
     */
    /**
     * 加速镜像前缀。用法：镜像前缀 + 完整的 github.com 地址。
     *
     * 2026-10-31 实测（1 MB Range 样本，代理关闭）。三条同时成立才收进来：
     *   ① 认 Range（回 206 + Content-Range），否则没法分段；
     *   ② 对同一资产返回同一个 ETag，否则混拉会拼出错包；
     *   ③ 单连接 400 KB/s 以上。
     *
     *   gh.monlor.com        602→7521 KB/s（1→12 路）  ETag 0x8DF3726C07913E5
     *   ghproxy.imciel.com   523 KB/s                   ETag 同上
     *   gitproxy.eu.org      498 KB/s                   ETag 同上
     *   gh.catmak.name       454 KB/s                   ETag 同上
     *   ghproxy.net          193 KB/s（1 路）→ 3.15 MB/s（16 路），按 IP 限速
     *
     * 剔除过：gh-proxy.com（10 KB/s）、ghfast.top / ghp.ci / hub.gitmirror.com /
     * github.moeyy.xyz / gh.xxooo.cf（DNS 死）、ghproxy.cc（TLS 失败）、
     * gh-proxy.net / gitproxy.click / ghproxy.org（302 跳广告页，判定劫持）。
     *
     * 注意：所有镜像都只是"能连上"的候选，实际用哪个由 rankSources()
     * 当场测速决定 —— 同一台机器 20 分钟内 GitHub 直连能从 4183 KB/s
     * 掉到 297 KB/s，写死顺序一定会踩坑。
     */
    private static final String[] MIRROR_PREFIXES = {
            "https://gh.monlor.com/",
            "https://ghproxy.imciel.com/",
            "https://gitproxy.eu.org/",
            "https://gh.catmak.name/",
            "https://ghproxy.net/",
    };

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
     *
     * ── 2026-10-31 起这个结论升级为架构决策 ──────────────────
     * Pages 已经承载不了 447 MB 的包（25 MiB 上限），GitHub 从"兜底"
     * 变成"唯一"。而它偏偏是时通时断的 —— 所以不能再靠固定顺序，
     * 改成下载前当场测速择优（rankSources）。详见 MIRROR_PREFIXES。
     */
    private volatile String fallbackGithubUrl;
    /** 最近一次 findApk 看到的 GitHub 网页直链（供候选池使用）。 */
    private volatile String lastWebUrl;
    /** GitHub 那个 Release 的版本号，用来确认清单与资产是不是同一版。 */
    private volatile int fallbackGithubCode = 0;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /**
     * 「忽略此版本」用。
     *
     * 只记一个版本号：忽略 1.5.47 不该连带忽略 1.5.48。
     * 键放 kv 表，和进度同库 —— 清空进度不会清这个，
     * 因为它属于"我自己的选择"而不是学习数据。
     */
    private static final String KV_SKIP_VER = "update_skip_ver";

    public Update(Activity a) {
        this.act = a;
    }

    /** 这个版本用户是不是说过「忽略」。 */
    public boolean isSkipped(String ver) {
        if (ver == null || ver.length() == 0) return false;
        try {
            Store s = new Store(act);
            String v = s.kvGet(KV_SKIP_VER, null);
            return v != null && parseVer(v) == parseVer(ver);
        } catch (Exception e) {
            return false;
        }
    }

    /** 记下「忽略此版本」。只影响这一个版本号。 */
    public void skip(String ver) {
        if (ver == null || ver.length() == 0) return;
        try {
            new Store(act).kvSet(KV_SKIP_VER, ver);
        } catch (Exception ignored) { }
    }

    /** 取消忽略（设置页「恢复更新提示」用）。 */
    public void clearSkip() {
        try {
            new Store(act).kvSet(KV_SKIP_VER, null);
        } catch (Exception ignored) { }
    }

    /** 当前被忽略的版本号，没有则 null。 */
    public String skippedVersion() {
        try {
            return new Store(act).kvGet(KV_SKIP_VER, null);
        } catch (Exception e) {
            return null;
        }
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
                    // 用户点过「忽略此版本」：静默检查时完全不打扰；
                    // 手动点「检查更新」时要说清楚为什么没弹窗，
                    // 否则用户会以为检查功能坏了。
                    if (isSkipped(lt.ver)) {
                        if (!silent) {
                            toastOnUi("已忽略 v" + lt.ver + "，可在设置里恢复");
                        }
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
        /** 首选下载地址（GitHub 资产 API 端点，带 Accept: octet-stream）。 */
        String apkUrl;
        /**
         * GitHub 网页直链（https://github.com/.../releases/download/vX/xxx.apk）。
         *
         * 为什么要单独记一份：
         *   ① 它能被镜像服务代理（api.github.com 那套资产 ID 接口不行），
         *      而镜像在国内某些网络下比直连通得更稳；
         *   ② 实测网页直链本身也常常比 api 端点快（4183 vs 1797 KB/s）。
         * 两者都进候选池，由 rankSources() 测速决定用谁。
         */
        String webUrl;
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
        // 2026-10-31：GitHub Release 升级为唯一权威清单源，所以它排第一，
        // 而且**只**用它的版本号做判定。update.json 还读，但只用来补
        // 「教材地址」和「更全的更新说明」，版本号一律以 GitHub 为准。
        //
        // 这是为了让"版本判定"只有一个事实来源。以前两份清单各说各话，
        // 出现过 tag 停在 v1.5.24、update.json 已经 1.5.39 的分裂状态，
        // 客户端取最大值虽然能兜住，但排查时极其难判断到底谁对。
        Latest gh = fetchFromGithub();
        if (gh != null) {
            // 顺手把 GitHub 没写全的说明从 update.json 补上。
            // 读不到就算了 —— 更新说明缺一行不影响升级。
            Latest man = fetchManifest(PRIMARY_JSON);
            if (man != null && man.code == gh.code) {
                if (gh.notes == null || gh.notes.trim().length() == 0) {
                    gh.notes = man.notes;
                }
            }
            return gh;
        }
        // GitHub 完全读不到时才退到 update.json。此时版本号可能过期，
        // 但总比"什么都查不到、直接报网络不通"强。
        Latest man = fetchManifest(PRIMARY_JSON);
        if (man != null) return man;
        if (!PAGES_JSON.equals(PRIMARY_JSON)) return fetchManifest(PAGES_JSON);
        return null;
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
            if (apk != null) {
                String u = apk.optString("url", null);
                if (u != null && u.length() > 0 && !"null".equals(u)) lt.apkUrl = u;
            }
            // 1.5.45+ 的清单会把 GitHub 资产地址直接写进 apkUrl。
            // 读到就用它，省掉「再去 GitHub 查一次 release」的往返 ——
            // 国内这一步经常要 1~3 秒，能省则省。
            String direct = o.optString("apkUrl", "");
            if (direct.length() > 0) lt.apkUrl = direct;
            // 清单里若给了网页直链，一并记下供镜像池使用。
            JSONObject gh = o.optJSONObject("github");
            if (gh != null) {
                String dl = gh.optString("downloadUrl", "");
                if (dl.length() > 0) lt.webUrl = dl;
            }
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
        lt.webUrl = lastWebUrl;
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
            if (arr == null) return null;
            // **挑版本号最大的那个附件**，不能见到 .apk 就拿。
            //
            // 2026-10-28 实测事故：tag 打在了没有提交 version.json 的 commit 上，
            // CI 用旧的 version.json 构建，于是 v1.5.40 这个 Release 底下
            // 同时躺着 zhuansheng-v1.5.39.apk 和 zhuansheng-v1.5.40.apk，
            // 而且 1.5.39 排在前面。以前这里 return 第一个，兜底源就指向
            // 旧包 —— 用户被提示升级 1.5.40，下载到的却是 1.5.39，
            // 然后被版本校验拦下，报一句看不懂的错。
            JSONObject best = null;
            int bestCode = -1;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                String name = a.optString("name", "");
                if (!name.endsWith(".apk")) continue;
                // 从文件名里抠版本号（zhuansheng-v1.5.40.apk → 1.5.40）。
                //
                // 注意不能直接 parseVer(文件名)：它要求字符串**以数字开头**，
                // "zhuansheng-v1.5.40.apk" 会返回 0（实测），于是所有附件
                // 分数一样，挑选逻辑等于没写。先正则抠出 d+.d+.d+。
                int code = verOfApkName(name);
                if (code > bestCode) {
                    bestCode = code;
                    best = a;
                }
            }
            if (best == null) return null;
            // 两个地址都记下来：
            //   url                    = api.github.com 资产端点（稳定但要 Accept 头）
            //   browser_download_url   = github.com 网页直链（可被镜像代理）
            // 它们进同一个候选池，由测速决定先用哪个。
            lastWebUrl = best.optString("browser_download_url", null);
            String api = best.optString("url", "");
            if (api.startsWith("https://api.github.com/")) {
                fallbackGithubUrl = api;
                return api;
            }
            // 兜底：万一副本接口没给 url，退回老地址（可能很慢但不至于不可用）
            fallbackGithubUrl = lastWebUrl;
            return fallbackGithubUrl;
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 组装下载源列表。
     *
     * 2026-10-31 改造后的逻辑：
     *   ① 收集候选（GitHub 资产端点 / 网页直链 / 各镜像）
     *   ② 当场并发测速，快的排前面
     *   ③ 下载按这个顺序试，任一成功即收工
     *
     * 以前这里是「Pages 地址排第一、GitHub 兜底」的固定顺序。
     * 那个顺序成立的前提是 Pages 能装下包；447 MB 之后前提没了。
     */
    private java.util.List<String> buildSources(Latest lt) {
        if (lt == null) return new java.util.ArrayList<>();

        java.util.List<String> cand = candidateUrls(lt.apkUrl, lt.webUrl);

        // 版本一致性校验：只有 fallbackGithubUrl 属于**同一个版本**时才能混用。
        //
        // 这条来自 2026-10-28 的真实事故：tag 打在没提交 version.json 的
        // commit 上，Release 底下同时挂着旧包和新包。不加校验的话
        // 「提示升级 1.5.40、下载到 1.5.39、装完还是旧版本」。
        //
        // 现在 GitHub 是唯一源，清单和资产天然同源，这条更像是
        // 对「镜像返回了缓存旧文件」的防御 —— 仍然保留。
        if (fallbackGithubUrl != null && fallbackGithubCode == ltCode(lt)
                && !cand.contains(fallbackGithubUrl)) {
            cand.add(fallbackGithubUrl);
        }

        // 测速择优。候选只有 1 个时 rankSources 直接返回，不浪费时间。
        java.util.List<String> ranked = rankSources(cand);
        return ranked == null ? cand : ranked;
    }


    // ══ 下载源择优 ═════════════════════════════════════════

    /**
     * 把一个 GitHub 资产地址展开成「直连 + 各镜像」的候选列表。
     *
     * 输入可能是两种形状：
     *   https://api.github.com/repos/O/R/releases/assets/123   资产 API 端点
     *   https://github.com/O/R/releases/download/v1/apk.apk   网页直链
     *
     * 这两种的可用性完全不同（实测：api 端点稳定 1797 KB/s，
     * 网页直链在 297 KB/s ~ 4183 KB/s 之间剧烈跳动、还会断流），
     * 所以两个都列进去，让测速决定。
     *
     * 镜像只对**网页直链**有意义 —— 镜像服务就是把 github.com 的路径
     * 拼在自己域名后面，它们不认识 api.github.com 那套资产 ID 接口。
     */
    private java.util.List<String> candidateUrls(String primary, String ghDownloadUrl) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (primary != null && primary.length() > 0) out.add(primary);

        // GitHub 网页直链：优先用清单里给的，其次由 api 端点换算不出来的话就跳过。
        String web = ghDownloadUrl;
        if ((web == null || web.length() == 0) && primary != null) web = primary;

        if (web != null && web.startsWith("https://github.com/")) {
            if (!out.contains(web)) out.add(web);
            for (String pre : MIRROR_PREFIXES) {
                String m = pre + web;
                if (!out.contains(m)) out.add(m);
            }
        }
        return out;
    }

    /**
     * 并发探测每个候选源的实际速率，按「快慢」重排，返回排好序的列表。
     *
     * 为什么要探测而不是按固定顺序试：
     *   2026-10-31 同一台机器、20 分钟内，GitHub 直连实测到
     *   297 KB/s → 4183 KB/s → 拉 3.66 MB 就断流。镜像 193 KB/s / 36 KB/s。
     *   固定顺序必然在某个时段选中最差的那个。
     *
     * 探测方式：对每个源发一个 Range: bytes=0-131071（128 KB）的请求，
     * 量它拿满 128 KB 用了几毫秒。128 KB 足够区分 36 KB/s 和 4000 KB/s，
     * 又不至于让用户等太久。
     *
     * 并发而不是串行：3 个源串行各等 6 秒 = 18 秒，用户会以为卡死。
     * 并发总耗时 = 最慢那个（这里有上限 6 秒），心理上可接受。
     *
     * 探测失败的源**不丢弃**，排到最后再试一次 —— 探测失败可能只是
     * 运气差（比如刚好撞上镜像重启），但正式下载是本机唯一的机会。
     */
    private java.util.List<String> rankSources(java.util.List<String> candidates) {
        if (candidates == null || candidates.size() <= 1) return candidates;

        final int n = candidates.size();
        final long[] speed = new long[n];      // 字节/秒，0 表示不可用
        final Thread[] ts = new Thread[n];

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final String url = candidates.get(i);
            ts[i] = new Thread(new Runnable() {
                public void run() {
                    speed[idx] = probeSpeed(url);
                }
            });
            ts[i].setDaemon(true);
            ts[i].start();
        }
        // 等 8 秒，与 probeSpeed 的读超时对齐。
        // 以前是 6 秒 —— 那时样本只有 128 KB，6 秒够；
        // 换成 1 MB 后，一个 200 KB/s 的源要约 5 秒才读完，
        // 6 秒会把"慢但确实能用"的源判成死源。
        for (int i = 0; i < n; i++) {
            try { ts[i].join(8000); } catch (InterruptedException ignored) { }
        }

        // 稳定排序：快的在前，测不出速度的在后，但保留原有相对次序。
        final java.util.List<Integer> order = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        java.util.Collections.sort(order, new java.util.Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                return Long.compare(speed[b], speed[a]);
            }
        });

        java.util.List<String> out = new java.util.ArrayList<>();
        for (Integer i : order) out.add(candidates.get(i));
        return out;
    }

    /**
     * 量一个源的速度（字节/秒）。拿不到就返回 0。
     *
     * 读 1 MB 而不是 128 KB。
     *
     * 2026-10-31 实测：同样两个源，64 KB 样本测出 69 和 72 KB/s（几乎分不出高下），
     * 换成 1 MB 样本是 983 和 702 KB/s。小样本量到的主要是 **TCP 慢启动和握手延迟**，
     * 不是带宽 —— 用它排序等于随机排序。
     *
     * 代价是多花 1 MB 流量（相对 447 MB 可忽略），换来的是排序真的有意义。
     */
    private long probeSpeed(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            // 读超时放到 8 秒：1 MB 样本比 128 KB 需要更长时间，
            // 卡在 5 秒会把"慢但能用"的源误判成死源。
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "zsb-plan");
            c.setRequestProperty("Range", "bytes=0-1048575");
            if (url.contains("api.github.com")) {
                c.setRequestProperty("Accept", "application/octet-stream");
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            }
            int sc = c.getResponseCode();
            // 200 和 206 都算通（有的镜像不认 Range，直接回整包）
            if (sc != 200 && sc != 206) return 0;

            long t0 = System.currentTimeMillis();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[16 * 1024];
            long got = 0;
            while (got < 1048576) {
                int r = in.read(buf, 0, buf.length);
                if (r <= 0) break;
                got += r;
            }
            long ms = System.currentTimeMillis() - t0;
            in.close();
            // 至少拿到 128 KB 才算有效样本：低于这个量仍是握手开销占主导，
            // 算出来的"速度"没有参考价值。
            if (got < 131072) return 0;
            return got * 1000L / Math.max(ms, 1);
        } catch (Exception e) {
            return 0;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private int ltCode(Latest lt) {
        return lt == null ? 0 : parseVer(lt.ver);
    }

    /** 从附件名里抠出版本号：zhuansheng-v1.5.40.apk → 10540。 */
    private static int verOfApkName(String name) {
        if (name == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+\\.\\d+)").matcher(name);
        // 找**第一个**三段式数字；文件名里一般只有版本号是这种形状。
        if (m.find()) return parseVer(m.group(1));
        // 退化：只有两段或一段的命名也认一下
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+)").matcher(name);
        if (m2.find()) return parseVer(m2.group(1));
        return 0;
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
                // 「以后再说」每次启动都还会问；「忽略此版本」不会。
                // 两者都要保留：多数更新用户只是想推迟，不是不想要。
                .setNeutralButton("忽略此版本", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        skip(ver);
                        toastOnUi("已忽略 v" + ver + "，不再提示（设置里可恢复）");
                    }
                })
                .setNegativeButton("以后再说", null)
                .show();
    }

    /**
     * 按顺序尝试多个下载源，任一成功即返回。
     *
     * 为什么要多源：本机 2026-10-28 实测——
     *   github.com（release 资产，302 到 release-assets）完整 20.12 MB / 3.1s / 6.6 MB/s
     *   zsb-update.pages.dev（Pages，APK 3.65 MB / 2.9s / 1.3 MB/s）
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
                        long got = fetchAny(src, out, pd);
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
                        // 与成功分支一致：界面已经销毁时不能再 dismiss / toast。
                        // 原实现漏了这道守卫 —— 用户在下载中途退出这个页面，
                        // 几秒后下载失败，就会对着一个已销毁的 Activity
                        // 调 dismiss()，轻则 WindowLeaked 日志，重则崩溃。
                        if (act == null || act.isFinishing() || act.isDestroyed()) {
                            return;
                        }
                        pd.dismiss();
                        toastOnUi(msg + "\n可在浏览器打开 github.com/" + REPO
                                + "/releases 手动下载。");
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
    /**
     * 把重定向的 Location 解析成绝对地址。
     *
     * HttpURLConnection 给的 Location 不一定带 scheme/host：
     *   · 绝对：https://cdn.example.com/a.apk
     *   · 绝对路径：/assets/a.apk
     *   · 相对路径：a.apk、../a.apk
     * 后三种都必须相对当前 URL 解析，否则 new URL() 直接抛异常。
     */
    static String resolveUrl(String base, String loc) {
        try {
            java.net.URI b = java.net.URI.create(base);
            java.net.URI r = b.resolve(loc);
            return r.toString();
        } catch (Throwable t) {
            return loc;   // 解析不了就原样返回，让调用方按原逻辑报错
        }
    }

    /**
     * 下载入口：优先分段并发，服务端不支持 Range 时退回单流。
     *
     * 为什么值得多写一套：447 MB 单流实测 600~780 KB/s（约 10 分钟），
     * 8 路并发 4.3 MB/s（约 2 分钟）。这不是调优，是能不能用的问题。
     * 但分段要求服务端认 Range，镜像不一定都认 —— 所以两条路都要有。
     *
     * 分段失败**不直接放弃这个源**：退回单流仍可能成功。
     * 只有两条路都失败才换下一个源。
     */
    private long fetchAny(String url, File out, final ProgressDialog pd) throws Exception {
        if (out.exists()) out.delete();

        final int[] pct = new int[1];
        ParallelFetch.Listener l = new ParallelFetch.Listener() {
            public void onBytes(long done, long total) {
                if (total <= 0) return;
                int p = (int) (done * 100L / total);
                if (p != pct[0]) {
                    pct[0] = p;
                    ui.post(new Runnable() {
                        public void run() {
                            if (act == null || act.isFinishing() || act.isDestroyed()) return;
                            pd.setMessage(p + "%");
                        }
                    });
                }
            }
            public boolean cancelled() {
                return act == null || act.isFinishing() || act.isDestroyed();
            }
        };

        try {
            long got = ParallelFetch.fetch(url, out, ParallelFetch.MAX_CONNS, l);
            ui.post(new Runnable() {
                public void run() {
                    if (act == null || act.isFinishing() || act.isDestroyed()) return;
                    pd.setMessage("100%");
                }
            });
            return got;
        } catch (ParallelFetch.NoRange e) {
            // 服务端不认 Range，退回单流。这条路径有断点续传。
            out.delete();
            return fetchResumable(url, out, pd);
        }
    }

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
                // Location 可能是相对路径（RFC 7231 允许 "/x/y.apk"、
                // 甚至 "../y.apk"）。原实现直接 target = loc，下一轮
                // new URL("/x/y.apk") 会抛 MalformedURLException，
                // 整次更新失败。这里按当前地址解析一次相对路径。
                target = resolveUrl(target, loc);
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
            // 先确认「安装未知来源应用」这个权限开着。
            //
            // 不预检的后果：Android 8.0+ 上会**静默**跳到系统设置页让用户
            // 自己找开关，或者干脆什么都不发生 —— 玩家下载完 20 MB，
            // 看到的却是"点了没反应"，而他不知道要去哪里开权限。
            // 这里明确告诉他在哪开。
            if (Build.VERSION.SDK_INT >= 26) {
                boolean can = act.getPackageManager().canRequestPackageInstalls();
                if (!can) {
                    toastOnUi("安装包已下载完成，但本应用没有「安装未知应用」权限。\n"
                            + "请到 设置 › 应用 › 本应用 › 安装未知应用 里打开后重试。\n"
                            + "APK 已保存，也可在文件管理器里手动安装。");
                    return;
                }
            }
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