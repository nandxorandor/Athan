# -*- coding: utf-8 -*-
"""
Play Store assets for Athan.

Screenshots come off the phone at 1080x2400 - a 1:2.22 ratio, which Play
rejects: it caps the long side at twice the short one. Padding to 1080x2160
with the app's own background keeps every pixel of the screen and lands
exactly on 1:2.
"""
import glob
import os
from PIL import Image, ImageDraw, ImageFont

RAW = r"c:\Users\msicu\android_apps\Athan\store\play\raw"
OUT = r"c:\Users\msicu\android_apps\Athan\store\play"

BG = (14, 20, 18)        # @color/bg
ACCENT = (79, 199, 154)  # @color/accent
TEXT = (230, 237, 234)
DIM = (124, 138, 133)


def font(size, bold=False):
    for name in (("seguibl.ttf" if bold else "segoeui.ttf"),
                 ("arialbd.ttf" if bold else "arial.ttf")):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def pad_screenshots():
    """1080x2400 -> 1080x2160, cropping nothing: the phone frame is scaled down
    to fit the taller-than-allowed content into a legal 1:2 canvas."""
    made = []
    for path in sorted(glob.glob(os.path.join(RAW, "*.png"))):
        src = Image.open(path).convert("RGB")
        w, h = src.size
        target_w, target_h = 1080, 2160

        # Scale to fit inside the target, preserving the whole screen.
        scale = min(target_w / w, target_h / h)
        new = (int(w * scale), int(h * scale))
        shrunk = src.resize(new, Image.LANCZOS)

        canvas = Image.new("RGB", (target_w, target_h), BG)
        canvas.paste(shrunk, ((target_w - new[0]) // 2, (target_h - new[1]) // 2))

        name = os.path.basename(path)
        dest = os.path.join(OUT, name)
        canvas.save(dest, "PNG")
        ratio = max(canvas.size) / min(canvas.size)
        made.append((name, canvas.size, round(ratio, 2)))
    return made


def feature_graphic():
    """1024x500, the one Play requires. No screenshot inside it: at the size it
    actually renders, a phone frame is unreadable clutter."""
    W, H = 1024, 500
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    # A soft vertical lift, so it is not a flat rectangle.
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)],
               fill=(int(14 + 9 * (1 - t)), int(20 + 16 * (1 - t)), int(18 + 12 * (1 - t))))

    # A crescent, drawn the same way as the app's evening icon: one disc with
    # another punched out of it.
    cx, cy, r = 838, 250, 132
    moon = Image.new("L", (W, H), 0)
    md = ImageDraw.Draw(moon)
    md.ellipse([cx - r, cy - r, cx + r, cy + r], fill=190)
    md.ellipse([cx - r + 54, cy - r - 26, cx + r + 54, cy + r - 26], fill=0)
    img.paste(Image.new("RGB", (W, H), ACCENT), (0, 0), moon)

    # No Arabic here: PIL does not shape Arabic, so the letters come out
    # disconnected and backwards. A wrong rendering of the app's own name is
    # worse than not showing it, and the store listing carries it correctly.
    d.text((72, 168), "Athan", font=font(104, bold=True), fill=TEXT)
    d.text((76, 300), "Prayer times, Qibla and athkar",
           font=font(34), fill=ACCENT)
    d.text((76, 352), "Works completely offline",
           font=font(30), fill=DIM)

    path = os.path.join(OUT, "feature-graphic-1024x500.png")
    img.save(path, "PNG")
    return path, img.size


if __name__ == "__main__":
    for name, size, ratio in pad_screenshots():
        ok = "OK" if ratio <= 2 else "STILL TOO TALL"
        print("  %-16s %sx%s  ratio %.2f  %s" % (name, size[0], size[1], ratio, ok))
    p, size = feature_graphic()
    print("  feature graphic  %sx%s  %s" % (size[0], size[1], os.path.basename(p)))
