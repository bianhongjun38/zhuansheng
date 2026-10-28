package com.zsb.plan;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Iterator;

/**
 * 云同步后端 —— 用 GitHub Gist 当免费的进度仓库，不需要自建服务器。
 *
 * 为什么选 Gist：
 *   · 没有服务器要维护、没有数据库要备份，用户自己的 GitHub 账号就是账号体系
 *   · 内容存成一个文件 zsb-progress.json，手机上看得到、出问题能手改
 *   · 私有 gist（public:false）只有 token 持有者能读
 *
 * 依赖限制：只用 Android 自带 API + java.net，不引任何第三方网络库。
 *
 * 线程模型（重要）：
 *   · push0/pull0 内部是**阻塞**的，绝不能在主线程调
 *   · 对外主要用 pushAsync/pullAsync，网络在后台线程跑，
 *     回调统一 post 回主线程，UI 里可以放心直接改控件
 *   · push/pull 这两个同步版本留给测试和特殊场景，失败返回 null，不抛异常
 *
 * 错误处理原则：不让任何异常漏到调用方。HTTP 状态码翻译成中文人话，
 * token 本身绝不进异常消息、绝不打日志（只出现在请求头里）。
 */
public class GistSync {

    /** 同步结果回调。实现方保证在主线程被调用。 */
    public interface Cb {
        void ok(String v);
        void fail(String msg);
    }

    private static final String API = "https://api.github.com/gists";
    private static final String FILE = "zsb-progress.json";
    /** 连接/读取超时，毫秒。手机弱网下 15 秒比 GitHub 默认短，够用且不卡死。 */
    private static final int TIMEOUT = 15000;
    private static final String DESC = "zsb-plan 学习进度（App 自动同步）";

    private final String token;
    private String gistId;
    private String lastError;

    private final Handler ui = new Handler(Looper.getMainLooper());

    public GistSync(String token, String gistId) {
        this.token = token == null ? "" : token.trim();
        this.gistId = gistId == null ? "" : gistId.trim();
    }

    public String getGistId() {
        return gistId;
    }

    /** 上一次同步失败的说明，成功时为 null。调试用，不用于 UI。 */
    public String getLastError() {
        return lastError;
    }

    public boolean hasGist() {
        return gistId != null && !gistId.isEmpty();
    }

    // ── 对外：异步 ────────────────────────────────────────
    /** 后台创建/更新 gist，成功后回调新的（或原有的）gistId。 */
    public void pushAsync(final String localJson, final Cb cb) {
        final Cb c = cb == null ? new Cb() {
            @Override public void ok(String v) { }
            @Override public void fail(String msg) { }
        } : cb;
        new Thread(new Runnable() {
            @Override public void run() {
                String id = null;
                String err = null;
                try {
                    id = push0(localJson);
                } catch (SyncErr e) {
                    err = e.getMessage();
                } catch (Throwable t) {
                    err = "同步出错：" + brief(t);
                }
                final String fid = id;
                final String ferr = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (fid != null && !fid.isEmpty()) c.ok(fid);
                        else c.fail(ferr == null ? "同步失败" : ferr);
                    }
                });
            }
        }, "zsb-gist-push").start();
    }

    /** 后台读取云端正文。没有 gist / 没有该文件时 ok(null)。 */
    public void pullAsync(final Cb cb) {
        final Cb c = cb == null ? new Cb() {
            @Override public void ok(String v) { }
            @Override public void fail(String msg) { }
        } : cb;
        new Thread(new Runnable() {
            @Override public void run() {
                String body = null;
                String err = null;
                try {
                    body = pull0();
                } catch (SyncErr e) {
                    err = e.getMessage();
                } catch (Throwable t) {
                    err = "读取出错：" + brief(t);
                }
                final String fbody = body;
                final String ferr = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (ferr == null) c.ok(fbody);
                        else c.fail(ferr);
                    }
                });
            }
        }, "zsb-gist-pull").start();
    }

    // ── 对外：同步（阻塞，勿在主线程调）────────────────────
    /**
     * 创建或更新 gist，返回 gistId；失败返回 null（原因见 getLastError）。
     * gistId 为空时走 POST 新建，非空时走 PUT 覆盖同名文件。
     */
    public String push(String localJson) {
        lastError = null;
        try {
            return push0(localJson);
        } catch (SyncErr e) {
            lastError = e.getMessage();
            return null;
        } catch (Throwable t) {
            lastError = "同步出错：" + brief(t);
            return null;
        }
    }

    /** 拉取 zsb-progress.json 正文；没有 gist 或文件不存在返回 null。 */
    public String pull() {
        lastError = null;
        try {
            return pull0();
        } catch (SyncErr e) {
            lastError = e.getMessage();
            return null;
        } catch (Throwable t) {
            lastError = "读取出错：" + brief(t);
            return null;
        }
    }

    // ── 内部实现 ──────────────────────────────────────────
    /** 内部错误载体：message 已经是能直接显示给用户的中文。 */
    private static class SyncErr extends Exception {
        private static final long serialVersionUID = 1L;

        SyncErr(String m) {
            super(m);
        }
    }

    private String push0(String localJson) throws SyncErr {
        if (token.isEmpty()) throw new SyncErr("还没填 GitHub Token");
        String content = (localJson == null || localJson.trim().isEmpty()) ? "{}" : localJson;

        JSONObject fileObj = new JSONObject();
        JSONObject wrap = new JSONObject();
        try {
            fileObj.put("content", content);
            wrap.put(FILE, fileObj);
        } catch (Throwable t) {
            throw new SyncErr("进度内容无法编码成 JSON");
        }

        // 已有 gistId → 只覆盖文件内容；404 说明 gist 被删了，退化回新建
        //
        // ⚠ 这里必须用 POST，不能用 PUT。
        // GitHub 更新 gist 的接口是 PATCH /gists/{id}，但 Android 的
        // HttpURLConnection 只允许 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，
        // PATCH 会直接抛 ProtocolException。而 PUT /gists/{id} 这个路由
        // 根本不存在 —— 实测返回 HTTP 404 "Not Found"。
        //
        // 后果（2026-10-28 审计发现，一直是坏的）：每次"上传到云端"都被
        // 当成"gist 已被删除"，走到下面新建一个全新 gist，并把本地保存的
        // gistId 悄悄改成新的。用户账号下越积越多僵尸 gist，另一台记着
        // 旧 id 的手机永远拉不到最新进度。
        //
        // 实测（真实 token 打 api.github.com）：
        //   PUT   /gists/{id}  → 404 Not Found
        //   PATCH /gists/{id}  → 200
        //   POST  /gists/{id}  → 200   ← 用它，HttpURLConnection 原生支持
        //   POST  + X-HTTP-Method-Override: PATCH → 200（等价，不需要）
        if (hasGist()) {
            JSONObject upd = new JSONObject();
            try {
                upd.put("files", wrap);
            } catch (Throwable t) {
                throw new SyncErr("请求体构造失败");
            }
            try {
                Resp r = send("POST", API + "/" + gistId, upd.toString());
                if (r.code == 200 || r.code == 201) {
                    String id = idOf(r.body);
                    if (id != null && !id.isEmpty()) gistId = id;
                    return gistId;
                }
                if (r.code != 404) throw new SyncErr(httpMsg(r.code, r.body));
                // 404：gist 不存在（被删/换了账号）→ 往下走新建
            } catch (SyncErr e) {
                throw e;
            } catch (Throwable t) {
                throw new SyncErr(netMsg(t));
            }
        }

        JSONObject create = new JSONObject();
        try {
            create.put("description", DESC);
            create.put("public", false);
            create.put("files", wrap);
        } catch (Throwable t) {
            throw new SyncErr("请求体构造失败");
        }
        try {
            Resp r = send("POST", API, create.toString());
            if (r.code == 201 || r.code == 200) {
                String id = idOf(r.body);
                if (id == null || id.isEmpty()) throw new SyncErr("云端没返回 gist id");
                gistId = id;
                return gistId;
            }
            throw new SyncErr(httpMsg(r.code, r.body));
        } catch (SyncErr e) {
            throw e;
        } catch (Throwable t) {
            throw new SyncErr(netMsg(t));
        }
    }

    private String pull0() throws SyncErr {
        if (token.isEmpty()) throw new SyncErr("还没填 GitHub Token");
        if (!hasGist()) return null;   // 没 id 就是没东西可拉，不算错误

        Resp r;
        try {
            r = send("GET", API + "/" + gistId, null);
        } catch (Throwable t) {
            throw new SyncErr(netMsg(t));
        }
        if (r.code == 404) return null;       // gist 不存在 → 当没有
        if (r.code != 200) throw new SyncErr(httpMsg(r.code, r.body));

        try {
            JSONObject root = new JSONObject(r.body);
            JSONObject files = root.optJSONObject("files");
            if (files == null) return null;
            JSONObject f = files.optJSONObject(FILE);
            if (f == null) return null;
            // GitHub 超过 1MB 的文件不给 content，要另取 raw_url —— 进度不会那么大
            if (!f.has("content") || f.isNull("content")) return null;
            return f.optString("content", null);
        } catch (Throwable t) {
            throw new SyncErr("云端返回内容不是合法 JSON");
        }
    }

    // ── HTTP 底座 ─────────────────────────────────────────
    private static class Resp {
        int code;
        String body = "";
    }

    /**
     * 发一次请求并把响应体读全。
     * 网络层异常不在这里翻译，统一抛给上层用 netMsg() 转中文。
     */
    private Resp send(String method, String url, String body) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "zsb-app");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            c.setUseCaches(false);

            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream os = c.getOutputStream();
                try {
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                } finally {
                    os.close();
                }
            }

            Resp r = new Resp();
            r.code = c.getResponseCode();
            InputStream in = null;
            try {
                if (r.code >= 200 && r.code < 300) in = c.getInputStream();
                else in = c.getErrorStream();
                r.body = in == null ? "" : readAll(in);
            } catch (IOException ignored) {
                // 错误响应偶发没有 body，保持空串即可
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) { }
            }
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), "UTF-8");
    }

    /** 从响应里取 gist id。取不到返回 null。 */
    private static String idOf(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(body);
            if (!o.has("id") || o.isNull("id")) return null;
            return o.optString("id", null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把 HTTP 状态码翻成人话，带上 GitHub 自己的 message 便于定位。 */
    static String httpMsg(int code, String body) {
        String detail = "";
        if (body != null && !body.isEmpty()) {
            try {
                JSONObject o = new JSONObject(body);
                if (o.has("message") && !o.isNull("message")) detail = o.optString("message", "");
            } catch (Throwable ignored) {
                // 不是 JSON（多半是网关返回的 HTML），忽略
            }
            if (detail.length() > 120) detail = detail.substring(0, 120) + "…";
        }

        String base;
        if (code == 401) {
            base = "Token 无效或已过期（401），去 GitHub 设置里重新生成一个带 gist 权限的 token";
        } else if (code == 403) {
            base = "GitHub 拒绝了请求（403）：token 缺 gist 权限，或请求太频繁被限流，稍再试";
        } else if (code == 404) {
            base = "找不到这个 Gist（404），检查 Gist ID 是否贴错";
        } else if (code == 422) {
            base = "云端不接受这次提交（422），进度内容可能过大或格式不对";
        } else if (code >= 500) {
            base = "GitHub 服务器出错（" + code + "），过一会儿再试";
        } else {
            base = "请求失败（HTTP " + code + "）";
        }
        return detail.isEmpty() ? base : base + "；" + detail;
    }

    /** 网络层异常 → 中文。绝不把 token 拼进消息。 */
    private static String netMsg(Throwable t) {
        if (t instanceof SocketTimeoutException) {
            return "连接超时（15 秒），网络不稳或 GitHub 暂时连不上，换个网络再试";
        }
        if (t instanceof UnknownHostException) {
            return "域名解析失败，检查手机是否联网";
        }
        if (t instanceof javax.net.ssl.SSLException) {
            return "HTTPS 连接失败（SSL），可能是系统时间不对或网络被劫持";
        }
        if (t instanceof IOException) {
            String m = t.getMessage();
            return "网络请求失败" + (m == null || m.isEmpty() ? "" : "：" + brief(t));
        }
        return "请求异常：" + brief(t);
    }

    /** 异常摘要，只用类名+短消息，避免把 URL 里的查询串或 token 带出去。 */
    private static String brief(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null) m = "";
        if (m.length() > 80) m = m.substring(0, 80) + "…";
        return t.getClass().getSimpleName() + (m.isEmpty() ? "" : "(" + m + ")");
    }

    // ══════════════════════════════════════════════════════
    //  合并策略
    // ══════════════════════════════════════════════════════
    /**
     * 合并两端进度 JSON，返回合并后的 JSON 字符串。永不抛异常。
     *
     * 规则（按「宁可多记完成，不可丢进度」的原则）：
     *   ① done / completed / star —— 勾选类，**并集**。任一端标记完成即视为完成。
     *      这是整个策略的核心：设备 A 勾了、设备 B 没勾，合并后是勾上的。
     *   ② hard / timing —— 数值类，**取较大值**。错题次数 n 取大；
     *      计时 ms 也取大（严格说累计该相加，但双端都在累加同一段时间，
     *      相加会把时长翻倍，取大更接近真实投入）。
     *   ③ notes —— 文本类，哪端非空用哪端；两端都有内容且不同，**用本地**
     *      （本地是用户刚敲的，远端多半是旧稿）。这条是唯一偏向本地的规则。
     *   ④ wrong（错题本，若有）—— 按题目 id 对齐：n 取大、ts 取新、
     *      学科/题干哪端非空用哪端。
     *   ⑤ 其它未知字段 —— 本地有就留本地，本地没有再取远端，不丢字段。
     *   ⑥ updatedAt 统一改写为合并时刻。
     *
     * 输入既可以是 StateLogic.exportJson() 那种 {_app,_version,state:{...}} 外层包装，
     * 也可以是 StateLogic.serialize() 那种裸状态对象；两端形态不一致也能合。
     * 输出形态跟随输入：任一端有包装就输出带包装的结构（跟 exportJson 一致）。
     * 任一端为 null/空/坏 JSON 时，等价于只取另一端，不会返回 null。
     */
    public static String merge(String localJson, String remoteJson) {
        JSONObject lo = parseObj(localJson);
        JSONObject ro = parseObj(remoteJson);

        JSONObject ls = stateOf(lo);
        JSONObject rs = stateOf(ro);
        boolean wrapped = (lo != null && lo.optJSONObject("state") != null)
                || (ro != null && ro.optJSONObject("state") != null);

        JSONObject out;
        if (ls == null && rs == null) {
            out = new JSONObject();
        } else if (ls == null) {
            out = copy(rs);
        } else if (rs == null) {
            out = copy(ls);
        } else {
            out = mergeState(ls, rs);
        }

        try {
            if (!wrapped) return out.toString();
            JSONObject root = new JSONObject();
            root.put("_app", "zsb-plan");
            root.put("_version", 1);
            root.put("_exportedAt", nowIso());
            root.put("state", out);
            return root.toString();
        } catch (Throwable t) {
            return out.toString();
        }
    }

    /** 合并两个裸状态对象。 */
    private static JSONObject mergeState(JSONObject a, JSONObject b) {
        JSONObject out = new JSONObject();

        // ① 勾选类：并集
        unionBool(out, "done", a, b);
        unionBool(out, "completed", a, b);
        unionBool(out, "star", a, b);

        // ② 数值类：取大
        maxNum(out, "hard", a, b);
        maxNum(out, "timing", a, b);

        // ③ 文本类
        mergeText(out, "notes", a, b);

        // ④ 错题本条目
        mergeWrong(out, "wrong", a, b);

        // ⑤ 其余字段：本地优先，本地没有再取远端
        copyRest(out, a, b);
        copyRest(out, b, a);

        try {
            out.put("updatedAt", nowIso());
        } catch (Throwable ignored) {
            // 放不进去就算了，不影响合并结果
        }
        return out;
    }

    /** 并集：任一端为 true 就是 true。 */
    private static void unionBool(JSONObject out, String field, JSONObject a, JSONObject b) {
        JSONObject ja = sub(a, field);
        JSONObject jb = sub(b, field);
        if (ja == null && jb == null) return;
        JSONObject r = new JSONObject();
        try {
            if (ja != null) {
                for (Iterator<String> it = ja.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (ja.optBoolean(k, false)) r.put(k, true);
                }
            }
            if (jb != null) {
                for (Iterator<String> it = jb.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (jb.optBoolean(k, false)) r.put(k, true);
                }
            }
            out.put(field, r);
        } catch (Throwable ignored) {
            // 单字段失败不影响其它字段
        }
    }

    /** 取较大值：两端都有取 max，只有一端有就保留它。 */
    private static void maxNum(JSONObject out, String field, JSONObject a, JSONObject b) {
        JSONObject ja = sub(a, field);
        JSONObject jb = sub(b, field);
        if (ja == null && jb == null) return;
        JSONObject r = new JSONObject();
        try {
            if (ja != null) {
                for (Iterator<String> it = ja.keys(); it.hasNext(); ) {
                    String k = it.next();
                    r.put(k, ja.optLong(k, 0L));
                }
            }
            if (jb != null) {
                for (Iterator<String> it = jb.keys(); it.hasNext(); ) {
                    String k = it.next();
                    long v = jb.optLong(k, 0L);
                    if (!r.has(k) || v > r.optLong(k, 0L)) r.put(k, v);
                }
            }
            out.put(field, r);
        } catch (Throwable ignored) {
        }
    }

    /** 文本：非空优先，两端都非空且不同则本地（a）优先。 */
    private static void mergeText(JSONObject out, String field, JSONObject a, JSONObject b) {
        JSONObject ja = sub(a, field);
        JSONObject jb = sub(b, field);
        if (ja == null && jb == null) return;
        JSONObject r = new JSONObject();
        try {
            if (jb != null) {
                for (Iterator<String> it = jb.keys(); it.hasNext(); ) {
                    String k = it.next();
                    String v = strOf(jb, k);
                    if (v != null && !v.isEmpty()) r.put(k, v);
                }
            }
            if (ja != null) {
                for (Iterator<String> it = ja.keys(); it.hasNext(); ) {
                    String k = it.next();
                    String v = strOf(ja, k);
                    if (v == null) continue;
                    String old = strOf(r, k);
                    // 本地非空就覆盖；本地为空但云端也为空，留本地那个空值
                    if (!v.isEmpty() || old == null) r.put(k, v);
                    else if (old.isEmpty()) r.put(k, v);
                }
            }
            out.put(field, r);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 错题本：按题目 id 对齐。
     * n 取大（做错次数不因换设备而变少），ts 取新（最近一次做错时间），
     * 学科/题干非空优先本地。
     */
    private static void mergeWrong(JSONObject out, String field, JSONObject a, JSONObject b) {
        JSONObject ja = sub(a, field);
        JSONObject jb = sub(b, field);
        if (ja == null && jb == null) return;
        JSONObject r = new JSONObject();
        try {
            java.util.HashSet<String> keys = new java.util.HashSet<>();
            if (ja != null) for (Iterator<String> it = ja.keys(); it.hasNext(); ) keys.add(it.next());
            if (jb != null) for (Iterator<String> it = jb.keys(); it.hasNext(); ) keys.add(it.next());

            for (String k : keys) {
                JSONObject ea = ja == null ? null : ja.optJSONObject(k);
                JSONObject eb = jb == null ? null : jb.optJSONObject(k);
                if (ea == null && eb == null) continue;
                JSONObject e = new JSONObject();
                // n / ts 取大
                long n = Math.max(ea == null ? 0 : ea.optLong("n", 0), eb == null ? 0 : eb.optLong("n", 0));
                long ts = Math.max(ea == null ? 0 : ea.optLong("ts", 0), eb == null ? 0 : eb.optLong("ts", 0));
                e.put("n", n <= 0 ? 1 : n);
                e.put("ts", ts);
                // 其余文本字段：本地非空优先
                e.put("s", firstNonEmpty(strOf(ea, "s"), strOf(eb, "s")));
                e.put("q", firstNonEmpty(strOf(ea, "q"), strOf(eb, "q")));
                r.put(k, e);
            }
            out.put(field, r);
        } catch (Throwable ignored) {
        }
    }

    /** 把 primary 里 out 还没有的字段搬过来（a 是 prefer 端）。 */
    private static void copyRest(JSONObject out, JSONObject prefer, JSONObject other) {
        if (prefer == null) return;
        for (Iterator<String> it = prefer.keys(); it.hasNext(); ) {
            String k = it.next();
            if (isKnown(k)) continue;
            if (out.has(k)) continue;
            try {
                out.put(k, prefer.opt(k));
            } catch (Throwable ignored) {
            }
        }
        if (other == null) return;
        for (Iterator<String> it = other.keys(); it.hasNext(); ) {
            String k = it.next();
            if (isKnown(k)) continue;
            if (out.has(k)) continue;
            try {
                out.put(k, other.opt(k));
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isKnown(String k) {
        return "done".equals(k) || "completed".equals(k) || "star".equals(k)
                || "notes".equals(k) || "hard".equals(k) || "timing".equals(k)
                || "wrong".equals(k) || "updatedAt".equals(k)
                || "_app".equals(k) || "_version".equals(k) || "_exportedAt".equals(k)
                || "state".equals(k);
    }

    // ── 合并用的小工具 ────────────────────────────────────
    private static JSONObject parseObj(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() < 2 || t.charAt(0) != '{') return null;
        try {
            return new JSONObject(t);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 取状态对象：有 state 包装就取里面，否则认为整个就是状态。 */
    private static JSONObject stateOf(JSONObject root) {
        if (root == null) return null;
        JSONObject st = root.optJSONObject("state");
        return st != null ? st : root;
    }

    private static JSONObject sub(JSONObject o, String k) {
        if (o == null || !o.has(k)) return null;
        return o.optJSONObject(k);
    }

    /** 安全取字符串：key 不存在返回 null（org.json 的 optString(k,null) 会 NPE）。 */
    private static String strOf(JSONObject o, String k) {
        if (o == null || !o.has(k)) return null;
        try {
            if (o.isNull(k)) return null;
            return o.optString(k, "");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        if (b != null && !b.isEmpty()) return b;
        return "";
    }

    private static JSONObject copy(JSONObject o) {
        if (o == null) return new JSONObject();
        try {
            return new JSONObject(o.toString());
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static String nowIso() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.CHINA)
                .format(new java.util.Date());
    }
}