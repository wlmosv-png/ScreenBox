#!/usr/bin/env python3
"""
第二十七轮：用「屏幕轮廓」当主体。

══ 为什么换到这条线 ══

前二十六轮主体都是「圆角方块 + 内部细节」或「多块叠放」。
两者都被既有符号占满（通知角标 / 文档 / 文件夹 / 暂停键）。

而「手机屏幕」这个轮廓有一个天然优势：
  · 它的识别特征是**剪影本身**（竖长比例 + 顶部缺口），
    不是内部细节 —— 所以缩到 32dp 也不丢
  · 它不与任何常见应用图标撞脸
  · 它就是「截图」的来源

══ 四个候选（都用剪影，不用内部线条）══

  N1  白屏幕 + 顶部琥珀缺口（刘海）
      缺口用强调色，一眼看出「这是一块屏幕」
  N2  白屏幕 + 顶部缺口露底色（负空间刘海）
      最克制，缺口是挖出来的
  N3  白屏幕 + 顶部缺口 + 底部琥珀圆点（home 指示点）
      信息最足，像真机
  N4  白屏幕 + 底部 1/4 琥珀（选区）
      对照：会不会又像电池
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

S = 108.0
BG_TOP = (78, 168, 158)
BG_BOTTOM = (26, 104, 96)
WHITE = (255, 255, 255)
AMBER = (245, 184, 70)

# 屏幕：竖长 42×64，圆角 12，居中
SCR = (33.0, 22.0, 75.0, 86.0, 12.0)
# 刘海：宽 18，高 7，顶部居中，圆角 3.5
NOTCH = (45.0, 22.0, 63.0, 29.0, 3.5)


def vgrad(size, top, bottom):
    img = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        img.putpixel((0, y), tuple(
            int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img.resize((size, size), Image.BILINEAR).convert("RGBA")


def draw(kind, size, scale=8):
    s = int(size * scale)
    k = s / S
    img = vgrad(s, BG_TOP, BG_BOTTOM)

    def box(t):
        return [t[0] * k, t[1] * k, t[2] * k, t[3] * k]

    # 屏幕投影
    sh = Image.new("L", (s, s), 0)
    ImageDraw.Draw(sh).rounded_rectangle(
        [SCR[0] * k, (SCR[1] + 2.5) * k, SCR[2] * k, (SCR[3] + 2.5) * k],
        radius=SCR[4] * k, fill=100)
    sh = sh.filter(ImageFilter.GaussianBlur(2.4 * k))
    shadow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    shadow.paste(Image.new("RGBA", (s, s), (0, 0, 0, 255)), (0, 0), sh)
    img.alpha_composite(shadow)

    d = ImageDraw.Draw(img)

    if kind == "N4":
        # 屏幕整体白，底部 1/4 琥珀（裁剪到圆角内）
        d.rounded_rectangle(box(SCR[:4]), radius=SCR[4] * k, fill=WHITE + (255,))
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).rounded_rectangle(box(SCR[:4]),
                                               radius=SCR[4] * k, fill=255)
        layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        ImageDraw.Draw(layer).rectangle(
            [SCR[0] * k, (SCR[3] - 16) * k, SCR[2] * k, SCR[3] * k],
            fill=AMBER + (255,))
        img.paste(layer, (0, 0), mask)
        return img.resize((size, size), Image.LANCZOS)

    # 屏幕白底
    d.rounded_rectangle(box(SCR[:4]), radius=SCR[4] * k, fill=WHITE + (255,))

    # 刘海
    if kind == "N1":
        d.rounded_rectangle(box(NOTCH[:4]), radius=NOTCH[4] * k, fill=AMBER + (255,))
    elif kind == "N2":
        # 负空间：挖出底色
        img.paste(vgrad(s, BG_TOP, BG_BOTTOM),
                  (0, 0),
                  _notch_mask(s, k))
    elif kind == "N3":
        d.rounded_rectangle(box(NOTCH[:4]), radius=NOTCH[4] * k, fill=AMBER + (255,))
        # 底部 home 指示条
        d.rounded_rectangle([44 * k, 78 * k, 64 * k, 82 * k],
                            radius=2 * k, fill=AMBER + (255,))

    return img.resize((size, size), Image.LANCZOS)


def _notch_mask(s, k):
    """只保留刘海区域，用于把底色挖回来。"""
    m = Image.new("L", (s, s), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [NOTCH[0] * k, NOTCH[1] * k, NOTCH[2] * k, NOTCH[3] * k],
        radius=NOTCH[4] * k, fill=255)
    return m


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = ["N1", "N2", "N3", "N4"]
    sizes = [160, 96, 64, 48, 32]

    col_w = 190
    total_h = sum(px + 14 for px in sizes) + 14
    canvas = Image.new("RGB", (col_w * len(kinds) + 16, total_h + 20), (248, 248, 246))
    for col, kd in enumerate(kinds):
        x = 8 + col * col_w
        y = 10
        for px in sizes:
            ic = draw(kd, px)
            ic.putalpha(mask_circle(px))
            canvas.paste(ic, (x + (160 - px) // 2, y), ic)
            y += px + 14
    canvas.save(os.path.join(OUT, "round27.png"))
    print("round27 written", canvas.size)


if __name__ == "__main__":
    main()
