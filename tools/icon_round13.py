#!/usr/bin/env python3
"""
第十三轮：定 S3 的偏移量。

══ 十二轮结论 ══
  竖长比例是决定性的一步。正方形读作「文档/应用」，
  竖长读作「屏幕」—— 长宽比比任何内部细节都强。

  裁切 + 32dp 之后：
    S1 白卡 + 琥珀下 1/3  → 读成「电池」               ✗
    S2 白卡 + 琥珀右窄条   → 读成「书脊」，32dp 条消失   ✗
    S3 两张叠放，琥珀在左上 → 清晰，轮廓有台阶          ✓
    S4 两张并排           → 干净，但 32dp 像两个竖条     △
    S5 白卡 + 右下琥珀圆   → 读成「消息红点」            ✗
    S6 三张扇形           → 96dp 好看，32dp 糊成一块     ✗

══ 本轮只定一件事：后卡露多少 ══

  偏移 9  → 琥珀边 9/108，32dp 下约 2.7dp
  偏移 12 → 琥珀边 12/108，32dp 下约 3.6dp
  偏移 15 → 琥珀边 15/108，32dp 下约 4.4dp，但前卡被挤小
  偏移 12 + 后卡更窄 → 琥珀边保持 12，前卡不变，后卡右缘不露出
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

BRAND = (47, 111, 107)
WHITE = (255, 255, 255)
AMBER = (240, 178, 66)
S = 108.0
R = 7.0

VARIANTS = {
    "O9": dict(back=(35, 25, 61, 79), front=(44, 34, 70, 88)),
    "O12": dict(back=(32, 22, 58, 76), front=(44, 34, 70, 88)),
    "O15": dict(back=(29, 19, 55, 73), front=(44, 34, 70, 88)),
    # 后卡更窄：右缘不露，只留左+上两条边
    "O12N": dict(back=(32, 22, 56, 76), front=(44, 34, 70, 88)),
}


def rr(d, k, box, r, color):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color)


def draw(kind, size, scale=8):
    v = VARIANTS[kind]
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    d = ImageDraw.Draw(img)
    k = s / S
    rr(d, k, v["back"], R, AMBER + (255,))
    rr(d, k, v["front"], R, WHITE + (255,))
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

    col_w = 160
    total_h = sum(px + 14 for px in sizes) + 14
    canvas = Image.new("RGB", (col_w * len(kinds) + 16, total_h + 20),
                       (245, 245, 244))
    for col, kd in enumerate(kinds):
        x = 8 + col * col_w
        y = 10
        for px in sizes:
            ic = draw(kd, px)
            ic.putalpha(mask_circle(px))
            canvas.paste(ic, (x + (128 - px) // 2, y), ic)
            y += px + 14
    canvas.save(os.path.join(OUT, "round13.png"))
    print("round13 written", canvas.size)


if __name__ == "__main__":
    main()
