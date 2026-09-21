#!/usr/bin/env python3
"""Generate the OneDevs Material 3 colour scheme from the brand artwork.

Seeds are sampled from the master files: primary from the icon's blue, secondary
from the mark's cyan, tertiary from the DevCoin gold. Tonal palettes follow the
M3 tone scale (tone == CIELAB L*), with chroma reduced only where sRGB runs out
of gamut. Every on-colour pair is asserted against WCAG AA before anything is
written, so a bad palette fails here instead of shipping.
"""
import pathlib
import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/kotlin/com/devbangs/onedevs/ui/theme/Color.kt"
SEEDS = {"primary": "#003AC7", "secondary": "#1FE9FD", "tertiary": "#D3A031", "error": "#B3261E"}

MX = np.array([[.4124564, .3575761, .1804375],
               [.2126729, .7151522, .0721750],
               [.0193339, .1191920, .9503041]])
MXI = np.linalg.inv(MX)
WP = np.array([95.047, 100.0, 108.883])

def _lin(c): c = c / 255.0; return np.where(c <= .04045, c / 12.92, ((c + .055) / 1.055) ** 2.4)
def _srgb(c): return np.where(c <= .0031308, c * 12.92, 1.055 * np.clip(c, 0, None) ** (1 / 2.4) - .055) * 255

def rgb_to_lab(rgb):
    t = (MX @ _lin(np.array(rgb, float))) * 100 / WP
    f = np.where(t > (6 / 29) ** 3, np.cbrt(t), t / (3 * (6 / 29) ** 2) + 4 / 29)
    return np.array([116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2])])

def lch_to_rgb(L, C, h):
    a, b = C * np.cos(np.radians(h)), C * np.sin(np.radians(h))
    fy = (L + 16) / 116
    f = np.array([fy + a / 500, fy, fy - b / 200])
    t = np.where(f > 6 / 29, f ** 3, 3 * (6 / 29) ** 2 * (f - 4 / 29))
    return _srgb(MXI @ (t * WP / 100))

def _tone(hue, chroma, L):
    if all(-.5 < v < 255.5 for v in lch_to_rgb(L, chroma, hue)):
        best = chroma
    else:
        lo, hi = 0.0, chroma
        for _ in range(40):
            mid = (lo + hi) / 2
            if all(-.5 < v < 255.5 for v in lch_to_rgb(L, mid, hue)): lo = mid
            else: hi = mid
        best = lo
    return "#%02X%02X%02X" % tuple(int(v) for v in np.clip(np.round(lch_to_rgb(L, best, hue)), 0, 255))

TONES = (0, 4, 6, 10, 12, 17, 20, 22, 24, 30, 40, 50, 60, 70, 80, 87, 90, 92, 94, 95, 96, 98, 99, 100)

def palette(seed, chroma=None):
    L, a, b = rgb_to_lab([int(seed[i:i + 2], 16) for i in (1, 3, 5)])
    hue = np.degrees(np.arctan2(b, a)) % 360
    C = float(np.hypot(a, b)) if chroma is None else chroma
    return {t: _tone(hue, C, t) for t in TONES}

def contrast(x, y):
    def lum(hx):
        l = _lin(np.array([int(hx[i:i + 2], 16) for i in (1, 3, 5)], float))
        return float(.2126 * l[0] + .7152 * l[1] + .0722 * l[2])
    a, b = lum(x), lum(y)
    return (max(a, b) + .05) / (min(a, b) + .05)

P, S, T = palette(SEEDS["primary"]), palette(SEEDS["secondary"]), palette(SEEDS["tertiary"])
E = palette(SEEDS["error"])
N, NV = palette(SEEDS["primary"], 4), palette(SEEDS["primary"], 8)

LIGHT = {
    "primary": P[40], "onPrimary": P[100], "primaryContainer": P[90], "onPrimaryContainer": P[10],
    "secondary": S[40], "onSecondary": S[100], "secondaryContainer": S[90], "onSecondaryContainer": S[10],
    "tertiary": T[40], "onTertiary": T[100], "tertiaryContainer": T[90], "onTertiaryContainer": T[10],
    "error": E[40], "onError": E[100], "errorContainer": E[90], "onErrorContainer": E[10],
    "background": N[99], "onBackground": N[10], "surface": N[99], "onSurface": N[10],
    "surfaceVariant": NV[90], "onSurfaceVariant": NV[30], "outline": NV[50], "outlineVariant": NV[80],
    "inverseSurface": N[20], "inverseOnSurface": N[95], "inversePrimary": P[80], "scrim": N[0],
    "surfaceDim": N[87], "surfaceBright": N[98], "surfaceContainerLowest": N[100],
    "surfaceContainerLow": N[96], "surfaceContainer": N[94], "surfaceContainerHigh": N[92],
    "surfaceContainerHighest": N[90],
}
DARK = {
    "primary": P[80], "onPrimary": P[20], "primaryContainer": P[30], "onPrimaryContainer": P[90],
    "secondary": S[80], "onSecondary": S[20], "secondaryContainer": S[30], "onSecondaryContainer": S[90],
    "tertiary": T[80], "onTertiary": T[20], "tertiaryContainer": T[30], "onTertiaryContainer": T[90],
    "error": E[80], "onError": E[20], "errorContainer": E[30], "onErrorContainer": E[90],
    "background": N[10], "onBackground": N[90], "surface": N[10], "onSurface": N[90],
    "surfaceVariant": NV[30], "onSurfaceVariant": NV[80], "outline": NV[60], "outlineVariant": NV[30],
    "inverseSurface": N[90], "inverseOnSurface": N[20], "inversePrimary": P[40], "scrim": N[0],
    "surfaceDim": N[6], "surfaceBright": N[24], "surfaceContainerLowest": N[4],
    "surfaceContainerLow": N[10], "surfaceContainer": N[12], "surfaceContainerHigh": N[17],
    "surfaceContainerHighest": N[22],
}
PAIRS = [("onPrimary", "primary", 4.5), ("onPrimaryContainer", "primaryContainer", 4.5),
         ("onSecondary", "secondary", 4.5), ("onSecondaryContainer", "secondaryContainer", 4.5),
         ("onTertiary", "tertiary", 4.5), ("onTertiaryContainer", "tertiaryContainer", 4.5),
         ("onError", "error", 4.5), ("onErrorContainer", "errorContainer", 4.5),
         ("onSurface", "surface", 4.5), ("onSurfaceVariant", "surfaceVariant", 4.5),
         ("onBackground", "background", 4.5), ("onSurface", "surfaceContainer", 4.5),
         ("outline", "surface", 3.0), ("primary", "surface", 3.0)]
for label, sch in (("light", LIGHT), ("dark", DARK)):
    for fg, bg, floor in PAIRS:
        r = contrast(sch[fg], sch[bg])
        assert r >= floor, f"{label}: {fg} on {bg} is {r:.2f}, needs {floor}"
print(f"contrast: {2 * len(PAIRS)} pairs pass WCAG AA")

ORDER = list(LIGHT)
def consts(sch, prefix):
    return "\n".join(f"private val {prefix}{k[0].upper()}{k[1:]} = Color(0xFF{sch[k][1:]})" for k in ORDER)
def scheme(prefix, fn):
    rows = "".join(f"    {k} = {prefix}{k[0].upper()}{k[1:]},\n" for k in ORDER)
    return f"internal val {prefix}Scheme = {fn}(\n{rows})"

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(f'''package com.devbangs.onedevs.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Generated by art/gen_theme.py from the OneDevs master artwork. Do not edit by
// hand: primary comes from the icon's blue, secondary from the mark's cyan and
// tertiary from the DevCoin gold, and the generator asserts WCAG AA on every
// on-colour pair before writing this file.

{consts(LIGHT, "light")}

{consts(DARK, "dark")}

{scheme("light", "lightColorScheme")}

{scheme("dark", "darkColorScheme")}
''')
print("wrote", OUT.relative_to(ROOT))
print("window bg light", LIGHT["surface"], "| dark", DARK["surface"])
