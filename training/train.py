"""
Train the Proview adaptive LUT.

  Stage 1, pre-train on paired data (e.g. MIT-Adobe FiveK: Proview renders -> expert edits):
    python train.py pretrain --inputs data/fivek/inputs --targets data/fivek/expertC --out runs/pre.pt

  Stage 2, style toward a reference set without pairs (e.g. Hasselblad samples):
    python train.py style --init runs/pre.pt --inputs data/fivek/inputs --reference data/hasselblad --out runs/style.pt

Runs on Apple-silicon GPUs (MPS), CUDA or CPU.
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import time
import torch
from proview_lut.model import AdaptiveLut
from proview_lut.data import Paired, Unpaired
from proview_lut import losses


def device():
    if torch.backends.mps.is_available():
        return torch.device("mps")
    if torch.cuda.is_available():
        return torch.device("cuda")
    return torch.device("cpu")


def regularisers(model, luts, a):
    return a.tv * losses.lut_smoothness(model.deltas) + a.mono * losses.lut_monotonicity(luts)


def pretrain(a):
    dev = device()
    data = Paired(a.inputs, a.targets, a.size)
    dl = torch.utils.data.DataLoader(data, batch_size=a.batch, shuffle=True, num_workers=a.workers, drop_last=len(data) > a.batch)
    model = AdaptiveLut().to(dev)
    if a.init:
        model.load_state_dict(torch.load(a.init, map_location=dev))
    opt = torch.optim.Adam(model.parameters(), lr=a.lr)
    for epoch in range(a.epochs):
        t0, total = time.time(), 0.0
        for x, y in dl:
            x, y = x.to(dev), y.to(dev)
            out, luts = model(x)
            loss = losses.oklab_l2(out, y) + regularisers(model, luts, a)
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item() * x.shape[0]
        print(f"epoch {epoch + 1}/{a.epochs} loss {total / len(data):.6f} ({time.time() - t0:.0f} s)", flush=True)
        torch.save(model.state_dict(), a.out)


def style(a):
    dev = device()
    inputs = Unpaired(a.inputs, a.size)
    ref = Unpaired(a.reference, a.size)
    dl = torch.utils.data.DataLoader(inputs, batch_size=a.batch, shuffle=True, num_workers=a.workers, drop_last=len(inputs) > a.batch)
    rl = torch.utils.data.DataLoader(ref, batch_size=a.batch, shuffle=True, num_workers=a.workers)
    model = AdaptiveLut().to(dev)
    if a.init:
        model.load_state_dict(torch.load(a.init, map_location=dev))
    opt = torch.optim.Adam(model.parameters(), lr=a.lr)
    ref_iter = iter(rl)
    for epoch in range(a.epochs):
        t0, total, n = time.time(), 0.0, 0
        for x in dl:
            try:
                r = next(ref_iter)
            except StopIteration:
                ref_iter = iter(rl)
                r = next(ref_iter)
            x, r = x.to(dev), r.to(dev)
            out, luts = model(x)
            loss = a.swd * losses.sliced_wasserstein(losses.pixels_oklab(out), losses.pixels_oklab(r)) \
                + a.identity * losses.oklab_l2(out, x) \
                + a.guard * losses.chroma_guard(out, x) \
                + regularisers(model, luts, a)
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item()
            n += 1
        print(f"epoch {epoch + 1}/{a.epochs} loss {total / max(n, 1):.6f} ({time.time() - t0:.0f} s)", flush=True)
        torch.save(model.state_dict(), a.out)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("stage", choices=["pretrain", "style"])
    p.add_argument("--inputs", required=True)
    p.add_argument("--targets")
    p.add_argument("--reference")
    p.add_argument("--init")
    p.add_argument("--out", required=True)
    p.add_argument("--epochs", type=int, default=100)
    p.add_argument("--batch", type=int, default=8)
    p.add_argument("--size", type=int, default=384)
    p.add_argument("--lr", type=float, default=1e-4)
    p.add_argument("--workers", type=int, default=2)
    p.add_argument("--tv", type=float, default=1e-4)
    p.add_argument("--mono", type=float, default=10.0)
    p.add_argument("--swd", type=float, default=1.0)
    p.add_argument("--identity", type=float, default=0.5)
    p.add_argument("--guard", type=float, default=10.0)
    a = p.parse_args()
    torch.manual_seed(0)
    if a.stage == "pretrain":
        if not a.targets:
            p.error("pretrain needs --targets")
        pretrain(a)
    else:
        if not a.reference:
            p.error("style needs --reference")
        style(a)


if __name__ == "__main__":
    main()
