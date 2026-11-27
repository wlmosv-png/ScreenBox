#!/usr/bin/env python3
"""
第十六轮：换构图语言。

══ 上一轮真图暴露的问题 ══

K1（两张等宽竖条并排）在 32dp 下读作 **暂停按钮** —— 两条竖杠是极强
的既有符号，用户会直接误解。K3 又一次证实「重叠 = 一个物体 + 阴影」。
K4 读作感叹号。K2 勉强成立但不够好看。

结论：继续在「两个矩形」里微调不会得到好看的图标，
这个构图在图标尺度上已经被既有符号占满了。

══ 这一轮四个全新构图 ══

  V1  取景框四角 + 中心琥珀方块
      四段粗角标围出画面，中间一块琥珀 = 「被框住并处理的那一块」
      粗角标在 32dp 依然清楚；完全不是文件夹/文档/照片
  V2  圆角方形放大镜（粗环 + 琥珀镜片 + 短柄）
      「搜索」的现代写法；环是单一强形状
  V3  单张竖屏 + 底部琥珀横带 + 顶部小缺口
      「屏幕 + 已处理部分」；缺口破掉「电池」的读法
  V4  圆角方形外框 + 内部右上角一块琥珀楔形
      「框里的一块被标记」；极简，两形状
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


def rr(d, k, box, r, color, alpha=255):
    d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                        radius=r * k, fill=color + (alpha,))


def draw(kind, size, scale=8):
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), BRAND + (255,))
    d = ImageDraw.Draw(img)
    k = s / S

    if kind == "V1":
        # 四段粗角标：每段长 20，粗 9，圆角 4.5
        t, ln, r = 9.0, 20.0, 4.5
        # 左上
        rr(d, k, (24, 24, 24 + ln, 24 + t), r, WHITE)
        rr(d, k, (24, 24, 24 + t, 24 + ln), r, WHITE)
        # 右上
        rr(d, k, (84 - ln, 24, 84, 24 + t), r, WHITE)
        rr(d, k, (84 - t, 24, 84, 24 + ln), r, WHITE)
        # 左下
        rr(d, k, (24, 84 - t, 24 + ln, 84), r, WHITE)
        rr(d, k, (24, 84 - ln, 24 + t, 84), r, WHITE)
        # 右下
        rr(d, k, (84 - ln, 84 - t, 84, 84), r, WHITE)
        rr(d, k, (84 - t, 84 - ln, 84, 84), r, WHITE)
        # 中心琥珀方块
        rr(d, k, (44, 44, 64, 64), 6, AMBER)

    elif kind == "V2":
        # 粗环（圆角方形）+ 琥珀镜片
        rr(d, k, (26, 26, 74, 74), 15, WHITE)
        rr(d, k, (38, 38, 62, 62), 8, BRAND)
        rr(d, k, (38, 38, 62, 62), 8, AMBER)
        # 短柄：右下 45°
        d.line([(70 * k, 70 * k), (86 * k, 86 * k)], fill=WHITE + (255,), width=int(10 * k))
        d.ellipse([64 * k, 64 * k, 76 * k, 76 * k], fill=WHITE + (255,))
        d.ellipse([80 * k, 80 * k, 92 * k, 92 * k], fill=WHITE + (255,))

    elif kind == "V3":
        rr(d, k, (36, 20, 72, 88), 9, WHITE)
        rr(d, k, (36, 64, 72, 88), 8, AMBER)
        rr(d, k, (36, 58, 72, 68), 0, AMBER)
        # 顶部缺口：破掉电池读法
        rr(d, k, (48, 20, 60, 28), 3, BRAND)

    elif kind == "V4":
        rr(d, k, (26, 26, 82, 82), 14, WHITE)
        # 右上角琥珀楔形（在框内）
        d.polygon([(82 * k, 26 * k), (82 * k, 56 * k), (52 * k, 26 * k)],
                  fill=AMBER + (255,))
        # 左下角一条短线，指示「有内容」
        rr(d, k, (38, 66, 58, 74), 4, BRAND)

    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = ["V1", "V2", "V3", "V4"]
    sizes = [120, 80, 56, 40, 32]

    # 列宽必须 >= 最大尺寸 + 左右边距，否则会互相压盖（前几轮踩过）
    col_w = 132
    total_h = sum(px + 12 for px in sizes) + 12
    canvas = Image.new("RGB", (col_w * len(kinds) + 16, total_h + 20), (245, 245, 244))

    for col, kd in enumerate(kinds):
        x = 8 + col * col_w
        y = 10
        for px in sizes:
            ic = draw(kd, px)
            ic.putalpha(mask_circle(px))
            canvas.paste(ic, (x + (120 - px) // 2, y), ic)
            y += px + 12
    canvas.save(os.path.join(OUT, "round16.png"))
    print("round16 written", canvas.size)


if __name__ == "__main__":
    main()
