# -*- coding: utf-8 -*-
"""用 App 里完全相同的解析逻辑，模拟真机上的更新检查判断。"""
import json
import os
import sys

sys.stdout.reconfigure(encoding="utf-8")


def parse_ver(s):
    """与 Update.java / VerTest.java 的 parseVer 逐行对应。"""
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
    print("  线上 %s -> %d" % (tag, remote))

    apk = None
    for a in j.get("assets", []):
        if a["name"].endswith(".apk"):
            apk = a
            break
    print("  资产 %s (%.2f MB)" % (apk["name"], apk["size"] / 1048576))
    print("  地址 %s" % apk["browser_download_url"])
    print()

    for local, label in [("1.0.0", "最初版用户"),
                         ("1.1.0", "上一版用户"),
                         ("1.1.1", "已是最新")]:
        v = parse_ver(local)
        verdict = "→ 会提示更新" if remote > v else "→ 静默（不弹窗）"
        print("  本机 %-7s (%-6d) %-8s %s" % (local, v, label, verdict))

    print()
    # 关键回归：确认不会每次都弹
    assert not (parse_ver("v1.1.1") > parse_ver("1.1.1")), "同版本误判！"
    print("  ✅ 同版本不会误判为新版（这正是 v1.1.1 修的 bug）")

    # 版本号序列严格递增
    seq = ["1.0.0", "1.1.0", "1.1.1", "1.2.0", "2.0.0"]
    vals = [parse_ver(x) for x in seq]
    assert vals == sorted(vals) and len(set(vals)) == len(vals), "序列不严格递增"
    print("  ✅ 版本序列严格递增：%s" % vals)


if __name__ == "__main__":
    main()
