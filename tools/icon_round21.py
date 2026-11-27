#!/usr/bin/env python3
"""
第二十一轮：方块 vs 竖版，只差长宽比。

══ 二十轮定稿（对角分割）的真实观感 ══

干净、现代、32dp 成立 —— 但读起来更像「品牌图形」，
不太像「截图工具」。缺的是长宽比这一层语义。

第 12 轮的发现仍然成立：正方形读作「文档/应用」，
竖长读作「屏幕」。当时的方案因为别的问题被否，
但长宽比这一条结论是独立的。

══ 本轮只比一件事 ══

同一个对角分割，主体从正方形改成竖版（约 0.80 比例）：

  P1  正方形 60×60（上一版，对照）
  P2  竖版 54×66
  P3  竖版 50×70（更瘦）
  P4  竖版 54×66，但分割线角度更陡（从 (0,1) 到 (1,0.15)）
      让白区更大，更像「屏幕主体 + 右下角被处理的部分」
"""
import math
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

BG = (23, 64, 61)
WHITE = (255, 255, 255)
AMBER = (240, 178, 66)
S = 108.0

VARIANTS = {
    "P1": dict(w=60, h=60, r=17, steep=False),
    "P2": dict(w=54, h=66, r=17, steep=False),
    "P3": dict(w=50, h=70, r=17, steep=False),
    "P4": dict(w=54, h=66, r=17, steep=True),
}


def draw(kind, size, scale=8):
    v = VARIANTS[kind]
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BG + (255,))
    k = s / S

    x1 = (S - v["w"]) / 2
    x2 = x1 + v["w"]
    y1 = (S - v["h"]) / 2
    y2 = y1 + v["h"]

    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [x1 * k, y1 * k, x2 * k, y2 * k], radius=v["r"] * k, fill=255)

    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)

    if v["steep"]:
        # 分割点：左上 -> 右下但更陡
        ax, ay = x1, y2
        bx, by = x2, y1 + v["h"] * 0.18
    else:
        ax, ay = x1, y2
        bx, by = x2, y1

    ld.polygon([(ax * k, ay * k), (bx * k, by * k), (x1 * k, y1 * k)],
               fill=WHITE + (255,))
    ld.polygon([(ax * k, ay * k), (x2 * k, y2 * k), (bx * k, by * k)],
               fill=AMBER + (255,))

    # 缝
    g = 2.5 * k
    dx, dy = bx - ax, by - ay
    ln = math.hypot(dx, dy)
    nx, ny = -dy / ln * g, dx / ln * g
    ld.polygon([
        ((ax + nx) * k, (ay + ny) * k),
        ((bx + nx) * k, (by + ny) * k),
        ((bx - nx) * k, (by - ny) * k),
        ((ax - nx) * k, (ay - ny) * k),
    ], fill=BG + (255,))

    img.paste(layer, (0, 0), mask)
    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = list(VARIANTS)
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
    canvas.save(os.path.join(OUT, "round21.png"))
    print("round21 written", canvas.size)


if __name__ == "__main__":
    main()
