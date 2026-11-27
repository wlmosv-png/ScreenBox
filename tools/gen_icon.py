#!/usr/bin/env python3
"""
ScreenBox 图标定稿 v11（屏幕剪影 + 命中行）。

══ 二十八轮，最终选择 T2 ══

否决主线（全部基于真实渲染图判断，不是推测）：

  卡片类（1–7、9–15、22–26）
    圆角方块在图标尺度上被读成 文档 / 便签 / 应用 / 通知角标。
    重叠 → 读成「一个物体 + 投影」；分离 → 读成「暂停键 / 应用网格」；
    内部横线 → 读成「文档 / 聊天 / 菜单」。三条路都被既有符号占满。

  符号类（8、16–18、20–21）
    2×2 网格 = 应用抽屉；圆环 + 半块 = 深色模式开关；
    取景框 + 中心块 = 扫码/对焦；对角分割 + 圆形遮罩 = 禁止通行。

  横带类（24）
    递减横带在 32dp 第三条缩成点，左对齐显得参差。

══ 为什么 T2 成立 ══

  主体用「手机屏幕的剪影」——识别特征是轮廓（竖长 + 刘海），
  不是内部细节，所以缩到 32dp 也不丢。

  内部两行给出「内容」语义：
    第一行琥珀 = 被识别 / 命中的那一行
    第二行淡灰 = 其余内容
  这正好是产品做的事：把截图里的文字切成可检索的行。

  与任何常见图标都不撞：不是放大镜、不是文件夹、不是相册、不是相机。

══ 几何（全部落在安全圆内）══

  屏幕   35..73 × 25..83，圆角 11
         四角距圆心 (54,54) = 34.7  < 36  ✅
  刘海   46..62 × 31..37，圆角 3
         悬浮不贴边（贴边会被读成夹子/标签）
  命中行 43..65 × 47..54，圆角 3.5，琥珀
  内容行 43..58 × 60..67，圆角 3.5，淡灰 #B0C8C5
"""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res")
OUT = os.path.join(ROOT, "assets/icon")

S = 108.0
BG_TOP = (78, 168, 158)      # #4EA89E
BG_BOTTOM = (26, 104, 96)    # #1A6860
WHITE = (255, 255, 255)
SCREEN_BOTTOM = (238, 244, 243)
AMBER = (245, 184, 70)       # #F5B846
LINE_GRAY = (176, 200, 197)  # #B0C8C5

SCR = (35.0, 25.0, 73.0, 83.0, 11.0)
NOTCH = (45.0, 25.0, 63.0, 30.0, 2.5)
LINE_HIT = (41.0, 43.0, 67.0, 49.0, 1.6)
LINE_REST = (41.0, 54.0, 59.0, 60.0, 1.6)
LINE_REST2 = (41.0, 65.0, 51.0, 71.0, 1.6)


def rrect(x1, y1, x2, y2, r):
    return (
        f"M{x1+r:.2f},{y1:.2f} "
        f"H{x2-r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x2:.2f},{y1+r:.2f} "
        f"V{y2-r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x2-r:.2f},{y2:.2f} "
        f"H{x1+r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x1:.2f},{y2-r:.2f} "
        f"V{y1+r:.2f} A{r:.2f},{r:.2f} 0 0 1 {x1+r:.2f},{y1:.2f} Z"
    )


def vector(body, with_aapt=False):
    ns = '    xmlns:aapt="http://schemas.android.com/aapt"\n' if with_aapt else ''
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'{ns}'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        f'{body}'
        '</vector>\n'
    )


def write_vectors():
    # 屏幕：刘海用 evenOdd 挖成真正的洞，露出背景色。
    # 之前刘海填琥珀，与琥珀命中行同色同形，看起来像两行高亮。
    screen = (
        '    <group>\n'
        '        <clip-path android:pathData="' + rrect(*SCR) + '" />\n'
        '        <path android:pathData="M0,0 L108,0 L108,108 L0,108 Z '
        + rrect(*NOTCH) + '">\n'
        '            <aapt:attr name="android:fillColor">\n'
        '                <gradient android:type="linear"\n'
        '                    android:startX="54" android:startY="25"\n'
        '                    android:endX="54" android:endY="83"\n'
        '                    android:startColor="#FFFFFF"\n'
        '                    android:endColor="#EEF4F3" />\n'
        '            </aapt:attr>\n'
        '        </path>\n'
        '    </group>\n'
    )
    detail = (
        '    <!-- 命中行：被识别的那一行文字 -->\n'
        '    <path android:fillColor="#F5B846"\n'
        f'        android:pathData="{rrect(*LINE_HIT)}" />\n'
        '    <!-- 其余文字行：左对齐、长度递减 —— 这是文字行的本质特征，\n'
        '         居中且等长会被读成按钮。 -->\n'
        '    <path android:fillColor="#B0C8C5"\n'
        f'        android:pathData="{rrect(*LINE_REST)}" />\n'
        '    <path android:fillColor="#B0C8C5"\n'
        f'        android:pathData="{rrect(*LINE_REST2)}" />\n'
    )
    with open(os.path.join(RES, "drawable/ic_launcher_foreground.xml"), "w") as f:
        f.write(vector(screen + detail, with_aapt=True))

    # 单色层：刘海与两行都挖空，系统着色后仍是「屏幕 + 三处镂空」
    mono = (
        '    <path android:fillColor="#FFFFFF" android:fillType="evenOdd"\n'
        f'        android:pathData="{rrect(*SCR)} {rrect(*NOTCH)} '
        f'{rrect(*LINE_HIT)} {rrect(*LINE_REST)} {rrect(*LINE_REST2)}" />\n'
    )
    with open(os.path.join(RES, "drawable/ic_launcher_monochrome.xml"), "w") as f:
        f.write(vector(mono))

    # 品牌标记：带品牌底色的一体图，关于页 / 设置页复用
    brand = (
        f'    <path android:fillColor="#4EA89E"\n'
        f'        android:pathData="{rrect(4, 4, 104, 104, 26)}" />\n'
        '    <path android:fillColor="#FFFFFF" android:fillType="evenOdd"\n'
        f'        android:pathData="{rrect(*SCR)} {rrect(*NOTCH)}" />\n'
        '    <path android:fillColor="#F5B846"\n'
        f'        android:pathData="{rrect(*LINE_HIT)}" />\n'
        '    <path android:fillColor="#B0C8C5"\n'
        f'        android:pathData="{rrect(*LINE_REST)}" />\n'
        '    <path android:fillColor="#B0C8C5"\n'
        f'        android:pathData="{rrect(*LINE_REST2)}" />\n'
    )
    with open(os.path.join(RES, "drawable/ic_brand_mark.xml"), "w") as f:
        f.write(vector(brand))

    bg = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:shape="rectangle">\n'
        '    <gradient\n'
        '        android:angle="270"\n'
        '        android:startColor="#4EA89E"\n'
        '        android:endColor="#1A6860"\n'
        '        android:type="linear" />\n'
        '</shape>\n'
    )
    with open(os.path.join(RES, "drawable/ic_launcher_background.xml"), "w") as f:
        f.write(bg)


def vgrad(size, top, bottom):
    img = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(1, size - 1)
        img.putpixel((0, y), tuple(
            int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img.resize((size, size), Image.BILINEAR).convert("RGBA")


def draw_icon(size, scale=8, round_mask=False):
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

    # 屏幕：渐变裁进圆角（用 alpha_composite，不用 paste，
    # 避免 mask 覆盖 alpha 导致露出黑底 —— 第 27 轮踩过）
    w = max(2, int((SCR[2] - SCR[0]) * k))
    h = max(2, int((SCR[3] - SCR[1]) * k))
    grad = vgrad(h, WHITE, SCREEN_BOTTOM).resize((w, h), Image.BILINEAR)
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1],
                                        radius=SCR[4] * k, fill=255)
    grad.putalpha(m)
    img.alpha_composite(grad, (int(SCR[0] * k), int(SCR[1] * k)))

    d = ImageDraw.Draw(img)
    # 刘海：用背景渐变挖回去，等价于 vector 里的 evenOdd 洞
    bg_layer = vgrad(s, BG_TOP, BG_BOTTOM)
    nm = Image.new("L", (s, s), 0)
    ImageDraw.Draw(nm).rounded_rectangle(box(NOTCH[:4]),
                                         radius=NOTCH[4] * k, fill=255)
    img.paste(bg_layer, (0, 0), nm)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(box(LINE_HIT[:4]), radius=LINE_HIT[4] * k, fill=AMBER + (255,))
    d.rounded_rectangle(box(LINE_REST[:4]), radius=LINE_REST[4] * k, fill=LINE_GRAY + (255,))
    d.rounded_rectangle(box(LINE_REST2[:4]), radius=LINE_REST2[4] * k, fill=LINE_GRAY + (255,))

    if round_mask:
        m2 = Image.new("L", (s, s), 0)
        ImageDraw.Draw(m2).ellipse([0, 0, s - 1, s - 1], fill=255)
        img.putalpha(m2)

    return img.resize((size, size), Image.LANCZOS)


def draw_mono(size, scale=8):
    s = int(size * scale)
    k = s / S
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([SCR[0] * k, SCR[1] * k, SCR[2] * k, SCR[3] * k],
                        radius=SCR[4] * k, fill=(255, 255, 255, 255))
    for t in (NOTCH, LINE_HIT, LINE_REST, LINE_REST2):
        d.rounded_rectangle([t[0] * k, t[1] * k, t[2] * k, t[3] * k],
                            radius=t[4] * k, fill=(0, 0, 0, 0))
    return img.resize((size, size), Image.LANCZOS)


def mask_circle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    ImageDraw.Draw(m).ellipse([0, 0, n - 1, n - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def mask_squircle(size):
    n = size * 4
    m = Image.new("L", (n, n), 0)
    exp = 4.2
    cx = cy = n / 2
    r = n / 2
    pts = []
    for i in range(240):
        t = 2 * math.pi * i / 240
        ct, st = math.cos(t), math.sin(t)
        pts.append((
            cx + r * (abs(ct) ** (2 / exp)) * (1 if ct >= 0 else -1),
            cy + r * (abs(st) ** (2 / exp)) * (1 if st >= 0 else -1),
        ))
    ImageDraw.Draw(m).polygon(pts, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def write_rasters():
    density = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for name, px in density.items():
        folder = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        draw_icon(px).save(os.path.join(folder, "ic_launcher.png"))
        draw_icon(px, round_mask=True).save(os.path.join(folder, "ic_launcher_round.png"))

    os.makedirs(OUT, exist_ok=True)
    draw_icon(512).save(os.path.join(OUT, "screenbox-icon-512.png"))
    draw_icon(1024).save(os.path.join(OUT, "screenbox-icon-1024.png"))

    for mname, mfn in (("circle", mask_circle), ("squircle", mask_squircle)):
        sizes = [192, 128, 72, 48, 32]
        gap = 26
        w = sum(sizes) + gap * (len(sizes) + 1)
        c = Image.new("RGB", (w, 240), (250, 250, 249))
        x = gap
        for px in sizes:
            ic = draw_icon(px)
            ic.putalpha(mfn(px))
            c.paste(ic, (x, (240 - px) // 2), ic)
            x += px + gap
        c.save(os.path.join(OUT, f"visibility-{mname}.png"))

    mono = draw_mono(192)
    for label, tint, bg in (
        ("themed-light", (30, 32, 31), (240, 240, 238)),
        ("themed-dark", (230, 232, 231), (18, 18, 18)),
    ):
        c = Image.new("RGB", (320, 240), bg)
        solid = Image.new("RGB", mono.size, tint)
        c.paste(solid, (64, 24), mono.split()[3])
        c.save(os.path.join(OUT, f"{label}.png"))


if __name__ == "__main__":
    write_vectors()
    write_rasters()
    print("icon v11 (T2) written")
