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
import urllib.error
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

PROJ = os.path.dirname(os.path.abspath(__file__))
PAGES = os.path.join(PROJ, "pages")
PROJECT = "zsb-update"

# 2026-10-28：自有域名 app.xxjcloudns.kdns.fr 按用户决定**取消**，
# 只用 Pages 的默认域名。那个域名指向同一个项目，只是多套一层代理，
# 实测会返回 403 "Website Access Blocked"（WAF 拦截），纯属负资产。
BASE = BASE_FALLBACK = "https://%s.pages.dev" % PROJECT
GH_REPO = "bianhongjun38/zhuansheng"

# Cloudflare Pages 单个文件的**硬上限**（官方文档 platform/limits 原文）：
#   "The maximum file size for a single Cloudflare Pages site asset is 25 MiB."
# 留 1 MiB 余量给 HTML/JSON，超过就自动不传 APK，改让 App 走 GitHub 兜底。
# 想彻底摆脱这个上限：在 Cloudflare 控制台点一次「启用 R2」，
# 然后把 APK 放 R2 公共桶 —— R2 单对象上限 5 TB、出网免费。
PAGES_MAX = 25 * 1024 * 1024
PAGES_SAFE = 24 * 1024 * 1024

# 代理只在**真的活着**时才挂。
#
# 以前是无条件 setdefault 127.0.0.1:7897。那个端口是本地翻墙代理，
# 平时不开；一旦它没运行，所有请求都会 ECONNREFUSED，
# wrangler 报一句 "fetch failed / 网络代理配置问题"，很难看出是代理的锅。
# 实测 2026-10-28：7897 未监听，部署直接失败。
#
# 这里改成先探端口：连得上才设，连不上就确保不设（顺便清掉外层环境里
# 可能残留的同名变量，避免把上一层的脏环境带进来）。
import socket as _socket


def _proxy_alive(port=7897, host="127.0.0.1"):
    try:
        with _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM) as s:
            s.settimeout(0.3)
            return s.connect_ex((host, port)) == 0
    except Exception:
        return False


if _proxy_alive():
    for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
        os.environ.setdefault(k, "http://127.0.0.1:7897")
    print("  代理     127.0.0.1:7897 可用，走代理")
else:
    # 端口不通就必须彻底摘掉，否则 wrangler / urllib 会拿它去连
    for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
        os.environ.pop(k, None)
    print("  代理     127.0.0.1:7897 未开启，直连")


def ver_int(name):
    """1.2.2 -> 10202，与 Update.parseVer / worker 保持一致。

    按 100 进制压缩，前提是每段都 < 100。patch 到 100 会碰撞
    （1.5.100 == 1.6.0 == 10600），所以 build_apk.py --bump
    已经在 99 时进位。这里再断言一次：宁可在发版时炸掉，
    也不要静默发一个手机上永远判定为"更旧"的包。
    """
    p = [int(x) for x in (name.split(".") + ["0", "0", "0"])[:3]]
    for seg_v in p:
        if seg_v >= 100:
            raise SystemExit(
                "❌ 版本号 %s 有一段 >= 100，会和更高级的版本碰撞"
                "（1.5.100 与 1.6.0 都算 10600）。"
                "请先手动把 version.json 调到 1.6.0 再发。" % name)
    return p[0] * 10000 + p[1] * 100 + p[2]


def github_asset_api(vname):
    """GitHub Release 资产的 api.github.com 直链（不是 github.com，那个被阻断）。

    这条通道实测可用：不带代理直连 200 / 551 KB/s，且没有体积上限。
    但 App 端要现查 release 才能拿到 asset id，所以这里只提示、不写死。
    """
    return "https://api.github.com/repos/%s/releases/tags/v%s" % (GH_REPO, vname)




# ══ GitHub Release 上传（2026-10-31 新增，唯一的 APK 分发通道）══
#
# 为什么要自己写上传：
#   Pages 单文件上限 25 MiB，447 MB 的包根本放不进去，只能走 GitHub Release
#   （单文件上限 2 GiB，447 MB 完全够）。而 release 上传没有 git 命令能代劳，
#   必须走 REST：POST 建 release → POST 传资产（原始字节流）。
#
# 为什么不用 gh CLI：本机没装，靠 npx/brew 装会引入不确定依赖；
# 起一个 HTTP 请求反而更可控，也方便把「边传边显示进度」写清楚。

GH_API = "https://api.github.com"


def gh_token():
    """从 git credential 里取 token。不读环境变量、不落盘。"""
    psi = subprocess.run(["git", "credential", "fill"],
                         input="protocol=https\nhost=github.com\n\n",
                         capture_output=True, text=True, encoding="utf-8",
                         errors="replace")
    for line in (psi.stdout or "").splitlines():
        if line.startswith("password="):
            return line[9:].strip()
    return None


def _gh(path, tok, method="GET", data=None, ctype="application/json"):
    """调 GitHub REST。返回 (status, 解析后的 JSON 或 None)。"""
    import urllib.request
    req = urllib.request.Request(GH_API + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + tok)
    req.add_header("User-Agent", "zsb-release")
    req.add_header("Accept", "application/vnd.github+json")
    if data is not None:
        req.add_header("Content-Type", ctype)
    op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with op.open(req, timeout=60) as r:
            body = r.read()
            return r.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        body = e.read()
        try:
            return e.code, json.loads(body)
        except Exception:
            return e.code, {"raw": body[:400].decode("utf-8", "replace")}


def gh_upload_asset(rel_upload_url, tok, apk_path, apk_name):
    """把 APK 传到 Release。返回资产的 api url。

    upload_url 形如 https://uploads.github.com/repos/O/R/releases/1/assets{?name,label}
    必须把末尾的 {?name,label} 模板去掉，否则会当成路径的一部分 → 404。

    447 MB 走一次 POST。GitHub 允许单个请求传完（上限 2 GiB），
    所以不需要分片；但必须**先删除同名旧资产**，否则会 422 already_exists。
    """
    import urllib.request
    base = rel_upload_url.split("{")[0]
    url = "%s?name=%s" % (base, urllib.parse.quote(apk_name))
    size = os.path.getsize(apk_path)

    with open(apk_path, "rb") as f:
        data = f.read()
    print("  正在上传 %.2f MB ..." % (size / 1048576.0))

    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Authorization", "Bearer " + tok)
    req.add_header("User-Agent", "zsb-release")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Content-Type", "application/vnd.android.package-archive")
    req.add_header("Content-Length", str(size))
    op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with op.open(req, timeout=1800) as r:
            return json.loads(r.read()).get("url")
    except urllib.error.HTTPError as e:
        raw = e.read()[:500].decode("utf-8", "replace")
        raise SystemExit("❌ 上传失败 HTTP %d：%s" % (e.code, raw))


def gh_publish(vname, vcode, apk_path, apk_name, notes):
    """建/更新 Release 并上传 APK。返回 (release 页面地址, 资产 api 地址)。

    幂等性：tag 已存在就直接复用那个 Release，先清掉同名旧资产再传。
    这样重复跑发版脚本不会产生「一个 Release 挂两个包」（2026-10-28 踩过）。
    """
    tok = gh_token()
    if not tok:
        raise SystemExit("❌ 取不到 GitHub token（git credential 里没有 password）")

    tag = "v" + vname
    # ① tag 有没有对应的 Release
    st, rel = _gh("/repos/%s/releases/tags/%s" % (GH_REPO, tag), tok)
    if st == 404:
        print("  Release %s 不存在，新建" % tag)
        payload = json.dumps({
            "tag_name": tag,
            "name": tag,
            "body": notes,
            "draft": False,
            "prerelease": False,
        }).encode("utf-8")
        st, rel = _gh("/repos/%s/releases" % GH_REPO, tok, "POST", payload)
        if st not in (200, 201):
            raise SystemExit("❌ 建 Release 失败 HTTP %d：%s" % (st, rel))
    elif st == 200:
        print("  Release %s 已存在，复用" % tag)
    else:
        raise SystemExit("❌ 查 Release 失败 HTTP %d：%s" % (st, rel))

    # ② 清掉同名旧资产 —— Release 附件是累加语义，不清就会一 tag 多包
    for a in (rel.get("assets") or []):
        if a.get("name") == apk_name:
            print("  删除同名旧资产 id=%s" % a["id"])
            _gh("/repos/%s/releases/assets/%s" % (GH_REPO, a["id"]), tok, "DELETE")

    # ③ 上传
    asset_api = gh_upload_asset(rel["upload_url"], tok, apk_path, apk_name)
    return rel.get("html_url"), asset_api

def build_manifest(apk_name, size, vcode, notes, host_on_pages, vname,
                   asset_api=None):
    """生成 update.json。

    2026-10-31 起语义变了：这份文件**不再是版本判定的依据**，
    只是给存量旧客户端（≤1.5.44）留的兼容清单。

    为什么要留：
        已经装在用户手机上的旧版 App 只会读 pages.dev/update.json。
        直接把这份文件删掉，全部存量用户会永久停在旧版本 —— 没有
        任何"下次更新"能把他们拉回来。这是最容易被忽略的坑，
        项目铁律里也写着「永远假设存量客户端还在跑」。

    存量客户端怎么被接走：
        旧版 Update.fetchFromGithub() 会读 GitHub 的最新 Release，
        并**只在版本号完全一致时**才用它的下载地址。
        所以这里 versionName/versionInt 必须和 GitHub tag 严格同版，
        它就会自动从 GitHub 拉包。这条链路 v1.5.39~1.5.44 都具备。

    新客户端（≥1.5.45）完全不看这份文件的版本号，直接读 GitHub。
    """
    man = {
        "ok": True,
        "versionName": vname,
        "versionInt": ver_int(vname),
        "versionCode": vcode,
        "notes": notes,
        # 标明真实分发通道，方便以后排查清单和实际下载源不一致的问题。
        "source": "github-release",
    }
    if host_on_pages:
        # 小包（<25 MiB）仍然可以双通道：Pages 直下 + GitHub 兜底。
        # 下载地址必须用 pages.dev，不能用自定义域名 ——
        # 自定义域名的 Zone 层会把 Range 请求再处理一遍，
        # 越界 Range 仍返回 416，导致老版本 App 把 416 当成"已下完"
        # 从而安装缓存里的旧包（表现为"更新了但还是老版本"）。
        man["apk"] = {
            "name": apk_name,
            "size": size,
            "url": "%s/%s" % (BASE_FALLBACK, apk_name),
        }
    else:
        # APK 超过 Pages 上限（447 MB 就是这种情况）：清单里明确不给
        # Pages 地址，App 看到 apk 缺失会自动转 GitHub，不会去请求一个 404。
        #
        # ⚠ 这里额外补 apkUrl：1.5.45+ 的新客户端优先读这个字段，
        #   它比"自己去 GitHub 查 release"少一次往返，也让清单和实际
        #   下载地址保持一致，排查时不用两边对照。
        man["apk"] = None
        man["apkTooBigForPages"] = True
        if asset_api:
            man["apkUrl"] = asset_api
            man["apkSize"] = size
            man["apkName"] = apk_name
    # downloadUrl 是**网页直链**，不是 api 端点。
    #
    # 区别很关键：加速镜像（ghproxy.net 这类）的原理是把 github.com 的
    # 路径拼在自己域名后面做反代，它们**不认识** api.github.com 的
    # 资产 ID 接口。所以清单必须同时给出这条直链，客户端才有的可用镜像。
    tag = "v" + vname
    man["github"] = {
        "release": github_asset_api(vname),
        "tag": tag,
        "downloadUrl": "https://github.com/%s/releases/download/%s/%s"
                       % (GH_REPO, tag, apk_name),
    }
    return man


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Notes", default="", help="更新说明")
    ap.add_argument("-SkipDeploy", action="store_true")
    ap.add_argument("-SkipUpload", action="store_true",
                    help="跳过 GitHub Release 上传（只试跑清单，别拿去发版）")
    args = ap.parse_args()

    vj = json.load(open(os.path.join(PROJ, "version.json"), encoding="utf-8"))
    vname, vcode = vj["versionName"], vj["versionCode"]
    apk = os.path.join(PROJ, "build", "zhuansheng-v%s.apk" % vname)
    if not os.path.exists(apk):
        apk = os.path.join(PROJ, "zhuansheng-v%s.apk" % vname)
    if not os.path.exists(apk):
        raise SystemExit("找不到 APK：先跑 python build_apk.py")

    os.makedirs(PAGES, exist_ok=True)

    # 只留本次这一个 APK。
    #
    # 历史包没有任何用处：Update.buildSources() 只会拼出"最新版的地址"
    # （update.json 里的 apk.url + GitHub 兜底），客户端从不去请求自己
    # 那一版的旧包。留旧包换不来任何一次"能装上"，只能白占体积。
    #
    # 删除本身有个副作用要记住（2026-10-28 踩过）：这个 Pages 项目对
    # **任何**缺失路径都返回 HTTP 200 + index.html（实测 nope.bin 也是），
    # 不是 404；所以"下架的版本"那个 URL 会返回一个 200 的 HTML 假包。
    # 服务端改不了这个平台行为（_routes.json / functions 都不生效），
    # 根治在客户端：Update.java 装前用 getPackageArchiveInfo 读包内真实
    # versionName，不高于本机就拒绝安装，不会再把错包交给安装器。
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

    # ── 硬校验：versionName 必须严格大于线上那一份 ──
    # App 的 Update.parseVer() 只比较 versionName。如果这次发的版本名
    # 和线上一样（或更小），手机端会直接判定「已是最新版本」，
    # 安装包换了、内容变了，用户却永远收不到更新。
    # 这不是理论风险：2026-10-27 的 1.5.0 就是因为 versionName 没变，
    # 连发三次都没人收到。宁可在这里失败，也不要静默发一个用户收不到的包。
    import urllib.request
    try:
        _op = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        _live = json.loads(_op.open(
            urllib.request.Request(BASE + "/update.json",
                                   headers={"User-Agent": "zsb-release"}),
            timeout=15).read())
        _lv, _li = _live.get("versionName", "0.0.0"), int(_live.get("versionInt", 0))
        if ver_int(vname) <= _li:
            print("\n❌ 版本号没有前进：本次 %s (%d) <= 线上 %s (%d)"
                  % (vname, ver_int(vname), _lv, _li))
            print("   手机端只比较 versionName，同名会判定「已是最新版本」，")
            print("   用户永远收不到这次更新。请先把 versionName 升上去。")
            raise SystemExit(1)
        print("  版本前进：%s (%d) > 线上 %s (%d)"
              % (vname, ver_int(vname), _lv, _li))
    except SystemExit:
        raise
    except Exception as e:
        print("  ⚠ 读不到线上 update.json（%s），跳过版本前进校验" % e)

    notes = args.Notes or ("v%s（versionCode %d）" % (vname, vcode))

    # ── ① 先传 GitHub Release（这是现在**唯一**的 APK 分发通道）──
    #
    # 顺序很关键：必须**先**把包传上去，**再**写 update.json。
    # 反过来的话会出现"清单已经宣布有新版本、下载地址却 404"的窗口期，
    # 用户点更新直接失败，还以为是网络问题。
    rel_html, asset_api = None, None
    if args.SkipUpload:
        print("  ⚠ -SkipUpload：跳过 GitHub 上传（仅用于本地试跑）")
        print("    注意：清单里将没有任何可用的下载地址，别拿去发版。")
    else:
        print("\n▶ 上传到 GitHub Release（tag v%s）" % vname)
        rel_html, asset_api = gh_publish(vname, vcode, apk, apk_name, notes)
        print("  Release  %s" % rel_html)
        print("  资产 API %s" % asset_api)

    man = build_manifest(apk_name, size, vcode, notes, host, vname,
                         asset_api=asset_api)
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

    # 教材 PDF 也放在 Pages（每本最大 5.3 MB，离 24 MB 余量还远）。
    # 单独列出来，是因为 App 内首次看原图才按需拉，用户想直接用浏览器看也方便。
    books = []
    bdir = os.path.join(PAGES, "books")
    if os.path.isdir(bdir):
        for f in sorted(os.listdir(bdir)):
            if not f.endswith(".pdf"):
                continue
            label = {"comp_book.pdf": "计算机", "eng_book.pdf": "英语",
                     "math_book.pdf": "数学"}.get(f, f[:-4])
            books.append("<a href='books/%s' style='color:#7C6CF0'>%s</a>"
                         % (f, label))
    books_line = ""
    if books:
        books_line = ("<p style='color:#8A90A0;font-size:13px;margin-top:18px'>"
                      "教材 PDF（App 内也可直接看）：%s</p>" % "　".join(books))


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
            "%s"
            % (vname, size / 1048576, vcode, dl_line, books_line))

    print("  APK      %s  (%.2f MB)%s"
          % (apk_name, size / 1048576, "" if host else "  [未上传 Pages]"))
    print("  清单     update.json  versionName=%s versionInt=%d"
          % (vname, man["versionInt"]))
    print("  下载页   index.html")
    print("  地址     %s/" % BASE)

    if args.SkipDeploy:
        return 0

    print("\n▶ 部署到 Cloudflare Pages")
    # 显式传一份**干净的环境**给 wrangler。
    #
    # 只改 os.environ 不够：实测（2026-10-28）外层 shell 里残留的
    # HTTP_PROXY/HTTPS_PROXY 会被继承下去，wrangler 检测到就死命走代理，
    # 而 7897 没在监听 → "fetch failed"，报的却是一句"网络代理配置问题"，
    # 完全看不出是代理的锅。这里直接把所有代理相关的键从 env 里剔掉，
    # 并给 NO_PROXY=* 作为双保险。
    env = dict(os.environ)
    for k in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY",
              "http_proxy", "https_proxy", "all_proxy"):
        env.pop(k, None)
    if not _proxy_alive():
        env["NO_PROXY"] = "*"
        env["no_proxy"] = "*"

    p = subprocess.run(
        "npx --yes wrangler@latest pages deploy . --project-name %s --branch main "
        "--commit-dirty=true" % PROJECT,
        cwd=PAGES, shell=True, capture_output=True, text=True,
        encoding="utf-8", errors="replace", env=env)
    for line in (p.stdout or "").splitlines()[-6:]:
        print("  " + line)
    if p.returncode != 0:
        print((p.stderr or "")[-800:])
        raise SystemExit("部署失败")

    print("\n✅ 完成：%s/" % BASE)

    # ── 打 tag：GitHub Release 只有打 tag 才会产生 ──
    #
    # build.yml 里发 Release 那一步的条件是
    #     if: startsWith(github.ref, 'refs/tags/v')
    # 也就是说 push 到 main 只构建 + 传 artifact（Actions 页可下，90 天过期），
    # **不会**创建 Release。以前每次发版只 commit + push，于是 tag 停在
    # v1.5.24，后面十多个版本的 Release 页一直是空的（用户来问"release
    # 怎么不更新"就是这个原因）。
    #
    # 这里补上自动打 tag。失败不影响发版 —— Pages 通道已经生效，
    # tag 只决定 GitHub 那份备份和 Release 页有没有。
    tag = "v" + vname
    if sha(tag):
        print("  tag      %s（已存在，跳过）" % tag)
    else:
        # ⚠ 打 tag 之前**必须先把 version.json 提交掉**。
        #
        # build_apk.py --bump 只改工作区里的 version.json，不 commit。
        # 如果直接在本地的 tag 命令上打，tag 会指向"上一个 commit"——
        # 那个 commit 里 versionName 还是旧值。CI 在 tag 上构建时读的是
        # 仓库内容，于是打出来的包版本号比 tag 少一截：Release 页写着
        # v1.5.40，附件却叫 zhuansheng-v1.5.39.apk（实测踩到）。
        # 更糟的是 update.json 已经是 1.5.40，GitHub 那份是 1.5.39，
        # 两个源版本不一致。
        need_commit = subprocess.run(
            ["git", "status", "--porcelain"], cwd=PROJ,
            capture_output=True, text=True, encoding="utf-8",
            errors="replace").stdout.strip()
        if need_commit:
            print("\n▶ 提交本次版本变更（tag 必须指向含新 version.json 的 commit）")
            for cmd in (["git", "add", "-A"],
                        ["git", "commit", "-m", "release: v%s" % vname]):
                p = subprocess.run(cmd, cwd=PROJ, capture_output=True,
                                   text=True, encoding="utf-8", errors="replace")
                if p.returncode != 0:
                    print("  ⚠ %s 失败：%s" % (" ".join(cmd),
                                            (p.stderr or "").strip()[:200]))
                    break
            p = subprocess.run(["git", "push", "origin", "main"], cwd=PROJ,
                               capture_output=True, text=True,
                               encoding="utf-8", errors="replace")
            if p.returncode != 0:
                print("  ⚠ push 失败：%s" % (p.stderr or "").strip()[:200])

        print("\n▶ 打 tag %s 并推送（触发 GitHub Release）" % tag)
        msg = "v%s：%s" % (vname, args.Notes or ("versionCode %d" % vcode))
        for cmd in (["git", "tag", "-a", tag, "-m", msg],
                    ["git", "push", "origin", tag]):
            p = subprocess.run(cmd, cwd=PROJ, capture_output=True,
                               text=True, encoding="utf-8", errors="replace")
            if p.returncode != 0:
                print("  ⚠ %s 失败：%s" % (" ".join(cmd), (p.stderr or "").strip()[:200]))
                print("    Pages 已发好，只是 GitHub Release 没建。可稍后手动："
                      "git tag -a %s -m \"...\" && git push origin %s" % (tag, tag))
                break
            print("  " + " ".join(cmd) + "  ok")
    return 0


def sha(ref):
    """本地是否已有这个 git 引用（tag/branch）。"""
    p = subprocess.run(["git", "rev-parse", "--verify", "--quiet", ref],
                       cwd=PROJ, capture_output=True, text=True)
    return p.returncode == 0


if __name__ == "__main__":
    sys.exit(main())