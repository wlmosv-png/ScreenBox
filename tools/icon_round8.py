#!/usr/bin/env python3
"""
第八轮：换掉「卡片 + 横条」这套语言，并修正预览排布。

══ 两处必须承认的错误 ══

1. 预览排布错了
   上一版把 112/56/32 三个尺寸按 62px 间距摆放，112px 图标宽 112，
   于是后一个盖在前一个右侧 —— 我在一张被压坏的图上做审美判断。
   现在每个尺寸独立留足边距。

2. 语言选错了
   「卡片 + 横条」无论怎么调，读出来都是便签 / 备忘录 / 汉堡菜单。
   产品是「截图太多，帮你找、理、清」，核心不是「一张纸」。
   继续在这个语言里微调只是浪费轮次。

══ 这一轮四个方向，都在争「轮廓独特 + 32dp 成立」══

  N1  2×2 方块，右上换成琥珀
      读作「很多张里有一张被标记」；32dp 就是四块色，绝对清楚
  N2  三张叠放的圆角方块（右下错位），最前一张是琥珀
      读作「一堆截图」；比双卡更明确是"多"
  N3  粗圆环 + 环内一块琥珀扇形
      读作「筛选/聚焦」；环是单一强形状
  N4  实心方块，右下 1/4 被挖空，挖空处浮一个琥珀方块
      「从一堆里切出一块」；轮廓有断口，记忆点强
"""
import math
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

BRAND = (47, 111, 107)
WHITE = (255, 255, 255)
AMBER = (240, 178, 66)
S = 108.0


def rr(d, k, box, r, color):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color)


def draw(kind, size, scale=6):
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    d = ImageDraw.Draw(img)
    k = s / S

    if kind == "N1":
        # 2×2，右上琥珀。缝宽 7/108，32dp 下约 2dp，刚好可见
        gap = 7
        left = (28, 28, 49, 49)
        right = (59, 28, 80, 49)
        bl = (28, 59, 49, 80)
        br = (59, 59, 80, 80)
        rr(d, k, left, 7, WHITE + (255,))
        rr(d, k, bl, 7, WHITE + (255,))
        rr(d, k, br, 7, WHITE + (255,))
        rr(d, k, right, 7, AMBER + (255,))

    elif kind == "N2":
        rr(d, k, (22, 24, 64, 66), 11, WHITE + (95,))
        rr(d, k, (30, 32, 72, 74), 11, WHITE + (150,))
        rr(d, k, (38, 40, 80, 82), 11, AMBER + (255,))
        # 前卡内两条挖空线，说明"有内容"
        rr(d, k, (48, 52, 70, 58), 3, BRAND + (255,))
        rr(d, k, (48, 64, 60, 70), 3, BRAND + (255,))

    elif kind == "N3":
        # 粗圆环
        d.ellipse([24 * k, 24 * k, 84 * k, 84 * k], fill=WHITE + (255,))
        d.ellipse([37 * k, 37 * k, 71 * k, 71 * k], fill=BRAND + (255,))
        # 环内琥珀扇形（右半），代表"被选中的那一块"
        d.pieslice([37 * k, 37 * k, 71 * k, 71 * k], start=-90, end=90,
                   fill=AMBER + (255,))

    elif kind == "N4":
        # 实心方块，右下挖一个圆角缺口
        rr(d, k, (26, 26, 82, 82), 16, WHITE + (255,))
        rr(d, k, (52, 52, 90, 90), 12, BRAND + (255,))
        # 缺口里浮一个琥珀小方块
        rr(d, k, (56, 56, 80, 80), 9, AMBER + (255,))

    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def mask_squircle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    exp = 4.2
    cx = cy = n / 2
    r = n / 2
    pts = []
    for i in range(240):
        t = 2 * math.pi * i / 240
        ct, st = math.cos(t), math.sin(t)
        pts.append((
            cx + r * (abs(ct) ** (2 / exp)) * (1 if ct >= 0 else -1),
            cy + r * (abs(st) ** (2 / exp)) * (1 if st >= 0 else -1),
        ))
    ImageDraw.Draw(m).polygon(pts, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = ["N1", "N2", "N3", "N4"]
    sizes = [120, 64, 40]

    # 每个尺寸给足边距：cell 宽 = 120 + 24，高 = 120 + 30
    cell_w, cell_h = 168, 156
    canvas = Image.new("RGB", (cell_w * len(kinds) + 20, cell_h * 2 + 30),
                       (245, 245, 244))

    for row, (mname, mfn) in enumerate([("circle", mask_circle),
                                        ("squircle", mask_squircle)]):
        for col, kd in enumerate(kinds):
            x0 = 14 + col * cell_w
            y0 = 16 + row * cell_h
            cx = x0 + 6
            for px in sizes:
                ic = draw(kd, px)
                ic.putalpha(mfn(px))
                canvas.paste(ic, (cx, y0), ic)
                cx += px + 8

    canvas.save(os.path.join(OUT, "round8-masked.png"))

    # 未裁切大图，看形状本身
    raw = Image.new("RGB", (cell_w * len(kinds) + 20, 200), (245, 245, 244))
    for col, kd in enumerate(kinds):
        ic = draw(kd, 150)
        raw.paste(ic, (14 + col * cell_w, 24), ic)
    raw.save(os.path.join(OUT, "round8-raw.png"))
    print("round8 written")


if __name__ == "__main__":
    main()
