"""Checks a trained model must pass before it can ship in the app."""
import math
import torch
from .color import srgb_to_oklab
from .model import apply_lut

# Display sRGB skin tones from light to deep, and muted/medium test colours.
SKIN = [(0.96, 0.80, 0.69), (0.92, 0.72, 0.60), (0.84, 0.62, 0.48), (0.70, 0.50, 0.38), (0.55, 0.38, 0.28), (0.40, 0.27, 0.20)]
GREYS = [(v, v, v) for v in (0.05, 0.2, 0.4, 0.6, 0.8, 0.95)]


def _apply(lut, colours):
    x = torch.tensor(colours, dtype=torch.float32).T.reshape(1, 3, 1, -1)
    return apply_lut(x, lut[None]).reshape(3, -1).T


def check(model, images, max_skin_hue_deg=3.0, max_chroma_gain=1.15, max_grey_tint=0.012):
    """Runs every image's LUT on the test colours. Returns (ok, report dict)."""
    worst_hue, worst_gain, worst_tint = 0.0, 0.0, 0.0
    model.eval()
    with torch.no_grad():
        for img in images:
            lut = model.luts(img[None])[0]
            s_in = srgb_to_oklab(torch.tensor(SKIN))
            s_out = srgb_to_oklab(_apply(lut, SKIN))
            for a, b in zip(s_in, s_out):
                h1 = math.degrees(math.atan2(a[2], a[1]))
                h2 = math.degrees(math.atan2(b[2], b[1]))
                d = abs(h1 - h2) % 360
                worst_hue = max(worst_hue, min(d, 360 - d))
                c1 = math.hypot(a[1], a[2])
                c2 = math.hypot(b[1], b[2])
                worst_gain = max(worst_gain, c2 / max(c1, 1e-6))
            g_out = srgb_to_oklab(_apply(lut, GREYS))
            worst_tint = max(worst_tint, float(torch.sqrt(g_out[:, 1] ** 2 + g_out[:, 2] ** 2).max()))
    report = dict(skin_hue_shift_deg=worst_hue, skin_chroma_gain=worst_gain, grey_tint=worst_tint)
    ok = worst_hue <= max_skin_hue_deg and worst_gain <= max_chroma_gain and worst_tint <= max_grey_tint
    return ok, report
