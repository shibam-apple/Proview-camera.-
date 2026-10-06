"""
Export a trained model for the app (little-endian binary read by AdaptiveLut.kt), after the
guardrails pass on a validation folder:

  python export.py runs/style.pt --check data/validation --out proview_adaptive.lut
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import struct
import torch
from proview_lut.model import AdaptiveLut, THUMB
from proview_lut.data import load_image, _files
from proview_lut import guardrails

MAGIC = b"PVAL"
VERSION = 1


def write(model: AdaptiveLut, path: str):
    with open(path, "wb") as f:
        f.write(MAGIC)
        f.write(struct.pack("<5i", VERSION, model.n_basis, model.size, len(model.convs), THUMB))
        for conv in model.convs:
            w = conv.weight.detach().float().cpu().contiguous()
            f.write(struct.pack("<4i", w.shape[1], w.shape[0], w.shape[2], conv.stride[0]))
            f.write(w.numpy().astype("<f4").tobytes())
            f.write(conv.bias.detach().float().cpu().numpy().astype("<f4").tobytes())
        hw = model.head.weight.detach().float().cpu().contiguous()
        f.write(struct.pack("<2i", hw.shape[1], hw.shape[0]))
        f.write(hw.numpy().astype("<f4").tobytes())
        f.write(model.head.bias.detach().float().cpu().numpy().astype("<f4").tobytes())
        f.write(model.deltas.detach().float().cpu().contiguous().numpy().astype("<f4").tobytes())


def main():
    p = argparse.ArgumentParser()
    p.add_argument("weights")
    p.add_argument("--check", help="folder of validation photos for the guardrails")
    p.add_argument("--out", required=True)
    p.add_argument("--force", action="store_true", help="export even if a guardrail fails")
    a = p.parse_args()
    model = AdaptiveLut()
    model.load_state_dict(torch.load(a.weights, map_location="cpu"))
    if a.check:
        imgs = [load_image(os.path.join(a.check, f), 512) for f in _files(a.check)]
        ok, report = guardrails.check(model, imgs)
        print("guardrails:", {k: round(v, 4) for k, v in report.items()}, "PASS" if ok else "FAIL")
        if not ok and not a.force:
            sys.exit("refusing to export: a guardrail failed (use --force to override)")
    write(model, a.out)
    print("wrote", a.out, os.path.getsize(a.out), "bytes")


if __name__ == "__main__":
    main()
