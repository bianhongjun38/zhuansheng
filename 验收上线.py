# -*- coding: utf-8 -*-
"""上线后最终验收：逐项实测。"""
import sys, time, json, urllib.request
sys.stdout.reconfigure(encoding="utf-8")
OP = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def head(url, rng=None, extra=None, timeout=25):
    h = {"User-Agent": "Mozilla/5.0 zsb-check"}
    if rng: h["Range"] = rng
    if extra: h.update(extra)
    t0 = time.time()
    try:
        r = urllib.request.Request(url, headers=h)
        with OP.open(r, timeout=timeout) as resp:
            body = resp.read()
            return resp.status, len(body), time.time()-t0, body
    except urllib.error.HTTPError as e:
        return e.code, 0, time.time()-t0, b""
    except Exception as e:
        return None, 0, time.time()-t0, str(e)[:60].encode()

checks = []

def show(name, ok, detail):
    tag = "✓" if ok else "✗"
    checks.append((name, ok))
    print("  %s %-34s %s" % (tag, name, detail))

print("=" * 70)
print("① 清单（旧版客户端唯一入口）")
print("=" * 70)
st, n, dt, body = head("https://zsb-update.pages.dev/update.json")
man = json.loads(body) if st == 200 else {}
show("update.json 可访问", st == 200, "HTTP %s  %.2fs" % (st, dt))
show("versionName = 1.5.45", man.get("versionName") == "1.5.45", str(man.get("versionName")))
show("versionInt = 10545", man.get("versionInt") == 10545, str(man.get("versionInt")))
show("apk 为 null（不指向死链）", man.get("apk") is None, repr(man.get("apk")))
show("apkUrl 已给出", bool(man.get("apkUrl")), (man.get("apkUrl") or "")[-30:])
show("downloadUrl 已给出", bool(man.get("github", {}).get("downloadUrl")), "ok" if man.get("github", {}).get("downloadUrl") else "缺失")

print()
print("=" * 70)
print("② GitHub 权威清单")
print("=" * 70)
st, n, dt, body = head("https://api.github.com/repos/bianhongjun38/zhuansheng/releases/latest",
                       extra={"Accept": "application/vnd.github+json"})
rel = json.loads(body) if st == 200 else {}
show("releases/latest 可访问", st == 200, "HTTP %s  %.2fs" % (st, dt))
show("tag = v1.5.45", rel.get("tag_name") == "v1.5.45", str(rel.get("tag_name")))
a0 = (rel.get("assets") or [{}])[0]
show("资产为 1.5.45（不是旧包）", "1.5.45" in a0.get("name", ""), a0.get("name", "无"))
show("资产大小 447.5 MB", abs(a0.get("size", 0) - 469234099) < 1, "%.2f MB" % (a0.get("size", 0)/1048576))

print()
print("=" * 70)
print("③ 下载可用性（Range 前 2MB，验 zip 头）")
print("=" * 70)
web = a0.get("browser_download_url", "")
for name, url, ex in [("api 资产端点", a0.get("url", ""), {"Accept": "application/octet-stream"}),
                      ("github 网页直链", web, None)]:
    st, n, dt, body = head(url, "bytes=0-2097151", ex, timeout=40)
    okzip = body[:4] == b"PK\x03\x04"
    kbs = n/1024/dt if dt > 0 else 0
    show(name, st in (200, 206) and okzip,
         "HTTP %s  %d KB  %.2fs  %d KB/s  zip=%s" % (st, n//1024, dt, kbs, "OK" if okzip else "坏"))

print()
print("=" * 70)
print("④ 教材 PDF 仍在 Pages")
print("=" * 70)
for b, mb in [("comp_book.pdf", 5.3), ("eng_book.pdf", 1.9), ("math_book.pdf", 4.6)]:
    st, n, dt, body = head("https://zsb-update.pages.dev/books/" + b, "bytes=0-1023")
    okpdf = body[:5] == b"%PDF-"
    show(b, st in (200, 206) and okpdf, "HTTP %s  %.1f MB  pdf=%s" % (st, mb, "OK" if okpdf else "坏"))

print()
print("=" * 70)
print("⑤ 下载页")
print("=" * 70)
st, n, dt, body = head("https://zsb-update.pages.dev/")
haslink = b"releases/download/v1.5.45" in body
show("首页可访问", st == 200, "HTTP %s" % st)
show("首页含下载链接", haslink, "有" if haslink else "无")
hasmirror = b"ghproxy.net" in body
show("首页含镜像链接", hasmirror, "有" if hasmirror else "无")

print()
print("=" * 70)
passed = sum(1 for _, ok in checks if ok)
print("结果：%d/%d 通过" % (passed, len(checks)))
if passed < len(checks):
    print("失败项：")
    for nm, ok in checks:
        if not ok: print("  - " + nm)
    sys.exit(1)
print("全部通过")
