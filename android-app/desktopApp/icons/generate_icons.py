#!/usr/bin/env python3
"""Generate the Mobile Agent desktop app icons (Phase 8 packaging).

Produces a single brand-blue rounded-square logo with a white chat-bubble glyph
and emits every format jpackage needs, all from one deterministic master so the
tray, window, and per-OS installer icons stay visually identical:

  icon.png    512x512  — Linux (.deb/.rpm) + the in-app tray/window icon resource
  icon.ico             — Windows (.msi/.exe), multi-resolution
  icon.icns            — macOS (.dmg/.pkg)

Reproducible (no randomness). Re-run after changing the artwork:

    python3 desktopApp/icons/generate_icons.py

Requires Pillow (PIL). The generated icons are committed so the build needs no
image toolchain; this script is the source of truth for regenerating them.
"""
from __future__ import annotations

import shutil
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
RES_DIR = HERE.parent / "src" / "main" / "resources"

BRAND_BLUE = (0x3B, 0x5B, 0xDB, 0xFF)  # matches the former procedural tray square
WHITE = (0xFF, 0xFF, 0xFF, 0xFF)
MASTER = 1024


def _rounded_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=255)
    return mask


def render_master() -> Image.Image:
    """A brand-blue rounded square with a white speech bubble + three dots."""
    img = Image.new("RGBA", (MASTER, MASTER), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Brand-blue rounded-square plate (full-bleed with generous corner radius).
    plate = Image.new("RGBA", (MASTER, MASTER), BRAND_BLUE)
    img.paste(plate, (0, 0), _rounded_mask(MASTER, radius=224))

    # White chat bubble body.
    bx0, by0, bx1, by1 = 224, 248, 800, 660
    draw.rounded_rectangle((bx0, by0, bx1, by1), radius=96, fill=WHITE)
    # Bubble tail (bottom-left), drawn as a triangle blended into the body.
    draw.polygon([(352, by1 - 8), (352, by1 + 132), (492, by1 - 8)], fill=WHITE)

    # Three "typing" dots, brand-blue, centered in the bubble.
    cy = (by0 + by1) // 2
    r = 46
    for cx in (392, 512, 632):
        draw.ellipse((cx - r, cy - r, cx + r, cy + r), fill=BRAND_BLUE)

    return img


def main() -> None:
    master = render_master()
    RES_DIR.mkdir(parents=True, exist_ok=True)

    # 512px PNG — Linux installer icon + the in-app tray/window resource.
    png = master.resize((512, 512), Image.LANCZOS)
    png_path = HERE / "icon.png"
    png.save(png_path, format="PNG")
    shutil.copyfile(png_path, RES_DIR / "icon.png")

    # Windows .ico — multi-resolution.
    master.save(
        HERE / "icon.ico",
        format="ICO",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )

    # macOS .icns — Pillow derives the standard sizes from the 1024 master.
    master.save(HERE / "icon.icns", format="ICNS")

    print(f"wrote {png_path}")
    print(f"wrote {HERE / 'icon.ico'}")
    print(f"wrote {HERE / 'icon.icns'}")
    print(f"wrote {RES_DIR / 'icon.png'} (in-app tray/window resource)")


if __name__ == "__main__":
    main()
