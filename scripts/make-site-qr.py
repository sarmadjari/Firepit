#!/usr/bin/env python3
"""make-site-qr.py: write the demo QR codes the website's invite screen shows (website/assets/qr/*.svg).

The site's invite screen refreshes every 8 seconds, as the app's does. A real invite would be pointless on a web
page, so each refresh shows one of these instead: scanning it just says hello, in one of five languages.

Error correction M, as both apps use. Version 6 gives the density of a real invite without being hard to scan from a
screen. UTF-8 with an ECI marker, so strict readers decode Arabic and Chinese correctly too.

Usage: scripts/make-site-qr.py [--png DIR]      (--png also writes PNGs, for checking with a QR reader)
Needs segno (python3 -m pip install segno); --png needs Pillow too.
"""
import argparse
import pathlib

import segno

GREETINGS = [
    ("en", "Hello Firepit"),
    ("es", "Hola Firepit"),
    ("fr", "Bonjour Firepit"),
    ("ar", "مرحباً Firepit"),
    ("zh", "你好 Firepit"),
]
VERSION = 6
BORDER = 1  # the white card around the code adds the rest of the quiet zone

parser = argparse.ArgumentParser()
parser.add_argument("--png", type=pathlib.Path, help="also write PNGs here")
args = parser.parse_args()

out = pathlib.Path(__file__).resolve().parent.parent / "website" / "assets" / "qr"
out.mkdir(parents=True, exist_ok=True)

for lang, text in GREETINGS:
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
    print(f"{lang}: {text!r} -> hello-{lang}.svg ({qr.designator}, {len(svg)} bytes)")

    if args.png:
        args.png.mkdir(parents=True, exist_ok=True)
        qr.save(args.png / f"hello-{lang}.png", scale=8, border=4)
