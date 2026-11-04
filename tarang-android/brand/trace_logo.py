"""Trace the user's two-colour Taraang logo PNGs into SVG + VectorDrawable.

usage: python -I trace.py <hyundai dir> <project root>
Pixels are classified to the nearest of white / orange / graphite (measured),
each colour mask is traced with potrace, and paths are emitted as even-odd fills.
"""
import os, sys
import numpy as np
from PIL import Image
import potrace

SRC, ROOT = sys.argv[1], sys.argv[2]
RES = os.path.join(ROOT, "app/src/main/res/drawable")
BRAND = os.path.join(ROOT, "brand")

ORANGE, GRAPHITE = "#F06519", "#31383F"   # measured from the official lockup (core pixel median)
UPSCALE = 2


def load(name):
    src = Image.open(os.path.join(SRC, name)).convert("RGBA")
    bg = Image.new("RGBA", src.size, "white")
    bg.alpha_composite(src)
    im = bg.convert("RGB")
    im = im.resize((im.width * UPSCALE, im.height * UPSCALE), Image.LANCZOS)
    return np.asarray(im).astype(np.int32)


def hexrgb(h):
    return np.array([int(h[i:i + 2], 16) for i in (1, 3, 5)])


def masks(im):
    refs = np.stack([hexrgb("#FFFFFF"), hexrgb(ORANGE), hexrgb(GRAPHITE)])
    d = ((im[:, :, None, :] - refs[None, None]) ** 2).sum(-1)
    k = d.argmin(-1)
    return k == 1, k == 2


def trace(mask):
    # potracer treats falsy pixels as ink, so pass the inverted mask.
    plist = potrace.Bitmap(~mask).trace(turdsize=40, alphamax=1.0, opticurve=True, opttolerance=0.2)
    return plist


def path_data(plist, ox, oy, s):
    def p(pt):
        return f"{(pt.x - ox) * s:.1f},{(pt.y - oy) * s:.1f}"
    out = []
    for curve in plist:
        out.append("M" + p(curve.start_point))
        for seg in curve.segments:
            if seg.is_corner:
                out.append("L" + p(seg.c) + "L" + p(seg.end_point))
            else:
                out.append("C" + p(seg.c1) + " " + p(seg.c2) + " " + p(seg.end_point))
        out.append("Z")
    return "".join(out)


def bbox(*ms):
    m = np.logical_or.reduce(ms)
    ys, xs = np.nonzero(m)
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1


def emit(name, layers, vw, vh, wdp, hdp, comment, svg=True, group=None):
    """layers: list of (pathData, color). group: (tx, ty, s) applied inside a 108 canvas."""
    vw_r, vh_r = f"{vw:.1f}".rstrip("0").rstrip("."), f"{vh:.1f}".rstrip("0").rstrip(".")
    ind = "        " if group else "    "
    paths = "\n".join(
        f'{ind}<path\n{ind}    android:fillType="evenOdd"\n{ind}    android:fillColor="{c}"\n{ind}    android:pathData="{d}" />'
        for d, c in layers)
    if group:
        tx, ty, s = group
        paths = (f'    <group\n        android:translateX="{tx:.2f}"\n        android:translateY="{ty:.2f}"\n'
                 f'        android:scaleX="{s:.4f}"\n        android:scaleY="{s:.4f}">\n{paths}\n    </group>')
    canvas = (108, 108) if group else (vw_r, vh_r)
    xml = (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} Traced from the official logo PNGs by brand/trace_logo.py. -->\n'
           f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           f'    android:width="{wdp}dp"\n    android:height="{hdp}dp"\n'
           f'    android:viewportWidth="{canvas[0]}"\n    android:viewportHeight="{canvas[1]}">\n{paths}\n</vector>\n')
    open(os.path.join(RES, name + ".xml"), "w").write(xml)
    if svg:
        body = "\n".join(f'  <path fill-rule="evenodd" fill="{c}" d="{d}"/>' for d, c in layers)
        if group:
            tx, ty, s = group
            body = f'  <g transform="translate({tx:.2f} {ty:.2f}) scale({s:.4f})">\n{body}\n  </g>'
        open(os.path.join(BRAND, name + ".svg"), "w").write(
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{canvas[0]}" height="{canvas[1]}" '
            f'viewBox="0 0 {canvas[0]} {canvas[1]}">\n{body}\n</svg>\n')


# ---- emblem (official emblem-only file) ----
im = load("두 사람과 지붕 곡선 엠블럼.png")
mo, mg = masks(im)
x0, y0, x1, y1 = bbox(mo, mg)
S = 1.0 / UPSCALE                         # viewport units = source pixels
ew, eh = (x1 - x0) * S, (y1 - y0) * S
emb_o = path_data(trace(mo), x0, y0, S)
emb_g = path_data(trace(mg), x0, y0, S)
emb_mono = path_data(trace(mo | mg), x0, y0, S)
emit("taraang_logo_mark", [(emb_o, ORANGE), (emb_g, GRAPHITE)], ew, eh, 96, round(96 * eh / ew), "Taraang emblem.")

def fit(w_target):
    s = w_target / ew
    return (54 - ew * s / 2, 54 - eh * s / 2, s)

emit("ic_launcher_foreground", [(emb_o, ORANGE), (emb_g, GRAPHITE)], ew, eh, 108, 108,
     "Adaptive icon foreground: emblem inside the 66dp safe zone on a white background.", svg=False, group=fit(56))
emit("ic_launcher_monochrome", [(emb_mono, "#FFFFFF")], ew, eh, 108, 108,
     "Adaptive icon monochrome: single-colour emblem silhouette.", svg=False, group=fit(56))
emit("brand_splash_icon", [(emb_o, ORANGE), (emb_g, GRAPHITE)], ew, eh, 108, 108,
     "Android 12+ system splash icon: emblem inside the 2/3 mask circle.", svg=False, group=fit(60))
emit("taraang_app_icon", [(emb_o, ORANGE), (emb_g, GRAPHITE)], ew, eh, 108, 108,
     "App icon preview.", svg=True, group=fit(56))
os.remove(os.path.join(RES, "taraang_app_icon.xml"))   # preview SVG only

# ---- lockup (official horizontal logo) + wordmark crop ----
im = load("Tarang logo with curved orange accent.png")
mo, mg = masks(im)
x0, y0, x1, y1 = bbox(mo, mg)
lw, lh = (x1 - x0) * S, (y1 - y0) * S
emit("taraang_splash_lockup", [(path_data(trace(mo), x0, y0, S), ORANGE), (path_data(trace(mg), x0, y0, S), GRAPHITE)],
     lw, lh, 320, round(320 * lh / lw), "Taraang horizontal lockup (emblem + wordmark) for a white background.")

# wordmark = ink right of the widest empty column gap
ink = (mo | mg).any(0)
cols = np.nonzero(ink)[0]
gaps = np.diff(cols)
split = cols[gaps.argmax()] + 1
wo, wg = mo.copy(), mg.copy()
wo[:, :split] = False
wg[:, :split] = False
x0, y0, x1, y1 = bbox(wo, wg)
ww, wh = (x1 - x0) * S, (y1 - y0) * S
emit("taraang_wordmark", [(path_data(trace(wo), x0, y0, S), ORANGE), (path_data(trace(wg), x0, y0, S), GRAPHITE)],
     ww, wh, 160, round(160 * wh / ww), "Taraang wordmark '타랑' cropped from the official lockup.")
print("emblem", ew, eh, "lockup", lw, lh, "wordmark", ww, wh, "split", split / UPSCALE)
