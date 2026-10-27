# -*- coding: utf-8 -*-
"""下载 CI 产物并发布 Release（CI 的 GITHUB_TOKEN 无 release 权限时用）。

用法：python make_release.py 1.1.0
"""
import json
import os
import sys
import urllib.request

TOKEN_PATH = r"E:\小黑盒运营\_保险箱\github_token.txt"
OWNER, REPO = "bianhongjun38", "zhuansheng"

for k in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY",
          "all_proxy", "ALL_PROXY"):
    os.environ.pop(k, None)

TOKEN = open(TOKEN_PATH, encoding="utf-8").read().strip()
sys.stdout.reconfigure(encoding="utf-8")


def api(url, method="GET", data=None, ctype=None, raw=False):
    req = urllib.request.Request(url, method=method, data=data)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("User-Agent", "zsb-release")
    req.add_header("Accept", "application/vnd.github+json")
    if ctype:
        req.add_header("Content-Type", ctype)
    with urllib.request.urlopen(req, timeout=120) as r:
        body = r.read()
        return body if raw else (json.loads(body) if body else None)


def download(url, dst):
    """产物下载会 302 到 blob 存储。重定向后必须去掉 Authorization，
    否则 blob 服务器返回 401。所以手动处理重定向。"""
    req = urllib.request.Request(url)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("User-Agent", "zsb-release")
    req.add_header("Accept", "application/vnd.github+json")

    opener = urllib.request.build_opener(NoRedirect)
    try:
        r = opener.open(req, timeout=300)
        resp = r
    except urllib.error.HTTPError as e:
        if e.code not in (301, 302, 303, 307, 308):
            raise
        loc = e.headers["Location"]
        # 关键：新请求不带认证头
        req2 = urllib.request.Request(loc)
        req2.add_header("User-Agent", "zsb-release")
        resp = urllib.request.urlopen(req2, timeout=300)

    with resp, open(dst, "wb") as f:
        while True:
            b = resp.read(65536)
            if not b:
                break
            f.write(b)
    return os.path.getsize(dst)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    ver = sys.argv[1] if len(sys.argv) > 1 else "1.1.0"
    tag = "v" + ver.replace("v", "")
    outdir = os.path.dirname(os.path.abspath(__file__))

    print("▶ 找 %s 的 CI 产物" % tag)
    runs = api("https://api.github.com/repos/%s/%s/actions/runs?per_page=10"
               % (OWNER, REPO))["workflow_runs"]
    run = None
    for r in runs:
        if r["head_branch"] == tag:
            run = r
            break
    if not run:
        print("✗ 没找到 %s 的运行" % tag)
        return 1
    print("  run id=%s  %s" % (run["id"], run["conclusion"]))

    arts = api("https://api.github.com/repos/%s/%s/actions/runs/%s/artifacts"
               % (OWNER, REPO, run["id"]))["artifacts"]
    if not arts:
        print("✗ 没有产物")
        return 1
    art = arts[-1]
    print("  产物 %s  %.2f MB" % (art["name"], art["size_in_bytes"] / 1048576))

    # 下载 zip
    zp = os.path.join(outdir, "ci_artifact.zip")
    print("▶ 下载产物")
    download(art["archive_download_url"], zp)
    print("  %.2f MB" % (os.path.getsize(zp) / 1048576))

    # 解出 APK
    import zipfile
    apk = None
    with zipfile.ZipFile(zp) as z:
        for n in z.namelist():
            if n.endswith(".apk"):
                apk = os.path.join(outdir, os.path.basename(n))
                with open(apk, "wb") as f:
                    f.write(z.read(n))
                break
    if not apk:
        print("✗ zip 里没有 apk")
        return 1
    print("  APK %s  %.2f MB" % (os.path.basename(apk),
                                 os.path.getsize(apk) / 1048576))

    # 建 Release
    print("▶ 建 Release %s" % tag)
    body = """## v%s

**手机安装**：点下方 APK 下载，打开安装。已装旧版可直接覆盖升级，进度保留。

### 这一版做了什么

- **内置教材阅读器**：3 本教材 + 3 本练习册共 1123 页全在 App 里，
  点今日任务直接翻到对应页，不用再去找 PDF
- **文字优先阅读**：数学公式按坐标重排过，可调字号，一键切原图对照
- **应用内更新**：启动自动查新版，App 内下载并拉起系统安装器
- **日期跟真实日历走**：今天是哪天就显示哪天，不再依赖手动推进
- **SQLite 进度库**：单条勾选只写一行，不再整份 JSON 重写
- **进度码**：全部进度压成一段可粘贴短串，换手机复制即恢复
""" % ver

    try:
        rel = api("https://api.github.com/repos/%s/%s/releases/tags/%s"
                  % (OWNER, REPO, tag))
        print("  已存在，复用 id=%s" % rel["id"])
    except Exception:
        rel = api("https://api.github.com/repos/%s/%s/releases"
                  % (OWNER, REPO), "POST",
                  json.dumps({"tag_name": tag, "name": tag,
                              "body": body, "draft": False,
                              "prerelease": False}).encode("utf-8"),
                  "application/json")
        print("  已创建 id=%s" % rel["id"])

    # 删同名资产
    for a in rel.get("assets", []):
        if a["name"] == os.path.basename(apk):
            api("https://api.github.com/repos/%s/%s/releases/assets/%s"
                % (OWNER, REPO, a["id"]), "DELETE")
            print("  删掉旧资产 %s" % a["name"])

    # 上传
    name = "zhuansheng-%s.apk" % tag
    print("▶ 上传 %s" % name)
    with open(apk, "rb") as f:
        content = f.read()
    up = ("https://uploads.github.com/repos/%s/%s/releases/%s/assets?name=%s"
          % (OWNER, REPO, rel["id"], name))
    res = api(up, "POST", content, "application/vnd.android.package-archive")
    print("  %.2f MB 已上传" % (res["size"] / 1048576))

    print("\n" + "=" * 56)
    print("  手机打开这个链接下载：")
    print("  https://github.com/%s/%s/releases/latest" % (OWNER, REPO))
    print("  直链：")
    print("  " + res["browser_download_url"])
    print("=" * 56)
    return 0


if __name__ == "__main__":
    sys.exit(main())
