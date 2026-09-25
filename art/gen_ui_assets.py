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
BOT = ROOT / "art/source/devbot_master.png"
BASE = "https://raw.githubusercontent.com/phosphor-icons/core/main/assets"
ICONS = {
    "squares-four": ("bold", "fill"),
    "list-checks": ("bold", "fill"),
    "rocket-launch": ("bold", "fill"),
    "flask": ("bold", "fill"),
    "medal": ("bold", "fill"),
    # DevBot's status-bar silhouette. Android flattens notification icons to
    # flat white at 24dp, where a solid shape survives and an outline breaks
    # up, so the bold weight would only ever be dead weight.
    "robot": ("fill",),
    # The top bar's profile action. Bold only: it is a button, never a selected
    # tab, so there is no state for a fill weight to carry.
    "user-circle": ("bold",),
    # Launch actions. Bold only, like everything else that is a control rather
    # than a tab: none of them has a selected state to swap a fill weight into.
    "users-three": ("bold",),
    "target": ("bold",),
    "globe": ("bold",),
    "lock": ("bold",),
    "arrow-right": ("bold",),
    "coins": ("bold",),
    # Badge catalogue. One glyph per badge, all bold: a badge is a badge whether
    # or not it has been earned, and the earned state is carried by colour.
    "seal-check": ("bold",), "test-tube": ("bold",), "trophy": ("bold",),
    "fire": ("bold",), "chat-circle-dots": ("bold",), "lightbulb": ("bold",),
    "bug": ("bold",), "handshake": ("bold",), "users": ("bold",),
    "star": ("bold", "fill"), "clipboard-text": ("bold",), "shield-check": ("bold",),
    "device-mobile": ("bold",), "user-check": ("bold",), "lock-open": ("bold",),
    "check": ("bold",),
    # Board. The star is the only glyph in the app that ships filled without a
    # bold twin: a rating star is a mark, not a toggle, and an outlined one at
    # 12dp reads as an empty rating.
    "caret-right": ("bold",), "pulse": ("bold",),
    "camera": ("bold",), "graduation-cap": ("bold",),
}
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
COIN_DP, HERO_DP = 32, 96
BOT_DP = 96

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
for icon, weights in ICONS.items():
    for weight in weights:
        suffix = "" if weight == "bold" else "_fill"
        src = f"{icon}-{weight}"
        xml = to_vector(fetch(f"{BASE}/{weight}/{src}.svg"), f"{weight}/{src}")
        (out / f"ic_{icon.replace('-', '_')}{suffix}.xml").write_text(xml)
        n += 1
print(f"wrote {n} Phosphor vector drawables")

assert COIN.exists(), f"missing {COIN}"
coin = Image.open(COIN).convert("RGBA")
# getbbox() would include the artwork's faint drop shadow; bound the coin itself
alpha = np.asarray(coin.getchannel("A"))
# A master with no transparency is a tile, not a coin. The previous one was an
# opaque white square: the chip's circular clip hid that and the 96dp hero on
# the wallet did not, so dark mode drew a white block. Fail here instead.
assert alpha.min() < 25, (
    f"{COIN.name} is fully opaque - the coin needs a transparent background, "
    "or it ships as a square tile at hero size.")
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

# DevBot: crop to his own alpha bounds, then pad to a square so the UI can size
# him in one dimension without stretching a 1.46:1 render.
assert BOT.exists(), f"missing {BOT}"
bot = Image.open(BOT).convert("RGBA")
ba = np.asarray(bot.getchannel("A"))
bys, bxs = np.where(ba > 30)
assert len(bxs), "DevBot master has no opaque pixels"
bot = bot.crop((int(bxs.min()), int(bys.min()), int(bxs.max()) + 1, int(bys.max()) + 1))
bside = max(bot.size)
bsq = Image.new("RGBA", (bside, bside), (0, 0, 0, 0))
bsq.paste(bot, ((bside - bot.size[0]) // 2, (bside - bot.size[1]) // 2), bot)
for dens, f in DENSITIES.items():
    px = int(round(BOT_DP * f))
    d = RES / f"drawable-{dens}"
    d.mkdir(parents=True, exist_ok=True)
    for stale in d.glob("ic_devbot.*"):
        stale.unlink()
    bsq.resize((px, px), Image.LANCZOS).save(d / "ic_devbot.webp", format="WEBP",
                                             quality=95, method=6)
print(f"wrote DevBot at {BOT_DP}dp (cropped {bot.size}, squared {bside}px)")
