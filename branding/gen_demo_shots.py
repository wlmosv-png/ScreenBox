#!/usr/bin/env python3
"""Generate synthetic demo screenshots for ScreenBox docs.

All content is invented. No real names, numbers, orders, or chats.
Rendered as clean phone-shaped screenshots at 1080x2376.
"""
import os, random
from PIL import Image, ImageDraw, ImageFont

OUT = "/tmp/demo_shots"
os.makedirs(OUT, exist_ok=True)

W, H = 1080, 2376
CJK = "/usr/share/fonts/truetype/eta/NotoSansCJK-Regular.ttc"
CJK_B = "/usr/share/fonts/truetype/eta/NotoSansCJK-Regular.ttc"

def f(size, bold=False):
    try:
        return ImageFont.truetype(CJK_B if bold else CJK, size)
    except Exception:
        return ImageFont.load_default()

# palette (neutral, no brand claims)
BG = (247, 248, 250)
CARD = (255, 255, 255)
INK = (28, 32, 36)
SUB = (110, 118, 126)
LINE = (228, 231, 235)
GREEN = (30, 140, 96)
ORANGE = (222, 138, 40)
BLUE = (44, 104, 190)

def base(dark=False):
    bg = (18, 20, 23) if dark else BG
    card = (30, 33, 37) if dark else CARD
    ink = (238, 241, 244) if dark else INK
    sub = (150, 158, 166) if dark else SUB
    line = (52, 57, 62) if dark else LINE
    return bg, card, ink, sub, line

def status_bar(d, ink, dark=False):
    d.text((40, 40), "9:41", font=f(30), fill=ink)
    # battery + wifi glyphs (simple)
    d.rounded_rectangle([980, 48, 1040, 78], radius=6, outline=ink, width=3)
    d.rectangle([984, 52, 1024, 74], fill=ink)

def header(d, title, ink, sub, line, subtext=None):
    status_bar(d, ink)
    d.text((48, 140), title, font=f(52, True), fill=ink)
    if subtext:
        d.text((48, 210), subtext, font=f(30), fill=sub)
    d.line([0, 280, W, 280], fill=line, width=2)

def make_order(dark=False):
    bg, card, ink, sub, line = base(dark)
    im = Image.new("RGB", (W, H), bg); d = ImageDraw.Draw(im)
    header(d, "订单详情", ink, sub, line, "示例数据 · 仅用于演示")
    y = 320
    d.rounded_rectangle([40, y, W-40, y+260], radius=24, fill=card)
    d.text((80, y+40), "示例商城", font=f(38, True), fill=ink)
    d.text((80, y+110), "订单号  DEMO-0000-0001", font=f(30), fill=sub)
    d.text((80, y+170), "状态    已完成", font=f(30), fill=GREEN)
    y += 300
    d.rounded_rectangle([40, y, W-40, y+380], radius=24, fill=card)
    rows = [("商品", "示例商品 A"), ("单价", "¥ 128.00"), ("数量", "× 1"), ("运费", "¥ 0.00")]
    for i, (k, v) in enumerate(rows):
        yy = y + 40 + i*78
        d.text((80, yy), k, font=f(30), fill=sub)
        d.text((300, yy), v, font=f(30), fill=ink)
    y += 420
    d.rounded_rectangle([40, y, W-40, y+180], radius=24, fill=card)
    d.text((80, y+40), "合计", font=f(34, True), fill=ink)
    d.text((760, y+36), "¥ 128.00", font=f(44, True), fill=ORANGE)
    return im

def make_chat(dark=False):
    bg, card, ink, sub, line = base(dark)
    im = Image.new("RGB", (W, H), bg); d = ImageDraw.Draw(im)
    header(d, "示例会话", ink, sub, line, "示例数据 · 仅用于演示")
    msgs = [("left", "明天几点出发？"), ("right", "上午十点，老地方"),
            ("left", "好的，记得带伞"), ("right", "收到 👌")]
    y = 340
    for side, txt in msgs:
        tw = d.textlength(txt, font=f(32))
        bw = int(tw) + 60
        if side == "left":
            box = [60, y, 60+bw, y+100]
            d.rounded_rectangle(box, radius=20, fill=card)
            d.text((90, y+30), txt, font=f(32), fill=ink)
        else:
            box = [W-60-bw, y, W-60, y+100]
            d.rounded_rectangle(box, radius=20, fill=(212, 235, 224))
            d.text((W-60-bw+30, y+30), txt, font=f(32), fill=(20, 60, 44))
        y += 140
    return im

def make_qr(dark=False):
    bg, card, ink, sub, line = base(dark)
    im = Image.new("RGB", (W, H), bg); d = ImageDraw.Draw(im)
    header(d, "扫码支付", ink, sub, line, "示例数据 · 仅用于演示")
    d.rounded_rectangle([140, 420, W-140, W-140+280], radius=32, fill=card)
    # fake QR blocks (deterministic, not a real code)
    random.seed(7)
    gx, gy, cell = 240, 520, 20
    for r in range(20):
        for c in range(20):
            if random.random() < 0.45:
                d.rectangle([gx+c*cell, gy+r*cell, gx+(c+1)*cell-2, gy+(r+1)*cell-2], fill=ink)
    d.text((300, 1000), "示例收款码", font=f(34), fill=sub)
    return im

def make_game(dark=False):
    bg, card, ink, sub, line = base(dark)
    im = Image.new("RGB", (W, H), (24, 26, 32)); d = ImageDraw.Draw(im)
    status_bar(d, (235, 238, 242))
    # fake HUD
    d.text((48, 160), "对局结算", font=f(52, True), fill=(235, 238, 242))
    d.text((48, 230), "示例数据 · 仅用于演示", font=f(28), fill=(150, 156, 166))
    y = 340
    for i, (k, v, c) in enumerate([("击杀", "12", GREEN), ("助攻", "7", BLUE),
                                   ("死亡", "3", ORANGE), ("评分", "9.2", (220, 200, 80))]):
        d.rounded_rectangle([40, y, W-40, y+150], radius=20, fill=(34, 37, 44))
        d.text((80, y+50), k, font=f(34), fill=(180, 186, 194))
        d.text((820, y+40), v, font=f(56, True), fill=c)
        y += 180
    return im

def make_expired(dark=False):
    bg, card, ink, sub, line = base(dark)
    im = Image.new("RGB", (W, H), bg); d = ImageDraw.Draw(im)
    header(d, "优惠券", ink, sub, line, "示例数据 · 仅用于演示")
    y = 340
    d.rounded_rectangle([40, y, W-40, y+280], radius=24, fill=card)
    d.text((80, y+40), "满 100 减 20", font=f(44, True), fill=ink)
    d.text((80, y+120), "有效期至 2024-04-21", font=f(30), fill=ORANGE)
    d.text((80, y+190), "已过期", font=f(30), fill=(170, 60, 60))
    y += 320
    d.rounded_rectangle([40, y, W-40, y+280], radius=24, fill=card)
    d.text((80, y+40), "新用户专享", font=f(44, True), fill=ink)
    d.text((80, y+120), "有效期至 2023-12-31", font=f(30), fill=ORANGE)
    d.text((80, y+190), "已过期", font=f(30), fill=(170, 60, 60))
    return im

def make_otp(dark=False):
    bg, card, ink, sub, line = base(dark)
    im = Image.new("RGB", (W, H), bg); d = ImageDraw.Draw(im)
    header(d, "短信", ink, sub, line, "示例数据 · 仅用于演示")
    y = 340
    d.rounded_rectangle([40, y, W-40, y+240], radius=24, fill=card)
    d.text((80, y+40), "示例服务", font=f(34, True), fill=ink)
    d.text((80, y+110), "您的验证码是 000000，5 分钟内有效。", font=f(28), fill=sub)
    y += 280
    d.rounded_rectangle([40, y, W-40, y+240], radius=24, fill=card)
    d.text((80, y+40), "示例平台", font=f(34, True), fill=ink)
    d.text((80, y+110), "登录验证码 000000，请勿泄露。", font=f(28), fill=sub)
    return im

def make_blank(dark=False):
    bg, card, ink, sub, line = base(dark)
    im = Image.new("RGB", (W, H), (252, 252, 252)); d = ImageDraw.Draw(im)
    status_bar(d, (200, 200, 200))
    return im

def make_note(dark=False):
    bg, card, ink, sub, line = base(dark)
    im = Image.new("RGB", (W, H), bg); d = ImageDraw.Draw(im)
    header(d, "阅读笔记", ink, sub, line, "示例数据 · 仅用于演示")
    y = 340
    body = ["把截图当成可检索的资料，", "而不是一堆图片。", "",
            "关键词：整理、检索、归档。"]
    for i, t in enumerate(body):
        d.text((60, y + i*80), t, font=f(34), fill=ink)
    return im

def make_settings(dark=False):
    bg, card, ink, sub, line = base(dark)
    im = Image.new("RGB", (W, H), bg); d = ImageDraw.Draw(im)
    header(d, "设置", ink, sub, line, "示例数据 · 仅用于演示")
    y = 340
    for t in ["账号与安全", "通知", "存储空间", "关于"]:
        d.rounded_rectangle([40, y, W-40, y+120], radius=20, fill=card)
        d.text((80, y+38), t, font=f(34), fill=ink)
        d.text((W-110, y+38), "›", font=f(40), fill=sub)
        y += 150
    return im

MAKERS = [
    ("make_order", make_order),
    ("make_chat", make_chat),
    ("make_qr", make_qr),
    ("make_game", make_game),
    ("make_expired", make_expired),
    ("make_otp", make_otp),
    ("make_note", make_note),
    ("make_settings", make_settings),
]

if __name__ == "__main__":
    n = 0
    for name, fn in MAKERS:
        for dark in (False, True):
            im = fn(dark)
            fn_out = os.path.join(OUT, f"Screenshot_demo_{n:03d}.png")
            im.save(fn_out)
            n += 1
    # a few blanks for the near-blank suggestion
    for i in range(2):
        make_blank(False).save(os.path.join(OUT, f"Screenshot_demo_blank{i}.png"))
    print(f"generated {len(os.listdir(OUT))} demo screenshots in {OUT}")
