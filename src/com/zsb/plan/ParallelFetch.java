package com.zsb.plan;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分段并发下载。
 *
 * <h3>为什么要分段</h3>
 * 2026-10-31 本机实测（代理关闭，每连接取 1 MB 样本，各连接取文件不同位置）：
 *
 * <pre>
 *   github.com 直连    1 路    780 KB/s        447 MB 约 10 分钟
 *   github.com 直连    8 路   4401 KB/s  (5.6x) 约  2 分钟
 *   github.com 直连   12 路    106 KB/s  (0.1x) 被限流打回
 *   gh.monlor.com      1 路    602 KB/s
 *   gh.monlor.com      8 路   4333 KB/s  (7.2x) 约  2 分钟
 *   gh.monlor.com     12 路   7521 KB/s (12.5x) 约  1 分钟
 * </pre>
 *
 * 两条结论都跟直觉相反：
 *   ① <b>单连接速度不是线路带宽</b>，只是一个 TCP 流能抢到的份额。
 *      开到 8 路，总吞吐是单路的 5~7 倍 —— 这才是手机上真正可用的速度。
 *   ② <b>并发不是越多越好</b>。GitHub 直连到 12 路会触发限流，掉到 1/7。
 *      所以上限钉死在 8；某段失败就降并发重试，不硬顶。
 *
 * <h3>为什么这比"多试几个镜像"有用</h3>
 * 镜像列表再长，单连接还是那个速度。实测 ghproxy.net 193 KB/s、
 * gh-proxy.com 10 KB/s —— 447 MB 分别要 40 分钟和 12 小时。
 * 换源只能在"能连上 / 连不上"之间选，改不了单流带宽。
 * 分段并发是把可用带宽真正吃满，和选哪个源**正交**，两者叠加才够。
 *
 * <h3>正确性</h3>
 * 每段自带 Range 区间，写进同一个文件的固定偏移，互不重叠。
 * 段内失败只重下该段**剩余部分**，不重下整包。下完校验总长度，
 * 再由 Update 校验 zip 头和包内 versionName —— 三道关都过了才交给安装器。
 */
final class ParallelFetch {

    /** 单段最小 2 MB。再小则请求数暴涨，握手开销吃掉收益。 */
    private static final long MIN_SEG = 2L * 1024 * 1024L;

    /**
     * 并发上限 8。
     *
     * 实测 12 路时 GitHub 直连被限流打回（7521 → 106 KB/s），
     * 而 8 路对两边都是拐点。宁可少一路，不要触发风控。
     */
    static final int MAX_CONNS = 8;

    /** 每段最多重试 3 次。指数退避 0.8s / 1.6s / 3.2s，与项目铁律一致。 */
    private static final int SEG_RETRY = 3;
    private static final long[] BACKOFF = {800, 1600, 3200};

    private ParallelFetch() {
    }

    /** 探测结果。 */
    static final class Probe {
        /** 跟完重定向后的最终地址（GitHub 会 302 到 release-assets...）。 */
        String url;
        long total;
        /** 服务端是否认 Range。不认就只能退回单流下载。 */
        boolean range;
    }

    interface Listener {
        void onBytes(long done, long total);
        /** 返回 true 表示调用方要求中止（例如用户退出页面）。 */
        boolean cancelled();
    }

    /** 服务端不支持分段时抛这个，调用方退回单流。 */
    static class NoRange extends Exception {
        NoRange(String m) { super(m); }
    }

    /**
     * 把 total 切成若干段。纯函数，方便桌面 JVM 上直接测。
     *
     * 段数取并发数的 <b>2 倍</b>：某一路恰好分配到慢段时，
     * 先做完的线程还能去领下一段，不至于让整体卡在那一段上。
     *
     * @return 每项 {起始字节, 结束字节}（含两端）
     */
    static long[][] plan(long total, int conns) {
        if (total <= 0) return new long[0][];
        if (conns < 1) conns = 1;
        if (conns > MAX_CONNS) conns = MAX_CONNS;
        int want = conns * 2;
        long seg = Math.max(MIN_SEG, (total + want - 1) / want);
        int n = (int) ((total + seg - 1) / seg);
        if (n < 1) n = 1;
        long[][] out = new long[n][];
        long p = 0;
        for (int i = 0; i < n; i++) {
            long end = Math.min(total - 1, p + seg - 1);
            out[i] = new long[]{p, end};
            p = end + 1;
        }
        return out;
    }

    /**
     * 探一次：拿最终地址、总长度、是否支持 Range。
     * 只发一个 1 字节的 Range 请求，成本可以忽略。
     */
    static Probe probe(String url) throws Exception {
        Probe p = new Probe();
        String target = url;
        HttpURLConnection c = null;
        for (int hop = 0; hop < 6; hop++) {
            c = open(target, 0, 0);
            int sc = c.getResponseCode();
            if (sc == 301 || sc == 302 || sc == 303 || sc == 307 || sc == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("重定向无地址");
                target = Update.resolveUrl(target, loc);
                continue;
            }
            if (sc == 206) {
                p.range = true;
                p.total = totalOf(c.getHeaderField("Content-Range"));
                p.url = target;
                c.disconnect();
                return p;
            }
            if (sc == 200) {
                // 服务端无视 Range 直接回整包 —— 不支持分段
                p.range = false;
                p.total = c.getContentLength();
                p.url = target;
                c.disconnect();
                return p;
            }
            c.disconnect();
            throw new Exception("HTTP " + sc);
        }
        throw new Exception("重定向过多");
    }

    /** 解析 "bytes 0-0/469467782" 里的总长度。 */
    static long totalOf(String contentRange) {
        if (contentRange == null) return -1;
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= contentRange.length()) return -1;
        try {
            long v = Long.parseLong(contentRange.substring(slash + 1).trim());
            return v > 0 ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 分段下载到 out。返回写入的总字节数。
     *
     * @param conns 并发路数，1..{@link #MAX_CONNS}
     * @throws NoRange 服务端不支持 Range，调用方应退回单流下载
     */
    static long fetch(String url, File out, int conns, Listener l) throws Exception {
        Probe p = probe(url);
        if (!p.range || p.total <= 0) {
            throw new NoRange(p.range ? "拿不到文件总长度" : "服务端不支持 Range");
        }
        if (conns < 1) conns = 1;
        if (conns > MAX_CONNS) conns = MAX_CONNS;

        final long total = p.total;
        final String finalUrl = p.url;
        final long[][] segs = plan(total, conns);

        final RandomAccessFile raf = new RandomAccessFile(out, "rw");
        try {
            raf.setLength(total);
            final AtomicLong done = new AtomicLong(0);
            final AtomicInteger next = new AtomicInteger(0);
            final Exception[] boom = new Exception[1];
            final java.util.concurrent.atomic.AtomicBoolean stop =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            int workers = Math.min(conns, segs.length);
            Thread[] ts = new Thread[workers];
            for (int i = 0; i < workers; i++) {
                ts[i] = new Thread(new Runnable() {
                    public void run() {
                        for (;;) {
                            if (stop.get()) return;
                            int k = next.getAndIncrement();
                            if (k >= segs.length) return;
                            try {
                                pull(finalUrl, segs[k], raf, done, total, l, stop);
                            } catch (Exception e) {
                                // 某一段彻底失败：记下来并叫停其它路，
                                // 免得白白把剩余流量跑完。
                                synchronized (boom) {
                                    if (boom[0] == null) boom[0] = e;
                                }
                                stop.set(true);
                                return;
                            }
                        }
                    }
                });
                ts[i].setDaemon(true);
                ts[i].start();
            }
            for (Thread t : ts) {
                try { t.join(); } catch (InterruptedException ignored) { }
            }

            if (l != null && l.cancelled()) throw new Exception("已取消");
            if (boom[0] != null) throw boom[0];

            long got = done.get();
            if (got != total) {
                throw new Exception("分段下载不完整（" + got + "/" + total + " 字节）");
            }
            return total;
        } finally {
            try { raf.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 拉一段。失败按指数退避重试，**从已下到的位置接着下**，不重头来。
     */
    private static void pull(String url, long[] seg, RandomAccessFile raf,
                             AtomicLong done, long total, Listener l,
                             java.util.concurrent.atomic.AtomicBoolean stop)
            throws Exception {
        long cur = seg[0];
        Exception last = null;
        for (int attempt = 0; attempt < SEG_RETRY; attempt++) {
            if (stop.get()) throw new Exception("已中止");
            HttpURLConnection c = null;
            try {
                c = open(url, cur, seg[1]);
                int sc = c.getResponseCode();
                if (sc == 416) {
                    // 区间越界说明这文件在服务端变了，重头探一次
                    throw new Exception("区间失效");
                }
                if (sc != 206) throw new Exception("HTTP " + sc + "（该源可能不支持分段）");

                InputStream in = c.getInputStream();
                byte[] buf = new byte[64 * 1024];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (stop.get() || (l != null && l.cancelled())) {
                            throw new Exception("已中止");
                        }
                        raf.seek(cur);
                        raf.write(buf, 0, n);
                        cur += n;
                        long d = done.addAndGet(n);
                        if (l != null) l.onBytes(d, total);
                        if (cur > seg[1]) break;
                    }
                } finally {
                    try { in.close(); } catch (Exception ignored) { }
                }
                if (cur > seg[1]) return;           // 这段完成
                throw new Exception("连接提前结束");   // 半截，退避后补
            } catch (Exception e) {
                last = e;
                if (stop.get()) throw e;
            } finally {
                if (c != null) c.disconnect();
            }
            // 退避：只在还有下一次机会时等
            if (attempt < SEG_RETRY - 1) {
                try {
                    Thread.sleep(BACKOFF[Math.min(attempt, BACKOFF.length - 1)]);
                } catch (InterruptedException ignored) { }
            }
        }
        throw last == null ? new Exception("分段失败") : last;
    }

    /** 开一个带 Range 的连接。跟重定向由调用方负责。 */
    private static HttpURLConnection open(String url, long from, long to)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "zsb-plan");
        c.setRequestProperty("Range", "bytes=" + from + "-" + to);
        // 必须显式要 identity。否则中间层（Cloudflare / nginx / 镜像）可能
        // 对响应做 gzip/br 压缩，Content-Length 和 Content-Range 描述的是
        // 压缩前的长度，而实际流出来的是压缩后的字节 ——
        // 段长度对不上、偏移全错，拼出来的 APK 是坏的，
        // 而且**不会抛任何异常**，只会装包失败或直接闪退。
        // APK 本身已是 zip（内部已压缩），identity 不会让传输变大。
        c.setRequestProperty("Accept-Encoding", "identity");
        if (url.contains("api.github.com")) {
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        }
        return c;
    }
}