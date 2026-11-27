#!/usr/bin/env python3
"""
第十二轮：换长宽比。

══ 十一轮之后终于看清的根因 ══

之前所有方案都用「正方形」当主体。正方形在图标尺度上会被读成：
  文档 / 便签 / 备忘录 / 应用 / 小组件
—— 全都不是「截图」。

而截图的特征恰恰是**竖长**（手机屏幕 9:19.5）。
长宽比本身就是最强的语义信号，比任何内部细节都强。

所以这一轮：主体一律用竖长方形（约 28×58，比例 0.48）。

══ 六个方案 ══

  S1  单张竖卡 + 底部 1/3 琥珀
      读作「屏幕 + 已处理的那部分」
  S2  单张竖卡 + 右上角琥珀竖条（宽 8）
      读作「屏幕 + 侧边标记」
  S3  两张竖卡叠放（琥珀在后，左上偏移）
      读作「不止一张截图」
  S4  两张竖卡并排（左白右琥珀，等高）
      读作「一批截图」
  S5  单张竖卡 + 右下角琥珀实心圆（压在角上）
      读作「屏幕 + 状态点」
  S6  三张竖卡扇形微错位（中间白，两侧琥珀淡）
      读作「一叠截图」，最花但信息最足
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
R = 7.0


def rr(d, k, box, r, color):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color)


def draw(kind, size, scale=8):
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    d = ImageDraw.Draw(img)
    k = s / S

    if kind == "S1":
        rr(d, k, (40, 25, 68, 83), R, WHITE + (255,))
        rr(d, k, (40, 64, 68, 83), 6, AMBER + (255,))
        # 覆盖接缝，让琥珀块与白卡的圆角衔接自然
        rr(d, k, (40, 60, 68, 68), 0, AMBER + (255,))

    elif kind == "S2":
        rr(d, k, (38, 25, 66, 83), R, WHITE + (255,))
        rr(d, k, (58, 25, 66, 83), 6, AMBER + (255,))
        rr(d, k, (56, 32, 66, 76), 0, AMBER + (255,))

    elif kind == "S3":
        rr(d, k, (34, 22, 60, 76), R, AMBER + (255,))
        rr(d, k, (46, 32, 72, 86), R, WHITE + (255,))

    elif kind == "S4":
        rr(d, k, (32, 28, 56, 80), R, WHITE + (255,))
        rr(d, k, (52, 28, 76, 80), R, AMBER + (255,))

    elif kind == "S5":
        rr(d, k, (38, 23, 66, 81), R, WHITE + (255,))
        d.ellipse([58 * k, 58 * k, 84 * k, 84 * k], fill=AMBER + (255,))

    elif kind == "S6":
        rr(d, k, (26, 26, 50, 78), R, AMBER + (115,))
        rr(d, k, (56, 26, 80, 78), R, AMBER + (115,))
        rr(d, k, (41, 30, 67, 84), R, WHITE + (255,))

    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = ["S1", "S2", "S3", "S4", "S5", "S6"]

    big = Image.new("RGB", (250 * 3 + 20, 250 * 2 + 20), (245, 245, 244))
    for i, kd in enumerate(kinds):
        col, row = i % 3, i // 3
        ic = draw(kd, 210)
        big.paste(ic, (10 + col * 250, 10 + row * 250), ic)
    big.save(os.path.join(OUT, "round12-big.png"))

    sizes = [96, 64, 48, 32]
    col_w = 150
    total_h = sum(px + 16 for px in sizes) + 16
    small = Image.new("RGB", (col_w * 3 + 16, total_h * 2 + 20), (245, 245, 244))
    for row, off in enumerate((0, 3)):
        for col in range(3):
            kd = kinds[off + col]
            x = 8 + col * col_w
            y = 10 + row * (total_h + 4)
            for px in sizes:
                ic = draw(kd, px)
                ic.putalpha(mask_circle(px))
                small.paste(ic, (x + (96 - px) // 2, y), ic)
                y += px + 16
    small.save(os.path.join(OUT, "round12-masked.png"))
    print("round12 written")


if __name__ == "__main__":
    main()
