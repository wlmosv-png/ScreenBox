#!/usr/bin/env python3
"""
第十九轮：换配色体系。

══ 十八轮之后看清的事 ══

形状我已经调了 18 轮：卡片、叠放、切角、圆环、四角框、2×2、竖屏……
每一轮都在「形状」里找答案，但真实观感一直「平」。

问题不在形状，在配色：

  深青底（#2F6F6B）+ 白色几何体 = 所有元素亮度接近，
  在图标尺度上没有层次，看起来像一张扁平贴纸。

  而现代图标（Notion / Things / Bear / Linear）普遍是
  浅底 + 深色主体 + 一个强调色。浅底给主体留出对比空间，
  整体显得「有材质」而不是「印刷色块」。

══ 本轮：同一形状，六种配色 ══

形状固定为「两张等大竖卡错位」（第 15 轮里最成立的那个），
只换配色，用真实渲染图判断哪套最不「平」。

  Y1  近白底 + 青卡 + 琥珀卡（浅色方案）
  Y2  淡青底 #E4EFED + 青卡 + 琥珀卡
  Y3  深青底 + 白卡 + 琥珀卡（当前方案，对照）
  Y4  近白底 + 青卡 + 琥珀卡，但青卡更深 #245A57
  Y5  极深青底 #17403D + 白卡 + 琥珀卡（深色高级感）
  Y6  近白底 + 青卡 + 白卡 + 琥珀小点（三色，最亮）
"""
import math
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

TEAL = (47, 111, 107)
TEAL_DEEP = (36, 90, 87)
TEAL_DARK = (23, 64, 61)
WHITE = (255, 255, 255)
NEAR_WHITE = (248, 248, 246)
PALE_TEAL = (228, 239, 237)
AMBER = (240, 178, 66)
S = 108.0
R = 9.0

BACK = (31, 22, 57, 78)     # 琥珀在后
FRONT = (45, 32, 71, 88)    # 白/青在前

SCHEMES = {
    "Y1": dict(bg=NEAR_WHITE, back=AMBER, front=TEAL),
    "Y2": dict(bg=PALE_TEAL, back=AMBER, front=TEAL),
    "Y3": dict(bg=TEAL, back=AMBER, front=WHITE),
    "Y4": dict(bg=NEAR_WHITE, back=AMBER, front=TEAL_DEEP),
    "Y5": dict(bg=TEAL_DARK, back=AMBER, front=WHITE),
    "Y6": dict(bg=NEAR_WHITE, back=WHITE, front=TEAL),
}


def rr(d, k, box, r, color):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color + (255,))


def draw(kind, size, scale=8):
    sc = SCHEMES[kind]
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), sc["bg"] + (255,))
    d = ImageDraw.Draw(img)
    k = s / S

    rr(d, k, BACK, R, sc["back"])
    rr(d, k, FRONT, R, sc["front"])

    if kind == "Y6":
        # 前卡右下角一个琥珀小点，作为第三色
        d.ellipse([58 * k, 66 * k, 74 * k, 82 * k], fill=AMBER + (255,))

    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = list(SCHEMES)
    sizes = [120, 80, 56, 40, 32]

    # 每个候选一列，列宽 132 > 最大尺寸 120，绝不重叠
    col_w = 136
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
    canvas.save(os.path.join(OUT, "round19.png"))
    print("round19 written", canvas.size)


if __name__ == "__main__":
    main()
