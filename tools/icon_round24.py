#!/usr/bin/env python3
"""
第二十四轮：放弃「卡片」，改用「堆叠的横带」。

══ 二十三轮的结论 ══

R1–R4 在渲染图上几乎无差别，48dp 以下两张卡糊成一块。
原因不是参数没调好，是「两个重叠的圆角矩形」在小尺寸必然合并。

══ 为什么改用横带 ══

一条粗横带在 32dp 时仍然是一条清晰的线；
两个重叠的矩形在 32dp 时必然变成一团。

横带是「在任何尺寸都保形」的基本单元。截图整理的本质是
「把画面切成可检索的行」—— 横带正是这个语义的最小表达。

══ 四个候选（都用横带，不用卡片）══

  B1  三条等长横带，中间一条琥珀（对照：最像菜单）
  B2  三条递减横带（100% / 68% / 40%），中间琥珀
      递减打破「菜单」的读法，读作「文字行」
  B3  四条递减横带，第二条琥珀
      信息更足，但 32dp 可能糊
  B4  三条递减横带，全部白，只在最右端加一小段琥珀
      「行 + 末端标记」，最克制
"""
import math
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

S = 108.0
BG_TOP = (46, 118, 112)
BG_BOTTOM = (19, 54, 52)
AMBER = (240, 178, 66)
WHITE = (255, 255, 255)


def gradient(size, top, bottom):
    img = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        img.putpixel((0, y), tuple(
            int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img.resize((size, size), Image.BILINEAR).convert("RGBA")


# 横带：(x1, y1, x2, y2, 圆角, 颜色)
BARS = {
    "B1": [
        (26, 30, 82, 44, 7, WHITE),
        (26, 47, 82, 61, 7, AMBER),
        (26, 64, 82, 78, 7, WHITE),
    ],
    "B2": [
        (26, 28, 82, 42, 7, WHITE),
        (26, 47, 64, 61, 7, AMBER),
        (26, 66, 52, 80, 7, WHITE),
    ],
    "B3": [
        (26, 24, 82, 36, 6, WHITE),
        (26, 40, 70, 52, 6, AMBER),
        (26, 56, 58, 68, 6, WHITE),
        (26, 72, 46, 84, 6, WHITE),
    ],
    "B4": [
        (26, 28, 82, 42, 7, WHITE),
        (26, 47, 64, 61, 7, WHITE),
        (26, 66, 52, 80, 7, WHITE),
    ],
}


def draw(kind, size, scale=8):
    s = int(size * scale)
    k = s / S
    img = gradient(s, BG_TOP, BG_BOTTOM)
    d = ImageDraw.Draw(img)

    for (x1, y1, x2, y2, r, color) in BARS[kind]:
        d.rounded_rectangle([x1 * k, y1 * k, x2 * k, y2 * k],
                            radius=r * k, fill=color + (255,))

    if kind == "B4":
        # 最右端一小段琥珀，作为「末端标记」
        d.rounded_rectangle([68 * k, 28 * k, 82 * k, 42 * k],
                            radius=7 * k, fill=AMBER + (255,))
        d.rounded_rectangle([54 * k, 47 * k, 64 * k, 61 * k],
                            radius=7 * k, fill=AMBER + (255,))
        d.rounded_rectangle([44 * k, 66 * k, 52 * k, 80 * k],
                            radius=7 * k, fill=AMBER + (255,))

    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = list(BARS)
    sizes = [128, 96, 64, 48, 32]

    col_w = 152
    total_h = sum(px + 12 for px in sizes) + 12
    canvas = Image.new("RGB", (col_w * len(kinds) + 16, total_h + 20), (236, 236, 234))
    for col, kd in enumerate(kinds):
        x = 8 + col * col_w
        y = 10
        for px in sizes:
            ic = draw(kd, px)
            ic.putalpha(mask_circle(px))
            canvas.paste(ic, (x + (128 - px) // 2, y), ic)
            y += px + 12
    canvas.save(os.path.join(OUT, "round24.png"))
    print("round24 written", canvas.size)


if __name__ == "__main__":
    main()
