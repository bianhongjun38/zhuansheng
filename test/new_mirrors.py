# -*- coding: utf-8 -*-
"""验子智能体推荐的 3 个新镜像：只要 1 次 Range 请求，不压测。"""
import urllib.request, time

GH = "https://github.com/bianhongjun38/zhuansheng/releases/download/v1.5.46/zhuansheng-v1.5.46.apk"
CAND = {
    "ghproxy.imciel.com": "https://ghproxy.imciel.com/" + GH,
    "gitproxy.eu.org":    "https://gitproxy.eu.org/" + GH,
    "gh.catmak.name":     "https://gh.catmak.name/" + GH,
}
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

for n, u in CAND.items():
    try:
        req = urllib.request.Request(u, headers={
            "Range": "bytes=0-1048575", "User-Agent": "zsb-plan"})
        t0 = time.time()
        r = opener.open(req, timeout=20)
        code = r.getcode()
        cr = r.headers.get("Content-Range", "")
        etag = r.headers.get("ETag", "")
        data = r.read()
        el = max(time.time()-t0, 0.001)
        kbps = len(data)/1024.0/el
        print("  %-22s HTTP %d  %6.0f KB/s  ETag=%s  Content-Range=%s" % (
            n, code, kbps, etag[:24] if etag else "-", cr[:40]))
        r.close()
    except Exception as e:
        print("  %-22s 失败: %s" % (n, str(e)[:60]))
    time.sleep(1)
