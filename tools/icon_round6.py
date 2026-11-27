#!/usr/bin/env python3
"""
第六轮：解决「后卡被读成投影」的根因。

第五轮 6 个变体全部失败，原因一致：
  后卡与前卡尺寸相同、只平移 8–14dp，且方向朝右下 ——
  而 Material 阴影的方向也是右下。两者在视觉语言上完全重合，
  所以那块琥珀无论怎么调都被读成「白色卡片的黄色投影」。

两个解决方向：

  一、破坏「同心」关系 —— 让后卡旋转
      旋转过的矩形不可能被读成投影，因为阴影永远与本体同角度。
      I1 旋转 -10°，I2 旋转 -16° 并加大偏移。

  二、放弃第二张卡 —— 只用一张卡，把「整理」做进卡片内部
      P1 卡内三条琥珀横条，长度递减（文字被抽成行）
      P2 卡内右上切一块琥珀楔形（标记已归档）
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

BRAND = (47, 111, 107)
WHITE = (255, 255, 255)
AMBER = (240, 178, 66)
S = 108.0

CARD = (30.0, 30.0, 78.0, 78.0, 13.0)


def rr(d, k, box, r, color):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color)


def rotated_card(img, k, center, size, radius, angle, color):
    """画一个旋转过的圆角方块，用于破坏「阴影」的语义。"""
    pad = int(size * 1.6 * k)
    layer = Image.new("RGBA", (pad, pad), (0, 0, 0, 0))
    ld = ImageDraw.Draw(layer)
    half = size * k / 2
    ld.rounded_rectangle(
        [pad / 2 - half, pad / 2 - half, pad / 2 + half, pad / 2 + half],
        radius=radius * k, fill=color,
    )
    layer = layer.rotate(angle, resample=Image.BICUBIC, center=(pad / 2, pad / 2))
    px = int(center[0] * k - pad / 2)
    py = int(center[1] * k - pad / 2)
    img.alpha_composite(layer, (px, py))


def draw(kind, size, scale=6):
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    k = s / S
    hole = BRAND + (255,)

    if kind == "I1":
        rotated_card(img, k, (57, 57), 48, 13, -10, AMBER + (255,))
        d = ImageDraw.Draw(img)
        rr(d, k, CARD[:4], CARD[4], WHITE + (255,))
        rr(d, k, (41, 44, 67, 54), 5, hole)
        rr(d, k, (41, 61, 57, 71), 5, hole)

    elif kind == "I2":
        rotated_card(img, k, (60, 60), 50, 14, -16, AMBER + (255,))
        d = ImageDraw.Draw(img)
        rr(d, k, CARD[:4], CARD[4], WHITE + (255,))
        rr(d, k, (41, 44, 67, 54), 5, hole)
        rr(d, k, (41, 61, 57, 71), 5, hole)

    elif kind == "P1":
        d = ImageDraw.Draw(img)
        rr(d, k, CARD[:4], CARD[4], WHITE + (255,))
        # 三条琥珀横条，长度递减 —— 文字被抽成行
        rr(d, k, (40, 43, 68, 51), 4, AMBER + (255,))
        rr(d, k, (40, 57, 60, 65), 4, AMBER + (255,))
        rr(d, k, (40, 71, 52, 79), 4, AMBER + (255,))

    elif kind == "P2":
        d = ImageDraw.Draw(img)
        rr(d, k, CARD[:4], CARD[4], WHITE + (255,))
        # 右上角琥珀楔形，压在卡内（不越界）
        d.polygon(
            [(78 * k, 30 * k), (78 * k, 52 * k), (56 * k, 30 * k)],
            fill=AMBER + (255,),
        )
        rr(d, k, (41, 60, 69, 68), 4, hole)
        rr(d, k, (41, 73, 57, 81), 4, hole)

    return img.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = ["I1", "I2", "P1", "P2"]
    canvas = Image.new("RGB", (1160, 300), (250, 250, 249))
    for i, kd in enumerate(kinds):
        x = 20 + i * 288
        big = draw(kd, 180)
        canvas.paste(big, (x, 16), big)
        for j, px in enumerate((64, 48, 32)):
            ic = draw(kd, px)
            canvas.paste(ic, (x + 4 + j * 58, 220), ic)
    canvas.save(os.path.join(OUT, "round6.png"))

    for kd in kinds:
        for name, bg in (("light", (250, 250, 249)), ("dark", (17, 19, 18))):
            c = Image.new("RGB", (420, 200), bg)
            for j, px in enumerate((128, 64, 40)):
                ic = draw(kd, px)
                c.paste(ic, (24 + j * 132, 34), ic)
            c.save(os.path.join(OUT, f"{kd}-{name}.png"))
    print("round6 written")


if __name__ == "__main__":
    main()
