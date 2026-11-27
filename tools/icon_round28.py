#!/usr/bin/env python3
"""
第二十八轮（收尾）：三个变体，选一个就定。

══ 上一版真图的问题 ══
  白色屏幕 + 顶部琥珀条 + 底部琥珀条。
  能认出是手机，但两条琥珀像「音量滑块」，看不出「截图里有内容」。

══ 三个变体 ══

  T1  长截图比例（36×68，比普通手机更瘦长）+ 刘海 + home 条
      长截图是截图工具独有的场景，比例本身就是语义
  T2  普通手机比例 + 刘海 + 两条文字行（代替 home 条）
      「屏幕里有文字」—— 比 home 条更贴产品
  T3  T2 但文字行是三条递减
      信息更足，32dp 可能糊

三者都验算在安全圆（圆心 54,54，半径 36）内。
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
AMBER_SOFT = (250, 214, 150)
LINE_GRAY = (176, 200, 197)

VARIANTS = {
    # 长截图比例 + 刘海 + home 条
    "T1": dict(
        scr=(36, 20, 72, 88, 10),
        notch=(46, 26, 62, 32, 3),
        extras="homebar",
    ),
    # 普通比例 + 刘海 + 两条文字行
    "T2": dict(
        scr=(35, 25, 73, 83, 11),
        notch=(46, 31, 62, 37, 3),
        extras="2lines",
    ),
    # 三条递减文字行
    "T3": dict(
        scr=(35, 25, 73, 83, 11),
        notch=(46, 30, 62, 36, 3),
        extras="3lines",
    ),
}


def vgrad(size, top, bottom):
    img = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        img.putpixel((0, y), tuple(
            int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img.resize((size, size), Image.BILINEAR).convert("RGBA")


def draw(kind, size, scale=8):
    v = VARIANTS[kind]
    s = int(size * scale)
    k = s / S
    img = vgrad(s, BG_TOP, BG_BOTTOM)
    scr, notch = v["scr"], v["notch"]

    def box(t):
        return [t[0] * k, t[1] * k, t[2] * k, t[3] * k]

    # 投影
    sh = Image.new("L", (s, s), 0)
    ImageDraw.Draw(sh).rounded_rectangle(
        [scr[0] * k, (scr[1] + 2.5) * k, scr[2] * k, (scr[3] + 2.5) * k],
        radius=scr[4] * k, fill=100)
    sh = sh.filter(ImageFilter.GaussianBlur(2.4 * k))
    shadow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    shadow.paste(Image.new("RGBA", (s, s), (0, 0, 0, 255)), (0, 0), sh)
    img.alpha_composite(shadow)

    # 屏幕（渐变裁进圆角）
    w = int((scr[2] - scr[0]) * k)
    h = int((scr[3] - scr[1]) * k)
    grad = vgrad(max(h, 2), WHITE, (238, 244, 243)).resize((w, max(h, 2)), Image.BILINEAR)
    m = Image.new("L", (w, max(h, 2)), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1],
                                        radius=scr[4] * k, fill=255)
    grad.putalpha(m)
    img.alpha_composite(grad, (int(scr[0] * k), int(scr[1] * k)))

    d = ImageDraw.Draw(img)
    d.rounded_rectangle(box(notch[:4]), radius=notch[4] * k, fill=AMBER + (255,))

    ex = v["extras"]
    if ex == "homebar":
        d.rounded_rectangle([46 * k, 78 * k, 62 * k, 82 * k],
                            radius=2 * k, fill=AMBER + (140,))
    elif ex == "2lines":
        # 两条文字行：第一条琥珀（命中），第二条淡灰（其他内容）
        d.rounded_rectangle([43 * k, 47 * k, 65 * k, 54 * k],
                            radius=3.5 * k, fill=AMBER + (255,))
        d.rounded_rectangle([43 * k, 60 * k, 58 * k, 67 * k],
                            radius=3.5 * k, fill=LINE_GRAY + (255,))
    elif ex == "3lines":
        d.rounded_rectangle([43 * k, 44 * k, 65 * k, 50 * k],
                            radius=3 * k, fill=AMBER + (255,))
        d.rounded_rectangle([43 * k, 55 * k, 58 * k, 61 * k],
                            radius=3 * k, fill=LINE_GRAY + (255,))
        d.rounded_rectangle([43 * k, 66 * k, 52 * k, 72 * k],
                            radius=3 * k, fill=LINE_GRAY + (255,))

    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = list(VARIANTS)
    sizes = [192, 128, 72, 48, 32]

    col_w = 220
    total_h = sum(px + 16 for px in sizes) + 16
    canvas = Image.new("RGB", (col_w * len(kinds) + 16, total_h + 20), (248, 248, 246))
    for col, kd in enumerate(kinds):
        x = 8 + col * col_w
        y = 10
        for px in sizes:
            ic = draw(kd, px)
            ic.putalpha(mask_circle(px))
            canvas.paste(ic, (x + (192 - px) // 2, y), ic)
            y += px + 16
    canvas.save(os.path.join(OUT, "round28.png"))
    print("round28 written", canvas.size)


if __name__ == "__main__":
    main()
