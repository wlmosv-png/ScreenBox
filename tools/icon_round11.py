#!/usr/bin/env python3
"""
第十一轮：去掉内部横线，只靠形状表达。

══ 看到真实渲染后的诊断 ══

上一版（N2c：琥珀后卡 + 白前卡 + 两条深色横条）装出来后：
  · 白卡里两条深色圆角横条 → 读成「眼睛 / 数字 8 / 聊天气泡」
  · 圆角 11 对 38 宽的卡来说太大 → 整体像气泡，不像卡片
  · 结果是「聊天应用图标」，与产品完全无关

根因：「矩形 + 内部横线」这个组合在图标尺度上永远读作文档/聊天/菜单。
      所有非截图类应用都在用这个符号，它无法承担识别功能。

══ 这一轮的原则 ══

1. 卡片内部不放任何线
2. 语义靠「形状关系」表达，不靠「内容」
3. 每个方案最多两个形状、两种颜色
4. 圆角降到 8（38 宽约 21%），避免气泡感

══ 六个方案 ══

  Q1  白描边方框 + 琥珀实心方块（左下露出）
      「屏幕」用描边表达，与「纸张」的实心区分开
  Q2  白实心方块 + 琥珀实心方块，左上错位 12
      纯叠放，无内部细节（N2c 去掉横条）
  Q3  白实心方块 + 右下角琥珀实心圆
      「一张图 + 一个状态点」
  Q4  白描边方框 + 框内右下角琥珀实心方块
      嵌套，全部收在框内，轮廓最稳
  Q5  白实心方块 + 右下角 1/4 圆角挖空 + 挖空处琥珀方块
      断口式，轮廓有记忆点
  Q6  三个白实心方块横向错位排列（左小右大），最大那个琥珀
      纯「一堆」的意象，最小尺寸也清楚
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
R = 8.0   # 统一圆角，比之前的 11 小，避免气泡感


def rr(d, k, box, r, color):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color)


def outline(d, k, box, r, color, w):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, outline=color, width=max(1, int(w * k)))


def draw(kind, size, scale=8):
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    d = ImageDraw.Draw(img)
    k = s / S

    if kind == "Q1":
        # 后：琥珀实心，左下露出
        rr(d, k, (32, 44, 68, 80), R, AMBER + (255,))
        # 前：白色描边方框
        outline(d, k, (42, 30, 80, 68), R, WHITE + (255,), 6.5)

    elif kind == "Q2":
        rr(d, k, (32, 32, 70, 70), R, AMBER + (255,))
        rr(d, k, (44, 44, 82, 82), R, WHITE + (255,))

    elif kind == "Q3":
        rr(d, k, (30, 30, 78, 78), 10, WHITE + (255,))
        d.ellipse([58 * k, 58 * k, 84 * k, 84 * k], fill=AMBER + (255,))

    elif kind == "Q4":
        outline(d, k, (28, 28, 80, 80), 10, WHITE + (255,), 6.5)
        rr(d, k, (50, 50, 74, 74), 7, AMBER + (255,))

    elif kind == "Q5":
        rr(d, k, (28, 28, 80, 80), 12, WHITE + (255,))
        rr(d, k, (52, 52, 92, 92), 14, BRAND + (255,))
        rr(d, k, (56, 56, 80, 80), 8, AMBER + (255,))

    elif kind == "Q6":
        rr(d, k, (24, 34, 52, 62), 7, WHITE + (145,))
        rr(d, k, (38, 40, 66, 68), 7, WHITE + (200,))
        rr(d, k, (52, 46, 84, 78), 7, AMBER + (255,))

    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = ["Q1", "Q2", "Q3", "Q4", "Q5", "Q6"]

    # 大图先看形状
    big = Image.new("RGB", (250 * 3 + 20, 250 * 2 + 20), (245, 245, 244))
    for i, kd in enumerate(kinds):
        col, row = i % 3, i // 3
        ic = draw(kd, 210)
        big.paste(ic, (10 + col * 250, 10 + row * 250), ic)
    big.save(os.path.join(OUT, "round11-big.png"))

    # 裁切 + 小尺寸：列宽 150，四档竖排
    sizes = [96, 64, 48, 32]
    col_w = 150
    total_h = sum(px + 16 for px in sizes) + 16
    small = Image.new("RGB", (col_w * 3 + 16, total_h * 2 + 20), (245, 245, 244))
    for row, kd_offset in enumerate((0, 3)):
        for col in range(3):
            kd = kinds[kd_offset + col]
            x = 8 + col * col_w
            y = 10 + row * (total_h + 4)
            for px in sizes:
                ic = draw(kd, px)
                ic.putalpha(mask_circle(px))
                small.paste(ic, (x + (96 - px) // 2, y), ic)
                y += px + 16
    small.save(os.path.join(OUT, "round11-masked.png"))
    print("round11 written", big.size, small.size)


if __name__ == "__main__":
    main()
