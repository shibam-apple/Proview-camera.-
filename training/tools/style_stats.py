# style_stats.py dir [dir2 ...]: measures a set of finished photos so two "styles" can be compared.
#   L p5/p50/p95  : Oklab lightness percentiles (tone placement: blacks, mids, highlights)
#   C mean/p90    : Oklab chroma (overall saturation / how far vivid colours go)
#   C by hue      : mean chroma in 6 hue sectors (reds, yellows, greens, cyans, blues, magentas)
#   hiC           : chroma of pixels with L > 0.85 (how much highlights desaturate)
#   lc            : local contrast (mean |L - blur(L)|, 9 px)
#   clip          : % pixels with any channel >= 254
import sys, os, numpy as np
from PIL import Image, ImageFilter
def oklab(rgb):
    c = rgb / 255.0
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    M1 = np.array([[0.4122214708, 0.5363325363, 0.0514459929], [0.2119034982, 0.6806995451, 0.1073969566], [0.0883024619, 0.2817188376, 0.6299787005]])
    M2 = np.array([[0.2104542553, 0.7936177850, -0.0040720468], [1.9779984951, -2.4285922050, 0.4505937099], [0.0259040371, 0.7827717662, -0.8086757660]])
    lms = np.cbrt(lin @ M1.T)
    return lms @ M2.T
def stats(path):
    im = Image.open(path).convert('RGB'); im.thumbnail((800, 800))
    a = np.asarray(im).astype(np.float64).reshape(-1, 3)
    lab = oklab(a); L, A, B = lab[:, 0], lab[:, 1], lab[:, 2]
    C = np.hypot(A, B); H = (np.degrees(np.arctan2(B, A)) + 360) % 360
    sectors = [np.mean(C[(H >= s) & (H < s + 60) & (C > 0.02)]) if np.any((H >= s) & (H < s + 60) & (C > 0.02)) else np.nan for s in range(0, 360, 60)]
    g = np.asarray(im.convert('L')).astype(np.float64) / 255
    bl = np.asarray(im.convert('L').filter(ImageFilter.BoxBlur(4))).astype(np.float64) / 255
    hi = C[L > 0.85]
    return dict(Lp5=np.percentile(L, 5), Lp50=np.percentile(L, 50), Lp95=np.percentile(L, 95), Cmean=C.mean(), Cp90=np.percentile(C, 90),
                hiC=hi.mean() if hi.size else np.nan, lc=np.abs(g - bl).mean(), clip=100 * np.mean(np.any(a >= 254, axis=1)), sectors=sectors)
for d in sys.argv[1:]:
    rows = [stats(os.path.join(d, f)) for f in sorted(os.listdir(d)) if f.lower().endswith(('.png', '.jpg', '.jpeg'))]
    keys = ['Lp5', 'Lp50', 'Lp95', 'Cmean', 'Cp90', 'hiC', 'lc', 'clip']
    med = {k: np.nanmedian([r[k] for r in rows]) for k in keys}
    sec = np.nanmedian(np.array([r['sectors'] for r in rows]), axis=0)
    print(f"{os.path.basename(d.rstrip('/')):>10} n={len(rows):2d} " + " ".join(f"{k}={med[k]:.3f}" for k in keys) +
          "  C[R,Y,G,C,B,M]=" + ",".join(f"{v:.3f}" for v in sec))
