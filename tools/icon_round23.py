#!/usr/bin/env python3
"""
第二十三轮：三个微调，一次定生死。

══ 当前 v6 的真实观感（看渲染图）══
  干净、有层次、32dp 清楚 —— 但读作「两张卡片 / 钱包 / 便签」。
  问题：两张卡完全平行且等大，是静态模板感。

══ 三个改动方向，都在 v6 基础上 ══

  R1  后卡旋转 -8°
      真实的一叠截图不会是完美平行的。旋转同时打破「钱包」的对称感，
      且旋转过的矩形不可能被读成投影（阴影永远与本体同角度）。
  R2  两卡改成屏幕比例（24×46，更瘦）
      手机屏幕 9:19.5，更瘦的比例强化「屏幕」而非「卡片」。
  R3  后卡旋转 -8° + 屏幕比例（两者叠加）
  R4  对照：v6 原样
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

S = 108.0
BG_TOP = (46, 118, 112)
BG_BOTTOM = (19, 54, 52)
AMBER = (240, 178, 66)
WHITE = (255, 255, 255)

VARIANTS = {
    "R1": dict(back=(33, 26, 59, 74, 8), front=(45, 36, 71, 84, 8), rot=-8),
    "R2": dict(back=(34, 28, 58, 74, 8), front=(46, 38, 70, 84, 8), rot=0),
    "R3": dict(back=(34, 28, 58, 74, 8), front=(46, 38, 70, 84, 8), rot=-8),
    "R4": dict(back=(33, 26, 59, 74, 8), front=(45, 36, 71, 84, 8), rot=0),
}


def gradient(size, top, bottom):
    img = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        img.putpixel((0, y), tuple(
            int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img.resize((size, size), Image.BILINEAR).convert("RGBA")


def rotated_card(size, k, box, radius, color, angle, shadow=False):
    """画一张可能旋转的卡，返回 (rgba 图层, alpha mask)。"""
    pad = int(size * 0.7)
    w = int(box[2] * k - box[0] * k)
    h = int(box[3] * k - box[1] * k)

    layer = Image.new("RGBA", (pad, pad), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.rounded_rectangle([pad / 2 - w / 2, pad / 2 - h / 2,
                         pad / 2 + w / 2, pad / 2 + h / 2],
                        radius=radius * k, fill=color + (255,))
    if angle:
        layer = layer.rotate(angle, resample=Image.BICUBIC,
                             center=(pad / 2, pad / 2))

    cx = (box[0] + box[2]) / 2 * k
    cy = (box[1] + box[3]) / 2 * k
    ox, oy = int(cx - pad / 2), int(cy - pad / 2)
    return layer, (ox, oy)


def draw(kind, size, scale=8):
    v = VARIANTS[kind]
    s = int(size * scale)
    k = s / S
    img = gradient(s, BG_TOP, BG_BOTTOM)

    # 前卡阴影
    fb = v["front"]
    sh = Image.new("L", (s, s), 0)
    sd = ImageDraw.Draw(sh)
    sd.rounded_rectangle(
        [fb[0] * k, (fb[1] + 3) * k, fb[2] * k, (fb[3] + 3) * k],
        radius=fb[4] * k, fill=110)
    sh = sh.filter(ImageFilter.GaussianBlur(2.2 * k))
    shadow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    shadow.paste(Image.new("RGBA", (s, s), (0, 0, 0, 255)), (0, 0), sh)
    img.alpha_composite(shadow)

    # 后卡（可能旋转）
    back_layer, (bx, by) = rotated_card(
        s, k, v["back"], v["back"][4], AMBER, v["rot"])
    img.alpha_composite(back_layer, (bx, by))

    # 前卡
    d = ImageDraw.Draw(img)
    fb = v["front"]
    d.rounded_rectangle([fb[0] * k, fb[1] * k, fb[2] * k, fb[3] * k],
                        radius=fb[4] * k, fill=WHITE + (255,))

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
    canvas.save(os.path.join(OUT, "round23.png"))
    print("round23 written", canvas.size)


if __name__ == "__main__":
    main()
