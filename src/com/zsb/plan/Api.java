package com.zsb.plan;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 后端通话层。就四个动作：注册、登录、推进度、报成绩。
 *
 * 为什么手搓 HttpURLConnection 而不是引 OkHttp：
 * 这个 App 一直是零第三方依赖（连 androidx 都没有），
 * 四个接口犯不上为此拉一个 800KB 的库进来。
 *
 * 所有方法都是阻塞的，调用方负责放到后台线程 ——
 * 和 GistSync 一样的约定，主线程绝不发网络请求。
 */
public class Api {

    /** Worker 地址。换成自定义域名时只改这里。 */
    public static final String BASE = "http://8.156.78.63:8081";

    private static final int TIMEOUT = 20000;

    /** 一次调用的结果。ok=false 时 msg 是给人看的中文。 */
    public static class R {
        public boolean ok;
        public String msg = "";
        public JSONObject body;
        public int code;
    }

    // ── 底层 ──────────────────────────────────────────────

    private static R call(String method, String path, JSONObject payload, String token) {
        R r = new R();
        HttpURLConnection c = null;
        try {
            URL u = new URL(BASE + path);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + token);
            }

            if (payload != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] out = payload.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(out.length);
                OutputStream os = c.getOutputStream();
                os.write(out);
                os.flush();
                os.close();
            }

            r.code = c.getResponseCode();
            InputStream in = r.code >= 200 && r.code < 300
                    ? c.getInputStream() : c.getErrorStream();
            String text = readAll(in);
            // trim 一下再判断首字符：响应体前面带个换行或空格时，
            // 原来的 charAt(0)=='{' 会直接判定"不是 JSON"，
            // 于是 body 为 null、ok 永远 false —— 明明服务端返回了 ok:true。
            if (text != null) text = text.trim();
            if (text != null && text.length() > 0 && text.charAt(0) == '{') {
                r.body = new JSONObject(text);
                // ok 必须同时满足：HTTP 2xx 且 body 里 ok=true。
                // 只看 body 的话，一个 500 响应里若恰好带 ok:true 也会被当真。
                r.ok = (r.code >= 200 && r.code < 300)
                        && r.body.optBoolean("ok", false);
                r.msg = r.body.optString("message", "");
            }
            if (!r.ok && r.msg.isEmpty()) {
                r.msg = friendly(r.code);
            }
        } catch (Exception e) {
            r.ok = false;
            r.msg = netMsg(e);
        } finally {
            if (c != null) c.disconnect();
        }
        return r;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 把异常翻成人话。
     * 同学看到 UnknownHostException 只会以为 App 坏了，
     * 看到「连不上服务器」才知道是网的问题。
     */
    private static String netMsg(Exception e) {
        String n = e.getClass().getSimpleName();
        if (n.contains("UnknownHost") || n.contains("Connect")) return "连不上服务器，检查一下网络";
        if (n.contains("Timeout") || n.contains("SocketTimeout")) return "服务器没响应，稍后再试";
        return "网络出错：" + n;
    }

    private static String friendly(int code) {
        if (code == 401) return "登录过期了，重新登录一下";
        if (code == 404) return "接口不存在，App 可能需要更新";
        if (code >= 500) return "服务器出错了，稍后再试";
        return "请求失败（" + code + "）";
    }

    // ── 四个动作 ──────────────────────────────────────────

    /** 注册。用户名重名会被拒绝，msg 直接给用户看。 */
    public static R register(String username, String password) {
        try {
            JSONObject p = new JSONObject();
            p.put("username", username);
            p.put("password", password);
            return call("POST", "/v1/register", p, null);
        } catch (Exception e) {
            R r = new R(); r.msg = netMsg(e); return r;
        }
    }

    public static R login(String username, String password) {
        try {
            JSONObject p = new JSONObject();
            p.put("username", username);
            p.put("password", password);
            return call("POST", "/v1/login", p, null);
        } catch (Exception e) {
            R r = new R(); r.msg = netMsg(e); return r;
        }
    }

    /**
     * 推进度。
     * 返回 action=conflict 时 body 里带着 serverData，调用方要问用户选哪份。
     */
    public static R pushProgress(String token, String data, long updatedAt, int planVer) {
        try {
            JSONObject p = new JSONObject();
            p.put("data", data);
            p.put("updatedAt", updatedAt);
            p.put("planVer", planVer);
            return call("POST", "/v1/sync", p, token);
        } catch (Exception e) {
            R r = new R(); r.msg = netMsg(e); return r;
        }
    }

    /** 拉进度（换手机用）。 */
    public static R pullProgress(String token) {
        return call("GET", "/v1/sync", null, token);
    }

    /** 上报一轮英语成绩。 */
    public static R postRound(String token, int right, int wrong, long ms,
                              long startedAt, String clientRev) {
        try {
            JSONObject p = new JSONObject();
            p.put("right", right);
            p.put("wrong", wrong);
            p.put("ms", ms);
            p.put("startedAt", startedAt);
            p.put("clientRev", clientRev);
            return call("POST", "/v1/eng/round", p, token);
        } catch (Exception e) {
            R r = new R(); r.msg = netMsg(e); return r;
        }
    }

    /** 排行榜。不需要登录。 */
    public static R board(String range) {
        return call("GET", "/v1/eng/board?range=" + ("week".equals(range) ? "week" : "day"), null, null);
    }

    /** 我的战绩。 */
    public static R me(String token) {
        return call("GET", "/v1/eng/me", null, token);
    }

    // ── 词汇塔 ────────────────────────────────────────────

    /** 上报一场战斗。rev 是幂等键，同一场重传不会在榜上算两次。 */
    public static R postBattle(String token, int floor, int node, boolean win,
                               int gold, int right, int wrong, long ms,
                               long startedAt, String clientRev) {
        try {
            JSONObject p = new JSONObject();
            p.put("floor", floor);
            p.put("node", node);
            p.put("win", win ? 1 : 0);
            p.put("gold", gold);
            p.put("right", right);
            p.put("wrong", wrong);
            p.put("ms", ms);
            p.put("startedAt", startedAt);
            p.put("clientRev", clientRev);
            return call("POST", "/v1/game/round", p, token);
        } catch (Exception e) {
            R r = new R(); r.msg = netMsg(e); return r;
        }
    }

    /**
     * 上传「当前整局进度」快照。
     *
     * 和 postBattle 的区别（这个区别是必须的，不是选择题）：
     *   postBattle  → /v1/game/round，逐场战报，服务端按条 SUM，
     *                 且有单场上限（金币≤300、题数≤40、单日≤60 条）。
     *   postSnapshot→ /v1/game/snapshot，每人每天一条，**覆盖**更新。
     *
     * 主动上传整局进度时金币和题数一定超过「单场」上限，走战报接口
     * 会被 out_of_range 拒收；就算不被拒，SUM 也会把多次上传累加。
     */
    public static R postSnapshot(String token, int floor, int mapNo, int gold,
                                 int right, int wrong, String clientRev) {
        try {
            JSONObject p = new JSONObject();
            p.put("floor", floor);
            p.put("map", mapNo);
            p.put("gold", gold);
            p.put("right", right);
            p.put("wrong", wrong);
            p.put("clientRev", clientRev);
            return call("POST", "/v1/game/snapshot", p, token);
        } catch (Exception e) {
            R r = new R(); r.msg = netMsg(e); return r;
        }
    }

    /** 词汇塔排行榜。和英语榜一样不需要登录。 */
    public static R gameBoard(String range) {
        return call("GET", "/v1/game/board?range=" + ("week".equals(range) ? "week" : "day"), null, null);
    }
}