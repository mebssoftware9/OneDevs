#!/usr/bin/env python3
"""Fetch Geist and generate the static weights used by res/font.

Geist ships as a variable font. Compose can load one, but that path needs an
experimental API and behaves differently across API levels, so the weights are
instanced statically here and subsetted to Latin instead — the app then loads
ordinary font resources that behave identically on every device.

Geist is SIL OFL 1.1, (c) 2023 Vercel, in collaboration with basement.studio.
"""
import io, pathlib, urllib.request
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset

ROOT = pathlib.Path(__file__).resolve().parent.parent
FONT_DIR = ROOT / "app/src/main/res/font"
LICENSES = ROOT / "art/licenses"
SRC = ("https://raw.githubusercontent.com/vercel/geist-font/main/packages/next/"
       "dist/fonts/geist-sans/Geist-Variable.ttf")
LIC = "https://raw.githubusercontent.com/vercel/geist-font/main/LICENSE.txt"
WEIGHTS = {"regular": 400, "medium": 500, "semibold": 600, "bold": 700}
UNICODES = ("U+0020-007E,U+00A0-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,"
            "U+02DC,U+2000-206F,U+2074,U+20AC,U+2122,U+2191-2193,U+2212,U+2215,"
            "U+FEFF,U+FFFD")
NEEDED = "OneDevsCakrtgum0123456789 \u2014\u00b7\u2019%+-"

def get(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        assert r.status == 200, f"{url} -> {r.status}"
        return r.read()

raw = get(SRC)
assert len(raw) > 50_000, f"Geist download looks truncated ({len(raw)} bytes)"
FONT_DIR.mkdir(parents=True, exist_ok=True)
LICENSES.mkdir(parents=True, exist_ok=True)
(LICENSES / "Geist-OFL.txt").write_bytes(get(LIC))

total = 0
for name, wght in WEIGHTS.items():
    static = instancer.instantiateVariableFont(
        TTFont(io.BytesIO(raw)), {"wght": wght}, inplace=False, updateFontNames=True)
    tmp = FONT_DIR / f"geist_{name}.tmp.ttf"
    out = FONT_DIR / f"geist_{name}.ttf"
    static.save(tmp)
    subset.main([str(tmp), f"--unicodes={UNICODES}", "--layout-features=*",
                 f"--output-file={out}"])
    tmp.unlink()
    cmap = TTFont(out).getBestCmap()
    missing = sorted({c for c in NEEDED if ord(c) not in cmap})
    assert not missing, f"geist_{name}: missing glyphs {missing}"
    total += out.stat().st_size
    print(f"  geist_{name}.ttf  {out.stat().st_size // 1024} KB")
print(f"wrote {len(WEIGHTS)} static weights, {total // 1024} KB total + OFL licence")
