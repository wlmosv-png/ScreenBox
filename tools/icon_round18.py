#!/usr/bin/env python3
"""
第十八轮：润色定稿候选。

══ 上一轮真实观感（看渲染图）══

  W1  角标 20/粗 9，中心 20  → 四角偏细，32dp 有点飘
  W2  角标 24/粗 10，中心 24 → 平衡好
  W3  角标 22/粗 9，中心 26  → 中心略占主导，48dp 最好看
  W4  角标 24/粗 10，中心 28 → 中心太大，四角变成装饰

取景框方向确定成立。这一轮只调「手感」：

  X1  W3 原样（对照）
  X2  W3 + 中心块圆角加大到 30%（更亲和，不那么硬）
  X3  W3 + 中心块改成圆（最亲和，但可能读成「录制点」）
  X4  W2 + 中心块圆角加大，四角略短（框更「紧」，中心更突出）
  X5  W3 + 四角外侧圆角加大（整体更软，Apple 风）
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
LO, HI = 22.0, 86.0


def rr(d, k, box, r, color):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color + (255,))


VARIANTS = {
    "X1": dict(ln=22.0, th=9.0, cs=26.0, cr=8.0, corner_r=None),
    "X2": dict(ln=22.0, th=9.0, cs=26.0, cr=8.0, corner_r=None),
    "X3": dict(ln=22.0, th=9.0, cs=26.0, cr=13.0, corner_r=None),
    "X4": dict(ln=20.0, th=10.0, cs=28.0, cr=9.0, corner_r=None),
    "X5": dict(ln=22.0, th=9.0, cs=26.0, cr=8.0, corner_r=None),
}


def draw(kind, size, scale=8):
    v = VARIANTS[kind]
    ln, th, cs, cr = v["ln"], v["th"], v["cs"], v["cr"]
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    d = ImageDraw.Draw(img)
    k = s / S

    if kind == "X5":
        # 用整段 L 形（一次画完），外侧圆角大，内侧自然
        # 左上
        d.rounded_rectangle([LO * k, LO * k, (LO + ln) * k, (LO + th) * k],
                            radius=th / 2 * k, fill=WHITE + (255,))
        d.rounded_rectangle([LO * k, LO * k, (LO + th) * k, (LO + ln) * k],
                            radius=th / 2 * k, fill=WHITE + (255,))
        d.rounded_rectangle([(HI - ln) * k, LO * k, HI * k, (LO + th) * k],
                            radius=th / 2 * k, fill=WHITE + (255,))
        d.rounded_rectangle([(HI - th) * k, LO * k, HI * k, (LO + ln) * k],
                            radius=th / 2 * k, fill=WHITE + (255,))
        d.rounded_rectangle([LO * k, (HI - th) * k, (LO + ln) * k, HI * k],
                            radius=th / 2 * k, fill=WHITE + (255,))
        d.rounded_rectangle([LO * k, (HI - ln) * k, (LO + th) * k, HI * k],
                            radius=th / 2 * k, fill=WHITE + (255,))
        d.rounded_rectangle([(HI - ln) * k, (HI - th) * k, HI * k, HI * k],
                            radius=th / 2 * k, fill=WHITE + (255,))
        d.rounded_rectangle([(HI - th) * k, (HI - ln) * k, HI * k, HI * k],
                            radius=th / 2 * k, fill=WHITE + (255,))
    else:
        r = th / 2
        rr(d, k, (LO, LO, LO + ln, LO + th), r, WHITE)
        rr(d, k, (LO, LO, LO + th, LO + ln), r, WHITE)
        rr(d, k, (HI - ln, LO, HI, LO + th), r, WHITE)
        rr(d, k, (HI - th, LO, HI, LO + ln), r, WHITE)
        rr(d, k, (LO, HI - th, LO + ln, HI), r, WHITE)
        rr(d, k, (LO, HI - ln, LO + th, HI), r, WHITE)
        rr(d, k, (HI - ln, HI - th, HI, HI), r, WHITE)
        rr(d, k, (HI - th, HI - ln, HI, HI), r, WHITE)

    c = S / 2
    if kind == "X3":
        d.ellipse([(c - cs / 2) * k, (c - cs / 2) * k,
                   (c + cs / 2) * k, (c + cs / 2) * k], fill=AMBER + (255,))
    else:
        rr(d, k, (c - cs / 2, c - cs / 2, c + cs / 2, c + cs / 2), cr, AMBER)

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
    canvas = Image.new("RGB", (col_w * len(kinds) + 16, total_h + 20), (245, 245, 244))
    for col, kd in enumerate(kinds):
        x = 8 + col * col_w
        y = 10
        for px in sizes:
            ic = draw(kd, px)
            ic.putalpha(mask_circle(px))
            canvas.paste(ic, (x + (128 - px) // 2, y), ic)
            y += px + 12
    canvas.save(os.path.join(OUT, "round18.png"))
    print("round18 written", canvas.size)


if __name__ == "__main__":
    main()
