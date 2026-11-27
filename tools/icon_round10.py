#!/usr/bin/env python3
"""
第十轮：只细化 N2（三张叠放），其它方向已排除。

══ 排除记录 ══
  N1  2×2 方块 + 一块琥珀 → 全尺寸都清楚，但读成「应用网格 / 小组件」
  N3  圆环 + 半块琥珀     → 读成「深色模式开关」，语义完全跑偏

══ N2 的问题 ══
  128dp 好看，72dp 好看，48dp 开始糊，32dp 后两张后卡合成一圈光晕。

  根因：三张卡尺寸相同、只平移 4–8dp，小尺寸下抗锯齿把它们糊在一起。
  另外后卡用了低 alpha 白（92/152），在品牌青底上对比度不足。

══ 三个修法 ══

  N2a  两张卡（不是三张），左上偏移 10dp，后卡用实心白 45%
       少一张就少一层糊；偏移加大让边缘明确分开
  N2b  三张卡，但后两张改成「描边」而不是「半透明白」
       描边在小尺寸下比低 alpha 填充更容易保住轮廓
  N2c  两张卡，后卡整块琥珀、前卡白色带两行挖空
       颜色对调 —— 琥珀在后当"那一叠"，白在前当"正在看的那张"
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


def outline(d, k, box, r, color, width):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, outline=color, width=int(width * k))


def draw(kind, size, scale=6):
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    d = ImageDraw.Draw(img)
    k = s / S

    if kind == "N2a":
        # 两张卡，左上偏移 10
        rr(d, k, (34, 30, 70, 66), 10, WHITE + (115,))
        rr(d, k, (44, 40, 80, 76), 10, AMBER + (255,))
        rr(d, k, (52, 50, 72, 57), 3.5, BRAND + (255,))
        rr(d, k, (52, 62, 64, 69), 3.5, BRAND + (255,))

    elif kind == "N2b":
        # 两张后卡改描边
        outline(d, k, (32, 28, 68, 64), 10, WHITE + (150,), 2.6)
        outline(d, k, (37, 33, 73, 69), 10, WHITE + (205,), 2.6)
        rr(d, k, (42, 38, 78, 74), 10, AMBER + (255,))
        rr(d, k, (50, 48, 70, 55), 3.5, BRAND + (255,))
        rr(d, k, (50, 60, 62, 67), 3.5, BRAND + (255,))

    elif kind == "N2c":
        # 后卡琥珀，前卡白
        rr(d, k, (34, 30, 72, 68), 11, AMBER + (255,))
        rr(d, k, (44, 40, 82, 78), 11, WHITE + (255,))
        rr(d, k, (52, 50, 74, 58), 4, BRAND + (255,))
        rr(d, k, (52, 63, 64, 71), 4, BRAND + (255,))

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
    kinds = ["N2a", "N2b", "N2c"]
    sizes = [128, 72, 48, 32]

    col_w = 156
    total_h = sum(px + 14 for px in sizes) + 20
    canvas = Image.new("RGB", (col_w * len(kinds) + 24, total_h * 2 + 26),
                       (245, 245, 244))

    for mrow, mfn in enumerate([mask_circle, mask_squircle]):
        base_y = 12 + mrow * (total_h + 4)
        for col, kd in enumerate(kinds):
            x = 14 + col * col_w
            y = base_y
            for px in sizes:
                ic = draw(kd, px)
                ic.putalpha(mfn(px))
                canvas.paste(ic, (x + (128 - px) // 2, y), ic)
                y += px + 14

    canvas.save(os.path.join(OUT, "round10.png"))
    print("round10 written", canvas.size)


if __name__ == "__main__":
    main()
