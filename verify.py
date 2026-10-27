#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
一条命令跑完全部验证。

用法：
    python verify.py            # 跑测试 + 检查 APK
    python verify.py --build    # 先重新构建再验证

退出码 0 = 全部通过，非 0 = 有失败项。
"""
import os
import subprocess
import sys
import shutil
import zipfile

PROJ = os.path.dirname(os.path.abspath(__file__))
DEV = r"D:\AndroidDev"
JAVA_HOME = os.path.join(DEV, "jdk17")
JAVA = os.path.join(JAVA_HOME, "bin", "java.exe")
JAVAC = os.path.join(JAVA_HOME, "bin", "javac.exe")
BT = os.path.join(DEV, "sdk", "build-tools", "34.0.0")
JSON_JAR = os.path.join(DEV, "libs", "json-20240303.jar")
SRC = os.path.join(PROJ, "src", "com", "zsb", "plan")
TEST = os.path.join(PROJ, "test")
ASSETS_PLAN = os.path.join(PROJ, "assets", "plan.json")
APK = os.path.join(PROJ, "build", "zhuansheng-v1.0.0.apk")
OUT = os.path.join(DEV, "verify_out")

sys.stdout.reconfigure(encoding="utf-8")

ENV = dict(os.environ)
ENV["JAVA_TOOL_OPTIONS"] = "-Duser.language=en -Duser.country=US"
ENV["JAVA_HOME"] = JAVA_HOME
ENV["PATH"] = os.path.join(JAVA_HOME, "bin") + os.pathsep + ENV.get("PATH", "")
for k in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
          "http_proxy", "https_proxy", "all_proxy", "no_proxy"):
    ENV.pop(k, None)

results = []


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("  ✅ " if ok else "  ❌ ") + name + (("  (" + detail + ")") if detail else ""))
    return ok


def run(cmd, cwd=None):
    p = subprocess.run(cmd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", cwd=cwd, env=ENV)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def main():
    rebuild = "--build" in sys.argv

    if rebuild:
        print("▶ 重新构建 APK")
        rc, out = run([sys.executable, os.path.join(PROJ, "build_apk.py")], cwd=PROJ)
        check("构建成功", rc == 0, "" if rc == 0 else out[-400:])

    # ── 1. 编译纯逻辑（不碰 Android）────────────────────
    print("\n▶ 编译测试")
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT, exist_ok=True)
    rc, out = run([JAVAC, "-encoding", "UTF-8", "-cp", JSON_JAR, "-d", OUT,
                   os.path.join(SRC, "Plan.java"),
                   os.path.join(SRC, "StateLogic.java"),
                   os.path.join(TEST, "LogicTest.java"),
                   os.path.join(TEST, "StoreTest.java")])
    if not check("测试代码编译通过", rc == 0, "" if rc == 0 else out[-500:]):
        return summary()

    # ── 2. 跑两套测试 ───────────────────────────────────
    for cls, label in (("com.zsb.plan.LogicTest", "计划解析与推进逻辑"),
                       ("com.zsb.plan.StoreTest", "状态存储与导入导出")):
        print("\n▶ " + label)
        rc, out = run([JAVA, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
                       "-cp", JSON_JAR + os.pathsep + OUT, cls, ASSETS_PLAN])
        # 从输出里抓「通过 N 项，失败 M 项」
        n_pass = n_fail = None
        for line in out.splitlines():
            if "通过" in line and "失败" in line:
                import re
                m = re.search(r"通过\s*(\d+)\s*项，失败\s*(\d+)\s*项", line)
                if m:
                    n_pass, n_fail = int(m.group(1)), int(m.group(2))
        if n_pass is None:
            check(label, False, "没拿到测试统计，输出尾部：" + out[-300:])
        else:
            check(label, n_fail == 0, "%d 项通过 / %d 项失败" % (n_pass, n_fail))

    # ── 3. APK 静态检查 ─────────────────────────────────
    print("\n▶ APK 检查")
    if not os.path.isfile(APK):
        check("APK 存在", False, APK)
        return summary()
    check("APK 存在", True, "%.2f MB" % (os.path.getsize(APK) / 1024 / 1024))

    # 拷到 ASCII 路径再验（aapt 不认中文路径）
    ascii_apk = os.path.join(DEV, "out", "verify.apk")
    os.makedirs(os.path.dirname(ascii_apk), exist_ok=True)
    shutil.copy(APK, ascii_apk)

    # aapt 在 build-tools 下
    rc, out = run([os.path.join(BT, "aapt.exe"), "dump", "badging", ascii_apk])
    check("包名 = com.zsb.plan", "name='com.zsb.plan'" in out)
    check("启动 Activity = MainActivity", "com.zsb.plan.MainActivity" in out)
    check("minSdk 23 / targetSdk 34",
          "sdkVersion:'23'" in out and "targetSdkVersion:'34'" in out)

    rc, out = run([os.path.join(BT, "aapt.exe"), "dump", "permissions", ascii_apk])
    has_perm = "uses-permission" in out
    check("零权限（不联网/不读存储）", not has_perm,
          "" if not has_perm else out.strip()[:200])

    rc, out = run([os.path.join(BT, "apksigner.bat"), "verify", "--verbose", ascii_apk])
    check("签名校验通过（v1+v2+v3）",
          "Verifies" in out and out.count("scheme") >= 3)

    # dex 里该有的类
    tmp = os.path.join(OUT, "dex")
    os.makedirs(tmp, exist_ok=True)
    with zipfile.ZipFile(ascii_apk) as z:
        z.extract("classes.dex", tmp)
        names = z.namelist()
    check("含 classes.dex", "classes.dex" in names)
    check("含 assets/plan.json", "assets/plan.json" in names)

    rc, out = run([os.path.join(BT, "dexdump.exe"), "-f", os.path.join(tmp, "classes.dex")])
    for cls in ("MainActivity", "Plan", "StateLogic", "Store"):
        check("dex 含 " + cls, ("com/zsb/plan/" + cls + ";") in out)

    # 图标五档齐全
    icons = [n for n in names if "ic_launcher" in n]
    check("图标 5 档密度", len(icons) == 5, "%d 个" % len(icons))

    return summary()


def summary():
    print("\n" + "=" * 56)
    bad = [r for r in results if not r[1]]
    print("  检查项 %d 个，失败 %d 个" % (len(results), len(bad)))
    if bad:
        print("\n  失败明细：")
        for n, _, d in bad:
            print("   ✗ " + n + (("  " + d) if d else ""))
    else:
        print("  ✅ 全部通过")
    print("=" * 56)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
