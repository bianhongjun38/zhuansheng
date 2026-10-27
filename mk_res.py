# -*- coding: utf-8 -*-
"""生成 Android 原生 App 的图标资源（各密度 PNG）。

不依赖 Android Studio / VectorDrawable：直接出 PNG，
丢进 res/drawable-*dpi/，aapt2 会自动按密度挑。
"""
import os, sys
sys.stdout.reconfigure(encoding="utf-8")
from PIL import Image, ImageDraw, ImageFont

ROOT = r"E:\小黑盒运营\cq-zhuanshengbens\mobile"
BG   = (20, 22, 28, 255)
FG   = (124, 108, 240, 255)
TXT  = (240, 242, 248, 255)
ACC  = (232, 154, 157, 255)
RING = (60, 64, 80, 255)

# 密度 → 图标边长（px）
DENS = {
    "mdpi":    48,
    "hdpi":    72,
    "xhdpi":   96,
    "xxhdpi":  144,
    "xxxhdpi": 192,
}


def pick_font(px):
    for p in [r"C:\Windows\Fonts\msyhbd.ttc", r"C:\Windows\Fonts\msyh.ttc",
              r"C:\Windows\Fonts\simhei.ttf"]:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, px)
            except Exception:
                pass
    return ImageFont.load_default()


def make(size, round_corner=True):
    S = size * 4
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if round_corner:
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.22), fill=BG)
    else:
        d.rectangle([0, 0, S, S], fill=BG)

    cx = cy = S // 2
    R = int(S * 0.33)
    w = max(2, int(S * 0.055))
    d.arc([cx - R, cy - R, cx + R, cy + R], start=-90, end=200, fill=FG, width=w)
    d.arc([cx - R, cy - R, cx + R, cy + R], start=200, end=270, fill=RING, width=w)

    f = pick_font(int(S * 0.33))
    ch = "专"
    bb = d.textbbox((0, 0), ch, font=f)
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    d.text((cx - tw / 2 - bb[0], cy - th / 2 - bb[1]), ch, font=f, fill=TXT)

    dot = int(S * 0.06)
    dx, dy = int(cx + R * 0.72), int(cy - R * 0.72)
    d.ellipse([dx - dot, dy - dot, dx + dot, dy + dot], fill=ACC)
    return im.resize((size, size), Image.LANCZOS)


total = 0
for dens, px in DENS.items():
    outdir = os.path.join(ROOT, "res", "drawable-" + dens)
    os.makedirs(outdir, exist_ok=True)
    p = os.path.join(outdir, "ic_launcher.png")
    make(px).save(p)
    print("  %-28s %4dpx  %6d B" % ("drawable-%s/ic_launcher.png" % dens, px, os.path.getsize(p)))
    total += 1

# 通知栏/状态栏用的单色小图标（不必须，但留一个干净的白版）
outdir = os.path.join(ROOT, "res", "drawable-xxxhdpi")
print("共生成 %d 个图标" % total)
