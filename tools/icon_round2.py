#!/usr/bin/env python3
"""
图标第二轮：放弃「文档 + 折角」语言。

上一轮的教训：
  · 双卡 + 三行 → 像便签/备忘录
  · 切角 + 角点 → 还是「一张纸」，只是折角换成了缺口
  根因：形状本身是「纸」，细节再怎么调都改不了这个第一印象。

这一轮换语言，四个方向都在争「轮廓独特」而不是「细节精致」：

  E 粗描边屏幕 + 三条内容行（首行琥珀）
     描边而不是实心 —— 读作「屏幕/显示器」，不是纸
  F 两个描边框交叠，交集处一块琥珀
     交叠 = 多张截图；琥珀交集 = 找出的重复部分
  G 实心块 + 左下被打掉一个圆孔，孔里浮着琥珀点
     轮廓被「咬掉一口」，形状本身有记忆点
  H 实心块 + 右下角整块琥珀三角
     两个形状、两个颜色，32dp 也不糊
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

    if kind == "E":
        # 粗描边屏幕：外框减去内框
        rr(d, k, (25, 25, 83, 83), 18, WHITE + (255,))
        rr(d, k, (34, 34, 74, 74), 11, hole)
        # 三条内容行，首行琥珀
        rr(d, k, (41, 44, 63, 50), 3, AMBER + (255,))
        rr(d, k, (41, 56, 67, 62), 3, WHITE + (255,))
        rr(d, k, (41, 68, 55, 74), 3, WHITE + (255,))

    elif kind == "F":
        # 两个描边框交叠
        rr(d, k, (22, 22, 66, 66), 14, WHITE + (255,))
        rr(d, k, (30, 30, 58, 58), 9, hole)
        rr(d, k, (42, 42, 86, 86), 14, WHITE + (255,))
        rr(d, k, (50, 50, 78, 78), 9, hole)
        # 交集处一块琥珀
        rr(d, k, (45, 45, 63, 63), 6, AMBER + (255,))

    elif kind == "G":
        # 实心块，左下被打掉一个圆
        rr(d, k, (24, 24, 84, 84), 18, WHITE + (255,))
        ell(d, k, 40, 68, 19, hole)
        ell(d, k, 40, 68, 8.5, AMBER + (255,))
        # 右上两条短线，说明这是「有内容的画面」
        rr(d, k, (52, 38, 72, 44), 3, hole)
        rr(d, k, (52, 50, 64, 56), 3, hole)

    elif kind == "H":
        # 实心块 + 右下角整块琥珀三角（按圆角形状裁切）
        mask = Image.new("L", (s, s), 0)
        md = ImageDraw.Draw(mask)
        md.rounded_rectangle([24 * k, 24 * k, 84 * k, 84 * k], radius=18 * k, fill=255)
        body = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        bd = ImageDraw.Draw(body)
        bd.rounded_rectangle([24 * k, 24 * k, 84 * k, 84 * k], radius=18 * k, fill=WHITE + (255,))
        bd.polygon([(84 * k, 50 * k), (84 * k, 84 * k), (50 * k, 84 * k)], fill=AMBER + (255,))
        img.paste(body, (0, 0), mask)
        # 左上两条内容行
        rr(d, k, (36, 40, 60, 47), 3.5, hole)
        rr(d, k, (36, 54, 48, 61), 3.5, hole)

    return img.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = ["E", "F", "G", "H"]
    canvas = Image.new("RGB", (1000, 500), (250, 250, 249))
    for i, kd in enumerate(kinds):
        x = 20 + i * 245
        big = draw(kd, 170)
        canvas.paste(big, (x, 12), big)
        for j, px in enumerate((64, 48, 32)):
            ic = draw(kd, px)
            canvas.paste(ic, (x + 4 + j * 56, 206), ic)
    canvas.save(os.path.join(OUT, "round2.png"))

    for kd in kinds:
        for name, bg in (("light", (250, 250, 249)), ("dark", (17, 19, 18))):
            c = Image.new("RGB", (420, 190), bg)
            for j, px in enumerate((128, 64, 40)):
                ic = draw(kd, px)
                c.paste(ic, (24 + j * 132, 30), ic)
            c.save(os.path.join(OUT, f"{kd}-{name}.png"))
    print("round2 written")


if __name__ == "__main__":
    main()
