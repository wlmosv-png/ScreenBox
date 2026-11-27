#!/usr/bin/env python3
"""
第二十五轮：只比三件事，然后收工。

上一版（v8：正方白卡 + 琥珀圆角标）在真实渲染图上：
  干净、平衡、32dp 清楚 —— 但读作「通知角标」，语义泛化，
  放任何一个消息/待办应用上也说得通。

第 12 轮的结论依然有效：正方形读作「应用/文档」，竖长读作「屏幕」。

本轮只比长宽比与角标形状，不再探索新语言：

  A  竖卡 42×54 + 琥珀圆角标（屏幕比例 + 角标语言）
  B  方卡 46×46 + 琥珀圆角标（当前 v8，对照）
  D  竖卡 42×54 + 琥珀圆角方形角标（更「应用感」，更少「通知感」）

三者几何都验算在安全圆（圆心 54,54，半径 36）内。
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

S = 108.0
BG_TOP = (46, 118, 112)
BG_BOTTOM = (19, 54, 52)
AMBER = (240, 178, 66)
WHITE = (255, 255, 255)

VARIANTS = {
    "A": dict(card=(33, 26, 75, 80, 12), badge="circle", bc=(70, 71), br=10),
    "B": dict(card=(30, 30, 76, 76, 13), badge="circle", bc=(71, 71), br=11),
    "D": dict(card=(33, 26, 75, 80, 12), badge="square", bc=(70, 71), br=10),
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
    img = gradient(s, BG_TOP, BG_BOTTOM)

    card = v["card"]
    sh = Image.new("L", (s, s), 0)
    ImageDraw.Draw(sh).rounded_rectangle(
        [card[0] * k, (card[1] + 2.5) * k, card[2] * k, (card[3] + 2.5) * k],
        radius=card[4] * k, fill=105)
    sh = sh.filter(ImageFilter.GaussianBlur(2.0 * k))
    shadow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    shadow.paste(Image.new("RGBA", (s, s), (0, 0, 0, 255)), (0, 0), sh)
    img.alpha_composite(shadow)

    d = ImageDraw.Draw(img)
    d.rounded_rectangle([card[0] * k, card[1] * k, card[2] * k, card[3] * k],
                        radius=card[4] * k, fill=WHITE + (255,))

    cx, cy = v["bc"][0] * k, v["bc"][1] * k
    r = v["br"] * k
    if v["badge"] == "circle":
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=AMBER + (255,))
    else:
        d.rounded_rectangle([cx - r, cy - r, cx + r, cy + r],
                            radius=r * 0.45, fill=AMBER + (255,))

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
    canvas.save(os.path.join(OUT, "round25.png"))
    print("round25 written", canvas.size)


if __name__ == "__main__":
    main()
