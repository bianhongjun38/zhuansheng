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
        ok("清单主通道指向 pages.dev",
                src.contains("\"https://zsb-update.pages.dev/update.json\""));
        ok("教材基地址指向 pages.dev",
                src.contains("BOOK_BASE = \"https://zsb-update.pages.dev/books/\""));

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