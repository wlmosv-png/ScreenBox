#!/usr/bin/env python3
"""ScreenBox brand visual generator.

Produces the GitHub social preview (1280x640) and the README hero,
composed from the real app icon so the brand stays consistent.

Output:
  docs/assets/social-preview.png   (1280x640, GitHub social preview)
  docs/assets/hero.png             (1280x640, README hero)
"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = "/workspace/screenbox"
ICON = f"{ROOT}/assets/icon/screenbox-icon-1024.png"
OUT_DIR = f"{ROOT}/docs/assets"

# Brand palette (matches ic_launcher_foreground / background)
TEAL_TOP = (78, 168, 158)      # #4EA89E
TEAL_BOT = (26, 104, 96)       # #1A6860
AMBER = (245, 178, 64)         # #F5B240 hit line
INK = (235, 246, 243)          # near-white text
MUTED = (200, 224, 220)        # muted text

FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_REG = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def vgradient(size, top, bottom):
    w, h = size
    base = Image.new("RGB", size, top)
    px = base.load()
    for y in range(h):
        t = y / max(1, h - 1)
        r = int(top[0] + (bottom[0] - top[0]) * t)
        g = int(top[1] + (bottom[1] - top[1]) * t)
        b = int(top[2] + (bottom[2] - top[2]) * t)
        for x in range(w):
            px[x, y] = (r, g, b)
    return base


def diagonal_sheen(img, cx, cy, radius, color, alpha=28):
    """Soft radial light to avoid a flat block of color."""
    w, h = img.size
    sheen = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    sp = sheen.load()
    for y in range(h):
        for x in range(w):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if d < radius:
                fall = 1 - d / radius
                sp[x, y] = (color[0], color[1], color[2], int(alpha * fall))
    return Image.alpha_composite(img.convert("RGBA"), sheen)


def rounded_icon(icon, target, radius_ratio=0.22):
    """Resize the square icon to target and apply a soft rounded mask."""
    icon = icon.convert("RGBA").resize((target, target), Image.LANCZOS)
    r = int(target * radius_ratio)
    mask = Image.new("L", (target, target), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, target - 1, target - 1], radius=r, fill=255)
    out = Image.new("RGBA", (target, target), (0, 0, 0, 0))
    out.paste(icon, (0, 0), mask)
    return out


def build(out_path, icon_size=330, title="ScreenBox", tagline="Your screenshots, finally organized."):
    W, H = 1280, 640
    img = vgradient((W, H), TEAL_TOP, TEAL_BOT)
    img = diagonal_sheen(img, cx=int(W * 0.72), cy=int(H * 0.34), radius=int(W * 0.5), color=(255, 255, 255), alpha=22)
    img = diagonal_sheen(img, cx=int(W * 0.18), cy=int(H * 0.86), radius=int(W * 0.42), color=(0, 0, 0), alpha=26)

    icon = rounded_icon(Image.open(ICON), icon_size)
    # soft drop shadow behind icon
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sx, sy = int(W * 0.14), int((H - icon_size) / 2) + 10
    sd.rounded_rectangle([sx + 8, sy + 14, sx + icon_size + 8, sy + icon_size + 14],
                         radius=int(icon_size * 0.22), fill=(0, 0, 0, 90))
    shadow = shadow.filter(ImageFilter.GaussianBlur(18))
    img = Image.alpha_composite(img.convert("RGBA"), shadow)
    img.paste(icon, (sx, sy), icon)

    d = ImageDraw.Draw(img)
    # accent rule
    ax = sx + icon_size + 54
    d.rounded_rectangle([ax, int(H * 0.40), ax + 96, int(H * 0.40) + 8], radius=4, fill=AMBER)

    # title
    title_font = ImageFont.truetype(FONT_BOLD, 118)
    d.text((ax, int(H * 0.44)), title, font=title_font, fill=INK)

    # tagline
    tag_font = ImageFont.truetype(FONT_REG, 40)
    d.text((ax, int(H * 0.60)), tagline, font=tag_font, fill=MUTED)

    # footer word
    foot_font = ImageFont.truetype(FONT_REG, 24)
    d.text((ax, int(H * 0.80)), "Local-first · Android · Open Source", font=foot_font, fill=MUTED)

    img.convert("RGB").save(out_path, "PNG", optimize=True)
    print(f"wrote {out_path}")


if __name__ == "__main__":
    import os
    os.makedirs(OUT_DIR, exist_ok=True)
    build(f"{OUT_DIR}/social-preview.png")
    build(f"{OUT_DIR}/hero.png")
