"""
Image-adaptive 3D LUT.

A small CNN looks at a 256x256 thumbnail (adaptive average pooling, the same as the Kotlin code)
and predicts one weight per basis LUT. The photo's LUT is identity + sum(w_i * delta_i), so an
untrained model changes nothing. The LUT is applied to full-resolution display sRGB with
trilinear interpolation. Layout of a LUT: [b][g][r][channel], red fastest (as Lut3d.kt).
"""
import torch
import torch.nn as nn
import torch.nn.functional as F

THUMB = 256


def identity_lut(size: int) -> torch.Tensor:
    g = torch.linspace(0, 1, size)
    b, gg, r = torch.meshgrid(g, g, g, indexing="ij")
    return torch.stack([r, gg, b], dim=-1)  # (size, size, size, 3), index [b][g][r]


class AdaptiveLut(nn.Module):
    def __init__(self, n_basis: int = 3, size: int = 33, channels=(16, 32, 64, 128, 128)):
        super().__init__()
        self.size = size
        self.n_basis = n_basis
        self.channels = tuple(channels)
        layers, cin = [], 3
        for c in channels:
            layers.append(nn.Conv2d(cin, c, 3, stride=2, padding=1))
            cin = c
        self.convs = nn.ModuleList(layers)
        self.head = nn.Linear(cin, n_basis)
        nn.init.zeros_(self.head.weight)
        nn.init.zeros_(self.head.bias)
        # Deltas start small and random so gradients flow to every basis.
        self.deltas = nn.Parameter(torch.randn(n_basis, size, size, size, 3) * 1e-3)
        self.register_buffer("identity", identity_lut(size))

    @staticmethod
    def thumbnail(img: torch.Tensor) -> torch.Tensor:
        return F.adaptive_avg_pool2d(img, (THUMB, THUMB))

    def weights(self, img: torch.Tensor) -> torch.Tensor:
        x = self.thumbnail(img)
        for conv in self.convs:
            x = F.leaky_relu(conv(x), 0.2)
        x = x.mean(dim=(2, 3))
        return self.head(x)  # (N, n_basis)

    def luts(self, img: torch.Tensor) -> torch.Tensor:
        w = self.weights(img)
        # Clamped like the phone (AdaptiveLut.kt), so training sees exactly what ships.
        return (self.identity + torch.einsum("nk,kbgrc->nbgrc", w, self.deltas)).clamp(0, 1)

    def forward(self, img: torch.Tensor):
        lut = self.luts(img)
        return apply_lut(img, lut), lut


def apply_lut(img: torch.Tensor, lut: torch.Tensor) -> torch.Tensor:
    """Trilinear lookup. img (N,3,H,W) in 0..1, lut (N,S,S,S,3) indexed [b][g][r]."""
    n, _, h, w = img.shape
    s = lut.shape[1]
    x = img.clamp(0, 1) * (s - 1)
    i0 = x.floor().clamp(max=s - 2).long()
    t = x - i0
    r0, g0, b0 = i0[:, 0], i0[:, 1], i0[:, 2]
    tr, tg, tb = t[:, 0:1], t[:, 1:2], t[:, 2:3]
    flat = lut.reshape(n, s * s * s, 3)

    def corner(dr, dg, db):
        idx = ((b0 + db) * s + (g0 + dg)) * s + (r0 + dr)  # (N,H,W)
        v = torch.gather(flat, 1, idx.reshape(n, -1, 1).expand(-1, -1, 3))
        return v.reshape(n, h, w, 3).permute(0, 3, 1, 2)

    c00 = corner(0, 0, 0) + (corner(1, 0, 0) - corner(0, 0, 0)) * tr
    c10 = corner(0, 1, 0) + (corner(1, 1, 0) - corner(0, 1, 0)) * tr
    c01 = corner(0, 0, 1) + (corner(1, 0, 1) - corner(0, 0, 1)) * tr
    c11 = corner(0, 1, 1) + (corner(1, 1, 1) - corner(0, 1, 1)) * tr
    c0 = c00 + (c10 - c00) * tg
    c1 = c01 + (c11 - c01) * tg
    return c0 + (c1 - c0) * tb
