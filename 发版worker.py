# -*- coding: utf-8 -*-
"""
发版到 Cloudflare Worker（dl.xxjcloudns.kdns.fr）

用法：
    python 发版worker.py                # 构建 + 部署 + 自检
    python 发版worker.py --no-build     # 只把现有 APK 推上去

为什么不再用 Pages：
    Pages 单文件上限 25 MiB，APK 已经 20.13 MiB，再长几个版本就顶死。
    Worker 静态资源没有这个单文件限制，而且可以绑自有域名
    （*.workers.dev 在国内 DNS 污染，实测解析出 202.160.129.6 /
      103.73.161.52 / 74.86.142.55，TCP 443 全部超时）。

为什么不再 302 到 GitHub：
    github.com 主站在国内被阻断（HTTP 000 / 超时 12s），
    旧版 Worker 一跳过去就下不动。现在 APK 本体随 Worker 一起上传。
"""
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
WORKER = os.path.join(HERE, "worker")
PUBLIC = os.path.join(WORKER, "public")
BUILD = os.path.join(HERE, "build")
MANIFEST = os.path.join(HERE, "AndroidManifest.xml")

DOMAIN = "https://dl.xxjcloudns.kdns.fr"

# 清掉进程级代理：本机 7897 是死的，不清就会 ECONNREFUSED
for _k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(_k, None)


def sh(cmd, cwd=None, timeout=600):
    """跑命令，返回 (code, output)。"""
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    p = subprocess.run(cmd, cwd=cwd or HERE, shell=True, capture_output=True,
                       timeout=timeout, env=env)
    out = (p.stdout or b"").decode("utf-8", "replace") + (p.stderr or b"").decode("utf-8", "replace")
    return p.returncode, out


def manifest_versions():
    """读 version.json 里的 versionName / versionCode。

    注意：不能从 AndroidManifest.xml 读 —— 这个项目的清单里**故意不写**
    versionName / versionCode（构建时由 aapt2 link 的 --version-* 传进去），
    清单里出现的同名文字只是说明注释。踩过一次，读到 None。
    """
    j = json.loads(open(os.path.join(HERE, "version.json"), encoding="utf-8").read())
    return (str(j.get("versionName") or ""), int(j.get("versionCode") or 0))


def ver_int(v):
    """与 Update.parseVer 同一套换算：1.2.1 → 10201。"""
    p = [int(x or 0) for x in (v.split(".") + ["0", "0"])[:3]]
    return p[0] * 10000 + p[1] * 100 + p[2]


def main():
    do_build = "--no-build" not in sys.argv

    if do_build:
        print("▶ 构建 APK")
        code, out = sh("python build_apk.py", cwd=HERE)
        if code != 0 or "构建成功" not in out:
            print(out[-3000:])
            print("✗ 构建失败，已中止（没有推送任何东西）")
            return 1
        print("  ✅ 构建成功")

    vname, vcode = manifest_versions()
    if not vname:
        print("✗ 读不到 versionName")
        return 1
    apk_name = "zhuansheng-v%s.apk" % vname
    src = os.path.join(BUILD, apk_name)
    if not os.path.exists(src):
        # 构建脚本可能带别的名字，退一步找最新的 apk
        cands = sorted(
            [os.path.join(BUILD, f) for f in os.listdir(BUILD) if f.endswith(".apk")],
            key=os.path.getmtime, reverse=True)
        if not cands:
            print("✗ build 目录里没有 APK")
            return 1
        src = cands[0]
        apk_name = os.path.basename(src)
    size = os.path.getsize(src)
    print("▶ APK  %s  %.2f MB   versionName=%s  versionCode=%d"
          % (apk_name, size / 1048576.0, vname, vcode))

    # 只告警不拦：Worker 没有 Pages 那个 25 MiB 单文件限制，
    # 但一次上传太大部署会慢，提前说一声。
    if size > 100 * 1048576:
        print("  ! APK 超过 100 MB，Worker 能传但部署会很慢，考虑拆分资源")

    # ── 清理 public：只保留当前版本的 apk，老包留在 Pages 那边做归档 ──
    if not os.path.isdir(PUBLIC):
        os.makedirs(PUBLIC)
    for f in os.listdir(PUBLIC):
        if f.endswith(".apk") and f != apk_name:
            os.remove(os.path.join(PUBLIC, f))
            print("  - 移除旧包 %s" % f)

    # ── 教材 PDF：不进 APK，单独放到 /books/ 供按需下载 ──
    # 三本教材共 11.7 MB，占安装包 60%。Cloudflare Pages 与 Workers
    # Static Assets 的单文件上限都是 25 MiB，APK 越涨越危险，
    # 所以 PDF 拆出来：装包只带代码，第一次点「原图」再拉那一本。
    # 上限检查：单个文件必须 < 25 MiB，否则部署会直接失败。
    BOOK_SRC = os.path.join(HERE, "assets_books_offline")
    BOOK_DST = os.path.join(PUBLIC, "books")
    LIMIT = 25 * 1024 * 1024
    if os.path.isdir(BOOK_SRC):
        if not os.path.isdir(BOOK_DST):
            os.makedirs(BOOK_DST)
        for f in sorted(os.listdir(BOOK_SRC)):
            if not f.endswith(".pdf"):
                continue
            # 练习册已从产品里整块移除，别再白白上传
            if "_pract" in f:
                continue
            fp = os.path.join(BOOK_SRC, f)
            sz = os.path.getsize(fp)
            if sz >= LIMIT:
                print("  X %s %.2f MB 超过 25 MiB 单文件上限，部署必失败"
                      % (f, sz / 1048576.0))
                return 1
            if (os.path.exists(os.path.join(BOOK_DST, f))
                    and os.path.getsize(os.path.join(BOOK_DST, f)) == sz):
                print("  = 教材 %s 已在，跳过" % f)
                continue
            shutil.copy2(fp, os.path.join(BOOK_DST, f))
            print("  + 教材 %s  %.2f MB" % (f, sz / 1048576.0))
    else:
        print("  ! 找不到 assets_books_offline，教材不会上传（App 看原图会失败）")
    shutil.copy2(src, os.path.join(PUBLIC, apk_name))
    print("  + 放入 %s" % apk_name)

    manifest = {
        "ok": True,
        "versionName": vname,
        "versionInt": ver_int(vname),
        "versionCode": vcode,
        "notes": "v%s（由 发版worker.py 自动生成）" % vname,
        "apk": {
            "name": apk_name,
            "size": size,
            "url": "%s/%s" % (DOMAIN, apk_name),
        },
        "publishedAt": time.strftime("%Y-%m-%dT%H:%M:%S+08:00"),
        "source": "worker-static",
    }
    js = json.dumps(manifest, ensure_ascii=False, indent=2)
    open(os.path.join(PUBLIC, "update.json"), "w", encoding="utf-8").write(js)
    open(os.path.join(PUBLIC, "version.json"), "w", encoding="utf-8").write(
        json.dumps({"versionName": vname, "versionInt": ver_int(vname)}, ensure_ascii=False))
    print("  + update.json / version.json  versionName=%s" % vname)

    # ── 下载页 ──
    html = """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>专升本 · 词汇塔 下载</title>
<style>body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#0f1115;
color:#e8eaf0;margin:0;padding:32px 20px;line-height:1.7}
.card{max-width:520px;margin:0 auto;background:#171a21;border:1px solid #242a36;
border-radius:16px;padding:26px 24px}
h1{font-size:20px;margin:0 0 4px}p.s{color:#8b93a7;font-size:13px;margin:0 0 20px}
a.b{display:block;text-align:center;background:#3b6cf6;color:#fff;text-decoration:none;
padding:14px;border-radius:10px;font-weight:600;margin:16px 0 8px}
a.b:active{background:#2f58cc}
.m{font-size:12.5px;color:#8b93a7;text-align:center}
code{background:#0b0d11;padding:2px 6px;border-radius:4px;font-size:12px;color:#9fb4d8}</style>
</head><body><div class="card">
<h1>专升本 · 词汇塔</h1>
<p class="s">最新版本 v%s（versionCode %d）</p>
<a class="b" href="/%s">下载 APK</a>
<p class="m">%.2f MB · 安卓 5.0 以上</p>
<p class="m" style="margin-top:18px">机器可读清单 <code>/update.json</code></p>
</div></body></html>""" % (vname, vcode, apk_name, size / 1048576.0)
    open(os.path.join(PUBLIC, "index.html"), "w", encoding="utf-8").write(html)
    print("  + index.html")

    # ── 部署 ──
    print("▶ 部署到 Worker")
    code, out = sh("npx --yes wrangler@latest deploy --config wrangler-upload.toml",
                   cwd=WORKER, timeout=900)
    if code != 0 or "Deployed" not in out:
        print(out[-3000:])
        print("✗ 部署失败")
        return 1
    for line in out.split("\n"):
        if "Current Version ID" in line or "Uploaded zsb-update" in line:
            print("  " + line.strip())

    # ── 自检：真的能下到吗 ──
    print("▶ 自检 %s" % DOMAIN)
    op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    # 必须带 UA：Cloudflare 会拦默认的 Python-urllib/x.y，返回 403。
    # 2026-10-28 踩过 —— 部署明明成功，自检却报 403，白排查一轮。
    UA = {"User-Agent": "zsb-plan"}
    try:
        req = urllib.request.Request(DOMAIN + "/update.json", headers=UA)
        with op.open(req, timeout=25) as r:
            j = json.loads(r.read().decode("utf-8"))
        print("  /update.json  versionName=%s  versionInt=%d"
              % (j.get("versionName"), j.get("versionInt", 0)))
        if j.get("versionName") != vname:
            print("  ! 清单版本与本地不一致（边缘缓存可能还没刷）")
    except Exception as e:
        print("  ! 清单读取失败：%s" % str(e)[:120])

    try:
        req = urllib.request.Request(DOMAIN + "/" + apk_name, method="HEAD", headers=UA)
        with op.open(req, timeout=30) as r:
            print("  /%s  %d  Content-Length=%s  type=%s"
                  % (apk_name, r.status, r.headers.get("Content-Length"),
                     r.headers.get("Content-Type")))
    except Exception as e:
        print("  ! APK HEAD 失败：%s" % str(e)[:120])

    print("\n✅ 发布完成：%s" % DOMAIN)
    print("   手机端 Update.java 主通道已指向 dl.xxjcloudns.kdns.fr")
    return 0


if __name__ == "__main__":
    sys.exit(main())
