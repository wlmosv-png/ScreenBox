#!/usr/bin/env python3
"""
图标预览必须带裁切遮罩。

══ 之前的方法错误 ══

前六轮我看的都是 108×108 未裁切方块，据此判断"好不好看"。
但 adaptive icon 在启动器上会被裁成圆形/方圆形，
实际可见范围只有中心约 66%（72/108）。

也就是说：我一直在一个用户看不到的画布上做审美判断。
这解释了为什么"看着还行"的版本装到桌面上就变味。

══ 本轮 ══

1. 预览一律套三种真实遮罩：circle / squircle / rounded-square
2. 同时给出 48dp 与 32dp（通知栏、设置列表尺寸）
3. 只在遮罩内比较三个横条长度方案
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

BRAND = (47, 111, 107)
WHITE = (255, 255, 255)
AMBER = (240, 178, 66)
S = 108.0

CARD = (30.0, 30.0, 78.0, 78.0, 16.0)

# 三种横条方案
VARIANTS = {
    # 原方案：28/20/12，第三条偏短
    "V1": [(40, 43, 68, 51, 4), (40, 56, 60, 64, 4), (40, 69, 52, 77, 4)],
    # 更均衡：28/21/15，第三条仍清楚是"一行"
    "V2": [(40, 43, 68, 50, 3.5), (40, 56, 61, 63, 3.5), (40, 69, 55, 76, 3.5)],
    # 两条长 + 一条中：28/24，留白更多
    "V3": [(40, 46, 68, 53, 3.5), (40, 61, 64, 68, 3.5)],
}


def draw(kind, size, scale=6):
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    k = s / S
    d = ImageDraw.Draw(img)

    def box(t):
        return [t[0] * k, t[1] * k, t[2] * k, t[3] * k]

    d.rounded_rectangle(box(CARD[:4]), radius=CARD[4] * k, fill=WHITE + (255,))
    for b in VARIANTS[kind]:
        d.rounded_rectangle(box(b[:4]), radius=b[4] * k, fill=AMBER + (255,))

    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    m = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def mask_squircle(size):
    """近似 squircle：用超椭圆。启动器实际用的是路径，这里取近似。"""
    n = size * 4
    m = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(m)
    exp = 4.2
    cx = cy = n / 2
    r = n / 2
    pts = []
    steps = 240
    import math
    for i in range(steps):
        t = 2 * math.pi * i / steps
        ct, st = math.cos(t), math.sin(t)
        x = cx + r * (abs(ct) ** (2 / exp)) * (1 if ct >= 0 else -1)
        y = cy + r * (abs(st) ** (2 / exp)) * (1 if st >= 0 else -1)
        pts.append((x, y))
    d.polygon(pts, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def mask_rounded(size, radius_ratio=0.22):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [0, 0, n - 1, n - 1], radius=n * radius_ratio, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def apply_mask(icon, mask):
    out = icon.copy()
    out.putalpha(mask)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = list(VARIANTS)
    masks = [("circle", mask_circle), ("squircle", mask_squircle),
             ("rounded", mask_rounded)]

    cell_w, cell_h = 210, 250
    canvas = Image.new("RGB", (cell_w * len(kinds) + 30, cell_h * len(masks) + 40),
                       (245, 245, 244))

    for row, (mname, mfn) in enumerate(masks):
        for col, kd in enumerate(kinds):
            x = 15 + col * cell_w
            y = 20 + row * cell_h
            for j, px in enumerate((112, 56, 32)):
                ic = draw(kd, px)
                ic = apply_mask(ic, mfn(px))
                canvas.paste(ic, (x + j * 62, y), ic)

    canvas.save(os.path.join(OUT, "masked.png"))
    print("masked preview written")


if __name__ == "__main__":
    main()
