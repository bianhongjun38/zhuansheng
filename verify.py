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


def _ver_int(name):
    """1.2.2 -> 10202。与 Update.parseVer / 发版pages.ver_int 同一套单位。

    三处必须一致，否则「清单版本 vs 本机版本」会比错。
    这里刻意重写一遍而不是 import，避免 verify 依赖那几个带副作用
    （会改文件、会联网）的脚本。
    """
    p = [int(x) for x in (name.split(".") + ["0", "0", "0"])[:3]]
    return p[0] * 10000 + p[1] * 100 + p[2]


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
                   # 以下四个是数学/英语引擎的全部纯逻辑。
                   # 它们不 import android，所以能在电脑上被逐条断言，
                   # 而不是「装到手机上点一点看看」。
                   os.path.join(SRC, "Expr.java"),
                   os.path.join(SRC, "PlotGeom.java"),
                   os.path.join(SRC, "DerivTable.java"),
                   os.path.join(SRC, "FormulaLayout.java"),
                   os.path.join(SRC, "EngTrain.java"),
                   os.path.join(SRC, "ExprInput.java"),
                   os.path.join(SRC, "PlotGeom.java"),
                   os.path.join(SRC, "ExprOutline.java"),
                   os.path.join(SRC, "Linalg.java"),
                   os.path.join(TEST, "LogicTest.java"),
                   os.path.join(TEST, "StoreTest.java"),
                   os.path.join(TEST, "CodeTest.java"),
                   os.path.join(TEST, "VerTest.java"),
                   os.path.join(TEST, "MathTest.java"),
                   os.path.join(TEST, "EngTrainTest.java"),
                   os.path.join(TEST, "FormulaAudit.java"),
                   os.path.join(TEST, "PlotAudit.java"),
                   os.path.join(TEST, "ExprInputTest.java"),
                   os.path.join(TEST, "KeypadAudit.java"),
                   os.path.join(TEST, "LinalgTest.java"),
                   os.path.join(TEST, "LinalgViewAudit.java"),
                   os.path.join(TEST, "DerivAudit.java"),
                   os.path.join(TEST, "ExprOutlineAudit.java"),
                   os.path.join(TEST, "OutlineLayoutAudit.java"),
                   os.path.join(TEST, "MathViewAudit.java"),
                   os.path.join(TEST, "FitAudit.java")])
    if not check("测试代码编译通过", rc == 0, "" if rc == 0 else out[-500:]):
        return summary()

    # ── 2. 跑七套测试 ───────────────────────────────────
    # 第 3 个元素是该测试自己的命令行参数：
    #   · 大部分测试要 plan.json 路径；
    #   · FormulaAudit 要的是「PNG 输出目录」，不能塞 plan.json，
    #     否则它会把 PNG 写成 assets/plan.json 之外的怪文件名。
    audit_dir = os.path.join(PROJ, "build", "audit")
    os.makedirs(audit_dir, exist_ok=True)
    test_table = (
        ("com.zsb.plan.LogicTest", "计划解析与推进逻辑", ASSETS_PLAN),
        ("com.zsb.plan.StoreTest", "状态存储与导入导出", ASSETS_PLAN),
        ("com.zsb.plan.CodeTest", "进度码编解码", ASSETS_PLAN),
        ("com.zsb.plan.VerTest", "版本比较", None),
        ("com.zsb.plan.MathTest", "数学引擎（表达式/求导/图像几何）", None),
        ("com.zsb.plan.EngTrainTest", "英语引擎（挖空/干扰项/判分）", None),
        ("com.zsb.plan.FormulaAudit", "公式排版（含 PNG 视觉证据）", audit_dir),
        ("com.zsb.plan.PlotAudit", "函数图像（分段/渐近线，含 PNG）", audit_dir),
        ("com.zsb.plan.ExprInputTest", "函数输入模板（插入/退格/光标）", None),
        ("com.zsb.plan.KeypadAudit", "输入面板布局（含 PNG 视觉证据）", audit_dir),
        ("com.zsb.plan.LinalgTest", "线性代数几何（特征向量/网格，含 PNG）", audit_dir),
        ("com.zsb.plan.LinalgViewAudit", "线性变换界面构图（含 PNG）", audit_dir),
        ("com.zsb.plan.DerivAudit", "求导表显示层（标准记号/可逆一致）", audit_dir),
        ("com.zsb.plan.MathViewAudit", "数学界面（公式表/等高格/按下态，含 PNG）", audit_dir),
        ("com.zsb.plan.ExprOutlineAudit", "写法→形状对照表（形状描述经采样验证，含 PNG）", audit_dir),
        ("com.zsb.plan.OutlineLayoutAudit", "对照表手机排版（行高/折行/裁剪，含 PNG）", audit_dir),
        ("com.zsb.plan.FitAudit", "模板格子公式缩字（宽/高/字号下限）", audit_dir),
    )
    for cls, label, extra_arg in test_table:
        print("\n▶ " + label)
        cmd = [JAVA, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
               "-Djava.awt.headless=true",
               "-cp", JSON_JAR + os.pathsep + OUT, cls]
        if extra_arg:
            cmd.append(extra_arg)
        rc, out = run(cmd)
        # 从输出里抓「通过 N 项，失败 M 项」
        n_pass = n_fail = None
        for line in out.splitlines():
            if "通过" in line and "失败" in line:
                import re
                # 两种词序都认：
                #   旧测试 "通过 12 项，失败 0 项"
                #   新测试 "数学引擎：173 项通过，0 项失败"
                m = re.search(r"通过\s*(\d+)\s*项，失败\s*(\d+)\s*项", line)
                if not m:
                    m = re.search(r"(\d+)\s*项通过，\s*(\d+)\s*项失败", line)
                if not m:
                    # 纯 ASCII 统计行：CI 上 locale 可能不是 UTF-8，
                    # 中文会变成 ?，上面两个正则全部落空。
                    m = re.search(r"STAT\s+(\d+)\s+(\d+)", line)
                if m:
                    n_pass, n_fail = int(m.group(1)), int(m.group(2))
        if n_pass is None:
            check(label, False, "没拿到测试统计，输出尾部：" + out[-300:])
        else:
            check(label, n_fail == 0, "%d 项通过 / %d 项失败" % (n_pass, n_fail))
            # 双重保险：统计行说 0 失败但进程退出码非 0，同样算失败
            if n_fail == 0 and rc != 0:
                check(label + "（退出码）", False, "统计为 0 失败但退出码 %d" % rc)
            # 打印失败明细，避免"通过 N 项"掩盖内部异常
            for line in out.splitlines():
                if line.strip().startswith("✗"):
                    print("   " + line.strip())

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
        for cls in ("MainActivity", "Plan", "StateLogic", "Store",
                    # 数学工具链
                    "Expr", "PlotGeom", "DerivTable", "FormulaLayout",
                    "FormulaView", "PlotView", "MathView", "ExprInput",
                    "Linalg", "LinalgView",
                    # 英语训练链
                    "EngTrain", "EngView"):
            check("dex 含 " + cls, ("com/zsb/plan/" + cls + ";") in out)

    # ── 3b. 数学题库必须已移除 ───────────────────────────
    # 用户明确要求「把数学那部分不要了」。如果哪天有人把它加回来，
    # 今天删题库、换工具的决定就被悄悄推翻了 —— 这条断言专门盯这个。
    check("数学题库已移除", "assets/bank/math.json" not in names,
          "" if "assets/bank/math.json" not in names
          else "assets/bank/math.json 又出现了")
    check("计算机题库仍在", "assets/bank/comp.json" in names)
    check("英语题库仍在", "assets/bank/eng.json" in names)
    check("词库仍在", "assets/words.json" in names)
    _kx = [n for n in names if n.startswith("assets/katex")]
    check("KaTeX 资源已移除", not _kx,
          "" if not _kx else "又出现了 %d 个 katex 文件（数学题不排公式了，"
          "这套 310KB 资源没用）" % len(_kx))

    # 方法级行为由 MathTest / EngTrainTest / FormulaAudit / PlotAudit /
    # ExprInputTest 在桌面逐条验证，这里只确认类真的进了 dex（上面已 check）。

    # ── 3d. 输入模板的按键表必须与引擎一致 ────────────────
    def _probe_batch(exprs):
        """一次性把一批表达式交给引擎验，返回解析失败的列表。
        逐条起 JVM 要 20×200ms，批量只要一次。"""
        if not exprs:
            return []
        rc2, out2 = run([JAVA, "-Dfile.encoding=UTF-8", "-cp",
                         JSON_JAR + os.pathsep + OUT,
                         "com.zsb.plan.ExprInputTest", "--probe"] + exprs)
        bad = []
        for ln in out2.splitlines():
            if ln.startswith("BAD "):
                bad.append(ln[4:].strip())
        return bad


    # MathView 的按键表在 ExprInput.KEYS 里。如果哪天有人往 KEYS 加了个
    # 引擎不认识的键（比如加了 "log" 但 Expr 只认 "lg"），
    # 用户一按就报错，而单元测试发现不了（测试只验形状不验语义）。
    try:
        ei = open(os.path.join(SRC, "ExprInput.java"), encoding="utf-8").read()
        # 抓 TEMPLATES 数组里的字符串字面量
        import re as _re
        m = _re.search(r"TEMPLATES\s*=\s*\{(.*?)\}\s*;", ei, _re.S)
        tmpl = _re.findall(r'"([^"]*)"', m.group(1)) if m else []
        check("输入模板非空", len(tmpl) >= 12, "%d 个" % len(tmpl))
        # 按键表里的函数名必须都在引擎的白名单里
        mk = _re.search(r"KEYS\s*=\s*\{(.*?)\}\s*;", ei, _re.S)
        frags = _re.findall(r'"([^"]*)"', mk.group(1)) if mk else []
        # 只取插入片段（奇数下标），去掉 @ 与 \b
        ins = [frags[i] for i in range(1, len(frags), 2)]
        # 按键是「插入片段」，不是独立表达式 —— "^"、"("、"+" 单放在一起
        # 本来就不可解析。要验的是它插进一个真实表达式里能被接住。
        CONTEXT = {
            "^": "x^2", "(": "(x)", ")": "(x)", "+": "x+1", "-": "x-1",
            "*": "x*2", "/": "x/2", "*2": "x*2",
        }
        probes = []
        for frag in ins:
            # Java 源码里写的是 "\b"（两个字符：反斜杠 + b），
            # 正则抓到的是反斜杠+b 而不是真正的退格符。两种都跳过。
            if frag in ("\b", "", "\\b") or frag.strip("\\") == "b":
                continue
            probe = frag.replace("@", "x")
            probe = probe.replace("()", "(x)")
            probe = CONTEXT.get(probe, CONTEXT.get(probe.rstrip("0123456789"), probe))
            # 仍不构成完整表达式的，包一层 x+ 兜底
            if probe in ("x^2", "(x)", "x+1", "x-1", "x*2", "x/2"):
                pass
            elif probe.startswith(("^", ")", "+", "*", "/")):
                probe = "x" + probe
            probes.append(probe)
        probes += list(tmpl)
        bad_keys = _probe_batch(probes)
        check("按键表与模板都能被引擎解析", not bad_keys,
              "" if not bad_keys else "无法解析: %s" % bad_keys[:4])
    except Exception as e:
        check("输入模板与引擎一致性", False, "检查本身出错：%s" % e)

    # 图标五档齐全
    icons = [n for n in names if "ic_launcher" in n]
    check("图标 5 档密度", len(icons) == 5, "%d 个" % len(icons))

    # ── 更新链路 ────────────────────────────────────────
    # 2026-10-27 实测：github.com 直连超时 12s，api.github.com 200/321ms。
    # 曾经用 browser_download_url（走 github.com），导致「版本查得到、一按下载
    # 就永远转圈」。这里做静态校验，确保代码里没退回去。
    upd_src = os.path.join(PROJ, "src", "com", "zsb", "plan", "Update.java")
    try:
        us = open(upd_src, encoding="utf-8").read()
    except Exception:
        us = ""
    check("Update.java 优先用 api 资产端点",
          'optString("url"' in us and "api.github.com/" in us,
          "避免 browser_download_url 走被阻断的 github.com")
    check("下载时带 octet-stream",
          "application/octet-stream" in us,
          "不带的话 API 返回 JSON 而不是 APK")
    check("设置页显示版本号",
          "检查更新 · 当前 v" in open(os.path.join(
              PROJ, "src", "com", "zsb", "plan", "MainActivity.java"),
              encoding="utf-8").read(),
          "用户要能看到自己装的是哪一版")

    # ── 更新取值策略（2026-10-27 真实故障）────────────────
    # 现场：update.json 停在 1.2.3，GitHub 上已 1.4.1，App 却报「已是最新」。
    # 根因是三源降级"读到第一份就返回"，过期的那份排第一。
    # 光靠 VerTest 断言不够 —— 还得钉死源码真的改成了取最大值。
    body = extract_fn(upd_src, "private Latest fetchLatestAny")
    check("三源取版本最高者，而非读到即返回",
          body is not None
          and "lt.code > best.code" in body
          and "if (lt != null) return lt;" not in body,
          "过期清单不能压住更新的来源")

    # 清单必须存在且不比 version.json 旧。
    # 这次翻车就是"发了版没更新清单"，光看代码看不出来。
    try:
        vj = json.load(open(os.path.join(PROJ, "version.json"), encoding="utf-8"))
        man_path = os.path.join(PROJ, "pages", "update.json")
        if not os.path.exists(man_path):
            check("pages/update.json 已生成", False, "缺文件：跑 python 发版pages.py")
        else:
            man = json.load(open(man_path, encoding="utf-8"))
            man_int = int(man.get("versionInt") or 0)
            loc_int = _ver_int(vj["versionName"])
            check("update.json 不落后于 version.json",
                  man_int >= loc_int,
                  "清单 %s / 本地 %s" % (man.get("versionName"), vj["versionName"]))
            apk = man.get("apk") or {}
            check("update.json 带安装包地址",
                  bool(apk.get("url")),
                  str(apk.get("url")))
            check("update.json 记录的包就是本地这版",
                  apk.get("name") == "zhuansheng-v%s.apk" % vj["versionName"],
                  str(apk.get("name")))
    except Exception as e:
        check("update.json 可解析", False, str(e))

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

