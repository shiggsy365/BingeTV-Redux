#!/usr/bin/env python3
"""Make BingeTV Redux's icons, banner and logos from branding/logo-source.jpg.

The source is the BingeTV logo on its dark background. The launcher icons use the
play-button "B"; the TV banner and the in-app logo use the wordmark with "REDUX"
under it. Where the logo sits on something else it's lifted off the background by
brightness (the background is dark grey; the logo red and white, with a glow).

    python3 tools/make_branding.py

Needs Pillow and a Roboto Black font (REDUX_FONT=<path> to use another). The build
itself needs neither: the generated images are committed.
"""

import os

from PIL import Image, ImageChops, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = os.path.join(HERE, "..", "branding", "logo-source.jpg")
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
WEB = os.path.join(HERE, "..", "app", "src", "main", "assets", "web")
FONT = os.environ.get("REDUX_FONT", "/usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/Roboto-Black.ttf")

INK = (21, 23, 28)                 # the logo's background, for icon tiles
RED = (227, 30, 45)                # BingeTV's brand red
WORDMARK = (430, 515, 2400, 1015)  # "Binge" + the TV badge, in the source image
LETTER_B = (440, 545, 830, 935)    # the play-button B (square)


def lifted(img, floor=70, span=110):
    """`img` with the dark background made transparent: a pixel's alpha follows its
    brightest channel."""
    r, g, b = img.split()
    alpha = ImageChops.lighter(ImageChops.lighter(r, g), b)
    alpha = alpha.point(lambda v: max(0, min(255, (v - floor) * 255 // span)))
    out = img.convert("RGBA")
    out.putalpha(alpha)
    return out


def fit(img, size, scale=1.0, background=(0, 0, 0, 0)):
    """`img` scaled to fit `scale` of `size`, centred on `background`."""
    w, h = size
    ratio = min(w * scale / img.width, h * scale / img.height)
    small = img.resize((max(1, round(img.width * ratio)), max(1, round(img.height * ratio))), Image.LANCZOS)
    canvas = Image.new("RGBA", size, background)
    canvas.alpha_composite(small.convert("RGBA"), ((w - small.width) // 2, (h - small.height) // 2))
    return canvas


def rounded(img, radius_ratio=0.22):
    big = 4
    mask = Image.new("L", (img.width * big, img.height * big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.width * big - 1, img.height * big - 1],
                                           img.width * big * radius_ratio, fill=255)
    out = img.convert("RGBA")
    out.putalpha(ImageChops.multiply(out.getchannel("A"), mask.resize(img.size, Image.LANCZOS)))
    return out


def icon(size, src, rounded_corners=True):
    """The B on an ink tile."""
    tile = fit(src.crop(LETTER_B), (size, size), 0.86, INK + (255,))
    return rounded(tile) if rounded_corners else tile


def redux_logo(src, width=1600):
    """The wordmark with REDUX spaced out beneath it, right-aligned under the TV badge,
    on transparent."""
    mark = lifted(src.crop(WORDMARK))
    mark = mark.resize((width, round(mark.height * width / mark.width)), Image.LANCZOS)
    size = round(mark.height * 0.30)
    font = ImageFont.truetype(FONT, size)
    tracking = round(size * 0.35)
    letters = "REDUX"
    widths = [font.getbbox(c)[2] - font.getbbox(c)[0] for c in letters]
    text_w = sum(widths) + tracking * (len(letters) - 1)
    gap = round(size * 0.25)
    out = Image.new("RGBA", (width, mark.height + gap + size + round(size * 0.1)), (0, 0, 0, 0))
    out.alpha_composite(mark, (0, 0))
    draw = ImageDraw.Draw(out)
    x = width - text_w - round(width * 0.02)
    y = mark.height + gap
    for c, w in zip(letters, widths):
        draw.text((x - font.getbbox(c)[0], y - font.getbbox(c)[1]), c, font=font, fill=(255, 255, 255, 255))
        x += w + tracking
    return out


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if path.endswith(".jpg"):
        img.convert("RGB").save(path, quality=92, optimize=True)
    else:
        img.save(path, optimize=True)
    print(os.path.relpath(path, os.path.join(HERE, "..")))


def main():
    src = Image.open(SOURCE).convert("RGB")
    for folder, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        save(icon(size, src), os.path.join(RES, f"mipmap-{folder}", "ic_launcher.png"))

    logo = redux_logo(src)
    # Android TV launcher banner: 320x180 at xhdpi.
    save(fit(logo, (640, 360), 0.82, INK + (255,)).resize((320, 180), Image.LANCZOS),
         os.path.join(RES, "drawable-xhdpi", "banner.png"))
    # In-app logo (menu bar, splash) and the web page's.
    save(fit(logo, (logo.width // 2, logo.height // 2)), os.path.join(RES, "drawable-nodpi", "logo_redux.png"))
    save(fit(logo, (480, round(480 * logo.height / logo.width))), os.path.join(WEB, "logo.png"))
    save(icon(64, src), os.path.join(WEB, "icon.png"))


if __name__ == "__main__":
    main()
