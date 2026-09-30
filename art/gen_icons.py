#!/usr/bin/env python3
"""Generate OneDevs launcher + splash assets from the master artwork.

The original pixels ship unchanged: the launcher shows icon_master.png framed
exactly as the Play Store icon is, with the mark matted out onto its own layer
so it can move over the background. Nothing is redrawn. Requires only numpy +
Pillow.
"""
import pathlib
import numpy as np
from PIL import Image, ImageFilter

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "art/source/icon_master.png"
RES = ROOT / "app/src/main/res"
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
# An adaptive icon is 108dp a side and shows the middle 72dp. The whole master
# fills those 72dp, as it fills the Play Store icon, so the mark sits in the
# launcher at the size and place it has in the store.
CANVAS_DP, VIEW_DP = 108.0, 72.0
# Splash sizes for an icon WITH an icon background: 240dp canvas, artwork
# inside a 160dp circle. The mark's crop is already inscribed in a circle of
# its own side length, so pasting it at 160dp fills that circle exactly.
SPLASH_CANVAS_DP, SPLASH_ART_DP = 240.0, 160.0

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

# --- launcher layers, at master resolution on the full 108dp canvas
full = int(round(w * CANVAS_DP / VIEW_DP))
off = (full - w) // 2
# Background: the master itself, shadows and all. Only where the mark stood
# is it the fitted gradient, which shows if the layers move apart.
back = src.copy()
back[mask > 0] = model[mask > 0]
back = Image.fromarray(np.clip(back, 0, 255).astype("uint8"))
# The ring outside the 72dp is seen only while the icon moves: the master's
# edge colours carried outward and softened.
launcher_bg = Image.fromarray(np.pad(np.asarray(back), ((off, full - w - off), (off, full - w - off), (0, 0)),
                                     mode="edge")).filter(ImageFilter.GaussianBlur(off / 4))
launcher_bg.paste(back, (off, off))
# Foreground: the mark's own pixels where the master has them. Over the
# background the two make the master again, pixel for pixel.
front = np.zeros((full, full, 4), "uint8")
front[off:off + h, off:off + w] = np.dstack([src.astype("uint8"), mask])
launcher_fg = Image.fromarray(front)
again = Image.alpha_composite(launcher_bg.convert("RGBA"), launcher_fg).crop((off, off, off + w, off + h))
assert np.abs(np.asarray(again.convert("RGB")).astype(int) - src).max() <= 1, "layers do not rebuild the master"

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
    fg = launcher_fg.resize((canvas, canvas), Image.LANCZOS)
    write(f"mipmap-{dens}", "ic_launcher_foreground.webp", fg, **LL)

    write(f"mipmap-{dens}", "ic_launcher_background.webp",
          launcher_bg.resize((canvas, canvas), Image.LANCZOS), **LL)

    # Themed icons: the same mark, same size, in the one colour the system paints.
    mono = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 255))
    mono.putalpha(fg.getchannel("A"))
    write(f"mipmap-{dens}", "ic_launcher_monochrome.webp", mono, **LL)

    # The mark on transparent, not the composited square. The splash window is
    # pure white or true black, and windowSplashScreenIconBackgroundColor paints
    # the brand circle behind this, so a baked-in square would sit inside it.
    sc, sa = int(round(SPLASH_CANVAS_DP * f)), int(round(SPLASH_ART_DP * f))
    sp = Image.new("RGBA", (sc, sc), (0, 0, 0, 0))
    sk = mark.resize((sa, sa), Image.LANCZOS)
    sp.paste(sk, ((sc - sa) // 2,) * 2, sk)
    write(f"drawable-{dens}", "splash_icon.webp", sp, **LL)
    n += 4

(ROOT / "art/play").mkdir(parents=True, exist_ok=True)
img.resize((512, 512), Image.LANCZOS).save(ROOT / "art/play/play_icon_512.png", optimize=True)

deep = np.clip(model, 0, 255).astype(int)
corner = min([deep[8, 8], deep[8, w - 9], deep[h - 9, 8], deep[h - 9, w - 9]], key=sum)
print("deep brand colour #%02X%02X%02X" % tuple(int(v) for v in corner))
print(f"wrote {n} density files + art/play/play_icon_512.png")
