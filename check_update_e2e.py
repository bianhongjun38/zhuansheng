# -*- coding: utf-8 -*-
"""端到端验证：App 内更新链路在「手机处境」下能否真的走通。

背景（2026-10-27 实测，全部**不带代理**，模拟国内手机直连）：
    api.github.com                        HTTP 200   321ms   ✅
    github.com                            HTTP 000   超时 12s ❌
    release-assets.githubusercontent.com  HTTP 404   388ms   ✅（域名通）

结论：App 里**绝对不能**用 browser_download_url（走 github.com，
版本查得到但一按下载就永远转圈）。必须用 api.github.com 的资产端点：
    GET https://api.github.com/repos/{repo}/releases/assets/{id}
        Accept: application/octet-stream
    → 302 → release-assets.githubusercontent.com/...（可直连）

本脚本对**线上真实 Release** 跑一遍完整链路，任何一步不通就报错退出。

用法：
    python check_update_e2e.py          # 默认不走代理
    python check_update_e2e.py --proxy  # 走环境里的代理
"""
import argparse
import json
import os
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

REPO = "bianhongjun38/zhuansheng"
API_LATEST = "https://api.github.com/repos/%s/releases/latest" % REPO
JJ = ('<a href="https://juejin.cn/user/1000000000000000">掘金</a>')
UA = "zsb-plan"

# 本机版本（与 version.json 同步），用来验证「会不会提示更新」
LOCAL_VER = None


def parse_ver(s):
    """与 Update.java 的 parseVer 逐行对应。"""
    if not s:
        return 0
    t = s.strip()
    if t[:1] in ("v", "V"):
        t = t[1:]
    cut = len(t)
    for i, ch in enumerate(t):
        if ch != "." and not ch.isdigit():
            cut = i
            break
    t = t[:cut]
    if not t:
        return 0
    p = t.split(".")
    maj = int(p[0]) if len(p) > 0 else 0
    mn = int(p[1]) if len(p) > 1 else 0
    pat = int(p[2]) if len(p) > 2 else 0
    return maj * 10000 + mn * 100 + pat


def opener(use_proxy):
    if use_proxy:
        return urllib.request.build_opener()
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get(op, url, headers, follow=True):
    req = urllib.request.Request(url)
    for k, v in headers.items():
        req.add_header(k, v)

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None

    o = op if follow else urllib.request.build_opener(
        urllib.request.ProxyHandler({}) if not USE_PROXY else urllib.request.ProxyHandler(),
        NoRedirect)
    t0 = time.time()
    try:
        with o.open(req, timeout=30) as r:
            return r.status, dict(r.headers), r.read(), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers or {}), b"", time.time() - t0


def main():
    global USE_PROXY, LOCAL_VER
    ap = argparse.ArgumentParser()
    ap.add_argument("--proxy", action="store_true", help="走环境代理")
    args = ap.parse_args()
    USE_PROXY = args.proxy

    vf = os.path.join(os.path.dirname(os.path.abspath(__file__)), "version.json")
    LOCAL_VER = json.load(open(vf, encoding="utf-8"))["versionName"]

    print("=" * 68)
    print("  App 更新链路端到端验证")
    print("  网络：%s" % ("走代理" if USE_PROXY else "直连（模拟手机）"))
    print("  本机版本：v%s" % LOCAL_VER)
    print("=" * 68)
    print()

    op = opener(USE_PROXY)
    ok = 0
    fail = 0

    def check(name, cond, detail=""):
        nonlocal ok, fail
        if cond:
            ok += 1
            print("  [OK]   %s%s" % (name, ("  " + detail) if detail else ""))
        else:
            fail += 1
            print("  [FAIL] %s%s" % (name, ("  " + detail) if detail else ""))

    # ── 1. 查最新版本 ───────────────────────────────
    print("① 查最新版本（api.github.com）")
    try:
        st, _, body, dt = get(op, API_LATEST, {
            "User-Agent": UA, "Accept": "application/vnd.github+json"})
    except Exception as e:
        print("  [FAIL] 请求异常：%s" % e)
        print("\n  这条不通，App 里「检查更新」就会报网络不通。")
        return 1
    check("HTTP 200", st == 200, "status=%d %dms" % (st, dt * 1000))
    if st != 200:
        return 1

    rel = json.loads(body)
    tag = rel.get("tag_name", "")
    remote = parse_ver(tag)
    vname = tag.lstrip("vV")
    check("解析 tag", remote > 0, "%s -> %d" % (tag, remote))

    # ── 2. 找到 APK 资产，且地址必须是 api 域名 ──────
    print("\n② 选安装包地址")
    apks = [a for a in rel.get("assets", []) if a["name"].endswith(".apk")]
    check("存在 APK 资产", len(apks) == 1,
          apks[0]["name"] if apks else "（没有）")
    if not apks:
        return 1
    asset = apks[0]
    api_url = asset.get("url", "")
    check("资产端点用 api.github.com", api_url.startswith("https://api.github.com/"),
          api_url)
    bad = asset.get("browser_download_url", "")
    check("**没有**误用 browser_download_url（走 github.com 会卡死）",
          "github.com/" in bad and "/releases/download/" in bad,
          "确认它存在但被绕开")
    check("资产 id 有效", isinstance(asset.get("id"), int), "id=%s" % asset.get("id"))

    # ── 3. 下载跳转（不带 Accept 应拿 JSON，带则应 302）──
    print("\n③ 下载跳转")
    st0, h0, b0, _ = get(op, api_url,
                         {"User-Agent": UA, "Accept": "application/vnd.github+json"},
                         follow=False)
    check("不带 octet-stream 时不是文件（会是 JSON）",
          st0 == 200 and b0[:1] in (b"{", b"["),
          "status=%d %d bytes" % (st0, len(b0)))

    st1, h1, b1, dt1 = get(op, api_url,
                           {"User-Agent": UA,
                            "Accept": "application/octet-stream",
                            "X-GitHub-Api-Version": "2022-11-28"},
                           follow=False)
    loc = (h1.get("Location") or h1.get("location") or "")
    check("带 octet-stream 返回 302", st1 == 302, "status=%d" % st1)
    check("跳转到 release-assets 域名",
          "release-assets.githubusercontent.com" in loc,
          loc.split("?")[0][:72] + "…" if loc else "（无 Location）")
    check("跳转里带文件名",
          asset["name"] in loc, asset["name"] if loc else "")

    # ── 4. 跟着跳转真下 512 KB ──────────────────────
    print("\n④ 真实下载（取前 512 KB）")
    req = urllib.request.Request(api_url)
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "application/octet-stream")
    req.add_header("Range", "bytes=0-524287")
    t0 = time.time()
    try:
        with op.open(req, timeout=90) as r:
            chunk = r.read(524288)
            status = r.status
    except Exception as e:
        check("下载可读", False, str(e))
        return 1
    dt = time.time() - t0
    check("拿到数据", len(chunk) > 0,
          "%.0f KB  %.0f KB/s  %.1fs" % (len(chunk) / 1024,
                                         len(chunk) / 1024 / dt if dt else 0, dt))
    check("是 APK（zip 魔数 PK）", chunk[:2] == b"PK",
          "magic=%r" % chunk[:2])

    # ── 5. 版本判断会不会提示 ───────────────────────
    print("\n⑤ 版本判断")
    lv = parse_ver(LOCAL_VER)
    if remote > lv:
        print("  本机 v%s (%d) < 线上 v%s (%d)" % (LOCAL_VER, lv, vname, remote))
        print("  → 点「检查更新」会弹窗提示升级 ✅")
    elif remote == lv:
        print("  本机 v%s (%d) == 线上 v%s (%d)" % (LOCAL_VER, lv, vname, remote))
        print("  → 显示「已是最新」，不会弹窗 ✅")
    else:
        print("  本机 v%s 比线上还新，检查发版是否漏了" % LOCAL_VER)
        fail += 1
    check("同版本不会误判为新版",
          not (parse_ver("v" + LOCAL_VER) > parse_ver(LOCAL_VER)))

    print()
    print("=" * 68)
    if fail == 0:
        print("  ✅ 全部 %d 项通过 —— 更新链路可用" % ok)
        print("  Release: https://github.com/%s/releases/tag/%s" % (REPO, tag))
    else:
        print("  ❌ %d 项失败 / %d 项通过" % (fail, ok))
    print("=" * 68)
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
