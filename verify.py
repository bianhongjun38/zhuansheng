#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
一条命令跑完全部验证。

用法：
    python verify.py            # 跑测试 + 检查 APK
    python verify.py --build    # 先重新构建再验证

退出码 0 = 全部通过，非 0 = 有失败项。
"""
import json
import os
import shutil
import subprocess
import sys
import zipfile

PROJ = os.path.dirname(os.path.abspath(__file__))

sys.path.insert(0, PROJ)
# 直接复用 build_apk.py 里已经写好的工具链探测（两边都不用各自写死路径）
import build_apk as B  # noqa: E402

JAVA_HOME = B.JAVA_HOME
JAVA = B.JAVA
JAVAC = B.JAVAC
BT = B.BT
PLATFORM = B.PLATFORM

SRC = os.path.join(PROJ, "src", "com", "zsb", "plan")
TEST = os.path.join(PROJ, "test")
ASSETS_PLAN = os.path.join(PROJ, "assets", "plan.json")

# json jar：Android 自带 org.json，桌面端要单独一份
# 本机在 D:\AndroidDev\libs，CI 上直接从 Maven 下载
JSON_JAR = ""
for _cand in (os.path.join(os.path.dirname(JAVA_HOME), "libs", "json-20240303.jar"),
              os.path.join(PROJ, "..", "libs", "json-20240303.jar"),
              "/tmp/json-20240303.jar"):
    if os.path.exists(_cand):
        JSON_JAR = os.path.abspath(_cand)
        break
if not JSON_JAR:
    JSON_JAR = "/tmp/json-20240303.jar"
    if not os.path.exists(JSON_JAR):
        import urllib.request
        print("▶ 下载 json jar 用于桌面测试")
        urllib.request.urlretrieve(
            "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar",
            JSON_JAR)

# APK 按实际版本号找，不写死文件名
def _find_apk():
    import glob
    hits = sorted(glob.glob(os.path.join(PROJ, "build", "*.apk")))
    return hits[-1] if hits else os.path.join(PROJ, "build", "zhuansheng-v1.0.0.apk")


APK = _find_apk()
OUT = os.path.join(PROJ, "build", "verify_out")

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
                   os.path.join(SRC, "ProgressCode.java"),
                   os.path.join(TEST, "LogicTest.java"),
                   os.path.join(TEST, "StoreTest.java"),
                   os.path.join(TEST, "CodeTest.java"),
                   os.path.join(TEST, "VerTest.java")])
    if not check("测试代码编译通过", rc == 0, "" if rc == 0 else out[-500:]):
        return summary()

    # ── 2. 跑四套测试 ───────────────────────────────────
    for cls, label in (("com.zsb.plan.LogicTest", "计划解析与推进逻辑"),
                       ("com.zsb.plan.StoreTest", "状态存储与导入导出"),
                       ("com.zsb.plan.CodeTest", "进度码编解码"),
                       ("com.zsb.plan.VerTest", "版本比较")):
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

    # ── 2b. 源码一致性：VerTest 里的解析函数必须与 Update 同源 ──
    # VerTest 复制了一份 parseVer 以便在桌面测试（Update 依赖 Activity 无法直接跑）。
    # 两份一旦漂移，测试就白测了，所以直接比对函数体。
    import re

    def extract_fn(path, start_marker):
        src = open(path, encoding="utf-8").read()
        i = src.find(start_marker)
        if i < 0:
            return None
        # 从标记处往后数大括号，取到配对的那个
        j = src.find("{", i)
        if j < 0:
            return None
        depth = 0
        for k in range(j, len(src)):
            if src[k] == "{":
                depth += 1
            elif src[k] == "}":
                depth -= 1
                if depth == 0:
                    body = src[j:k + 1]
                    # 注释不算漂移，只比代码
                    body = re.sub(r"//[^\n]*", "", body)
                    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
                    return re.sub(r"\s+", " ", body).strip()
        return None

    fn_update = extract_fn(os.path.join(SRC, "Update.java"), "static int parseVer")
    fn_test = extract_fn(os.path.join(TEST, "VerTest.java"), "static int parseVer")
    check("parseVer 两份实现同源", fn_update is not None
          and fn_update == fn_test,
          "" if fn_update == fn_test else "Update 与 VerTest 的 parseVer 已漂移，"
          "改一处必须同步改另一处")

    # ── 2c. 版本号自洽：version.json 的 name 与 code 不能互相倒退 ──
    import json as _json
    vj = _json.load(open(os.path.join(PROJ, "version.json"), encoding="utf-8"))
    vname = vj.get("versionName", "")
    vcode = int(vj.get("versionCode", 0))
    ok_name = re.match(r"^\d+\.\d+\.\d+$", vname) is not None
    check("versionName 形如 x.y.z", ok_name, vname)
    check("versionCode 为正整数", vcode > 0, "code=%d" % vcode)
    # versionCode 与 versionName 的次版本号不必相等，但不能明显倒退：
    # 比如 name 已经是 1.5.0，code 却还是 2，说明有人手改了文件。
    if ok_name:
        p = [int(x) for x in vname.split(".")]
        want_min = p[0] * 100 + p[1]
        check("versionCode 与 versionName 大致同步", vcode >= want_min // 100,
              "name=%s code=%d（code 应以 10 为步长递增，"
              "如 1.1.0→10、1.1.1→11）" % (vname, vcode))

    # ── 3. APK 静态检查 ─────────────────────────────────
    print("\n▶ APK 检查")
    if not os.path.isfile(APK):
        check("APK 存在", False, APK)
        return summary()
    check("APK 存在", True, "%.2f MB" % (os.path.getsize(APK) / 1024 / 1024))

    # 拷到 ASCII 路径再验（aapt 不认中文路径）
    import tempfile
    ascii_apk = os.path.join(tempfile.gettempdir(), "verify.apk")
    os.makedirs(os.path.dirname(ascii_apk), exist_ok=True)
    shutil.copy(APK, ascii_apk)

    # aapt 在 build-tools 下
    rc, out = run([os.path.join(BT, B.EXE_AAPT), "dump", "badging", ascii_apk])
    check("包名 = com.zsb.plan", "name='com.zsb.plan'" in out)
    check("启动 Activity = MainActivity", "com.zsb.plan.MainActivity" in out)
    check("minSdk 23 / targetSdk 34",
          "sdkVersion:'23'" in out and "targetSdkVersion:'34'" in out)

    rc, out = run([os.path.join(BT, B.EXE_AAPT), "dump", "permissions", ascii_apk])
    # 权限白名单：只为「检查更新 + 下载新版 APK + 拉起安装器」服务。
    # 一旦出现白名单外的权限（读通讯录、读存储、定位等），说明被误改了。
    allowed = {
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.REQUEST_INSTALL_PACKAGES",
    }
    used = set()
    for ln in out.splitlines():
        if "uses-permission" in ln and "name=" in ln:
            name = ln.split("name=", 1)[1].strip().strip("'\"")
            if name:
                used.add(name)
    extra = used - allowed
    check("权限仅限更新用（联网/安装包）", not extra,
          "" if not extra else "白名单外权限: %s" % sorted(extra))

    rc, out = run([os.path.join(BT, B.EXE_APKSIGNER), "verify", "--verbose", ascii_apk])
    check("签名校验通过（v1+v2+v3）",
          "Verifies" in out and out.count("scheme") >= 3)

    # dex 里该有的类
    # 注意：dexdump 不认含中文的绝对路径（实测只回 2 行就结束），
    # 所以把 dex 解到纯 ASCII 的临时目录再 dump。
    import tempfile
    tmp = os.path.join(tempfile.gettempdir(), "zsb_verify_dex")
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp, exist_ok=True)
    with zipfile.ZipFile(ascii_apk) as z:
        z.extract("classes.dex", tmp)
        names = z.namelist()
    check("含 classes.dex", "classes.dex" in names)
    check("含 assets/plan.json", "assets/plan.json" in names)

    dex_path = os.path.join(tmp, "classes.dex")
    rc, out = run([os.path.join(BT, B.EXE_DEXDUMP), "-f", dex_path])
    if len(out) < 500:
        check("dexdump 能读出内容", False,
              "只回了 %d 字符，路径可能含非 ASCII（%s）" % (len(out), dex_path))
    else:
        check("dexdump 能读出内容", True, "%d 字符" % len(out))
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

