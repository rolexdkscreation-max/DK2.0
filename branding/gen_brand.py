#!/usr/bin/env python3
"""DK ब्रांड: एक ही spec से Android vector drawables + SVG preview (python3 branding/gen_brand.py)."""
import os, sys
ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "..", "app", "src", "main", "res")

# ---- रंग (DKWorkingIndicator.kt में भी यही)
BLUE, MID, PURPLE = "#2EC5FF", "#5B6CFF", "#B14DFF"
BG_IN, BG_MID, BG_OUT = "#1B1446", "#0A0B1C", "#030409"

# ---- साउंड-वेव बार (108 viewport, safe zone = केंद्र से 33)
BAR_W, BAR_STEP, BAR_CY = 4.4, 7.2, 44.0
BAR_H = [8.0, 15.0, 24.0, 31.0, 19.0, 26.0, 11.0]       # "D" का उभार + "K" की दो चोटियाँ
def bars(cx=54.0, cy=BAR_CY, scale=1.0):
    n = len(BAR_H); out = []
    for i, h in enumerate(BAR_H):
        x = cx + (i - (n - 1) / 2) * BAR_STEP * scale
        w, hh = BAR_W * scale, h * scale
        out.append((x - w / 2, cy - hh / 2, w, hh))
    return out

def rrect_path(x, y, w, h):
    r = w / 2
    return (f"M{x+r:.2f},{y:.2f} h{w-2*r:.2f} a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{r:.2f} v{h-2*r:.2f} "
            f"a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{r:.2f} h{-(w-2*r):.2f} a{r:.2f},{r:.2f} 0 0 1 {-r:.2f},{-r:.2f} "
            f"v{-(h-2*r):.2f} a{r:.2f},{r:.2f} 0 0 1 {r:.2f},{-r:.2f} z")

def bars_path(**kw): return " ".join(rrect_path(*b) for b in bars(**kw))

# ---- "DK" wordmark (geometric, गोल सिरे) - stroke path
def wordmark(cx=54.0, top=66.0, h=10.0):
    w_d, gap = 8.2, 4.6
    x0 = cx - (w_d + gap + 7.6) / 2
    r = h / 2
    d = (f"M{x0:.2f},{top:.2f} v{h:.2f} h{w_d-r:.2f} a{r:.2f},{r:.2f} 0 0 0 0,{-h:.2f} z")
    kx = x0 + w_d + gap
    k = (f"M{kx:.2f},{top:.2f} v{h:.2f} M{kx+7.6:.2f},{top:.2f} L{kx+1.2:.2f},{top+h/2:.2f} L{kx+7.6:.2f},{top+h:.2f}")
    return d + " " + k

WM_STROKE = 2.3

def lin(x1, y1, x2, y2, stops):
    return ("lin", x1, y1, x2, y2, stops)

GRAD_BARS = lin(28, 30, 80, 62, [(0, BLUE), (0.5, MID), (1, PURPLE)])
GRAD_WM = lin(40, 66, 68, 77, [(0, "#FFFFFF"), (1, "#CFC8FF")])

# ---------------------------------------------------------------- Android XML
def a_grad(g):
    _, x1, y1, x2, y2, stops = g
    items = "".join(f'\n                <item android:offset="{o}" android:color="{c}" />' for o, c in stops)
    return (f'<aapt:attr name="{{attr}}">\n            <gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
            f'android:endX="{x2}" android:endY="{y2}">{items}\n            </gradient>\n        </aapt:attr>')

def argb(c):   # spec #RRGGBBAA -> Android #AARRGGBB
    return "#" + c[7:9] + c[1:7] if len(c) == 9 else c

def a_radial(cx, cy, r, stops):
    items = "".join(f'\n                <item android:offset="{o}" android:color="{argb(c)}" />' for o, c in stops)
    return (f'<aapt:attr name="{{attr}}">\n            <gradient android:type="radial" android:centerX="{cx}" android:centerY="{cy}" '
            f'android:gradientRadius="{r}">{items}\n            </gradient>\n        </aapt:attr>')

HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- branding/gen_brand.py से बना - हाथ से मत बदलो -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:aapt="http://schemas.android.com/aapt"\n'
        '    android:width="{w}dp" android:height="{w}dp" android:viewportWidth="108" android:viewportHeight="108">\n')

def a_path(d, fill=None, stroke=None, sw=None, alpha=None):
    attrs = [f'android:pathData="{d}"']
    body = ""
    if isinstance(fill, str): attrs.append(f'android:fillColor="{fill}"')
    if isinstance(stroke, str): attrs.append(f'android:strokeColor="{stroke}"')
    if sw: attrs += [f'android:strokeWidth="{sw}"', 'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
    if alpha is not None: attrs.append(f'android:fillAlpha="{alpha}"' if fill is not None else f'android:strokeAlpha="{alpha}"')
    if isinstance(fill, tuple): body += "\n        " + (a_grad(fill) if fill[0] == "lin" else fill[1]).replace("{attr}", "android:fillColor")
    if isinstance(stroke, tuple): body += "\n        " + (a_grad(stroke) if stroke[0] == "lin" else stroke[1]).replace("{attr}", "android:strokeColor")
    if body: return "    <path " + " ".join(attrs) + ">" + body + "\n    </path>\n"
    return "    <path " + " ".join(attrs) + " />\n"

FULL = "M0,0h108v108h-108z"
def glow(cx, cy, r, color, a):   # नीयन ग्लो = radial gradient (vector में blur नहीं)
    return ("rad", a_radial(cx, cy, r, [(0, color + a), (0.55, color + "22"), (1, color + "00")]))

def fg_layers(mono=False, wordmark_on=True, cy=BAR_CY, scale=1.0):
    out = []
    if mono:
        out.append(a_path(bars_path(cy=cy, scale=scale), fill="#FFFFFFFF"))
        if wordmark_on: out.append(a_path(wordmark(), stroke="#FFFFFFFF", sw=WM_STROKE))
        return out
    out.append(a_path(FULL, fill=glow(54, cy, 30, "#7A5CFF", "8C")))
    # बार के पीछे हल्की चौड़ी परत = चमक
    out.append(a_path(bars_path(cy=cy), fill=GRAD_BARS))
    if wordmark_on:
        out.append(a_path(wordmark(), stroke=GRAD_WM, sw=WM_STROKE))
    return out

def bg_layers():
    return [a_path(FULL, fill=("rad", a_radial(54, 40, 86, [(0, BG_IN), (0.45, BG_MID), (1, BG_OUT)]))),
            a_path(FULL, fill=glow(54, 98, 46, "#2EC5FF", "1F"))]

def write(name, layers, w=108):
    p = os.path.join(RES, "drawable", name + ".xml")
    with open(p, "w") as f: f.write(HEAD.format(w=w) + "".join(layers) + "</vector>\n")

# ---------------------------------------------------------------- SVG preview
def s_grad(id_, g):
    _, x1, y1, x2, y2, stops = g
    st = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops)
    return f'<linearGradient id="{id_}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{st}</linearGradient>'
def s_rad(id_, cx, cy, r, stops):
    st = "".join(f'<stop offset="{o}" stop-color="{c[:7]}" stop-opacity="{int(c[7:9],16)/255 if len(c)>7 else 1:.3f}"/>' for o, c in stops)
    return f'<radialGradient id="{id_}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">{st}</radialGradient>'

def svg(mask=None, cy=BAR_CY, bg=True, wordmark_on=True):
    defs = s_grad("gb", GRAD_BARS) + s_grad("gw", GRAD_WM) + \
        s_rad("bg", 54, 40, 86, [(0, BG_IN), (0.45, BG_MID), (1, BG_OUT)]) + \
        s_rad("g1", 54, cy, 30, [(0, "#7A5CFF8C"), (0.55, "#7A5CFF22"), (1, "#7A5CFF00")]) + \
        s_rad("g2", 54, 98, 46, [(0, "#2EC5FF1F"), (0.55, "#2EC5FF22"), (1, "#2EC5FF00")])
    body = ""
    if bg: body += '<rect width="108" height="108" fill="url(#bg)"/><rect width="108" height="108" fill="url(#g2)"/>'
    body += f'<rect width="108" height="108" fill="url(#g1)"/>'
    body += f'<path d="{bars_path(cy=cy)}" fill="url(#gb)"/>'
    if wordmark_on:
        body += f'<path d="{wordmark()}" fill="none" stroke="url(#gw)" stroke-width="{WM_STROKE}" stroke-linecap="round" stroke-linejoin="round"/>'
    clip = ""
    if mask == "circle": clip = '<clipPath id="m"><circle cx="54" cy="54" r="54"/></clipPath>'
    if mask == "squircle": clip = '<clipPath id="m"><rect x="0" y="0" width="108" height="108" rx="26"/></clipPath>'
    g = f'<g clip-path="url(#m)">{body}</g>' if clip else body
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="512" height="512"><defs>{defs}{clip}</defs>{g}</svg>'

if __name__ == "__main__":
    write("ic_launcher_background", bg_layers())
    write("ic_launcher_foreground", fg_layers())
    write("ic_launcher_monochrome", fg_layers(mono=True))
    # notification small icon (सफ़ेद, सिर्फ़ बार - पुराना माइक हटा)
    write("ic_dk", fg_layers(mono=True, wordmark_on=False, cy=54.0, scale=1.85), w=24)
    # splash: सिर्फ़ logo (पृष्ठभूमि theme से)
    write("dk_splash_logo", fg_layers(), w=288)
    open(os.path.join(ROOT, "dk_icon.svg"), "w").write(svg())
    if "--png" in sys.argv:          # pip install cairosvg pillow
        import cairosvg
        os.makedirs(os.path.join(ROOT, "out"), exist_ok=True)
        cairosvg.svg2png(bytestring=svg().encode(), write_to=os.path.join(ROOT, "dk_icon_512.png"), output_width=512, output_height=512)
        from PIL import Image, ImageDraw
        import io
        base = Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg().encode(), output_width=512, output_height=512))).convert("RGBA")
        for m in ("circle", "squircle"):
            mk = Image.new("L", (512, 512), 0); d = ImageDraw.Draw(mk)
            if m == "circle": d.ellipse((0, 0, 511, 511), fill=255)
            else: d.rounded_rectangle((0, 0, 511, 511), radius=120, fill=255)
            out = Image.new("RGBA", (512, 512), (40, 40, 48, 255)); out.paste(base, (0, 0), mk)
            out.save(os.path.join(ROOT, "out", f"prev_{m}.png"))
        for s in (48, 72, 96, 144, 192):
            cairosvg.svg2png(bytestring=svg("circle").encode(), write_to=os.path.join(ROOT, "out", f"prev_{s}.png"), output_width=s, output_height=s)
    print("ok")
