#!/usr/bin/env python3
"""
第十四轮：最后一轮比较，然后定稿。

══ 上一版（O 系列）看到真图后的判断 ══

O9/O12/O15 都能读成「两张竖卡叠放」，语义对了。
但有两个问题：
  1. 32dp 时琥珀只剩左侧一条细边，像「左侧高光」而不是第二张卡
  2. 两张卡等高同宽，读起来像「钱包 / 两张票」，缺少「截图」的特征

══ 截图真正的特征：长度不一样 ══

同一部手机截出来的图，长度差别很大（一屏 vs 长截图）。
把「长度不同」做进形状里，比单纯叠放更准确地表达「一堆截图」。

══ 四个最终候选 ══

  F1  后卡短、前卡长，左上偏移 12
      「一叠长度不一的截图」—— 语义最准
  F2  后卡长、前卡短，左上偏移 12
      反过来，前卡更小更像「被选中的那一张」
  F3  三张：短 / 中 / 长，扇形错位（最花）
  F4  O15 原样但琥珀加深、圆角加大（对照组）
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

VARIANTS = {
    # 后卡短（y 34..72），前卡长（y 24..88）
    "F1": dict(
        cards=[((31, 34, 57, 72), 7, AMBER), ((43, 24, 69, 88), 8, WHITE)],
    ),
    # 后卡长（y 20..84），前卡短（y 34..80）
    "F2": dict(
        cards=[((31, 20, 57, 84), 8, AMBER), ((43, 34, 69, 80), 7, WHITE)],
    ),
    # 三张：短 / 长 / 中
    "F3": dict(
        cards=[
            ((24, 32, 46, 66), 6, AMBER),
            ((38, 22, 60, 82), 7, AMBER),
            ((52, 28, 78, 84), 8, WHITE),
        ],
    ),
    # 对照：O15 加大圆角
    "F4": dict(
        cards=[((29, 19, 55, 73), 8, AMBER), ((44, 34, 70, 88), 8, WHITE)],
    ),
}


def rr(d, k, box, r, color, alpha=255):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color + (alpha,))


def draw(kind, size, scale=8):
    v = VARIANTS[kind]
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    d = ImageDraw.Draw(img)
    k = s / S
    for box, r, color in v["cards"]:
        rr(d, k, box, r, color)
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
    canvas.save(os.path.join(OUT, "round14.png"))

    # 深浅底各一排 48dp，看对比度
    strip = Image.new("RGB", (col_w * len(kinds) + 16, 200), (250, 250, 249))
    for col, kd in enumerate(kinds):
        ic = draw(kd, 64)
        ic.putalpha(mask_circle(64))
        strip.paste(ic, (8 + col * col_w + 30, 10), ic)
    strip.save(os.path.join(OUT, "round14-light.png"))

    dark = Image.new("RGB", (col_w * len(kinds) + 16, 100), (17, 19, 18))
    for col, kd in enumerate(kinds):
        ic = draw(kd, 64)
        ic.putalpha(mask_circle(64))
        dark.paste(ic, (8 + col * col_w + 30, 18), ic)
    dark.save(os.path.join(OUT, "round14-dark.png"))

    print("round14 written", canvas.size)


if __name__ == "__main__":
    main()
