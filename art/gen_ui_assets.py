#!/usr/bin/env python3
"""Generate in-app icons: Phosphor vector drawables + the DevCoin bitmap.

Phosphor icons (MIT, phosphor-icons/core) are fetched as SVG and transcoded to
VectorDrawable — single path, 256 viewBox, no transforms, so the conversion is
lossless. The DevCoin comes from the master artwork cropped to its own alpha
bounds so it fills its box instead of floating in the master's padding.
"""
import pathlib, re, urllib.request
import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
COIN = ROOT / "art/source/devcoin_master.png"
BASE = "https://raw.githubusercontent.com/phosphor-icons/core/main/assets"
ICONS = ["squares-four", "users-three", "list-checks", "wrench", "user-circle"]
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
COIN_DP, HERO_DP = 28, 96

def fetch(url):
    with urllib.request.urlopen(url, timeout=30) as r:
        assert r.status == 200, f"{url} -> {r.status}"
        return r.read().decode()

def to_vector(svg, name):
    vb = re.search(r'viewBox="0 0 ([\d.]+) ([\d.]+)"', svg)
    assert vb, f"{name}: no viewBox"
    paths = re.findall(r'<path[^>]*\sd="([^"]+)"', svg)
    assert paths, f"{name}: no path data"
    assert "<linearGradient" not in svg and "transform=" not in svg, f"{name}: unsupported SVG feature"
    body = "".join(
        f'    <path\n        android:fillColor="#FFFFFFFF"\n        android:pathData="{p}" />\n'
        for p in paths)
    return (f'<!-- Phosphor Icons (MIT) - phosphor-icons/core - {name} -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="24dp"\n'
            f'    android:height="24dp"\n'
            f'    android:viewportWidth="{vb.group(1)}"\n'
            f'    android:viewportHeight="{vb.group(2)}">\n{body}</vector>\n')

out = RES / "drawable"
out.mkdir(parents=True, exist_ok=True)
n = 0
for icon in ICONS:
    for weight, suffix in (("bold", ""), ("fill", "_fill")):
        src = f"{icon}-{weight}"
        xml = to_vector(fetch(f"{BASE}/{weight}/{src}.svg"), f"{weight}/{src}")
        (out / f"ic_{icon.replace('-', '_')}{suffix}.xml").write_text(xml)
        n += 1
print(f"wrote {n} Phosphor vector drawables")

assert COIN.exists(), f"missing {COIN}"
coin = Image.open(COIN).convert("RGBA")
# getbbox() would include the artwork's faint drop shadow; bound the coin itself
alpha = np.asarray(coin.getchannel("A"))
ys, xs = np.where(alpha > 25)
assert len(xs), "DevCoin master has no opaque pixels"
box = (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)
coin = coin.crop(box)
side = max(coin.size)
square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
square.paste(coin, ((side - coin.size[0]) // 2, (side - coin.size[1]) // 2), coin)
print(f"coin cropped {box} -> {coin.size}, squared to {side}px")

for name, dp in (("ic_devcoin", COIN_DP), ("ic_devcoin_hero", HERO_DP)):
    for dens, f in DENSITIES.items():
        px = int(round(dp * f))
        d = RES / f"drawable-{dens}"
        d.mkdir(parents=True, exist_ok=True)
        for stale in d.glob(f"{name}.*"):
            stale.unlink()
        enc = dict(lossless=True) if dp <= 32 else dict(quality=95)
        square.resize((px, px), Image.LANCZOS).save(d / f"{name}.webp", format="WEBP",
                                                    method=6, **enc)
print("wrote DevCoin at", COIN_DP, "dp and", HERO_DP, "dp across", len(DENSITIES), "densities")
