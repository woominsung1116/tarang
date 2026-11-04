import math, os, sys

ROOT = sys.argv[1]
RES = os.path.join(ROOT, "app/src/main/res")
BRAND = os.path.join(ROOT, "brand")
os.makedirs(BRAND, exist_ok=True)

ORANGE, WHITE, GRAPHITE = "#FF6A13", "#FFFFFF", "#2E3135"

def f(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s

# ---------- Mark (48 x 48): open course loop + rider dot ----------
C, R, SW = 24.0, 15.0, 7.0          # loop centre, centreline radius, road width
GAP_AT, GAP_HALF = -45.0, 40.0       # gap centred upper-right; half-angle
DOT_R = 4.6

def pt(deg):
    a = math.radians(deg)
    return C + R * math.cos(a), C + R * math.sin(a)

sx, sy = pt(GAP_AT + GAP_HALF)
ex, ey = pt(GAP_AT - GAP_HALF)
LOOP = f"M{f(sx)},{f(sy)}A{f(R)},{f(R)} 0 1,1 {f(ex)},{f(ey)}"
dx, dy = pt(GAP_AT)
DOT = (f"M{f(dx - DOT_R)},{f(dy)}"
       f"A{f(DOT_R)},{f(DOT_R)} 0 1,1 {f(dx + DOT_R)},{f(dy)}"
       f"A{f(DOT_R)},{f(DOT_R)} 0 1,1 {f(dx - DOT_R)},{f(dy)}Z")

def mark_paths(color):
    return [dict(d=LOOP, stroke=color, sw=SW), dict(d=DOT, fill=color)]

# ---------- Wordmark "타랑" (88 x 48), monoline, round caps ----------
WS = 6.0
def circ(cx, cy, r):
    return (f"M{f(cx - r)},{f(cy)}A{f(r)},{f(r)} 0 1,1 {f(cx + r)},{f(cy)}"
            f"A{f(r)},{f(r)} 0 1,1 {f(cx - r)},{f(cy)}Z")

TA = [
    "M20,3H3V45H20",        # ㅌ outer (ㄷ)
    "M3,24H18",             # ㅌ middle bar
    "M30,3V45",             # ㅏ stem
    "M30,24H39",            # ㅏ branch
]
X = 49
RANG = [
    f"M{X+3},3H{X+19}V13H{X+4}V23H{X+20}",   # ㄹ
    f"M{X+29},3V27",                          # ㅏ stem (shortened over 받침)
    f"M{X+29},13H{X+37}",                     # ㅏ branch
    circ(X + 19, 38.5, 7.0),                  # ㅇ 받침
]
WORD_W, WORD_H = X + 40, 48

def word_paths(color):
    return [dict(d=d, stroke=color, sw=WS) for d in TA + RANG]

# ---------- emitters ----------
def svg_path(p):
    if "stroke" in p:
        return (f'<path d="{p["d"]}" fill="none" stroke="{p["stroke"]}" stroke-width="{f(p["sw"])}" '
                f'stroke-linecap="round" stroke-linejoin="round"/>')
    return f'<path d="{p["d"]}" fill="{p["fill"]}"/>'

def vd_path(p, ind="    "):
    if "stroke" in p:
        return (f'{ind}<path\n{ind}    android:pathData="{p["d"]}"\n'
                f'{ind}    android:strokeColor="{p["stroke"]}"\n'
                f'{ind}    android:strokeWidth="{f(p["sw"])}"\n'
                f'{ind}    android:strokeLineCap="round"\n'
                f'{ind}    android:strokeLineJoin="round" />')
    return f'{ind}<path\n{ind}    android:pathData="{p["d"]}"\n{ind}    android:fillColor="{p["fill"]}" />'

def svg(name, w, h, groups, bg=None):
    body = []
    if bg:
        body.append(f'<rect width="{f(w)}" height="{f(h)}" fill="{bg}"/>')
    for (tx, ty, s, paths) in groups:
        inner = "\n    ".join(svg_path(p) for p in paths)
        body.append(f'<g transform="translate({f(tx)} {f(ty)}) scale({f(s)})">\n    {inner}\n  </g>')
    out = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{f(w)}" height="{f(h)}" viewBox="0 0 {f(w)} {f(h)}">\n  '
           + "\n  ".join(body) + "\n</svg>\n")
    open(os.path.join(BRAND, name), "w").write(out)

def vd(name, wdp, hdp, vw, vh, groups, comment):
    parts = []
    for (tx, ty, s, paths) in groups:
        inner = "\n".join(vd_path(p, "        ") for p in paths)
        if tx == 0 and ty == 0 and s == 1:
            parts.append("\n".join(vd_path(p) for p in paths))
        else:
            parts.append(f'    <group\n        android:translateX="{f(tx)}"\n        android:translateY="{f(ty)}"\n'
                         f'        android:scaleX="{f(s)}"\n        android:scaleY="{f(s)}">\n{inner}\n    </group>')
    out = (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} Source: brand/ (generated from shared geometry). -->\n'
           f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           f'    android:width="{f(wdp)}dp"\n    android:height="{f(hdp)}dp"\n'
           f'    android:viewportWidth="{f(vw)}"\n    android:viewportHeight="{f(vh)}">\n'
           + "\n".join(parts) + "\n</vector>\n")
    open(os.path.join(RES, "drawable", name), "w").write(out)

os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)

# Logo mark
svg("taraang_logo_mark.svg", 48, 48, [(0, 0, 1, mark_paths(ORANGE))])
vd("taraang_logo_mark.xml", 48, 48, 48, 48, [(0, 0, 1, mark_paths(ORANGE))],
   "Taraang logo mark: open course loop + rider dot, orange.")

# Wordmark
svg("taraang_wordmark.svg", WORD_W, WORD_H, [(0, 0, 1, word_paths(GRAPHITE))])
vd("taraang_wordmark.xml", WORD_W * 1.5, WORD_H * 1.5, WORD_W, WORD_H, [(0, 0, 1, word_paths(GRAPHITE))],
   "Taraang wordmark '타랑', graphite.")

# Lockup: mark left, wordmark right at 0.72 scale, optically centred
WSC = 0.72
LW = 48 + 12 + WORD_W * WSC
lock = [(0, 0, 1, mark_paths(ORANGE)), (60, (48 - WORD_H * WSC) / 2, WSC, word_paths(GRAPHITE))]
svg("taraang_splash_lockup.svg", LW, 48, lock)
vd("taraang_splash_lockup.xml", LW * 2.5, 48 * 2.5, LW, 48, lock,
   "Splash lockup: mark + '타랑' wordmark for a white background.")

# Launcher foreground: 108 viewport, mark inside the 66 safe zone
MS = 1.5                      # loop outer diameter 37*1.5 = 55.5 (< 66 safe zone)
off = 54 - 24 * MS
fg = [(off, off, MS, mark_paths(WHITE))]
vd("ic_launcher_foreground.xml", 108, 108, 108, 108, fg, "Adaptive icon foreground (white mark on orange background).")
vd("ic_launcher_monochrome.xml", 108, 108, 108, 108, fg, "Adaptive icon monochrome layer (Android 13+ themed icons).")
svg("taraang_app_icon.svg", 108, 108, fg, bg=ORANGE)

# Splash icon for the Android 12 system splash: 108dp canvas, mark within the 72 inner circle
SS = 1.4
soff = 54 - 24 * SS
vd("brand_splash_icon.xml", 108, 108, 108, 108, [(soff, soff, SS, mark_paths(ORANGE))],
   "Android 12+ system splash icon: orange mark centred inside the 2/3 mask circle.")
print("ok", f(LW))
