# -*- coding: utf-8 -*-
"""一键发版：本地验证 → 推 GitHub → 等 CI → 建 Release → 打印手机链接。

用法：
    python 发版.py -Note "改了什么"            # 完整流程
    python 发版.py -Note "..." -SkipBuild      # 跳过本地构建
    python 发版.py -Note "..." -Yes            # 不询问

前置：token 在 E:\\小黑盒运营\\_保险箱\\github_token.txt
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile

sys.stdout.reconfigure(encoding="utf-8")

PROJ = os.path.dirname(os.path.abspath(__file__))
REPO = "bianhongjun38/zhuansheng"
TOKEN_FILE = r"E:\小黑盒运营\_保险箱\github_token.txt"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

for k in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
          "http_proxy", "https_proxy", "all_proxy", "no_proxy"):
    os.environ.pop(k, None)


def sh(cmd, cwd=PROJ, check=True):
    print("  $ " + " ".join(cmd if isinstance(cmd, list) else [cmd]))
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", shell=isinstance(cmd, str))
    out = (p.stdout or "") + (p.stderr or "")
    if check and p.returncode != 0:
        print(out[-1500:])
        raise SystemExit("命令失败: %s" % cmd)
    return p.returncode, out


def token():
    if not os.path.exists(TOKEN_FILE):
        raise SystemExit("找不到 token 文件: %s" % TOKEN_FILE)
    return open(TOKEN_FILE, encoding="ascii").read().strip()


def api(path, method="GET", body=None):
    url = "https://api.github.com" + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token())
    req.add_header("User-Agent", "dsh-agent")
    req.add_header("Accept", "application/vnd.github+json")
    if data:
        req.add_header("Content-Type", "application/json")
    with OPENER.open(req, timeout=180) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


def next_version(cur_name):
    """tag 已被占用就递增 patch 段，直到找到没被占用的版本号。

    这样「重发」不会撞到已有 tag，也不会出现同名不同内容的 Release。
    """
    parts = [int(x) for x in cur_name.split(".")]
    while len(parts) < 3:
        parts.append(0)
    while True:
        cand = ".".join(str(p) for p in parts)
        try:
            api("/repos/%s/releases/tags/v%s" % (REPO, cand))
            # 已存在 → 进位
            parts[2] += 1
        except urllib.error.HTTPError:
            return cand


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Note", default="常规更新", help="这次改了什么")
    ap.add_argument("-Ver", default="", help="指定版本名，如 1.1.0；不给则自动递增")
    ap.add_argument("-SkipBuild", action="store_true", help="跳过本地构建验证")
    ap.add_argument("-Yes", action="store_true", help="不询问，直接发")
    args = ap.parse_args()

    print("=" * 60)
    print("  发版流程")
    print("=" * 60)

    # ── 0. 定版本名 ────────────────────────────────────
    vf = os.path.join(PROJ, "version.json")
    vj = json.load(open(vf, encoding="utf-8"))
    if args.Ver:
        vname = args.Ver
    else:
        vname = next_version(vj.get("versionName", "1.0.0"))
    vj["versionName"] = vname
    with open(vf, "w", encoding="utf-8") as f:
        json.dump(vj, f, ensure_ascii=False, indent=2)
    print("\n▶ 0. 版本：%s（versionCode 由 CI 自增）" % vname)

    # ── 1. 本地验证 ─────────────────────────────────────
    if not args.SkipBuild:
        print("\n▶ 1. 本地构建 + 全量验证")
        env = dict(os.environ, JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US")
        p = subprocess.run([sys.executable, "verify.py", "--build"], cwd=PROJ,
                           capture_output=True, text=True,
                           encoding="utf-8", errors="replace", env=env)
        tail = (p.stdout or "").splitlines()
        for line in tail[-14:]:
            print("  " + line)
        if p.returncode != 0:
            raise SystemExit("\n❌ 本地验证没过，不发版。把上面的失败项修掉再来。")
        print("  ✅ 本地验证通过")
    else:
        print("\n▶ 1. 跳过本地构建")

    # ── 2. 提交并推 ─────────────────────────────────────
    print("\n▶ 2. 推送到 GitHub")
    sh(["git", "add", "-A"], check=False)
    rc, _ = sh(["git", "diff", "--cached", "--quiet"], check=False)
    if rc != 0:  # 有改动
        sh(["git", "commit", "-m", args.Note])
        print("  已提交: " + args.Note)
    sh(["git", "-c", "http.proxy=", "push", "origin", "main"])
    print("  ✅ 已推送")

    # ── 3. 等 CI ────────────────────────────────────────
    print("\n▶ 3. 等 CI 构建")
    time.sleep(8)
    run = None
    for i in range(80):
        runs = api("/repos/%s/actions/runs" % REPO)["workflow_runs"]
        if runs:
            run = runs[0]
            print("  [%4ds] %s / %s" % (i * 10, run["status"], run["conclusion"] or "-"))
            if run["status"] == "completed":
                break
        time.sleep(10)
    if not run or run["conclusion"] != "success":
        print("\n❌ CI 失败：%s" % (run["html_url"] if run else "没有 run"))
        raise SystemExit("去 Actions 页看日志")

    # ── 4. 读 CI 版本 ───────────────────────────────────
    print("\n▶ 4. 读 CI 产出的版本")
    arts = api("/repos/%s/actions/artifacts" % REPO)["artifacts"]
    art = None
    for a in arts:
        if a["name"].startswith("zhuansheng-v") and not a["expired"]:
            if art is None or a["id"] > art["id"]:
                art = a
    if not art:
        raise SystemExit("没找到 artifact")
    m = re.search(r"-c(\d+)$", art["name"])
    vcode = m.group(1) if m else "?"
    tag = "v" + vname
    print("  %s → %s (code %s)" % (art["name"], tag, vcode))

    # ── 5. 下 APK ──────────────────────────────────────
    print("\n▶ 5. 下载 CI 的 APK")
    tmp = os.path.join(PROJ, "build", "_ci.zip")
    os.makedirs(os.path.dirname(tmp), exist_ok=True)

    cur = art["archive_download_url"]
    for _ in range(6):
        req = urllib.request.Request(cur)
        req.add_header("User-Agent", "dsh-agent")
        if "api.github.com" in cur:
            req.add_header("Authorization", "Bearer " + token())
            req.add_header("Accept", "application/vnd.github+json")

        class NR(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None
        op = urllib.request.build_opener(urllib.request.ProxyHandler({}), NR())
        try:
            with op.open(req, timeout=300) as r:
                open(tmp, "wb").write(r.read())
            break
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 303, 307, 308):
                cur = e.headers["Location"]
                continue
            raise

    apk = None
    with zipfile.ZipFile(tmp) as z:
        for n in z.namelist():
            if n.endswith(".apk"):
                apk = os.path.join(PROJ, "build", os.path.basename(n))
                open(apk, "wb").write(z.read(n))
                break
    if not apk:
        raise SystemExit("zip 里没有 apk")
    print("  %s (%d KB)" % (os.path.basename(apk), os.path.getsize(apk) // 1024))

    # ── 6. 建 Release ──────────────────────────────────
    print("\n▶ 6. 发 Release %s" % tag)
    try:
        rel = api("/repos/%s/releases/tags/%s" % (REPO, tag))
        print("  Release 已存在，跳过创建")
    except urllib.error.HTTPError:
        rel = api("/repos/%s/releases" % REPO, method="POST", body={
            "tag_name": tag,
            "target_commitish": "main",
            "name": "专升本 %s" % tag,
            "body": (
                "## 手机安装\n\n"
                "点下方 APK 下载 → 打开安装。\n"
                "**已装旧版直接覆盖**，进度保留。\n\n"
                "## 本次更新\n\n" + args.Note + "\n\n"
                "versionCode %s · 约 100 KB · 零权限" % vcode
            ),
            "draft": False,
            "prerelease": False,
        })
        print("  已创建")

    # 已有同名资产先删，否则上传冲突
    for a in rel.get("assets", []):
        if a["name"] == os.path.basename(apk):
            api("/repos/%s/releases/assets/%d" % (REPO, a["id"]), method="DELETE")
            print("  删除同名旧资产")

    up_url = ("https://uploads.github.com/repos/%s/releases/%d/assets?name=%s"
              % (REPO, rel["id"], os.path.basename(apk)))
    req = urllib.request.Request(up_url, data=open(apk, "rb").read(), method="POST")
    req.add_header("Authorization", "Bearer " + token())
    req.add_header("User-Agent", "dsh-agent")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Content-Type", "application/vnd.android.package-archive")
    with OPENER.open(req, timeout=300) as r:
        up = json.loads(r.read())

    print("\n" + "=" * 60)
    print("  ✅ 发版完成")
    print("=" * 60)
    print("  手机打开这个页面下载：")
    print("  %s" % rel["html_url"])
    print("")
    print("  APK 直链：")
    print("  %s" % up["browser_download_url"])
    print("=" * 60)


if __name__ == "__main__":
    main()
