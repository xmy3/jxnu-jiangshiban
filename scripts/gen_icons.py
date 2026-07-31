"""One-shot script: build mipmap PNGs for the new launcher icon.

Reads ChatGPT Image.png at the repo root and writes:
  app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/
    ic_launcher_foreground.png   (source scaled to safe zone, transparent edges)
    ic_launcher_monochrome.png   (red-channel silhouette for Android 13+ themed icons)
    ic_splash_logo.png           (white silhouette sized for Android 12+ SplashScreen)

Run once; commit the output. Adaptive-icon XMLs and the background drawable are
edited by hand, not by this script.
"""

import os
from PIL import Image
import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, "ChatGPT Image.png")
RES = os.path.join(REPO, "app", "src", "main", "res")

# Adaptive icon canvas is 108dp; foreground content should fit inside the
# 66dp safe zone. 72% gives a small extra margin for launchers that crop hard.
SAFE_FRAC = 0.72

DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

# Android 12+ SplashScreen (no icon background): 288dp canvas with the icon
# rendered inside a 192dp circle. 192/288 == 0.6667.
SPLASH_DENSITIES = {
    "mdpi": 288,
    "hdpi": 432,
    "xhdpi": 576,
    "xxhdpi": 864,
    "xxxhdpi": 1152,
}
SPLASH_INNER_FRAC = 192 / 288


def make_monochrome(src: Image.Image) -> Image.Image:
    """White silhouette of the red graphic on transparent background.

    Heuristic: how 'red' a pixel is = R - max(G, B). Scale that to alpha so
    anti-aliased edges fade smoothly. Output RGB is pure white because the
    Android 13+ themed-icon system tints it to the user's wallpaper colour.
    """
    arr = np.array(src.convert("RGBA")).astype(np.int16)
    r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    redness = np.clip(r - np.maximum(g, b), 0, 255)
    alpha = np.clip(redness * 4, 0, 255).astype(np.uint8)

    out = np.zeros_like(arr, dtype=np.uint8)
    out[:, :, 0] = 255
    out[:, :, 1] = 255
    out[:, :, 2] = 255
    out[:, :, 3] = alpha
    return Image.fromarray(out, "RGBA")


def main() -> None:
    src = Image.open(SRC).convert("RGBA")
    mono = make_monochrome(src)

    for density, canvas in DENSITIES.items():
        outdir = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(outdir, exist_ok=True)

        inner = int(canvas * SAFE_FRAC)
        offset = (canvas - inner) // 2

        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        scaled = src.resize((inner, inner), Image.LANCZOS)
        fg.paste(scaled, (offset, offset), scaled)
        fg.save(os.path.join(outdir, "ic_launcher_foreground.png"), optimize=True)

        mc = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        mono_scaled = mono.resize((inner, inner), Image.LANCZOS)
        mc.paste(mono_scaled, (offset, offset), mono_scaled)
        mc.save(os.path.join(outdir, "ic_launcher_monochrome.png"), optimize=True)

        print(f"mipmap-{density}: {canvas}x{canvas} inner={inner}")

    for density, canvas in SPLASH_DENSITIES.items():
        outdir = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(outdir, exist_ok=True)

        inner = round(canvas * SPLASH_INNER_FRAC)
        offset = (canvas - inner) // 2

        splash = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        mono_scaled = mono.resize((inner, inner), Image.LANCZOS)
        splash.paste(mono_scaled, (offset, offset), mono_scaled)
        splash.save(os.path.join(outdir, "ic_splash_logo.png"), optimize=True)

        print(f"splash mipmap-{density}: {canvas}x{canvas} inner={inner}")


if __name__ == "__main__":
    main()
