#!/usr/bin/env python3
"""
第十七轮：细化 V1（取景框四角 + 中心琥珀块）。

══ 为什么选 V1 ══

第十六轮四个候选的真实观感（看渲染图，不是推测）：
  V2 放大镜   32dp 最清楚，但「放大镜 = 搜索应用」，
              而且任务书明确要求不要把放大镜/图片框/文件夹拼在一起
  V3 竖屏+琥珀带 32dp 读作「电池」，顶部缺口没救回来
  V4 圆角方+琥珀角 32dp 读作「折角便签」，又回到文档语义
  V1 四角取景框 + 中心琥珀块
              32dp 仍能看出「一个框 + 中间一块」，且不与任何常见符号撞脸

V1 的语义正好是产品做的事：框住画面（截图），取出其中一块（识别/整理）。

══ 本轮只调三个数 ══

  W1  角标长 20、粗 9、中心块 20×20（上一版原样，对照）
  W2  角标长 24、粗 10、中心块 24×24（框更完整，中心更实）
  W3  角标长 22、粗 9、中心块 26×26（中心占主导，小尺寸不丢）
  W4  角标长 24、粗 10、中心块 28×28 且圆角更小（最实）
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
    "W1": dict(ln=20.0, th=9.0, cs=20.0, cr=6.0),
    "W2": dict(ln=24.0, th=10.0, cs=24.0, cr=7.0),
    "W3": dict(ln=22.0, th=9.0, cs=26.0, cr=8.0),
    "W4": dict(ln=24.0, th=10.0, cs=28.0, cr=9.0),
}

LO, HI = 22.0, 86.0   # 框的外边界


def rr(d, k, box, r, color):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color + (255,))


def draw(kind, size, scale=8):
    v = VARIANTS[kind]
    ln, th, cs, cr = v["ln"], v["th"], v["cs"], v["cr"]
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    d = ImageDraw.Draw(img)
    k = s / S
    r = th / 2

    # 四个角，每个角两段
    # 左上
    rr(d, k, (LO, LO, LO + ln, LO + th), r, WHITE)
    rr(d, k, (LO, LO, LO + th, LO + ln), r, WHITE)
    # 右上
    rr(d, k, (HI - ln, LO, HI, LO + th), r, WHITE)
    rr(d, k, (HI - th, LO, HI, LO + ln), r, WHITE)
    # 左下
    rr(d, k, (LO, HI - th, LO + ln, HI), r, WHITE)
    rr(d, k, (LO, HI - ln, LO + th, HI), r, WHITE)
    # 右下
    rr(d, k, (HI - ln, HI - th, HI, HI), r, WHITE)
    rr(d, k, (HI - th, HI - ln, HI, HI), r, WHITE)

    # 中心琥珀块
    c = S / 2
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
    canvas.save(os.path.join(OUT, "round17.png"))
    print("round17 written", canvas.size)


if __name__ == "__main__":
    main()
