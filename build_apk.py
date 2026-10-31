# -*- coding: utf-8 -*-
"""原生 Android APK 构建脚本 —— 不依赖 Gradle。

链路：
    aapt2 compile   资源 → .flat
    aapt2 link      .flat + Manifest → 未签名 APK（含 R.java）
    javac           .java + R.java + android.jar → .class
    d8              .class → classes.dex
    aapt2/zip       把 dex 塞进 APK
    zipalign        4 字节对齐
    apksigner       debug 签名

用法：python build_apk.py
输出：build/zhuansheng-v1.0.0.apk
"""
import json
import os
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime

sys.stdout.reconfigure(encoding="utf-8")

# ── 路径：自动探测工具链 ─────────────────────────────────
# 本机是 D:\AndroidDev（手工装的），GitHub Actions 用 ANDROID_SDK_ROOT。
# 两边都要能跑，所以不能写死。
def _find_dev():
    """返回 (sdk, java_home)。优先环境变量，其次本机私有路径。"""
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    java = os.environ.get("JAVA_HOME")

    # 本机：D:\AndroidDev\{sdk,jdk17}
    local_dev = r"D:\AndroidDev"
    if not sdk and os.path.isdir(os.path.join(local_dev, "sdk")):
        sdk = os.path.join(local_dev, "sdk")
    if not java and os.path.isdir(os.path.join(local_dev, "jdk17")):
        java = os.path.join(local_dev, "jdk17")

    # 最后兜底：SDK 在常见位置
    if not sdk:
        for c in (r"C:\Android\Sdk", os.path.expanduser("~/Android/Sdk")):
            if os.path.isdir(c):
                sdk = c
                break
    return sdk or "", java or ""


SDK, JAVA_HOME = _find_dev()

# 找 build-tools：优先 34.0.0，否则取版本号最大的
BT = ""
_bt_root = os.path.join(SDK, "build-tools") if SDK else ""
if os.path.isdir(_bt_root):
    _vers = sorted(os.listdir(_bt_root), reverse=True)
    if "34.0.0" in _vers:
        BT = os.path.join(_bt_root, "34.0.0")
    elif _vers:
        BT = os.path.join(_bt_root, _vers[0])

# 找 platform android.jar
PLATFORM = ""
_plat_root = os.path.join(SDK, "platforms") if SDK else ""
if os.path.isdir(_plat_root):
    for _v in ("android-34", "android-35", "android-33", "android-36"):
        _p = os.path.join(_plat_root, _v, "android.jar")
        if os.path.exists(_p):
            PLATFORM = _p
            break
    if not PLATFORM:
        _cand = [d for d in os.listdir(_plat_root) if d.startswith("android-")]
        if _cand:
            PLATFORM = os.path.join(_plat_root, sorted(_cand)[-1], "android.jar")

# Windows 加 .exe，Linux 不加
_EXE = ".exe" if os.name == "nt" else ""
JAVAC = os.path.join(JAVA_HOME, "bin", "javac" + _EXE)
JAVA = os.path.join(JAVA_HOME, "bin", "java" + _EXE)
KEYTOOL = os.path.join(JAVA_HOME, "bin", "keytool" + _EXE)

AAPT2 = os.path.join(BT, "aapt2" + _EXE) if BT else ""
ZIPALIGN = os.path.join(BT, "zipalign" + _EXE) if BT else ""
D8_JAR = os.path.join(BT, "lib", "d8.jar") if BT else ""
APKSIGNER_JAR = os.path.join(BT, "lib", "apksigner.jar") if BT else ""
# 编译 lambda 必需（javac 要能找到 LambdaMetafactory.metafactory）
LAMBDA_STUBS = os.path.join(BT, "core-lambda-stubs.jar") if BT else ""

# verify.py 用到的三个工具（Windows 带扩展名，Linux 不带）
EXE_AAPT = "aapt" + _EXE
EXE_DEXDUMP = "dexdump" + _EXE
# apksigner 在 Windows 上是 .bat 包装，Linux 上直接是 shell 脚本
EXE_APKSIGNER = "apksigner.bat" if os.name == "nt" else "apksigner"

PROJ = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(PROJ, "src")
RES = os.path.join(PROJ, "res")
ASSETS = os.path.join(PROJ, "assets")
MANIFEST = os.path.join(PROJ, "AndroidManifest.xml")
BUILD = os.path.join(PROJ, "build")

APP_NAME = "zhuansheng"
PKG = "com.zsb.plan"

# 签名钥匙放在工程目录下，**不能**放 build/（临时目录随时会被清掉，
# 钥匙一丢，之后所有更新都无法覆盖安装，只能卸载重装、进度全没了）
KEYSTORE = os.path.join(PROJ, "keystore", "release.keystore")
KS_ALIAS = "androiddebugkey"
KS_PASS = "android"

# 版本号由 version.json 决定；不存在则从 1 起。
# 每次构建自动 +1，保证安卓认得出这是新版本（否则覆盖安装会失败）。
VERSION_FILE = os.path.join(PROJ, "version.json")


def run(cmd, desc, cwd=None):
    print("\n▶ %s" % desc)
    print("  " + " ".join('"%s"' % c if " " in str(c) else str(c) for c in cmd[:3]) + " ...")
    r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if r.stdout.strip():
        for line in r.stdout.strip().splitlines()[:25]:
            print("    " + line)
    if r.returncode != 0:
        print("\n❌ 失败（exit %d）" % r.returncode)
        if r.stderr.strip():
            print(r.stderr.strip()[:4000])
        sys.exit(1)
    if r.stderr.strip():
        # aapt2 / javac 的警告走 stderr，正常情况不中断
        warn = r.stderr.strip().splitlines()
        if len(warn) > 8:
            print("    (%d 行警告，略)" % len(warn))
        else:
            for line in warn:
                print("    " + line)
    return r


def need(path, name):
    if not os.path.exists(path):
        print("❌ 缺少 %s：%s" % (name, path))
        print("   请先装好 Android SDK（platforms;android-34 + build-tools;34.0.0）")
        sys.exit(1)


def read_version(bump):
    """读 version.json，可选自增。返回 (versionCode, versionName)。"""
    code, name = 1, "1.0.0"
    if os.path.exists(VERSION_FILE):
        try:
            with open(VERSION_FILE, "r", encoding="utf-8") as f:
                v = json.load(f)
            code = int(v.get("versionCode", 1))
            name = str(v.get("versionName", "1.0.0"))
        except Exception as e:
            print("⚠ version.json 读不了（%s），从 1 开始" % e)
    if bump:
        code += 1
        # 关键：--bump 必须同时递增 versionName 的 patch 段。
        # 只动 versionCode 是**静默失效**的：Update.parseVer() 只比较
        # versionName（"1.5.0" → 10500），两次构建都得到 10500，
        # remoteCode <= localCode 成立 → App 永远显示「已是最新版本」，
        # 而 APK 内容其实已经变了。2026-10-27 就是这么卡住的：
        # versionCode 23→25，versionName 一直是 1.5.0。
        try:
            seg = [int(x) for x in name.split(".")]
            while len(seg) < 3:
                seg.append(0)
            seg[2] += 1
            # 每段必须 < 100。
            #
            # Update.parseVer() 把这版号压成 maj*10000 + min*100 + pat，
            # 也就是按 100 进制。patch 段一旦到 100，1.5.100 算出来是
            # 10600 —— 和 1.6.0 完全相等；再到 1.5.101（10601），切到
            # 1.6.0 反而算"变旧"：发版脚本拒绝发布、客户端显示
            # "已是最新版本"，包永远发不出去。
            #
            # 按 1.5.39 的速度（单日几十次 --bump），再 build 61 次就撞线，
            # 所以必须在这里进位，而不是等出事再补。
            # 99 → 进位到下一段，和 parseVer 的 100 进制保持一致。
            if seg[2] >= 100:
                seg[2] = 0
                seg[1] += 1
            if seg[1] >= 100:
                seg[1] = 0
                seg[0] += 1
            name = ".".join(str(x) for x in seg)
        except Exception:
            name = "1.0.1"
    return code, name


def write_version(code, name):
    with open(VERSION_FILE, "w", encoding="utf-8") as f:
        json.dump({"versionCode": code, "versionName": name,
                   "builtAt": datetime.now().strftime("%Y-%m-%d %H:%M:%S")},
                  f, ensure_ascii=False, indent=2)


def check_keystore():
    """签名钥匙是更新的命脉：没有它，新版本装不上去。"""
    if not os.path.exists(KEYSTORE):
        print("\n" + "!" * 62)
        print("  找不到签名钥匙：%s" % KEYSTORE)
        print("!" * 62)
        print("  这把钥匙决定「新版本能不能覆盖安装」。")
        print("  如果手机上已经装了旧版，换一把新钥匙 → 新版装不上，")
        print("  只能卸载重装，学习进度全部丢失。")
        print("")
        print("  恢复办法（按顺序试）：")
        print("   1. 从 _签名备份\\release.keystore 复制回 keystore\\release.keystore")
        print("   2. 找你之前备份过的地方")
        print("   3. 实在找不到 → 确认可以接受「卸载重装、进度清零」后，")
        print("      删掉这行判断让它自动生成一把新的（只能用一次）")
        print("")
        sys.exit(1)


# ══ 雅思词图（独立模块）══════════════════════════════════════
#
# 词图是另一个目录里的 React 应用（E:/小黑盒运营/ielts-atlas）。
# 它构建出来的 dist/ 就是一套静态站点，拷进 assets/atlas/ 即可运行。
#
# 为什么不提交到本仓库：dist 有 37,000 个文件 / 635 MB，其中 22,905 段音频
# 和 14,596 张图。放进 git 会让 clone 变成灾难，而且它们是可再生的构建产物。
# 所以这里在构建时从源目录同步过来，源目录路径可通过环境变量覆盖。

# 词图与本仓库同级：小黑盒运营/ielts-atlas。
# PROJ = .../小黑盒运营/cq-zhuanshengbens/mobile，往上两级就到 小黑盒运营。
ATLAS_SRC = os.environ.get(
    "IELTS_ATLAS_DIST",
    os.path.join(os.path.dirname(os.path.dirname(PROJ)),
                 "ielts-atlas", "dist"))

# 已经压过的格式：再 deflate 一遍几乎不减小，只是白花 CPU 和安装时间。
ATLAS_STORE_EXT = {".webp", ".jpg", ".jpeg", ".png", ".mp3", ".m4a", ".ogg",
                   ".wav", ".mp4", ".woff2", ".woff", ".gif"}

# lite 模式丢掉的重目录（相对 dist/）
ATLAS_LITE_SKIP = ("atlas/audio/ex/",)


def sync_atlas(mode):
    """把 ielts-atlas/dist 同步到 assets/atlas/。返回 (文件数, 字节数)。"""
    dst = os.path.join(ASSETS, "atlas")
    if not os.path.isdir(ATLAS_SRC):
        print("\n❌ 找不到词图构建产物：%s" % ATLAS_SRC)
        print("   先在词图目录构建：")
        print("     cd ielts-atlas && npm run build")
        print("   或用 IELTAS_ATLAS_DIST 环境变量指向别处")
        sys.exit(1)
    idx = os.path.join(ATLAS_SRC, "index.html")
    if not os.path.isfile(idx):
        print("\n❌ %s 里没有 index.html，不像构建产物" % ATLAS_SRC)
        sys.exit(1)

    print("\n▶ 同步雅思词图（%s）" % ("全量" if mode == "full" else "精简"))
    print("  源：%s" % ATLAS_SRC)
    print("  目标：%s" % dst)

    if os.path.isdir(dst):
        shutil.rmtree(dst)

    n, total, skipped = 0, 0, 0
    for root, _, files in os.walk(ATLAS_SRC):
        rel_root = os.path.relpath(root, ATLAS_SRC).replace("\\", "/")
        if rel_root == ".":
            rel_root = ""
        for fn in files:
            relp = (rel_root + "/" + fn) if rel_root else fn
            if mode == "lite" and any(relp.startswith(p) for p in ATLAS_LITE_SKIP):
                skipped += 1
                continue
            src = os.path.join(root, fn)
            out = os.path.join(dst, relp.replace("/", os.sep))
            os.makedirs(os.path.dirname(out), exist_ok=True)
            shutil.copy2(src, out)
            n += 1
            total += os.path.getsize(src)

    print("  %d 个文件，%.1f MB%s" % (n, total / 1048576.0,
                                    ("，跳过 %d 个" % skipped) if skipped else ""))
    return n, total


# 数学工具站点（assets/math/）：nerdamer + function-plot + index.html。
# 这三个文件在 APK 里必须有 —— 缺了 MainActivity 会退回手写的老 MathView，
# 求导练习就只剩"比字符串"，学生写法不同一律判错。构建时直接拦下来。
MATH_ASSETS = ("index.html", "nerdamer.js", "function-plot.js")


def check_math_assets():
    """确认数学工具的三个文件都在。缺一个就退出，不产出残废包。"""
    d = os.path.join(ASSETS, "math")
    if not os.path.isdir(d):
        print("\n❌ 缺少数学工具目录：%s" % d)
        print("   这一版会退回手写 MathView（判不了等价）。")
        print("   真要这样发包，先删掉 MainActivity 里的 mathTool 引用。")
        sys.exit(1)
    missing = [f for f in MATH_ASSETS if not os.path.isfile(os.path.join(d, f))]
    if missing:
        print("\n❌ assets/math/ 缺文件：%s" % ", ".join(missing))
        sys.exit(1)
    total = sum(os.path.getsize(os.path.join(d, f)) for f in MATH_ASSETS)
    print("\n▶ 数学工具：%d 个文件，%.0f KB" % (len(MATH_ASSETS), total / 1024.0))


def zip_add(z, fp, arcname):
    """按扩展名决定压缩级别。媒体文件直接 store。"""
    ext = os.path.splitext(fp)[1].lower()
    method = zipfile.ZIP_STORED if ext in ATLAS_STORE_EXT else zipfile.ZIP_DEFLATED
    if method == zipfile.ZIP_STORED:
        # ZIP_STORED 时 compresslevel 必须省略
        z.write(fp, arcname, compress_type=method)
    else:
        z.write(fp, arcname, compress_type=method, compresslevel=6)


def main():
    bump = "--bump" in sys.argv
    # --debug：出 debuggable 包，可用 adb run-as 直接读写存档。
    # 只在本地验证时用；发版永远不带这个参数（生产包必须不可调试）。
    debug = "--debug" in sys.argv
    # --atlas / --atlas-lite：把雅思词图（独立 React 应用）打进 assets/atlas/。
    # 默认不打 —— 它让 APK 从 3.7 MB 涨到 600 MB 以上，必须是显式选择。
    #   --atlas       全量：词库 + 插画 + 英美发音 + 雅思语境朗读
    #   --atlas-lite  省掉 atlas/audio/ex/（雅思语境朗读，约 193 MB），
    #                 单词发音和插画都在，听写题型仍可用
    atlas_mode = None
    if "--atlas" in sys.argv:
        atlas_mode = "full"
    elif "--atlas-lite" in sys.argv:
        atlas_mode = "lite"
    vcode, vname = read_version(bump)
    if debug:
        print("! 调试构建：application 标记为 debuggable，不要拿去发版")
    print("=" * 62)
    print("  原生 Android APK 构建 · %s v%s (code %d)" % (APP_NAME, vname, vcode))
    print("=" * 62)

    check_keystore()

    # ── 域名红线检查 ────────────────────────────────────────
    # 2026-10-28：因客户端反复请求已失效的自有域名，xxjcloudns.kdns.fr
    # 整条 zone 被 Cloudflare 全站 403，永久报废。
    # 这里挡住"会对自有域名制造高频/并发请求"的代码，不许打进包。
    _guard = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                          "scripts", "域名红线检查.py")
    if os.path.isfile(_guard):
        _r = subprocess.run([sys.executable, _guard],
                            capture_output=True, text=True, encoding="utf-8")
        if _r.returncode != 0:
            print(_r.stdout or "")
            print(_r.stderr or "")
            print("构建中止：域名红线检查未通过。规则见项目根 AGENTS.md 第一节。")
            sys.exit(1)
        print("域名红线检查：通过")
    else:
        print("! 未找到域名红线检查脚本，跳过（不建议）")

    for p, n in [(AAPT2, "aapt2"), (JAVAC, "javac"), (D8_JAR, "d8.jar"),
                 (APKSIGNER_JAR, "apksigner.jar"), (ZIPALIGN, "zipalign"),
                 (PLATFORM, "android.jar"), (KEYSTORE, "签名钥匙")]:
        need(p, n)

    # 清空 build
    if os.path.exists(BUILD):
        shutil.rmtree(BUILD)
    for d in ["compiled", "gen", "classes", "dex", "out"]:
        os.makedirs(os.path.join(BUILD, d), exist_ok=True)

    compiled = os.path.join(BUILD, "compiled")
    gen = os.path.join(BUILD, "gen")
    classes = os.path.join(BUILD, "classes")
    dexdir = os.path.join(BUILD, "dex")
    outdir = os.path.join(BUILD, "out")

    # ── 1. 编译资源 ─────────────────────────────────────
    res_dirs = [d for d in os.listdir(RES) if os.path.isdir(os.path.join(RES, d))]
    res_files = []
    for d in res_dirs:
        dd = os.path.join(RES, d)
        for f in os.listdir(dd):
            if os.path.isfile(os.path.join(dd, f)):
                res_files.append(os.path.join(dd, f))
    print("\n找到 %d 个资源文件（%s）" % (len(res_files), ", ".join(sorted(res_dirs))))
    run([AAPT2, "compile", "-o", compiled] + res_files, "aapt2 compile 资源")

    flats = []
    for root, _, files in os.walk(compiled):
        for f in files:
            if f.endswith(".flat"):
                flats.append(os.path.join(root, f))
    print("  生成 %d 个 .flat" % len(flats))

    # ── 2. 链接资源 + 生成 R.java ────────────────────────
    unsigned = os.path.join(outdir, "unsigned.apk")
    link = [AAPT2, "link",
            "-o", unsigned,
            "-I", PLATFORM,
            "--manifest", MANIFEST,
            "--java", gen,
            "--min-sdk-version", "23",
            "--target-sdk-version", "34",
            "--version-code", str(vcode),
            "--version-name", vname,
            "--auto-add-overlay"]
    # aapt2 没有单独的 debuggable 参数，用 --debug-mode 让它
    # 在 <application> 上写入 android:debuggable="true"
    if debug:
        link += ["--debug-mode"]
    # assets 不用 aapt2 -A（中文路径下解析会出错），
    # 改成最后用 zipfile 直接写进 APK，路径更可控
    link += flats
    run(link, "aapt2 link 资源 + 生成 R.java")

    rjava = None
    for root, _, files in os.walk(gen):
        for f in files:
            if f == "R.java":
                rjava = os.path.join(root, f)
    if not rjava:
        print("❌ 没生成 R.java")
        sys.exit(1)
    print("  R.java → %s" % rjava)

    # ── 3. 编译 Java ────────────────────────────────────
    java_files = []
    for root, _, files in os.walk(SRC):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))
    java_files.append(rjava)
    print("\n找到 %d 个 .java" % len(java_files))

    run([JAVAC, "-source", "8", "-target", "8",
         "-encoding", "UTF-8",
         "-bootclasspath", PLATFORM,
         "-classpath", os.pathsep.join([PLATFORM, LAMBDA_STUBS]),
         "-d", classes,
         "-nowarn"] + java_files, "javac 编译")

    class_files = []
    for root, _, files in os.walk(classes):
        for f in files:
            if f.endswith(".class"):
                class_files.append(os.path.join(root, f))
    print("  生成 %d 个 .class" % len(class_files))

    # ── 4. dex ──────────────────────────────────────────
    run([JAVA, "-cp", D8_JAR, "com.android.tools.r8.D8",
         "--min-api", "23",
         "--output", dexdir,
         "--lib", PLATFORM] + class_files, "d8 转 dex")

    dex_file = os.path.join(dexdir, "classes.dex")
    if not os.path.exists(dex_file):
        print("❌ 没生成 classes.dex")
        sys.exit(1)
    print("  classes.dex  %.1f KB" % (os.path.getsize(dex_file) / 1024))

    # ── 4.5 同步雅思词图 ─────────────────────────────────
    if atlas_mode:
        sync_atlas(atlas_mode)

    # 数学工具是独立目录，sync_atlas 不会碰它。
    # 放这里校验是为了让缺文件在打包前就报错，而不是等用户装上发现没这个功能。
    check_math_assets()

    # ── 5. 把 dex + assets 塞进 APK ─────────────────────
    print("\n▶ 把 classes.dex 与 assets 写入 APK")
    tmp_apk = os.path.join(outdir, "with-dex.apk")
    shutil.copy(unsigned, tmp_apk)
    # 用 zipfile 追加（aapt2 产出的 APK 是标准 zip）
    with zipfile.ZipFile(tmp_apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex_file, "classes.dex")
        # assets/ 整棵目录打进去，保留相对路径。
        # 媒体（webp/mp3/woff2）用 STORED：它们本身已压缩，再 deflate
        # 只多花时间、几乎不减体积，还会让 WebView 每次解码多一步。
        assets_dir = os.path.join(PROJ, "assets")
        n_asset = 0
        if os.path.isdir(assets_dir):
            for root, _, files in os.walk(assets_dir):
                for f in files:
                    fp = os.path.join(root, f)
                    rel = os.path.relpath(fp, assets_dir).replace("\\", "/")
                    zip_add(z, fp, "assets/" + rel)
                    n_asset += 1
        print("  classes.dex + %d 个 asset" % n_asset)
    print("  写入完成")

    # ── 6. 对齐 ─────────────────────────────────────────
    aligned = os.path.join(outdir, "aligned.apk")
    run([ZIPALIGN, "-f", "-p", "4", tmp_apk, aligned], "zipalign 4 字节对齐")

    # ── 7. 签名 ─────────────────────────────────────────
    # 钥匙固定在 keystore/release.keystore，**绝不自动重新生成**。
    # main() 里 check_keystore() 已经保证它存在，这里不再有「没有就造一把」
    # 的分支——那正是让更新装不上的根因。
    suffix = "-debug" if debug else ""
    final = os.path.join(BUILD, "%s-v%s%s.apk" % (APP_NAME, vname, suffix))
    run([JAVA, "-cp", APKSIGNER_JAR, "com.android.apksigner.ApkSignerTool",
         "sign",
         "--ks", KEYSTORE,
         "--ks-key-alias", KS_ALIAS,
         "--ks-pass", "pass:" + KS_PASS,
         "--key-pass", "pass:" + KS_PASS,
         "--out", final,
         aligned], "apksigner 签名（固定钥匙）")

    # ── 8. 校验 ─────────────────────────────────────────
    print("\n▶ 校验签名")
    r = subprocess.run([JAVA, "-cp", APKSIGNER_JAR,
                        "com.android.apksigner.ApkSignerTool", "verify", "--verbose", final],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    for line in (r.stdout or "").strip().splitlines()[:12]:
        print("    " + line)

    # 打印签名指纹：装机出问题时，这个值能确认是不是同一把钥匙
    print("\n▶ 签名指纹（更新能否覆盖安装，取决于它是否始终一致）")
    r = subprocess.run([KEYTOOL, "-list", "-v", "-keystore", KEYSTORE,
                        "-alias", KS_ALIAS, "-storepass", KS_PASS],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    for line in (r.stdout or "").splitlines():
        if "SHA256:" in line:
            print("    " + line.strip())

    # 版本号落盘：下次构建自动 +1
    write_version(vcode, vname)

    print("\n" + "=" * 62)
    print("  ✅ 构建成功")
    print("  " + final)
    print("  版本 %s (versionCode %d)" % (vname, vcode))
    print("  大小 %.2f MB" % (os.path.getsize(final) / 1024 / 1024))
    print("=" * 62)

    # 顺手把最终包复制一份到 mobile/ 根目录，方便手机传。
    # 调试包不复制成正式名、不写更新清单：否则一次本地验证就会把
    # 线上 1.5.x 的清单指到一个 debuggable 包上，手机自动更新后能被调试。
    share = os.path.join(PROJ, "%s-v%s%s.apk" % (APP_NAME, vname, suffix))
    shutil.copy(final, share)
    print("  已复制到：%s" % share)

    if debug:
        print("\n  调试包已就绪，装它可以直接 run-as 改存档：")
        print("    adb install -r \"%s\"" % share)
        print("    adb shell run-as com.zsb.plan cat shared_prefs/zsb_game.xml")
        return

    # 顺手把 pages/ 下的清单也生成了。
    #
    # 这一步以前要手动跑 发版pages.py，于是 1.4.0 / 1.4.1 两次发版都忘了，
    # update.json 一直停在 1.2.3 —— App 点「检查更新」永远回「已是最新」。
    # 构建完就把清单写好，不会再有"忘了跑脚本"这种漏。
    # 真正推到 Cloudflare 仍需手动跑 发版pages.py（要联网、要凭据）。
    try:
        sp = os.path.join(PROJ, "发版pages.py")
        if os.path.exists(sp):
            subprocess.run([sys.executable, sp, "-SkipDeploy"],
                           cwd=PROJ, check=False,
                           capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
            print("  更新清单已生成：pages/update.json（部署请跑 发版pages.py）")
    except Exception as e:
        print("  ⚠ 更新清单生成失败：%s" % e)


if __name__ == "__main__":
    main()