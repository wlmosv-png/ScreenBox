#!/usr/bin/env python3
"""
第二十二轮：加材质（渐变 + 真实阴影），并用更明确的截图语义。

══ 二十一轮的致命问题（看真图才发现）══

对角分割 + 圆形遮罩 = **禁止通行标志**。
这是极强的既有符号，用户第一眼只会读到「禁止」。

══ 前二十一轮反复撞墙的三个规律 ══

  1. 扁平几何 + 重叠 → 读作「一个物体 + 它的投影」
     因为扁平图形没有真实阴影，偏移只能靠位置暗示层次，
     而 Material 阴影方向固定在右下，任何偏移都被它抢占语义。

  2. 扁平几何 + 分离 → 读作「暂停按钮 / 应用网格」
     两条竖杠、四块方块都是被占满的符号。

  3. 扁平几何 + 内部线条 → 读作「文档 / 聊天 / 菜单」

三条规律的共同点：**问题不在形状，在「扁平」**。
扁平图形只能靠轮廓表意，而轮廓恰好是既有符号最密集的维度。

══ 本轮两个改变 ══

一、加真实阴影（高斯模糊），让「叠放」真的读作两个独立物体
   阴影方向可控，不依赖系统材质，也就不会被误读成投影。

二、加背景渐变，让整体有材质感，不再是印刷色块。

══ 四个候选 ══

  G1  竖屏 + 顶部琥珀状态栏
      截图最直白的特征：屏幕 + 状态栏。竖长比例避开「电池」。
  G2  三张竖卡叠放，真实阴影
      阴影解决第 1 条规律：叠放读作多个物体，不再糊成一体。
  G3  两张竖卡叠放，真实阴影，前卡琥珀
      同上，元素更少，32dp 更稳。
  G4  竖屏 + 上下两条琥珀横带（模拟选中区域）
      框住中间内容，语义是「从屏幕里取出这一段」。
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

WHITE = (255, 255, 255)
AMBER = (240, 178, 66)
S = 108.0


def gradient_bg(size, top=(46, 118, 112), bottom=(19, 54, 52)):
    """竖直渐变，给整体一点材质感，但不喧宾夺主。"""
    img = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        img.putpixel((0, y), (
            int(top[0] + (bottom[0] - top[0]) * t),
            int(top[1] + (bottom[1] - top[1]) * t),
            int(top[2] + (bottom[2] - top[2]) * t),
        ))
    return img.resize((size, size), Image.BILINEAR).convert("RGBA")


def shadow_layer(size, k, boxes, radius, blur, alpha=95):
    """为每个 box 生成一层柔和阴影。"""
    pad = int(blur * 3)
    layer = Image.new("L", (size + pad * 2, size + pad * 2), 0)
    d = ImageDraw.Draw(layer)
    for (x1, y1, x2, y2) in boxes:
        d.rounded_rectangle(
            [x1 * k + pad, y1 * k + pad + 3 * k, x2 * k + pad, y2 * k + pad + 3 * k],
            radius=radius * k, fill=alpha)
    layer = layer.filter(ImageFilter.GaussianBlur(blur * k))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(Image.new("RGBA", layer.size, (0, 0, 0, 255)), (-pad, -pad), layer)
    return out


def draw(kind, size, scale=8):
    s = int(size * scale)
    k = s / S
    img = gradient_bg(s)
    d = ImageDraw.Draw(img)

    def rr(box, r, color):
        d.rounded_rectangle([box[0] * k, box[1] * k, box[2] * k, box[3] * k],
                            radius=r * k, fill=color + (255,))

    if kind == "G1":
        # 竖屏 42×70，顶部 13 高的琥珀状态栏
        x1, y1, x2, y2 = 33, 19, 75, 89
        sh = shadow_layer(s, k, [(x1, y1, x2, y2)], 11, 2.2, 110)
        img.alpha_composite(sh)
        card = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        cd = ImageDraw.Draw(card)
        cd.rounded_rectangle(
            [x1 * k, y1 * k, x2 * k, y2 * k], radius=11 * k, fill=WHITE + (255,))
        cd.rectangle(
            [x1 * k, y1 * k, x2 * k, (y1 + 13) * k], fill=AMBER + (255,))
        cmask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(cmask).rounded_rectangle(
            [x1 * k, y1 * k, x2 * k, y2 * k], radius=11 * k, fill=255)
        card.putalpha(Image.composite(card.split()[3], Image.new("L", (s, s), 0), cmask))
        img.alpha_composite(card)

    elif kind == "G2":
        cards = [(26, 30, 56, 84), (39, 24, 69, 78), (52, 18, 82, 72)]
        sh = shadow_layer(s, k, cards, 8, 2.4, 100)
        img.alpha_composite(sh)
        rr(cards[0], 8, WHITE)
        rr(cards[1], 8, WHITE)
        rr(cards[2], 8, WHITE)

    elif kind == "G3":
        cards = [(30, 26, 62, 82), (46, 22, 78, 78)]
        sh = shadow_layer(s, k, cards, 10, 2.6, 105)
        img.alpha_composite(sh)
        rr(cards[0], 10, AMBER)
        rr(cards[1], 10, WHITE)

    elif kind == "G4":
        x1, y1, x2, y2 = 33, 19, 75, 89
        sh = shadow_layer(s, k, [(x1, y1, x2, y2)], 11, 2.2, 110)
        img.alpha_composite(sh)
        card = Image.new("RGBA", (s, s), (0, 0, 0, 0))
        cd = ImageDraw.Draw(card)
        cd.rounded_rectangle(
            [x1 * k, y1 * k, x2 * k, y2 * k], radius=11 * k, fill=WHITE + (255,))
        cd.rectangle([x1 * k, y1 * k, x2 * k, (y1 + 11) * k], fill=AMBER + (255,))
        cd.rectangle([x1 * k, (y2 - 11) * k, x2 * k, y2 * k], fill=AMBER + (255,))
        cmask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(cmask).rounded_rectangle(
            [x1 * k, y1 * k, x2 * k, y2 * k], radius=11 * k, fill=255)
        card.putalpha(Image.composite(card.split()[3], Image.new("L", (s, s), 0), cmask))
        img.alpha_composite(card)

    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = ["G1", "G2", "G3", "G4"]
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
    canvas.save(os.path.join(OUT, "round22.png"))
    print("round22 written", canvas.size)


if __name__ == "__main__":
    main()
