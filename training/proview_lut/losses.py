"""Losses: Oklab fidelity, LUT smoothness/monotonicity, unpaired colour-distribution matching."""
import torch
import torch.nn.functional as F
from .color import srgb_to_oklab


def oklab_l2(out: torch.Tensor, target: torch.Tensor, size: int = 128) -> torch.Tensor:
    """Per-pixel Oklab error on downsampled images: a LUT is a global mapping, so low resolution
    is enough and tolerates slight misalignment between a render and an expert edit."""
    a = srgb_to_oklab(F.adaptive_avg_pool2d(out, size))
    b = srgb_to_oklab(F.adaptive_avg_pool2d(target, size))
    return ((a - b) ** 2).mean()


def lut_smoothness(deltas: torch.Tensor) -> torch.Tensor:
    """Total variation of the basis deltas along r, g and b."""
    d = deltas
    return ((d[:, :, :, 1:] - d[:, :, :, :-1]) ** 2).mean() + ((d[:, :, 1:] - d[:, :, :-1]) ** 2).mean() + \
        ((d[:, 1:] - d[:, :-1]) ** 2).mean()


def lut_monotonicity(luts: torch.Tensor) -> torch.Tensor:
    """Each output channel must not decrease along its own input axis (no tone inversions)."""
    r = luts[..., 0]
    g = luts[..., 1]
    b = luts[..., 2]
    return F.relu(r[..., :-1] - r[..., 1:]).mean() + F.relu(g[:, :, :-1] - g[:, :, 1:]).mean() + \
        F.relu(b[:, :-1] - b[:, 1:]).mean()


def sliced_wasserstein(x: torch.Tensor, y: torch.Tensor, projections: int = 64) -> torch.Tensor:
    """Distance between two point clouds (N,3) and (M,3) in Oklab: random 1-D projections,
    sorted, compared quantile by quantile. Matches colour *distributions* without pairing."""
    n = min(x.shape[0], y.shape[0])
    x = x[torch.randperm(x.shape[0], device=x.device)[:n]]
    y = y[torch.randperm(y.shape[0], device=y.device)[:n]]
    dirs = F.normalize(torch.randn(3, projections, device=x.device), dim=0)
    px, _ = torch.sort(x @ dirs, dim=0)
    py, _ = torch.sort(y @ dirs, dim=0)
    return ((px - py) ** 2).mean()


def chroma_guard(out: torch.Tensor, inp: torch.Tensor, max_gain: float = 1.15) -> torch.Tensor:
    """Penalises chroma above max_gain x the input's: the model may not oversaturate."""
    a = srgb_to_oklab(F.adaptive_avg_pool2d(out, 128))
    b = srgb_to_oklab(F.adaptive_avg_pool2d(inp, 128))
    ca = torch.sqrt(a[:, 1] ** 2 + a[:, 2] ** 2 + 1e-8)
    cb = torch.sqrt(b[:, 1] ** 2 + b[:, 2] ** 2 + 1e-8)
    return F.relu(ca - max_gain * cb - 0.005).mean()


def pixels_oklab(img: torch.Tensor, per_image: int = 4096) -> torch.Tensor:
    """Random sample of pixels from (N,3,H,W) as (N*per_image, 3) Oklab points."""
    n, _, h, w = img.shape
    idx = torch.randint(0, h * w, (n, per_image), device=img.device)
    flat = img.reshape(n, 3, -1)
    px = torch.gather(flat, 2, idx.unsqueeze(1).expand(-1, 3, -1))  # (N,3,P)
    return srgb_to_oklab(px.permute(0, 2, 1).reshape(-1, 3))
