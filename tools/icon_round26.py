#!/usr/bin/env python3
"""
第二十六轮：调亮配色 + 优化构图比例，然后收工。

══ 上一轮真图的判断 ══

A/B/D 三个方案在 32dp 下几乎一样 —— 都是「白块 + 琥珀点」。
说明形状已经收敛到同一个解，差别只在配色与比例。

真图暴露的真问题：
  · 背景太暗（#2E7670 → #133634），整块像深色贴纸，没有活力
  · 白卡占比太大（46/108 = 43%），四周留白不足，显得拥挤
  · 琥珀角标偏小，在大尺寸下不够醒目

══ 本轮三个调整 ══

1. 配色调亮：换成明快的青绿 → 深青绿渐变，提高明度
2. 构图放松：白卡缩到 40×46，四周留白从 30 提到 34
3. 角标放大：半径 11 → 12，且向外多溢出 2dp

══ 四个候选 ══

  M1  明快青绿渐变 + 白卡 + 大琥珀点
  M2  深青渐变 + 白卡 + 大琥珀点（对照：保留深色但放大角标）
  M3  明快青绿渐变 + 白卡 + 琥珀方形角标
  M4  珊瑚橙渐变 + 白卡 + 深青角标（对比色，最跳）
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

S = 108.0
WHITE = (255, 255, 255)
AMBER = (245, 184, 70)
TEAL_DEEP = (18, 62, 58)
CORAL = (233, 106, 82)
CORAL_DEEP = (170, 62, 48)

VARIANTS = {
    "M1": dict(bg=((78, 168, 158), (26, 104, 96)), card=(34, 32, 74, 78, 12),
               badge="circle", bc=(70, 72), br=12, bcolor=AMBER),
    "M2": dict(bg=((46, 118, 112), (19, 54, 52)), card=(34, 32, 74, 78, 12),
               badge="circle", bc=(70, 72), br=12, bcolor=AMBER),
    "M3": dict(bg=((78, 168, 158), (26, 104, 96)), card=(34, 32, 74, 78, 12),
               badge="square", bc=(70, 72), br=12, bcolor=AMBER),
    "M4": dict(bg=((240, 128, 100), (176, 66, 50)), card=(34, 32, 74, 78, 12),
               badge="circle", bc=(70, 72), br=12, bcolor=TEAL_DEEP),
}


def gradient(size, top, bottom):
    img = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        img.putpixel((0, y), tuple(
            int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img.resize((size, size), Image.BILINEAR).convert("RGBA")


def draw(kind, size, scale=8):
    v = VARIANTS[kind]
    s = int(size * scale)
    k = s / S
    img = gradient(s, v["bg"][0], v["bg"][1])

    card = v["card"]
    sh = Image.new("L", (s, s), 0)
    ImageDraw.Draw(sh).rounded_rectangle(
        [card[0] * k, (card[1] + 2.5) * k, card[2] * k, (card[3] + 2.5) * k],
        radius=card[4] * k, fill=100)
    sh = sh.filter(ImageFilter.GaussianBlur(2.2 * k))
    shadow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    shadow.paste(Image.new("RGBA", (s, s), (0, 0, 0, 255)), (0, 0), sh)
    img.alpha_composite(shadow)

    d = ImageDraw.Draw(img)
    d.rounded_rectangle([card[0] * k, card[1] * k, card[2] * k, card[3] * k],
                        radius=card[4] * k, fill=WHITE + (255,))

    cx, cy = v["bc"][0] * k, v["bc"][1] * k
    r = v["br"] * k
    col = v["bcolor"] + (255,)
    if v["badge"] == "circle":
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
    else:
        d.rounded_rectangle([cx - r, cy - r, cx + r, cy + r],
                            radius=r * 0.42, fill=col)

    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = list(VARIANTS)
    sizes = [160, 96, 64, 48, 32]

    col_w = 190
    total_h = sum(px + 14 for px in sizes) + 14
    canvas = Image.new("RGB", (col_w * len(kinds) + 16, total_h + 20), (248, 248, 246))
    for col, kd in enumerate(kinds):
        x = 8 + col * col_w
        y = 10
        for px in sizes:
            ic = draw(kd, px)
            ic.putalpha(mask_circle(px))
            canvas.paste(ic, (x + (160 - px) // 2, y), ic)
            y += px + 14
    canvas.save(os.path.join(OUT, "round26.png"))
    print("round26 written", canvas.size)


if __name__ == "__main__":
    main()
