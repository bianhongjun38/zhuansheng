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


def main():
    bump = "--bump" in sys.argv
    vcode, vname = read_version(bump)
    print("=" * 62)
    print("  原生 Android APK 构建 · %s v%s (code %d)" % (APP_NAME, vname, vcode))
    print("=" * 62)

    check_keystore()

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

    # ── 5. 把 dex + assets 塞进 APK ─────────────────────
    print("\n▶ 把 classes.dex 与 assets 写入 APK")
    tmp_apk = os.path.join(outdir, "with-dex.apk")
    shutil.copy(unsigned, tmp_apk)
    # 用 zipfile 追加（aapt2 产出的 APK 是标准 zip）
    with zipfile.ZipFile(tmp_apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex_file, "classes.dex")
        # assets/ 整棵目录打进去，保留相对路径
        assets_dir = os.path.join(PROJ, "assets")
        n_asset = 0
        if os.path.isdir(assets_dir):
            for root, _, files in os.walk(assets_dir):
                for f in files:
                    fp = os.path.join(root, f)
                    rel = os.path.relpath(fp, assets_dir).replace("\\", "/")
                    z.write(fp, "assets/" + rel)
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
    final = os.path.join(BUILD, "%s-v%s.apk" % (APP_NAME, vname))
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

    # 顺手把最终包复制一份到 mobile/ 根目录，方便手机传
    share = os.path.join(PROJ, "%s-v%s.apk" % (APP_NAME, vname))
    shutil.copy(final, share)
    print("  已复制到：%s" % share)

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
