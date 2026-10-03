#!/usr/bin/env python3
"""make-site-qr.py: write the demo QR codes the website's invite screen shows (website/assets/qr/*.svg).

The site's invite screen refreshes every 8 seconds, as the app's does. A real invite would be pointless on a web
page, so each refresh shows one of these instead: scanning it just says hello, in one of eleven languages, in turn
from west to east.

Right-to-left greetings are wrapped in RIGHT-TO-LEFT EMBEDDING ... POP DIRECTIONAL FORMATTING (U+202B ... U+202C).
Whether a phone shows "مرحباً Firepit" with the Arabic on the right depends on how its scanner lays out the text:
one that decides from the first letter gets it right, one that lays everything out left to right does not. The
embedding makes it right in both: the greeting starts on the right and "Firepit" follows to its left. The marks are
invisible. Left-to-right greetings need none: every letter in them is left-to-right already.

Error correction M, as both apps use. Version 6 gives the density of a real invite without being hard to scan from a
screen. UTF-8 with an ECI marker, so strict readers decode every script correctly.

Usage: scripts/make-site-qr.py [--png DIR]      (--png also writes PNGs, for checking with a QR reader)
Needs segno (python3 -m pip install segno); --png needs Pillow too.
"""
import argparse
import pathlib

import segno

RTL_EMBEDDING, POP_FORMATTING = "\u202b", "\u202c"

# (file name, greeting, right to left), in the order the site shows them.
GREETINGS = [
    ("en", "Hello Firepit", False),
    ("es", "Hola Firepit", False),
    ("fr", "Bonjour Firepit", False),
    ("de", "Hallo Firepit", False),
    ("no", "Hei Firepit", False),
    ("ru", "Привет Firepit", False),
    ("ar", "مرحباً Firepit", True),
    ("hi", "नमस्ते Firepit", False),
    ("zh", "你好 Firepit", False),
    ("ko", "안녕하세요 Firepit", False),
    ("ja", "こんにちは Firepit", False),
]
VERSION = 6
BORDER = 1  # the white card around the code adds the rest of the quiet zone

parser = argparse.ArgumentParser()
parser.add_argument("--png", type=pathlib.Path, help="also write PNGs here")
args = parser.parse_args()

out = pathlib.Path(__file__).resolve().parent.parent / "website" / "assets" / "qr"
out.mkdir(parents=True, exist_ok=True)

for lang, greeting, rtl in GREETINGS:
    text = f"{RTL_EMBEDDING}{greeting}{POP_FORMATTING}" if rtl else greeting
    qr = segno.make(text, error="m", version=VERSION, encoding="utf-8", eci=True, boost_error=False)
    rows = [list(row) for row in qr.matrix]
    size = len(rows) + 2 * BORDER

    # One filled rectangle per run of dark modules: no strokes, so no hairline seams at any scale.
    path = []
    for y, row in enumerate(rows):
        x = 0
        while x < len(row):
            if row[x]:
                start = x
                while x < len(row) and row[x]:
                    x += 1
                path.append(f"M{start + BORDER} {y + BORDER}h{x - start}v1h-{x - start}z")
            else:
                x += 1
    svg = (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" shape-rendering="crispEdges">'
        f'<path fill="#000" d="{"".join(path)}"/></svg>\n'
    )
    (out / f"hello-{lang}.svg").write_text(svg, encoding="utf-8")
    print(f"{lang}: {greeting!r}{' (right to left)' if rtl else ''} -> hello-{lang}.svg ({qr.designator}, {len(svg)} bytes)")

    if args.png:
        args.png.mkdir(parents=True, exist_ok=True)
        qr.save(args.png / f"hello-{lang}.png", scale=8, border=4)
