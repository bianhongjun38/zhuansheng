package com.zsb.plan;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 更新器防线审计 —— 针对 2026-10-28 "手机卡在 1.5.35 升不上去" 事故。
 *
 * 事故链：
 *   ① 发版pages.py 每次发版删光所有旧 .apk，只留最新一个；
 *   ② pages/_routes.json 是 include ["/*"] 的 SPA 回退，缺失路径返回
 *      HTTP 200 + index.html（1108B text/html），不是 404；
 *   ③ 客户端 download() 只有"长度 > 1MB"和"zip 魔数"两道校验，
 *      而且**从不核对包里真实的 versionName**。
 *      于是"下载链路全绿但内容是旧版/错包"时，安装器会弹
 *      "已安装相同版本"，用户永远升不上去。
 *
 * 本审计锁死三条防线，任何一条被删掉就会红。
 */
public class UpdGuardAudit {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name); }
    }

    static String read(String p) throws Exception {
        InputStream in = new FileInputStream(p);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void main(String[] a) throws Exception {
        String root = a.length > 0 ? a[0] : "E:/\u5c0f\u9ed1\u76d2\u8fd0\u8425/cq-zhuanshengbens/mobile";
        String src = read(new File(root, "src/com/zsb/plan/Update.java").getPath());
        String rel = read(new File(root, "\u53d1\u7248pages.py").getPath());

        System.out.println("=== ① 客户端必须核对 APK 真实版本号（核心防线）===");
        ok("安装前读取包内 versionName", src.contains("apkVersionName("));
        ok("用 getPackageArchiveInfo 解析（与安装器同源）",
                src.contains("getPackageArchiveInfo"));
        ok("版本不高于本机则拒绝安装",
                src.contains("apkCode <= myCode"));
        ok("拒绝时给出明确错误而不是装完才发现",
                src.contains("\u5df2\u62d2\u7edd\u5b89\u88c5"));
        ok("校验发生在交给安装器之前",
                src.indexOf("apkCode <= myCode") < src.indexOf("install(out)"));

        System.out.println("=== ② 不再依赖无效的服务端拦截 ===");
        ok("已删除无效的 _routes.json（Pages 不认它）",
                !new File(root, "pages/_routes.json").exists());
        ok("已删除从不执行的 pages/functions",
                !new File(root, "pages/functions").exists());

        System.out.println("=== ③ 发版脚本只留最新包（历史包无用）===");
        ok("部署前清掉旧 apk", rel.contains("os.remove"));

        // ── 2026-10-28 审计后新增的防线 ──────────────────────────
        System.out.println();
        System.out.println("=== ④ 下载源不得混入不同版本（串版本装旧包）===");
        ok("GitHub 兜底源必须版本号一致才启用",
                src.contains("fallbackGithubCode == ltCode(lt)"));
        ok("记录了 GitHub 那版的版本号",
                src.contains("fallbackGithubCode = code"));
        // 2026-10-28：自有域名取消后，只剩 pages.dev 一个入口。
        // 这条改成断言「不再出现自有域名」，防止以后有人手滑加回去。
        ok("只用 pages.dev，自有域名已彻底移除",
                src.contains("zsb-update.pages.dev/")
                        && !src.contains("PRIMARY_JSON =\n            \"https://app.xxjcloudns"));
        ok("教材基地址指向 pages.dev",
                src.contains("BOOK_BASE = \"https://zsb-update.pages.dev/books/\""));

        // ── 2026-10-31：APK 分发迁到 GitHub Release ────────────
        // 触发原因：词图并进来后包 447.5 MB，Pages 单文件硬上限 25 MiB，
        // 超 18 倍，Pages 已经装不下包了。这些检查防止以后有人
        // 按老思路把 APK 又塞回 Pages。
        System.out.println();
        System.out.println("=== ④b APK 分发必须走 GitHub Release ===");
        ok("清单权威源是 api.github.com releases/latest",
                src.contains("api.github.com/repos/bianhongjun38/zhuansheng/releases/latest"));
        ok("仓库名统一到常量子单（镜像拼地址要用）",
                src.contains("REPO = \"bianhongjun38/zhuansheng\""));
        ok("GitHub 是唯一权威清单（先读它，失败才退清单文件）",
                src.indexOf("fetchFromGithub()") < src.indexOf("fetchManifest(PRIMARY_JSON)"));
        ok("清单里的 apkUrl 直读（省一次往返）",
                src.contains("optString(\"apkUrl\""));
        ok("下载源组装走测速择优而不是固定顺序",
                src.contains("rankSources(") && src.contains("probeSpeed("));
        ok("镜像表存在且是常量数组", src.contains("MIRROR_PREFIXES"));
        ok("镜像只对 github.com 直链生效（api 端点不能拼镜像）",
                src.contains("startsWith(\"https://github.com/\")"));
        // 10 KB/s 的镜像混进候选池会让用户下 12 小时，必须清掉。
        // 只查数组字面量里有没有，注释里提到"为何剔除"是好事，不算违规。
        String mirrors = src.contains("MIRROR_PREFIXES = {")
                ? src.substring(src.indexOf("MIRROR_PREFIXES = {"),
                                src.indexOf("};", src.indexOf("MIRROR_PREFIXES = {")))
                : "";
        ok("已剔除实测不可用的镜像 gh-proxy.com（10 KB/s）",
                !mirrors.contains("gh-proxy.com")
                        && !mirrors.contains("ghfast.top"));
        ok("下载失败提示指向 GitHub Releases（不是 pages.dev）",
                src.contains("\"/releases 手动下载"));

        // 兼容性红线：update.json 是存量客户端（<=1.5.44）唯一的入口，
        // 它必须继续存在并且版本号与 GitHub tag 同版，否则老用户永久失联。
        System.out.println();
        System.out.println("=== ④c 存量客户端兼容（10 月发出去的包还在用户手机上）===");
        ok("发版脚本仍然生成 pages/update.json",
                rel.contains("update.json"));
        ok("清单里同时给出 GitHub 网页直链（镜像只能代理它）",
                rel.contains("\"downloadUrl\""));
        ok("清单 source 标为 github-release",
                rel.contains("\"source\": \"github-release\""));
        ok("发版必须先传 GitHub 再写清单（避免 404 窗口期）",
                rel.indexOf("gh_publish(") < rel.indexOf("json.dump(man"));

        System.out.println();
        System.out.println("=== ⑤ 版本号进位（1.5.100 会与 1.6.0 碰撞）===");
        String build = read(new File(root, "build_apk.py").getPath());
        ok("bump 时 patch 段到 100 必须进位", build.contains("seg[2] >= 100"));
        ok("minor 段到 100 也要进位", build.contains("seg[1] >= 100"));
        ok("发版脚本对越界版本号直接报错",
                rel.contains("会和更高级的版本碰撞"));

        System.out.println();
        System.out.println("=== ⑥ Gist 更新必须用 POST（PUT 是 404）===");
        String gist = read(new File(root, "src/com/zsb/plan/GistSync.java").getPath());
        ok("不再用 PUT 更新 gist",
                !gist.contains("send(\"PUT\", API"));
        ok("改用 POST /gists/{id}", gist.contains("send(\"POST\", API + \"/\" + gistId"));

        System.out.println();
        System.out.println("=== ⑦ 教材 PDF 必须验魔数（Pages 会回 200+HTML）===");
        String book = read(new File(root, "src/com/zsb/plan/BookView.java").getPath());
        ok("有 PDF 魔数校验", book.contains("looksLikePdf("));
        ok("校验 %PDF- 前五字节",
                book.contains("0x25") && book.contains("0x2D"));
        ok("渲染失败要删掉坏缓存", book.contains("catch (Exception e) {"));
        ok("下载后校验长度与格式", book.contains("expect > 0 && tmp.length() != expect"));

        System.out.println();
        System.out.println("=== ⑧ 导入进度必须立刻落库 ===");
        String store = read(new File(root, "src/com/zsb/plan/Store.java").getPath());
        ok("Store 重写了 importJson", store.contains("public int importJson(String raw)"));
        ok("导入后调用 writeNow()",
                store.indexOf("public int importJson") < store.indexOf("writeNow();",
                        store.indexOf("public int importJson")));

        System.out.println();
        System.out.println("=== ⑨ 生命周期护栏（界面没了不要弹窗）===");
        ok("askUpdate 检查 isFinishing",
                src.contains("isFinishing() || act.isDestroyed()"));

        System.out.println();
        System.out.println("STAT " + pass + " " + fail);
    }
}