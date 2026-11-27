#!/usr/bin/env python3
"""
D 方向细化：切角 + 角标点。

为什么选 D：
  A 双卡 + 三行 —— 48dp 下像便签/备忘录，与产品关系弱
  B 三层叠放 —— 叠出来的锯齿边在 32dp 变成一团
  C 框内三行 + 角点 —— 点压在圆角框上，缩到 32dp 变成一块脏斑
  D 切角方块 + 角标点 —— 轮廓独特、单一亮点、小尺寸仍清晰

三个细化方向：
  D1 方形缺口，点居中在缺口里
  D2 四分之一圆缺口（更现代），点居中
  D3 方形缺口，点跨在缺口边缘（更紧的构图）
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

    if kind == "D1":
        rr(d, k, (24, 24, 84, 84), 16, WHITE + (255,))
        d.rectangle([60 * k, 24 * k, 84 * k, 48 * k], fill=hole)
        ell(d, k, 72, 36, 7, AMBER + (255,))
        rr(d, k, (37, 55, 71, 62), 3.5, hole)
        rr(d, k, (37, 69, 55, 76), 3.5, hole)

    elif kind == "D2":
        rr(d, k, (24, 24, 84, 84), 16, WHITE + (255,))
        # 四分之一圆缺口：用底色的圆盖掉右上角
        ell(d, k, 84, 24, 26, hole)
        ell(d, k, 72, 37, 7.2, AMBER + (255,))
        rr(d, k, (37, 55, 71, 62), 3.5, hole)
        rr(d, k, (37, 69, 55, 76), 3.5, hole)

    elif kind == "D3":
        rr(d, k, (24, 24, 84, 84), 16, WHITE + (255,))
        d.rectangle([62 * k, 24 * k, 84 * k, 46 * k], fill=hole)
        ell(d, k, 70, 38, 7.5, AMBER + (255,))
        rr(d, k, (37, 54, 71, 61), 3.5, hole)
        rr(d, k, (37, 68, 55, 75), 3.5, hole)

    return img.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = ["D1", "D2", "D3"]
    canvas = Image.new("RGB", (760, 500), (250, 250, 249))
    for i, kd in enumerate(kinds):
        x = 20 + i * 250
        big = draw(kd, 176)
        canvas.paste(big, (x, 12), big)
        for j, px in enumerate((64, 48, 32)):
            ic = draw(kd, px)
            canvas.paste(ic, (x + 6 + j * 58, 210), ic)
    canvas.save(os.path.join(OUT, "d-refine.png"))

    # 深浅底 + 圆形遮罩检查
    for kd in kinds:
        for name, bg in (("light", (250, 250, 249)), ("dark", (17, 19, 18))):
            c = Image.new("RGB", (420, 190), bg)
            for j, px in enumerate((128, 64, 40)):
                ic = draw(kd, px)
                c.paste(ic, (24 + j * 132, 30), ic)
            c.save(os.path.join(OUT, f"{kd}-{name}.png"))
    print("refined written")


if __name__ == "__main__":
    main()
