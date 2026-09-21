#!/usr/bin/env python3
"""Generate OneDevs launcher + splash assets from the master artwork.

The original pixels ship unchanged: the mark is matted out of icon_master.png
and the background gradient is refitted from that same file. Nothing is redrawn.
Requires only numpy + Pillow.
"""
import pathlib
import numpy as np
from PIL import Image, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "art/source/icon_master.png"
RES = ROOT / "app/src/main/res"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
CANVAS_DP, SAFE_RADIUS_DP = 108.0, 33.0      # keep content inside the 66dp safe circle
SPLASH_CANVAS_DP, SPLASH_ART_DP = 288.0, 192.0   # icon with no icon-background

assert SRC.exists(), f"missing {SRC}"
img = Image.open(SRC).convert("RGB")
w, h = img.size
assert w == h, f"master icon must be square, got {w}x{h}"
src = np.asarray(img).astype(np.float64)

# --- model the smooth background gradient, then matte whatever departs from it
hue = np.asarray(img.convert("HSV"))[..., 0].astype(int)
seed = (hue >= 158).ravel()
assert seed.mean() > 0.3, "background seed too small - is this the right artwork?"
yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
X, Y = xx / w - .5, yy / h - .5
A = np.stack([((X ** i) * (Y ** j)).ravel() for i in range(5) for j in range(5 - i)], 1)
model = np.zeros(src.shape, np.float64)
for c in range(3):
    coef, *_ = np.linalg.lstsq(A[seed], src[..., c].ravel()[seed], rcond=None)
    model[..., c] = (A @ coef).reshape(h, w)

mask = ((np.linalg.norm(src - model, axis=2) > 60) * 255).astype("uint8")
m = Image.fromarray(mask)
m = m.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(9))   # close
m = m.filter(ImageFilter.MinFilter(9)).filter(ImageFilter.MaxFilter(9))   # open
mask = np.asarray(m)

# keep only what is connected to the centre: glow near the frame is not the mark
centre = np.zeros_like(mask)
centre[int(.3 * h):int(.7 * h), int(.3 * w):int(.7 * w)] = 1
grown = mask * centre
prev, steps = -1, 0
while grown.sum() != prev:
    prev = grown.sum()
    grown = np.asarray(Image.fromarray(grown).filter(ImageFilter.MaxFilter(9))) & mask
    steps += 1
    assert steps < 400, "matte reconstruction did not converge"
mask = grown

ys, xs = np.where(mask > 0)
x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
radius = float(np.hypot(xs - cx, ys - cy).max())
assert 0.25 * w < radius < 0.48 * w, f"matte looks wrong: mark radius {radius:.0f}px of {w}px"
assert abs(cx - w / 2) < 0.04 * w and abs(cy - h / 2) < 0.04 * h, "mark is not centred in the master"
print(f"mark {x1-x0+1}x{y1-y0+1}px  centre ({cx:.0f},{cy:.0f})  radius {radius:.0f}px")

half = int(np.ceil(radius)) + 2
L, T, side = int(round(cx - half)), int(round(cy - half)), half * 2
rgba = np.dstack([src.astype("uint8"), mask])
pad = ((max(0, -T), max(0, T + side - h)), (max(0, -L), max(0, L + side - w)), (0, 0))
rgba = np.pad(rgba, pad, mode="edge")
mark = Image.fromarray(rgba[T + pad[0][0]:T + pad[0][0] + side,
                            L + pad[1][0]:L + pad[1][0] + side])
assert mark.size == (side, side)
bg_img = Image.fromarray(np.clip(model, 0, 255).astype("uint8"))

def write(folder, name, im, **kw):
    d = RES / folder
    d.mkdir(parents=True, exist_ok=True)
    for stale in d.glob(pathlib.Path(name).stem + ".*"):
        stale.unlink()
    im.save(d / name, **kw)

LL = dict(format="WEBP", lossless=True, method=6)
LOSSY = dict(format="WEBP", quality=95, method=6)
n = 0
for dens, f in DENSITIES.items():
    canvas = int(round(CANVAS_DP * f))
    content = int(round(2 * SAFE_RADIUS_DP * f))
    mk = mark.resize((content, content), Image.LANCZOS)
    pos = ((canvas - content) // 2,) * 2

    fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    fg.paste(mk, pos, mk)
    write(f"mipmap-{dens}", "ic_launcher_foreground.webp", fg, **LL)

    write(f"mipmap-{dens}", "ic_launcher_background.webp",
          bg_img.resize((canvas, canvas), Image.LANCZOS), **LL)

    mono = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    mono.paste(Image.new("RGBA", (content, content), (0, 0, 0, 255)), pos, mk.getchannel("A"))
    write(f"mipmap-{dens}", "ic_launcher_monochrome.webp", mono, **LL)

    sc, sa = int(round(SPLASH_CANVAS_DP * f)), int(round(SPLASH_ART_DP * f))
    sp = Image.new("RGBA", (sc, sc), (0, 0, 0, 0))
    sp.paste(img.resize((sa, sa), Image.LANCZOS).convert("RGBA"), ((sc - sa) // 2,) * 2)
    write(f"drawable-{dens}", "splash_icon.webp", sp, **LOSSY)
    n += 4

(ROOT / "art/play").mkdir(parents=True, exist_ok=True)
img.resize((512, 512), Image.LANCZOS).save(ROOT / "art/play/play_icon_512.png", optimize=True)

deep = np.clip(model, 0, 255).astype(int)
corner = min([deep[8, 8], deep[8, w - 9], deep[h - 9, 8], deep[h - 9, w - 9]], key=sum)
print("deep brand colour #%02X%02X%02X" % tuple(int(v) for v in corner))
print(f"wrote {n} density files + art/play/play_icon_512.png")
