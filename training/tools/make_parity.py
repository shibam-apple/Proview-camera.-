"""Writes a tiny random model + input + PyTorch outputs, for the Kotlin parity test
(pipeline-ref/src/test/resources/adaptive/)."""
import os, struct, sys
import numpy as np
import torch
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from proview_lut.model import AdaptiveLut, apply_lut
from export import write

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
torch.manual_seed(7)
m = AdaptiveLut(n_basis=3, size=9, channels=(4, 8, 8, 8, 8))
with torch.no_grad():
    for p in m.parameters():
        p.copy_(torch.randn_like(p) * 0.3)
    m.deltas.mul_(0.1)
m.eval()
write(m, os.path.join(out, "tiny.lut"))
# A smooth test image, 8-bit like a photo.
h, w = 48, 64
yy, xx = np.mgrid[0:h, 0:w]
img = np.stack([xx / (w - 1), yy / (h - 1), 0.5 + 0.4 * np.sin(xx / 7.0) * np.cos(yy / 5.0)], -1)
img8 = (img.clip(0, 1) * 255 + 0.5).astype(np.uint8)
argb = (0xFF << 24) | (img8[..., 0].astype(np.int64) << 16) | (img8[..., 1].astype(np.int64) << 8) | img8[..., 2]
argb.astype("<u4").tofile(os.path.join(out, "input.argb"))
x = torch.from_numpy(img8.astype(np.float32) / 255).permute(2, 0, 1)[None]
with torch.no_grad():
    wts = m.weights(x)[0]
    lut = m.luts(x)
    y = apply_lut(x, lut)[0]
with open(os.path.join(out, "expected.bin"), "wb") as f:
    f.write(struct.pack("<2i", h, w))
    f.write(wts.numpy().astype("<f4").tobytes())
    f.write(y.permute(1, 2, 0).numpy().astype("<f4").tobytes())
print("weights", wts.tolist())
