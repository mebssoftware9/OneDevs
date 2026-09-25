#!/usr/bin/env python3
"""Generate the OneDevs colour system from the brand artwork.

Three decisions make this scheme OneDevs' rather than Material's default.

Surfaces are absolute. Pure #FFFFFF in light, pure #000000 in dark. M3's
generated neutrals land on tone 99/10 and carry the seed's chroma, which is
exactly what makes stock Material apps read as off-white and charcoal. Here the
neutral palette is built at chroma 0 and the surface roles are pinned to the
endpoints, so white is white and black is true black on OLED.

Hue follows the artwork instead of being held constant. Measured in CIELCh the
mark runs 302.9deg through the icon's navy container, 295.7deg at the glyph's
core blue and 281.1deg at its brightest face. Holding one hue across a tone
scale is what sends M3 blues periwinkle at the light end; the ramp interpolates
those measurements, so every tone is a blue the artwork actually contains.

surfaceTint equals surface in both schemes. M3 composites surfaceTint over any
Surface whose colour equals colorScheme.surface, scaled by tonalElevation. That
overlay is why a NavigationBar set to surface still renders faintly blue. Making
the tint equal the surface turns every tonal overlay in the app into a no-op
without touching one call site.

Every on-colour pair is asserted against WCAG AA before anything is written.
"""
import pathlib
import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/kotlin/com/devbangs/onedevs/ui/theme/Color.kt"

# Measured off art/source/icon_master.png. BRAND_NAVY is the icon's container,
# reused for DevBot's plate and the splash icon background so the app and the
# launcher share one deep colour.
BRAND_NAVY = "#03106B"
BRAND_HUE = ((0, 303.5), (12, 302.9), (45, 295.7), (55, 284.7), (60, 281.1), (100, 277.0))
BRAND_CHROMA = 95.0
SUPPORT_HUE_SEED = "#348DFC"               # the mark's bright face — keeps secondary blue, not violet
SUPPORT_CHROMA = 26.0                      # muted blue siblings, never cyan
SEEDS = {"tertiary": "#D3A031", "error": "#B3261E"}   # DevCoin gold, error red

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
    """Most saturated in-gamut sRGB at this lightness, bisecting on chroma."""
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

TONES = (0, 4, 6, 10, 12, 15, 17, 20, 22, 24, 25, 30, 40, 50, 60, 70, 80, 87, 90, 92, 94, 95, 96, 98, 99, 100)
_HT, _HV = zip(*BRAND_HUE)

def brand(chroma):
    """Brand ramp: hue tracks the artwork, chroma is capped by the sRGB gamut."""
    return {t: _tone(float(np.interp(t, _HT, _HV)), chroma, t) for t in TONES}

def fixed(seed, chroma=None):
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

P = brand(BRAND_CHROMA)          # primary: the glyph's blue
S = fixed(SUPPORT_HUE_SEED, SUPPORT_CHROMA)   # secondary: blue-grey, drained of chroma
T = fixed(SEEDS["tertiary"])     # tertiary: DevCoin gold
E = fixed(SEEDS["error"])
N = fixed("#000000", 0)          # neutral: chroma 0, no cast in either theme

WHITE, BLACK = "#FFFFFF", "#000000"

LIGHT = {
    "primary": P[40], "onPrimary": WHITE, "primaryContainer": P[90], "onPrimaryContainer": P[15],
    "secondary": S[40], "onSecondary": WHITE, "secondaryContainer": S[94], "onSecondaryContainer": S[15],
    "tertiary": T[40], "onTertiary": WHITE, "tertiaryContainer": T[90], "onTertiaryContainer": T[15],
    "error": E[40], "onError": WHITE, "errorContainer": E[90], "onErrorContainer": E[15],
    # Pure white is the page. The container ladder is the only grey in the app.
    "background": WHITE, "onBackground": N[10], "surface": WHITE, "onSurface": N[10],
    "surfaceVariant": N[94], "onSurfaceVariant": N[40], "outline": N[50], "outlineVariant": N[90],
    "inverseSurface": N[20], "inverseOnSurface": N[95], "inversePrimary": P[70], "scrim": BLACK,
    "surfaceDim": N[90], "surfaceBright": WHITE, "surfaceContainerLowest": WHITE,
    "surfaceContainerLow": N[98], "surfaceContainer": N[96], "surfaceContainerHigh": N[94],
    "surfaceContainerHighest": N[92], "surfaceTint": WHITE,
}
DARK = {
    "primary": P[60], "onPrimary": P[10], "primaryContainer": P[25], "onPrimaryContainer": P[90],
    "secondary": S[70], "onSecondary": S[15], "secondaryContainer": S[25], "onSecondaryContainer": S[90],
    "tertiary": T[70], "onTertiary": T[15], "tertiaryContainer": T[25], "onTertiaryContainer": T[90],
    "error": E[70], "onError": E[15], "errorContainer": E[25], "onErrorContainer": E[90],
    # True black, not charcoal: on OLED the page is unlit, so the brand is the
    # only thing emitting.
    "background": BLACK, "onBackground": N[95], "surface": BLACK, "onSurface": N[95],
    "surfaceVariant": N[20], "onSurfaceVariant": N[70], "outline": N[60], "outlineVariant": N[24],
    "inverseSurface": N[95], "inverseOnSurface": N[20], "inversePrimary": P[40], "scrim": BLACK,
    "surfaceDim": BLACK, "surfaceBright": N[24], "surfaceContainerLowest": BLACK,
    "surfaceContainerLow": N[4], "surfaceContainer": N[6], "surfaceContainerHigh": N[10],
    "surfaceContainerHighest": N[12], "surfaceTint": BLACK,
}

# 4.5 on every text pair, including primary on surface: the selected navigation
# label is 12sp primary-on-surface, so the 3.0 large-text floor would not cover it.
PAIRS = [("onPrimary", "primary", 4.5), ("onPrimaryContainer", "primaryContainer", 4.5),
         ("onSecondary", "secondary", 4.5), ("onSecondaryContainer", "secondaryContainer", 4.5),
         ("onTertiary", "tertiary", 4.5), ("onTertiaryContainer", "tertiaryContainer", 4.5),
         ("onError", "error", 4.5), ("onErrorContainer", "errorContainer", 4.5),
         ("onSurface", "surface", 4.5), ("onSurfaceVariant", "surfaceVariant", 4.5),
         ("onSurfaceVariant", "surface", 4.5), ("onBackground", "background", 4.5),
         ("onSurface", "surfaceContainerHighest", 4.5), ("primary", "surface", 4.5),
         ("primary", "surfaceContainerHighest", 4.5), ("outline", "surface", 3.0)]
for label, sch in (("light", LIGHT), ("dark", DARK)):
    for fg, bg, floor in PAIRS:
        r = contrast(sch[fg], sch[bg])
        assert r >= floor, f"{label}: {fg} {sch[fg]} on {bg} {sch[bg]} is {r:.2f}, needs {floor}"
assert LIGHT["surface"] == WHITE and DARK["surface"] == BLACK, "surfaces must stay absolute"
assert LIGHT["surfaceTint"] == LIGHT["surface"] and DARK["surfaceTint"] == DARK["surface"], \
    "surfaceTint must equal surface or tonal elevation tints the app again"
def over(fg, bg, alpha):
    """fg composited onto bg at alpha, which is how the tint is specified."""
    f = np.array([int(fg[i:i + 2], 16) for i in (1, 3, 5)], float)
    b = np.array([int(bg[i:i + 2], 16) for i in (1, 3, 5)], float)
    return "#%02X%02X%02X" % tuple(int(round(v)) for v in f * alpha + b * (1 - alpha))

# The tint behind top-bar controls, given as a percentage of primary over the
# page rather than a hex, so it stays the brand's blue if the brand's blue moves.
# 5% is measured off the product mockups. That same 5% over true black comes out
# at #060E1A, which is less a container than a rumour, so the dark value is
# lifted until the control is findable without becoming a panel in its own right.
BRAND_TINT_LIGHT = over(LIGHT["primary"], LIGHT["surface"], 0.05)
BRAND_TINT_DARK = over(DARK["primary"], DARK["surface"], 0.20)

# Per-function accents. Different products are allowed to look different, but
# they are still generated rather than pasted: each seed is run through the same
# tone machinery as the brand, so every accent inherits the same contrast floor
# and the same light/dark behaviour. Seeds are measured off the product mockups.
ACCENTS = {"testing": "#2189FC", "mission": "#A968FC", "live": "#24DBA5",
           "feedback": "#5F53EB", "community": "#2AB2BF"}

def vivid_on(seed, bg, floor=4.5):
    """The most saturated tone of this hue that still carries a label on bg.

    Most saturated means closest to bg in lightness while still clearing the
    floor, so the search runs down the tone scale on a light background and up
    it on a dark one. Picking by contrast rather than by eye is what stops an
    accent shipping a label nobody can read: the mockups' own mint green does
    not carry white text at any size.
    """
    p = fixed(seed)
    bg_is_dark = contrast(bg, WHITE) > contrast(bg, BLACK)
    for t in sorted(TONES, reverse=not bg_is_dark):
        if contrast(p[t], bg) >= floor:
            return p[t]
    raise AssertionError(f"no tone of {seed} reaches {floor}:1 on {bg}")

ACCENT_LIGHT, ACCENT_DARK = {}, {}
for key, seed in ACCENTS.items():
    lo = vivid_on(seed, WHITE)
    # In dark the label is the accent's own darkest tone rather than pure black,
    # so the solid is chosen against that label and not against the page.
    do_on = fixed(seed)[10]
    do = vivid_on(seed, do_on)
    ACCENT_LIGHT[key] = (lo, WHITE, over(lo, WHITE, 0.07))
    ACCENT_DARK[key] = (do, do_on, over(do, BLACK, 0.22))
    for label, (solid, on, tint), page in (("light", ACCENT_LIGHT[key], WHITE), ("dark", ACCENT_DARK[key], BLACK)):
        assert contrast(on, solid) >= 4.5, f"{label} {key}: label unreadable on the accent"
        assert contrast(solid, tint) >= 3.0, f"{label} {key}: accent lost on its own tint"
        assert contrast(tint, page) >= 1.05, f"{label} {key}: tint vanishes into the page"
print("accents:", {k: v[0] for k, v in ACCENT_LIGHT.items()}, "|", {k: v[0] for k, v in ACCENT_DARK.items()})
print("tints  :", {k: v[2] for k, v in ACCENT_LIGHT.items()}, "|", {k: v[2] for k, v in ACCENT_DARK.items()})

# DevBot's plate carries his white shell in both themes.
assert contrast("#FFFFFF", BRAND_NAVY) >= 4.5, "DevBot plate too light for the shell"
# Whatever sits on the tint has to stay readable on it.
assert contrast(LIGHT["onSurface"], BRAND_TINT_LIGHT) >= 4.5, "light tint too dark for onSurface"
assert contrast(DARK["onSurface"], BRAND_TINT_DARK) >= 4.5, "dark tint too light for onSurface"
assert contrast(LIGHT["onSurfaceVariant"], BRAND_TINT_LIGHT) >= 4.5, "light tint too dark for the label"
assert contrast(DARK["onSurfaceVariant"], BRAND_TINT_DARK) >= 4.5, "dark tint too light for the label"
# A container the page swallows is not a container.
assert contrast(BRAND_TINT_DARK, DARK["surface"]) >= 1.15, "dark tint vanishes into true black"
# Two roles resolving to the same hex means one of them is not doing any work.
for label, sch in (("light", LIGHT), ("dark", DARK)):
    for a, b in (("primaryContainer", "secondaryContainer"), ("outlineVariant", "surfaceContainerHighest"),
                 ("primary", "secondary"), ("surfaceContainer", "surfaceContainerHigh")):
        assert sch[a] != sch[b], f"{label}: {a} and {b} are both {sch[a]}"
print(f"contrast: {2 * len(PAIRS)} pairs pass WCAG AA")
print(f"light primary {LIGHT['primary']} on white {contrast(LIGHT['primary'], WHITE):.2f}:1")
print(f"dark  primary {DARK['primary']} on black {contrast(DARK['primary'], BLACK):.2f}:1")

def accent_kt():
    rows = []
    for key in ACCENTS:
        for label, table in (("Light", ACCENT_LIGHT), ("Dark", ACCENT_DARK)):
            solid, on, tint = table[key]
            rows.append(
                f"internal val accent{key.capitalize()}{label} = Accent(\n"
                f"    solid = Color(0xFF{solid[1:]}),\n"
                f"    onSolid = Color(0xFF{on[1:]}),\n"
                f"    tint = Color(0xFF{tint[1:]}),\n)")
    return "\n\n".join(rows)

accent_block = ("/**\n"
                " * One accent per launch action. The solid carries a glyph or a label, the\n"
                " * tint backs the card, and onSolid is whatever stays readable on the solid.\n"
                " * Generated, not chosen: the solid is the most saturated tone of the seed\n"
                " * hue that still clears 4.5:1 against the page it sits on, which is why the\n"
                " * live accent lands deeper than the mockup's mint -- mint carries no label.\n"
                " */\n") + accent_kt()

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
// hand. Surfaces are absolute — pure white and true black — the blue ramp
// follows the mark's measured hue rather than one held hue, and surfaceTint
// equals surface so no tonal overlay ever tints a surface. The generator
// asserts WCAG AA on every on-colour pair before writing this file.

{consts(LIGHT, "light")}

{consts(DARK, "dark")}

/**
 * The icon's container blue, measured off the master artwork. DevBot's plate and
 * the splash icon background share it, so the launcher and the app carry the
 * same deep colour. Fixed in both themes: the asset is a white shell, and this
 * is what it is legible against.
 */
internal val brandNavy = Color(0xFF{BRAND_NAVY[1:]})

/**
 * The tint behind top-bar controls: primary over the page, 5% in light and 20%
 * on true black where the same 5% would be invisible. Material has no role for
 * "a container that is brand-coloured but is not a button", and reaching for
 * surfaceContainerHigh instead put a grey slab on a blue brand. Read this
 * through OneDevsTheme's LocalOneDevsColors rather than picking a side by hand.
 */
internal val brandTintLight = Color(0xFF{BRAND_TINT_LIGHT[1:]})
internal val brandTintDark = Color(0xFF{BRAND_TINT_DARK[1:]})

{accent_block}

{scheme("light", "lightColorScheme")}

{scheme("dark", "darkColorScheme")}
''')
print("wrote", OUT.relative_to(ROOT))

# The splash window and the app surface have to be the same colour or the
# handoff flashes. Emitting both from this one palette stops them drifting.
for folder, sch in (("values", LIGHT), ("values-night", DARK)):
    d = ROOT / "app/src/main/res" / folder
    d.mkdir(parents=True, exist_ok=True)
    (d / "colors.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        f'    <color name="window_background">#FF{sch["surface"][1:]}</color>\n'
        f'    <color name="devbot_accent">#FF{sch["primary"][1:]}</color>\n'
        f'    <color name="brand_navy">#FF{BRAND_NAVY[1:]}</color>\n'
        "</resources>\n")
print("window background light", LIGHT["surface"], "| dark", DARK["surface"])
