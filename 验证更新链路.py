# -*- coding: utf-8 -*-
"""端到端验证：模拟三种客户端走完整更新判定链路。"""
import json, sys, urllib.request, urllib.error
sys.stdout.reconfigure(encoding="utf-8")

OP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def get(url, headers=None, timeout=30):
    r = urllib.request.Request(url, headers=headers or {})
    with OP.open(r, timeout=timeout) as resp:
        return resp.status, resp.read()

def parse_ver(s):
    t = (s or "").strip().lstrip("vV")
    cut = len(t)
    for i, ch in enumerate(t):
        if ch != "." and not ch.isdigit():
            cut = i; break
    t = t[:cut]
    if not t: return 0
    p = [int(x) for x in (t.split(".") + ["0","0","0"])[:3]]
    return p[0]*10000 + p[1]*100 + p[2]

print("=" * 66)
print("① 新版客户端（>=1.5.45）：只读 GitHub")
print("=" * 66)
st, body = get("https://api.github.com/repos/bianhongjun38/zhuansheng/releases/latest",
               {"User-Agent": "zsb-plan", "Accept": "application/vnd.github+json"})
rel = json.loads(body)
tag = rel["tag_name"]
gh_code = parse_ver(tag)
print("  HTTP %d  tag=%s  code=%d" % (st, tag, gh_code))
assets = rel.get("assets") or []
# findApk 逻辑：按文件名里的版本取最大
best, best_v = None, -1
for a in assets:
    nm = a["name"]
    v = parse_ver(nm.replace("zhuansheng-v", "").replace(".apk", ""))
    if v > best_v:
        best_v, best = v, a
print("  选中资产 %s  %.2f MB" % (best["name"], best["size"]/1048576))
print("  api 端点 %s" % best["url"])
print("  网页直链 %s" % best["browser_download_url"])

print()
print("=" * 66)
print("② 旧版客户端（<=1.5.44）：读 pages.dev 清单 + GitHub 兜底")
print("=" * 66)
st, body = get("https://zsb-update.pages.dev/update.json", {"User-Agent": "zsb-plan"})
man = json.loads(body)
print("  HTTP %d  versionName=%s  versionInt=%d" % (st, man["versionName"], man["versionInt"]))
print("  apk=%s   apkUrl=%s" % (man.get("apk"), (man.get("apkUrl") or "(无)")[:70]))

same = (man["versionInt"] == gh_code)
print("  版本一致（决定旧版能否被接走）：%s" % ("✓ 是" if same else "✗ 否 —— 旧版会失联！"))

print()
print("=" * 66)
print("③ 实际可下载性（Range 前 1MB，模拟客户端断点续传探测）")
print("=" * 66)
cands = [("api 端点", best["url"], {"Accept": "application/octet-stream"}),
         ("网页直链", best["browser_download_url"], {})]
for pre in ["https://ghproxy.net/"]:
    cands.append(("镜像 ghproxy", pre + best["browser_download_url"], {}))
import time
for name, url, hdr in cands:
    hh = {"User-Agent": "zsb-plan", "Range": "bytes=0-1048575"}
    hh.update(hdr)
    t0 = time.time()
    try:
        r = urllib.request.Request(url, headers=hh)
        with OP.open(r, timeout=30) as resp:
            data = resp.read()
            st = resp.status
        dt = time.time() - t0
        zip_ok = data[:4] == b"PK\x03\x04"
        print("  %-14s HTTP %d  %d KB  %.2fs  %d KB/s  zip头=%s"
              % (name, st, len(data)//1024, dt, len(data)/1024/dt, "OK" if zip_ok else "坏"))
    except Exception as e:
        print("  %-14s FAIL %.1fs %s" % (name, time.time()-t0, str(e)[:50]))
