#!/usr/bin/env python3
"""
ScreenBox 图标候选对比。

第一版（双卡 + 三条挖空行）被否：太像文件夹，48dp 下细节糊成一片。

这一版的原则变了：
  · 少元素，大形状 —— 48dp 下先看清轮廓，再谈细节
  · 高对比 —— 纯白主体，不叠半透明白
  · 只有一个亮点 —— 一抹琥珀，其余克制
  · 缝宽 >= 5/108 —— 48dp 下约 2.2dp，能看见

四个方向：
  A 双层叠放 + 一行高亮      「截图不止一张，其中一张被识别」
  B 三层叠放 + 一个点        「整理成叠」，极简
  C 扫描框 + 三行            「正在识别这个画面」
  D 切角方块 + 角标点        「已归档」，现代角标语言
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets/icon/candidates")

BRAND = (47, 111, 107)
BRAND_DEEP = (31, 78, 75)
WHITE = (255, 255, 255)
AMBER = (240, 178, 66)

S = 108.0


def rr(d, k, box, r, color):
    d.rounded_rectangle(
        [box[0] * k, box[1] * k, box[2] * k, box[3] * k],
        radius=r * k,
        fill=color,
    )


def ell(d, k, cx, cy, r, color):
    d.ellipse(
        [(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k],
        fill=color,
    )


def draw(kind, size, scale=6, bg=BRAND):
    s = int(size * scale)
    img = Image.new("RGBA", (s, s), bg + (255,))
    d = ImageDraw.Draw(img)
    k = s / S
    hole = bg + (255,)

    if kind == "A":
        # 后层：一张错位的卡，只露左边和下边
        rr(d, k, (25, 33, 75, 87), 11, WHITE + (72,))
        # 前层：主体
        rr(d, k, (33, 24, 83, 78), 11, WHITE + (255,))
        # 三条内容行：两条挖空，中间一条琥珀高亮
        rr(d, k, (43, 36, 73, 43), 3.5, hole)
        rr(d, k, (43, 50, 73, 57), 3.5, AMBER + (255,))
        rr(d, k, (43, 64, 65, 71), 3.5, hole)

    elif kind == "B":
        rr(d, k, (20, 34, 64, 82), 9, WHITE + (58,))
        rr(d, k, (29, 27, 73, 75), 9, WHITE + (118,))
        rr(d, k, (38, 20, 82, 68), 9, WHITE + (255,))
        # 唯一细节：前层中心一个琥珀点
        ell(d, k, 60, 44, 7, AMBER + (255,))

    elif kind == "C":
        # 实心圆角方块当「屏幕」
        rr(d, k, (23, 23, 85, 85), 17, WHITE + (255,))
        # 挖出内部区域，形成「框」
        rr(d, k, (35, 35, 73, 73), 9, hole)
        # 框内三行
        rr(d, k, (41, 45, 67, 50), 2.5, WHITE + (255,))
        rr(d, k, (41, 55, 59, 60), 2.5, WHITE + (255,))
        rr(d, k, (41, 65, 67, 70), 2.5, WHITE + (255,))
        # 右下角琥珀点，压在框上
        ell(d, k, 74, 74, 7, AMBER + (255,))

    elif kind == "D":
        rr(d, k, (25, 25, 83, 83), 15, WHITE + (255,))
        # 右上角切一个方形缺口
        d.rectangle([62 * k, 25 * k, 83 * k, 46 * k], fill=hole)
        # 缺口里的琥珀点
        ell(d, k, 72, 36, 6.5, AMBER + (255,))
        # 两条内容行
        rr(d, k, (38, 55, 70, 61), 3, hole)
        rr(d, k, (38, 68, 56, 74), 3, hole)

    return img.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(OUT, exist_ok=True)
    kinds = ["A", "B", "C", "D"]
    label_h = 34
    cell = 200
    canvas = Image.new("RGB", (cell * 4 + 40, 480), (250, 250, 249))

    for i, kd in enumerate(kinds):
        big = draw(kd, 176)
        canvas.paste(big, (20 + i * cell, 10), big)
        small = draw(kd, 48)
        canvas.paste(small, (20 + i * cell + 64, 200), small)
        tiny = draw(kd, 32)
        canvas.paste(tiny, (20 + i * cell + 72, 260), tiny)

    canvas.save(os.path.join(OUT, "compare.png"))

    # 每个候选单独出深/浅底检查
    for kd in kinds:
        for name, bg in (("light", (250, 250, 249)), ("dark", (17, 19, 18))):
            c = Image.new("RGB", (420, 180), bg)
            for j, px in enumerate((128, 64, 40)):
                ic = draw(kd, px)
                c.paste(ic, (24 + j * 130, 26), ic)
            c.save(os.path.join(OUT, f"{kd}-{name}.png"))
        draw(kd, 512).save(os.path.join(OUT, f"{kd}-512.png"))

    print("candidates written")


if __name__ == "__main__":
    main()
