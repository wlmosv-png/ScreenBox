#!/usr/bin/env python3
"""
第三轮：只细化 G 与 H 两条最有希望的线。

第二轮结果：
  E 粗描边 + 三行  → 32dp 下还是「一张便签」，形状无记忆点
  F 双框交叠       → 概念准（重复检测），但 32dp 下两个框糊在一起
  G 实心块咬掉一口 → 轮廓独特，缺口里的琥珀点在小尺寸仍可见
  H 实心块 + 琥珀角 → 最干净，但琥珀角容易被读成「角标/贴纸」

细化的目的不是加装饰，是让「一个形状」在 32dp 就成立：

  G1  圆缺口在左下，琥珀点居中（第二轮原样）
  G2  圆缺口更大，点更大，去掉两条短线（减元素）
  G3  缺口移到右下，点更大（避开左上视觉重心）
  H1  琥珀角改成 45° 直切（更利落）
  H2  琥珀角 + 缺口内一个小白点（暗示「已识别」）
  H3  琥珀角缩到 1/3，留更多白（更克制）
"""
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


def ell(d, k, cx, cy, r, color):
    d.ellipse([(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k], fill=color)


def draw(kind, size, scale=6):
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    d = ImageDraw.Draw(img)
    k = s / S
    hole = BRAND + (255,)

    if kind == "G1":
        rr(d, k, (24, 24, 84, 84), 18, WHITE + (255,))
        ell(d, k, 40, 68, 19, hole)
        ell(d, k, 40, 68, 8.5, AMBER + (255,))
        rr(d, k, (52, 38, 72, 44), 3, hole)
        rr(d, k, (52, 50, 64, 56), 3, hole)

    elif kind == "G2":
        rr(d, k, (23, 23, 85, 85), 19, WHITE + (255,))
        ell(d, k, 39, 69, 22, hole)
        ell(d, k, 39, 69, 11.5, AMBER + (255,))

    elif kind == "G3":
        rr(d, k, (23, 23, 85, 85), 19, WHITE + (255,))
        ell(d, k, 69, 69, 22, hole)
        ell(d, k, 69, 69, 11.5, AMBER + (255,))
        rr(d, k, (36, 40, 58, 47), 3.5, hole)

    elif kind == "H1":
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            [23 * k, 23 * k, 85 * k, 85 * k], radius=19 * k, fill=255)
        body = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        bd = ImageDraw.Draw(body)
        bd.rounded_rectangle([23 * k, 23 * k, 85 * k, 85 * k], radius=19 * k, fill=WHITE + (255,))
        bd.polygon([(85 * k, 46 * k), (85 * k, 85 * k), (46 * k, 85 * k)], fill=AMBER + (255,))
        img.paste(body, (0, 0), mask)
        rr(d, k, (36, 40, 60, 47), 3.5, hole)
        rr(d, k, (36, 54, 48, 61), 3.5, hole)

    elif kind == "H2":
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            [23 * k, 23 * k, 85 * k, 85 * k], radius=19 * k, fill=255)
        body = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        bd = ImageDraw.Draw(body)
        bd.rounded_rectangle([23 * k, 23 * k, 85 * k, 85 * k], radius=19 * k, fill=WHITE + (255,))
        bd.polygon([(85 * k, 46 * k), (85 * k, 85 * k), (46 * k, 85 * k)], fill=AMBER + (255,))
        img.paste(body, (0, 0), mask)
        ell(d, k, 68, 68, 5.5, WHITE + (255,))
        rr(d, k, (36, 40, 60, 47), 3.5, hole)
        rr(d, k, (36, 54, 48, 61), 3.5, hole)

    elif kind == "H3":
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).rounded_rectangle(
            [23 * k, 23 * k, 85 * k, 85 * k], radius=19 * k, fill=255)
        body = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        bd = ImageDraw.Draw(body)
        bd.rounded_rectangle([23 * k, 23 * k, 85 * k, 85 * k], radius=19 * k, fill=WHITE + (255,))
        bd.polygon([(85 * k, 57 * k), (85 * k, 85 * k), (57 * k, 85 * k)], fill=AMBER + (255,))
        img.paste(body, (0, 0), mask)
        rr(d, k, (36, 40, 62, 47), 3.5, hole)
        rr(d, k, (36, 54, 50, 61), 3.5, hole)

    return img.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = ["G1", "G2", "G3", "H1", "H2", "H3"]
    canvas = Image.new("RGB", (1010, 720), (250, 250, 249))
    for i, kd in enumerate(kinds):
        col, row = i % 3, i // 3
        x = 20 + col * 330
        y = 12 + row * 355
        big = draw(kd, 170)
        canvas.paste(big, (x, y), big)
        for j, px in enumerate((64, 48, 32)):
            ic = draw(kd, px)
            canvas.paste(ic, (x + 4 + j * 56, y + 190), ic)
    canvas.save(os.path.join(OUT, "round3.png"))

    for kd in kinds:
        for name, bg in (("light", (250, 250, 249)), ("dark", (17, 19, 18))):
            c = Image.new("RGB", (420, 190), bg)
            for j, px in enumerate((128, 64, 40)):
                ic = draw(kd, px)
                c.paste(ic, (24 + j * 132, 30), ic)
            c.save(os.path.join(OUT, f"{kd}-{name}.png"))
    print("round3 written")


if __name__ == "__main__":
    main()
