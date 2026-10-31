# -*- coding: utf-8 -*-
"""用旧版 Update.java 的真实算法，验证它会选中哪个资产。

旧版（1.5.39~1.5.44）的 findApk 用 verOfApkName() 从文件名解析版本，
取版本**最大**的那个。它和 parseVer 不是同一个函数 —— 这一点很关键：
parseVer("zhuansheng-v1.5.45.apk") 会返回 0（遇到非数字就截断），
而 verOfApkName 会剥掉前缀再解析。
"""
import json, sys, urllib.request, re
sys.stdout.reconfigure(encoding="utf-8")

OP = urllib.request.build_opener(urllib.request.ProxyHandler({}))

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

def ver_of_apk(name):
    """复刻 Java 的 verOfApkName：从 xxx-v1.2.3.apk 里抠出版本。"""
    if not name: return 0
    m = re.search(r"v?(\d+\.\d+\.\d+)", name, re.I)
    return parse_ver(m.group(1)) if m else 0

r = urllib.request.Request(
    "https://api.github.com/repos/bianhongjun38/zhuansheng/releases/latest",
    headers={"User-Agent": "zsb-plan", "Accept": "application/vnd.github+json"})
with OP.open(r, timeout=30) as resp:
    rel = json.loads(resp.read())

print("tag = %s" % rel["tag_name"])
print("资产列表：")
best, best_v = None, -1
for a in rel.get("assets") or []:
    v = ver_of_apk(a["name"])
    mark = ""
    if v > best_v:
        best_v, best, mark = v, a, "  <- 选中"
    print("  %-28s verOfApkName=%d%s" % (a["name"], v, mark))

print()
if best:
    print("最终下载：%s" % best["name"])
    print("  大小 %.2f MB" % (best["size"]/1048576))
    print("  api  %s" % best["url"])
    print("  web  %s" % best["browser_download_url"])
    print()
    # 旧版会先试清单里的 apk.url（这里是 null），再试 GitHub
    print("判定：旧版会从 GitHub 拉 %s，版本 %d 与本机比较。"
          % (best["name"], ver_of_apk(best["name"])))
else:
    print("⚠ 没有资产！旧版会报「新版没有找到安装包」")
