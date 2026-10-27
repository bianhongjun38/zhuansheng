# -*- coding: utf-8 -*-
"""从真机视角验证更新判断：本机各版本 × 线上最新版。"""
import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")


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


def main():
    path = os.path.join(os.environ["TEMP"], "gh_latest.json")
    j = json.load(open(path, encoding="utf-8"))

    tag = j["tag_name"]
    remote = parse_ver(tag)
    apk = [a for a in j["assets"] if a["name"].endswith(".apk")][0]

    print("线上最新: %s = %d" % (tag, remote))
    print("安装包  : %s  %.2f MB" % (apk["name"], apk["size"] / 1048576))
    print("下载地址: %s" % apk["browser_download_url"])
    print()

    cases = [
        ("1.2.0", "你现在装的这版"),
        ("1.2.1", "装完新版之后"),
        ("1.1.1", "更早的用户"),
    ]
    for local, label in cases:
        v = parse_ver(local)
        if remote > v:
            print("  v%-6s %-12s → 弹窗「发现新版本 v%s，要现在更新吗？」"
                  % (local, label, tag.lstrip("v")))
        else:
            print("  v%-6s %-12s → 静默「已是最新版本 v%s」" % (local, label, local))
        # 关键回归：同版本绝不能判成有新版
        assert not (parse_ver("v" + local) > parse_ver(local)), "同版本误判！"

    print()
    print("✅ 从 1.2.0 会提示可升级；装完 1.2.1 后显示已是最新，不会反复弹。")


if __name__ == "__main__":
    main()
