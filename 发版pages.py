# -*- coding: utf-8 -*-
"""发版到 Cloudflare Pages —— App 更新的**主通道**。

为什么不用 GitHub 直连：
    2026-10-27 实测（不带代理，模拟手机直连国内网络）：
        api.github.com                        HTTP 200   321ms   ✅
        github.com                            HTTP 000   超时 12s ❌
        release-assets.githubusercontent.com  HTTP 404   388ms   ✅
        zsb-update.pages.dev                  HTTP 200   810ms   ✅

    github.com 主站被阻断 => GitHub Release 页面下载不动。
    Cloudflare Workers 在这个账号上**所有脚本**（含 10 月就在跑的老 Worker）
    都返回 500 / error 1101，是账号级 Workers 故障，与代码无关。
    Pages **不受影响**，所以更新改用 Pages 托管 APK 本体。

产物（全在 pages/ 下，静态文件，不走任何运行时）：
    pages/update.json            版本清单（App 直接读这个）
    pages/zhuansheng-vX.Y.Z.apk  安装包本体
    pages/index.html             人看的下载页

用法：
    python 发版pages.py                 # 用 version.json 的当前版本发
    python 发版pages.py -SkipDeploy     # 只生成文件，不部署
"""
import argparse
import json
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8")

PROJ = os.path.dirname(os.path.abspath(__file__))
PAGES = os.path.join(PROJ, "pages")
PROJECT = "zsb-update"

# 自定义域名（已实测直连 200，手机可达）。优先用它，pages.dev 作兜底：
#   xxjcloudns.kdns.fr 是用户自己的域名，Cloudflare 托管，绑定 Pages 项目，
#   DNS: app.xxjcloudns.kdns.fr CNAME zsb-update.pages.dev（已代理）
BASE = "https://app.xxjcloudns.kdns.fr"
BASE_FALLBACK = "https://%s.pages.dev" % PROJECT
GH_REPO = "bianhongjun38/zhuansheng"

# Cloudflare Pages 单个文件的**硬上限**（官方文档 platform/limits 原文）：
#   "The maximum file size for a single Cloudflare Pages site asset is 25 MiB."
# 留 1 MiB 余量给 HTML/JSON，超过就自动不传 APK，改让 App 走 GitHub 兜底。
# 想彻底摆脱这个上限：在 Cloudflare 控制台点一次「启用 R2」，
# 然后把 APK 放 R2 公共桶 —— R2 单对象上限 5 TB、出网免费。
PAGES_MAX = 25 * 1024 * 1024
PAGES_SAFE = 24 * 1024 * 1024

for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.setdefault(k, "http://127.0.0.1:7897")


def ver_int(name):
    """1.2.2 -> 10202，与 Update.parseVer / worker 保持一致。"""
    p = [int(x) for x in (name.split(".") + ["0", "0", "0"])[:3]]
    return p[0] * 10000 + p[1] * 100 + p[2]


def github_asset_api(vname):
    """GitHub Release 资产的 api.github.com 直链（不是 github.com，那个被阻断）。

    这条通道实测可用：不带代理直连 200 / 551 KB/s，且没有体积上限。
    但 App 端要现查 release 才能拿到 asset id，所以这里只提示、不写死。
    """
    return "https://api.github.com/repos/%s/releases/tags/v%s" % (GH_REPO, vname)


def build_manifest(apk_name, size, vcode, notes, host_on_pages, vname):
    man = {
        "ok": True,
        "versionName": vname,
        "versionInt": ver_int(vname),
        "versionCode": vcode,
        "notes": notes,
        "source": "cloudflare-pages",
    }
    if host_on_pages:
        man["apk"] = {
            "name": apk_name,
            "size": size,
            "url": "%s/%s" % (BASE, apk_name),
        }
    else:
        # APK 超过 Pages 上限：清单里明确不给 Pages 地址，
        # App 看到 apk 缺失会自动转去 GitHub 兜底，不会去请求一个 404。
        man["apk"] = None
        man["apkTooBigForPages"] = True
    man["github"] = {"release": github_asset_api(vname)}
    return man


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Notes", default="", help="更新说明")
    ap.add_argument("-SkipDeploy", action="store_true")
    args = ap.parse_args()

    vj = json.load(open(os.path.join(PROJ, "version.json"), encoding="utf-8"))
    vname, vcode = vj["versionName"], vj["versionCode"]
    apk = os.path.join(PROJ, "build", "zhuansheng-v%s.apk" % vname)
    if not os.path.exists(apk):
        apk = os.path.join(PROJ, "zhuansheng-v%s.apk" % vname)
    if not os.path.exists(apk):
        raise SystemExit("找不到 APK：先跑 python build_apk.py")

    os.makedirs(PAGES, exist_ok=True)

    # 清掉旧 APK，Pages 上只留最新一个，避免体积累积
    for f in os.listdir(PAGES):
        if f.endswith(".apk"):
            os.remove(os.path.join(PAGES, f))

    apk_name = "zhuansheng-v%s.apk" % vname
    size = os.path.getsize(apk)

    # Pages 单文件硬上限 25 MiB。超了就**不往 Pages 放**，清单里 apk 置 null，
    # App 会自动转 GitHub API 通道（那条没有体积限制，实测 551 KB/s）。
    host = size <= PAGES_SAFE
    if host:
        shutil.copy2(apk, os.path.join(PAGES, apk_name))
    else:
        print("  ⚠ APK %.2f MB 超过 Pages 单文件上限 25 MiB，本次不托管到 Pages；"
              % (size / 1048576))
        print("    App 将走 GitHub 通道下载（约 551 KB/s）。")
        print("    想彻底解决：Cloudflare 控制台启用 R2，把 APK 放 R2 公共桶。")

    notes = args.Notes or ("v%s（versionCode %d）" % (vname, vcode))
    man = build_manifest(apk_name, size, vcode, notes, host, vname)
    with open(os.path.join(PAGES, "update.json"), "w", encoding="utf-8") as f:
        json.dump(man, f, ensure_ascii=False, indent=2)

    if host:
        dl_line = ("<p><a href='%s' style='display:block;padding:14px;"
                   "text-align:center;background:#2FA8A0;color:#fff;"
                   "border-radius:12px;text-decoration:none'>下载 APK</a></p>"
                   % apk_name)
    else:
        dl_line = ("<p style='color:#E0A94A'>安装包 %.2f MB，超过 Pages 单文件"
                   "上限，请从 GitHub Release 下载。</p>" % (size / 1048576))

    with open(os.path.join(PAGES, "index.html"), "w", encoding="utf-8") as f:
        f.write(
            "<!doctype html><meta charset=utf-8>"
            "<meta name=viewport content='width=device-width,initial-scale=1'>"
            "<title>专升本 App</title>"
            "<body style='font:16px/1.7 system-ui;max-width:520px;margin:40px auto;"
            "padding:0 20px;background:#14161C;color:#E8EAF0'>"
            "<h2>专升本 App v%s</h2>"
            "<p>%.2f MB · versionCode %d</p>%s"
            "<p style='color:#8A90A0;font-size:13px'>已装旧版可直接覆盖升级，"
            "学习进度保留。安装时若提示「不允许安装」，在系统设置里允许本应用"
            "安装未知应用即可。</p>"
            "<p style='color:#8A90A0;font-size:12px'>机器可读清单："
            "<a href='update.json' style='color:#7C6CF0'>update.json</a></p>"
            % (vname, size / 1048576, vcode, dl_line))

    print("  APK      %s  (%.2f MB)%s"
          % (apk_name, size / 1048576, "" if host else "  [未上传 Pages]"))
    print("  清单     update.json  versionName=%s versionInt=%d"
          % (vname, man["versionInt"]))
    print("  下载页   index.html")
    print("  地址     %s/" % BASE)

    if args.SkipDeploy:
        return 0

    print("\n▶ 部署到 Cloudflare Pages")
    p = subprocess.run(
        "npx --yes wrangler@latest pages deploy . --project-name %s --branch main "
        "--commit-dirty=true" % PROJECT,
        cwd=PAGES, shell=True, capture_output=True, text=True,
        encoding="utf-8", errors="replace")
    for line in (p.stdout or "").splitlines()[-6:]:
        print("  " + line)
    if p.returncode != 0:
        print((p.stderr or "")[-800:])
        raise SystemExit("部署失败")

    print("\n✅ 完成：%s/" % BASE)
    return 0


if __name__ == "__main__":
    sys.exit(main())
