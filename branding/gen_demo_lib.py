#!/usr/bin/env python3
"""Build the demo screenshot library for docs.

~20 synthetic images, all invented content. Includes exact duplicates,
near-duplicates, expired coupons, OTP codes and a near-blank shot so the
cleanup workbench has real material to show.
"""
import os, shutil, random
from PIL import Image, ImageDraw, ImageFont

OUT = "/tmp/demo_lib"
shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(OUT, exist_ok=True)

W, H = 1080, 2376
CJK = "/usr/share/fonts/truetype/eta/NotoSansCJK-Regular.ttc"
BG, CARD, INK, SUB, LINE = (247,248,250), (255,255,255), (28,32,36), (110,118,126), (228,231,235)
GREEN, ORANGE, BLUE = (30,140,96), (222,138,40), (44,104,190)

def f(s):
    return ImageFont.truetype(CJK, s)

def new(bg=BG):
    im = Image.new("RGB", (W, H), bg); return im, ImageDraw.Draw(im)

def sbar(d, ink=(28,32,36)):
    d.text((40, 40), "9:41", font=f(30), fill=ink)
    d.rounded_rectangle([980, 48, 1040, 78], radius=6, outline=ink, width=3)
    d.rectangle([984, 52, 1024, 74], fill=ink)

def head(d, title, note="示例数据 · 仅用于演示"):
    sbar(d)
    d.text((48, 140), title, font=f(52), fill=INK)
    d.text((48, 212), note, font=f(30), fill=SUB)
    d.line([0, 280, W, 280], fill=LINE, width=2)

def card(d, y, h):
    d.rounded_rectangle([40, y, W-40, y+h], radius=24, fill=CARD)
    return y

def order(idx=1, amount="128.00"):
    im, d = new(); head(d, "订单详情")
    y = card(d, 320, 250)
    d.text((80, y+40), "示例商城", font=f(38), fill=INK)
    d.text((80, y+108), f"订单号  DEMO-0000-{idx:04d}", font=f(30), fill=SUB)
    d.text((80, y+168), "状态    已完成", font=f(30), fill=GREEN)
    y = card(d, 610, 370)
    for i, (k, v) in enumerate([("商品","示例商品 A"),("单价",f"¥ {amount}"),("数量","× 1"),("运费","¥ 0.00")]):
        d.text((80, y+40+i*78), k, font=f(30), fill=SUB)
        d.text((300, y+40+i*78), v, font=f(30), fill=INK)
    y = card(d, 1020, 170)
    d.text((80, y+36), "合计", font=f(34), fill=INK)
    d.text((740, y+30), f"¥ {amount}", font=f(44), fill=ORANGE)
    return im

def chat():
    im, d = new(); head(d, "示例会话")
    y = 340
    for side, txt in [("l","明天几点出发？"),("r","上午十点，老地方"),
                      ("l","好的，记得带伞"),("r","收到 👌")]:
        tw = d.textlength(txt, font=f(32)); bw = int(tw)+60
        if side == "l":
            d.rounded_rectangle([60,y,60+bw,y+100], radius=20, fill=CARD)
            d.text((90,y+30), txt, font=f(32), fill=INK)
        else:
            d.rounded_rectangle([W-60-bw,y,W-60,y+100], radius=20, fill=(212,235,224))
            d.text((W-90-bw+30,y+30), txt, font=f(32), fill=(20,60,44))
        y += 140
    return im

def qr():
    im, d = new(); head(d, "扫码支付")
    d.rounded_rectangle([140,420,W-140,1290], radius=32, fill=CARD)
    random.seed(7); gx, gy, cell = 240, 520, 20
    for r in range(20):
        for c in range(20):
            if random.random() < 0.45:
                d.rectangle([gx+c*cell, gy+r*cell, gx+(c+1)*cell-2, gy+(r+1)*cell-2], fill=INK)
    d.text((300, 1000), "示例收款码", font=f(34), fill=SUB)
    return im

def game():
    im, d = new((24,26,32)); sbar(d, (235,238,242))
    d.text((48,160), "对局结算", font=f(52), fill=(235,238,242))
    d.text((48,232), "示例数据 · 仅用于演示", font=f(28), fill=(150,156,166))
    y = 340
    for k, v, c in [("击杀","12",GREEN),("助攻","7",BLUE),("死亡","3",ORANGE),("评分","9.2",(220,200,80))]:
        d.rounded_rectangle([40,y,W-40,y+150], radius=20, fill=(34,37,44))
        d.text((80,y+50), k, font=f(34), fill=(180,186,194))
        d.text((820,y+40), v, font=f(56), fill=c)
        y += 180
    return im

def expired(date="2024-04-21", title="满 100 减 20"):
    im, d = new(); head(d, "优惠券")
    y = card(d, 340, 260)
    d.text((80,y+40), title, font=f(44), fill=INK)
    d.text((80,y+118), f"有效期至 {date}", font=f(30), fill=ORANGE)
    d.text((80,y+186), "已过期", font=f(30), fill=(170,60,60))
    y = card(d, 640, 260)
    d.text((80,y+40), "新用户专享", font=f(44), fill=INK)
    d.text((80,y+118), "有效期至 2023-12-31", font=f(30), fill=ORANGE)
    d.text((80,y+186), "已过期", font=f(30), fill=(170,60,60))
    return im

def otp():
    im, d = new(); head(d, "短信")
    y = card(d, 340, 220)
    d.text((80,y+36), "示例服务", font=f(34), fill=INK)
    d.text((80,y+104), "您的验证码是 000000，5 分钟内有效。", font=f(28), fill=SUB)
    y = card(d, 600, 220)
    d.text((80,y+36), "示例平台", font=f(34), fill=INK)
    d.text((80,y+104), "登录验证码 000000，请勿泄露。", font=f(28), fill=SUB)
    return im

def note():
    im, d = new(); head(d, "阅读笔记")
    for i, t in enumerate(["把截图当成可检索的资料，","而不是一堆图片。","","关键词：整理、检索、归档。"]):
        d.text((60, 340+i*80), t, font=f(34), fill=INK)
    return im

def settings():
    im, d = new(); head(d, "设置")
    y = 340
    for t in ["账号与安全","通知","存储空间","关于"]:
        d.rounded_rectangle([40,y,W-40,y+120], radius=20, fill=CARD)
        d.text((80,y+38), t, font=f(34), fill=INK)
        d.text((W-110,y+34), "›", font=f(44), fill=SUB)
        y += 150
    return im

def blank():
    im, d = new((252,252,252)); sbar(d, (205,205,205))
    return im

def pay():
    im, d = new(); head(d, "付款成功")
    d.text((60, 360), "¥ 128.00", font=f(80), fill=INK)
    d.text((60, 470), "示例商户", font=f(32), fill=SUB)
    y = card(d, 580, 260)
    d.text((80,y+40), "支付方式", font=f(30), fill=SUB)
    d.text((80,y+100), "示例钱包", font=f(32), fill=INK)
    d.text((80,y+170), "2024-03-08 12:30", font=f(28), fill=SUB)
    return im

items = [
    ("order1", order(1)), ("order2", order(2, "269.00")), ("order3", order(3, "58.50")),
    ("chat1", chat()),
    ("qr1", qr()),
    ("game1", game()),
    ("expired1", expired()), ("expired2", expired("2024-01-15", "限时立减 50")),
    ("otp1", otp()),
    ("note1", note()),
    ("settings1", settings()),
    ("pay1", pay()),
    ("blank1", blank()),
]

n = 0
def save(im, tag):
    global n
    im.save(os.path.join(OUT, f"Screenshot_demo_{n:02d}_{tag}.png")); n += 1

for tag, im in items:
    save(im, tag)
# exact duplicates (same bytes) for exact-duplicate detection
save(items[0][1], "order1copy")
save(items[4][1], "qr1copy")
# near duplicates (slight shift) for visual-similar grouping
for base_tag, base_im in [("order1", items[0][1]), ("game1", items[5][1])]:
    sh = Image.new("RGB", (W, H), (255,255,255))
    sh.paste(base_im.crop((0, 12, W, H)), (0, 0))
    save(sh, base_tag + "near")
# a couple more order shots for volume
save(order(4, "19.90"), "order4")
save(order(5, "432.00"), "order5")

print(f"{len(os.listdir(OUT))} demo images -> {OUT}")
for fn in sorted(os.listdir(OUT)):
    print(" ", fn)
