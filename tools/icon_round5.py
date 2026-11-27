#!/usr/bin/env python3
"""
第五轮：修「琥珀卡读成投影」的问题。

问题诊断（看图得出，不是推测）：
  后卡偏移方向是 (+8, +8)，也就是右下。
  而 Material 阴影的方向正好也是右下 —— 所以那块琥珀不被读成
  「第二张卡」，而是被读成「白色卡片的黄色投影」。

这解释了为什么前三轮怎么调都不对：形状本身没错，
偏移方向在语义上和阴影撞车了。

修法：只沿水平方向偏移（纯右），或向右上偏移。
阴影不会出现在正右方或右上，所以这两种偏移都能读成「另一张卡」。

同时把内部两条线加粗到 10/108（32dp 下约 2.9dp），
避免小尺寸糊成一块。

对比 6 个组合：
  offset: 纯右 +10 / 右上 (+8,-8) / 纯右 +14
  mark:   两条粗线 / 一条粗线 + 一个点
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

BRAND = (47, 111, 107)
WHITE = (255, 255, 255)
AMBER = (240, 178, 66)
S = 108.0

FRONT = (30.0, 30.0, 78.0, 78.0, 13.0)


def rr(d, k, box, r, color):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color)


def ell(d, k, cx, cy, r, color):
    d.ellipse([(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k], fill=color)


VARIANTS = {
    # 纯右 +10，两条粗线
    "R10-2L": dict(back=(40, 30, 88, 78), mark="2lines"),
    # 纯右 +14，两条粗线
    "R14-2L": dict(back=(44, 30, 92, 78), mark="2lines"),
    # 右上 (+8,-8)，两条粗线
    "UR-2L": dict(back=(38, 22, 86, 70), mark="2lines"),
    # 纯右 +10，一条粗线 + 点
    "R10-1LD": dict(back=(40, 30, 88, 78), mark="line+dot"),
    # 右上 (+8,-8)，一条粗线 + 点
    "UR-1LD": dict(back=(38, 22, 86, 70), mark="line+dot"),
    # 纯右 +12，三条粗线（信息更足）
    "R12-3L": dict(back=(42, 30, 90, 78), mark="3lines"),
}


def draw(kind, size, scale=6):
    v = VARIANTS[kind]
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    d = ImageDraw.Draw(img)
    k = s / S
    hole = BRAND + (255,)

    # 后卡：完整圆角方块
    bx = v["back"]
    rr(d, k, bx, 13, AMBER + (255,))
    # 前卡
    rr(d, k, FRONT[:4], FRONT[4], WHITE + (255,))

    m = v["mark"]
    if m == "2lines":
        rr(d, k, (41, 43, 67, 53), 5, hole)
        rr(d, k, (41, 60, 57, 70), 5, hole)
    elif m == "3lines":
        rr(d, k, (41, 40, 67, 49), 4.5, hole)
        rr(d, k, (41, 54, 57, 63), 4.5, hole)
        rr(d, k, (41, 68, 61, 77), 4.5, hole)
    elif m == "line+dot":
        rr(d, k, (41, 42, 67, 52), 5, hole)
        ell(d, k, 48, 64, 6.5, AMBER + (255,))

    return img.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = list(VARIANTS)
    canvas = Image.new("RGB", (1120, 560), (250, 250, 249))
    for i, kd in enumerate(kinds):
        col, row = i % 3, i // 3
        x, y = 20 + col * 366, 14 + row * 272
        big = draw(kd, 160)
        canvas.paste(big, (x, y), big)
        for j, px in enumerate((64, 48, 32)):
            ic = draw(kd, px)
            canvas.paste(ic, (x + 176 + j * 58, y + 40), ic)
    canvas.save(os.path.join(OUT, "round5.png"))
    print("round5 written")


if __name__ == "__main__":
    main()
