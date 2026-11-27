#!/usr/bin/env python3
"""
第十五轮：等大 + 分离 + 放大。

══ 看到真实渲染图后的诊断 ══

上一版（F2）装出来的实际观感：
  · 白卡 28×48，琥珀卡 28×66 —— 宽度相同、长度不同、位置重叠
  · 白卡被读成「药丸」，琥珀被读成「C 形括号」
  · 合起来完全不像两张截图

根因（这次是真的根因，不是猜测）：
  重叠 + 尺寸不同 = 必然被读成「一个物体 + 它的阴影/高光/内衬」。
  只有「等大 + 分离」的两个形状，才会被读成「两个独立的东西」。

同时上一版图形只占画布 30..78，四周留白过多，
48dp 下整体偏小，进一步降低识别度。

══ 本轮原则 ══

1. 两个形状**等大**（同宽同高）
2. 彼此**不重叠**，留明确间隙
3. 整体放大到 18..90，占满画布
4. 只有两种颜色，零内部细节

══ 四个候选 ══

  K1  两张等大竖卡，水平并排，间隙 8，左白右琥珀
      读作「两张截图并排」—— 最直白
  K2  两张等大竖卡，垂直错位 14，水平错位 10，不重叠
      读作「一叠错开的截图」—— 有层次
  K3  两张等大竖卡，垂直错位 20，水平不偏移
      读作「一叠」—— 最稳
  K4  一张大竖卡 + 一张等宽短卡叠在上方（像截图列表）
      读作「一屏 + 上面还有」
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

# 等大竖卡：宽 30，高 58
CW, CH = 30.0, 58.0
R = 9.0

VARIANTS = {
    # 水平并排，间隙 8
    "K1": [((19, 25, 49, 83), WHITE), ((57, 25, 87, 83), AMBER)],
    # 错位：左白在前偏下，右琥珀在后偏上（不重叠，间隙 6）
    "K2": [((16, 32, 46, 90), WHITE), ((52, 18, 82, 76), AMBER)],
    # 垂直错位 20，水平居中不偏移 → 用左右轻微错开避免完全对齐
    "K3": [((20, 20, 50, 78), AMBER), ((38, 34, 68, 92), WHITE)],
    # 一张长卡 + 一张等宽短卡
    "K4": [((36, 18, 66, 44), AMBER), ((36, 50, 66, 90), WHITE)],
}


def rr(d, k, box, r, color):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color + (255,))


def draw(kind, size, scale=8):
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    d = ImageDraw.Draw(img)
    k = s / S
    for box, color in VARIANTS[kind]:
        rr(d, k, box, R, color)
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

    col_w = 156
    total_h = sum(px + 14 for px in sizes) + 14
    canvas = Image.new("RGB", (col_w * len(kinds) + 16, total_h + 24),
                       (245, 245, 244))
    for col, kd in enumerate(kinds):
        x = 8 + col * col_w
        y = 12
        for px in sizes:
            ic = draw(kd, px)
            ic.putalpha(mask_circle(px))
            canvas.paste(ic, (x + (128 - px) // 2, y), ic)
            y += px + 14
    canvas.save(os.path.join(OUT, "round15.png"))
    print("round15 written", canvas.size)


if __name__ == "__main__":
    main()
