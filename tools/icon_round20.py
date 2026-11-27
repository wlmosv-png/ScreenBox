#!/usr/bin/env python3
"""
第二十轮：更敢的图形语言 + 最优配色。

══ 十九轮的结果 ══
  Y1/Y4/Y5 都能用，但都只是「两张圆角矩形叠在一起」。
  这个形状做钱包、笔记、文件管理器都成立 —— 概念太弱，不够「ScreenBox」。

  配色结论（真实渲染图）：
    · 浅底 + 深色主体 + 一个琥珀 → 层次最好（Y1/Y4）
    · 深青底 + 白 + 琥珀 → 对比最强，最「高级」（Y5）
    · 白底 + 白卡 → 白卡消失（Y6，否决）

══ 这一轮五个概念，都在争「一眼是它」══

  Z1  大方块 + 右下角小块「掉出来」
      整块是被截下的画面，小块是被取出的内容。缺口语义 = 提取
  Z2  大方块右下角被挖掉一块（洞）
      同一语义的另一种写法，轮廓更完整
  Z3  斜切：一块方被 45° 切成两半，下半琥珀
      「分割/分类」；纯几何，无任何陈词符号
  Z4  Y5 对照组（深青底 + 琥珀后卡 + 白前卡）
  Z5  三条粗横带，长度 100%/70%/45%，中间一条琥珀
      把「截图里的文字行」抽象成主图形本身，不做卡片
"""
import math
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

TEAL = (47, 111, 107)
TEAL_DARK = (23, 64, 61)
WHITE = (255, 255, 255)
NEAR_WHITE = (248, 248, 246)
AMBER = (240, 178, 66)
S = 108.0


def rr(d, k, box, r, color):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color + (255,))


def draw(kind, size, scale=8):
    s = int(size * scale)
    k = s / S

    if kind == "Z1":
        img = Image.new("RGBA", (s, s), TEAL_DARK + (255,))
        d = ImageDraw.Draw(img)
        rr(d, k, (26, 26, 74, 74), 14, AMBER)
        rr(d, k, (60, 60, 88, 88), 9, WHITE)

    elif kind == "Z2":
        img = Image.new("RGBA", (s, s), TEAL_DARK + (255,))
        d = ImageDraw.Draw(img)
        rr(d, k, (24, 24, 84, 84), 16, WHITE)
        rr(d, k, (60, 60, 96, 96), 11, TEAL_DARK)   # 挖洞（预览用底色盖）

    elif kind == "Z3":
        img = Image.new("RGBA", (s, s), TEAL_DARK + (255,))
        d = ImageDraw.Draw(img)
        rr(d, k, (24, 24, 84, 84), 16, WHITE)
        # 45° 切出的下半块
        d.polygon([(24 * k, 84 * k), (84 * k, 84 * k), (84 * k, 24 * k)],
                  fill=AMBER + (255,))
        # 重新盖圆角，避免直角外露
        rr(d, k, (24, 24, 84, 84), 16, TEAL_DARK)
        d.polygon([(25.5 * k, 82.5 * k), (82.5 * k, 82.5 * k), (82.5 * k, 25.5 * k)],
                  fill=AMBER + (255,))
        # 只保留下半三角，上半用白
        d.polygon([(25.5 * k, 82.5 * k), (82.5 * k, 25.5 * k),
                   (25.5 * k, 25.5 * k)], fill=WHITE + (255,))
        # 中央一条缝，让两块分开
        d.line([(24 * k, 84 * k), (84 * k, 24 * k)], fill=TEAL_DARK + (255,),
               width=int(5 * k))

    elif kind == "Z4":
        img = Image.new("RGBA", (s, s), TEAL_DARK + (255,))
        d = ImageDraw.Draw(img)
        rr(d, k, (31, 22, 57, 78), 9, AMBER)
        rr(d, k, (45, 32, 71, 88), 9, WHITE)

    else:  # Z5
        img = Image.new("RGBA", (s, s), NEAR_WHITE + (255,))
        d = ImageDraw.Draw(img)
        rr(d, k, (24, 30, 84, 44), 7, TEAL)
        rr(d, k, (24, 50, 66, 64), 7, AMBER)
        rr(d, k, (24, 70, 52, 84), 7, TEAL)

    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = ["Z1", "Z2", "Z3", "Z4", "Z5"]
    sizes = [120, 80, 56, 40, 32]

    col_w = 140
    total_h = sum(px + 10 for px in sizes) + 10
    canvas = Image.new("RGB", (col_w * len(kinds) + 16, total_h + 18), (236, 236, 234))
    for col, kd in enumerate(kinds):
        x = 8 + col * col_w
        y = 8
        for px in sizes:
            ic = draw(kd, px)
            ic.putalpha(mask_circle(px))
            canvas.paste(ic, (x + (120 - px) // 2, y), ic)
            y += px + 10
    canvas.save(os.path.join(OUT, "round20.png"))
    print("round20 written", canvas.size)


if __name__ == "__main__":
    main()
