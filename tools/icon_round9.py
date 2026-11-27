#!/usr/bin/env python3
"""
第九轮：修正排布 + 把叠放收进安全区。

══ 排布错误（第三次犯同一个错）══

cell_w=168，但三个尺寸 120+64+40 加间距 = 232 > 168，
所以每个单元格的内容溢出到右边一格，图标互相压盖。

这次改成：每个候选一列，尺寸从上到下竖排，列宽 = 最大尺寸 + 边距。

══ 同时修 N2 ══

N2 三张叠放，后两张在 108 画布上放到 (22,24) 和 (30,32)，
被 adaptive icon 的遮罩切掉一部分，留下孤立碎片。
修法：整组向内收，最外层不超出安全圆（半径 36，圆心 54）。

══ 三个候选（都只在遮罩内比）══

  N1  2×2 方块，右上琥珀          轮廓：四块色，32dp 最清楚
  N2  三张叠放，最前琥珀（内收）  轮廓：一叠，语义最准
  N3  粗圆环 + 环内半块琥珀        轮廓：单一强形状，最有辨识度
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
        rr(d, k, (30, 30, 50, 50), 7, WHITE + (255,))
        rr(d, k, (30, 58, 50, 78), 7, WHITE + (255,))
        rr(d, k, (58, 58, 78, 78), 7, WHITE + (255,))
        rr(d, k, (58, 30, 78, 50), 7, AMBER + (255,))

    elif kind == "N2":
        # 整组向内收：最外层 34..74，全部落在安全圆内
        rr(d, k, (34, 32, 70, 68), 9, WHITE + (92,))
        rr(d, k, (38, 37, 74, 73), 9, WHITE + (152,))
        rr(d, k, (42, 42, 78, 78), 9, AMBER + (255,))
        rr(d, k, (50, 53, 70, 59), 3, BRAND + (255,))
        rr(d, k, (50, 64, 62, 70), 3, BRAND + (255,))

    elif kind == "N3":
        d.ellipse([30 * k, 30 * k, 78 * k, 78 * k], fill=WHITE + (255,))
        d.ellipse([41 * k, 41 * k, 67 * k, 67 * k], fill=BRAND + (255,))
        d.pieslice([41 * k, 41 * k, 67 * k, 67 * k], start=-90, end=90,
                   fill=AMBER + (255,))

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
    kinds = ["N1", "N2", "N3"]
    sizes = [128, 72, 48, 32]

    # 列宽 = 128 + 32 边距 = 160；行高 = 各尺寸 + 12
    col_w = 160
    total_h = sum(px + 14 for px in sizes) + 24
    canvas = Image.new("RGB", (col_w * len(kinds) + 24, total_h * 2 + 30),
                       (245, 245, 244))

    for mrow, mfn in enumerate([mask_circle, mask_squircle]):
        base_y = 14 + mrow * (total_h + 6)
        for col, kd in enumerate(kinds):
            x = 16 + col * col_w
            y = base_y
            for px in sizes:
                ic = draw(kd, px)
                ic.putalpha(mfn(px))
                canvas.paste(ic, (x + (128 - px) // 2, y), ic)
                y += px + 14

    canvas.save(os.path.join(OUT, "round9.png"))
    print("round9 written")
    print(f"canvas={canvas.size}")


if __name__ == "__main__":
    main()
