"""Colour helpers in torch: sRGB transfer and Oklab (Ottosson), matching the app's Kotlin code."""
import torch


def srgb_to_linear(x: torch.Tensor) -> torch.Tensor:
    x = x.clamp(0, 1)
    return torch.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


_M1 = torch.tensor([[0.4122214708, 0.5363325363, 0.0514459929],
                    [0.2119034982, 0.6806995451, 0.1073969566],
                    [0.0883024619, 0.2817188376, 0.6299787005]])
_M2 = torch.tensor([[0.2104542553, 0.7936177850, -0.0040720468],
                    [1.9779984951, -2.4285922050, 0.4505937099],
                    [0.0259040371, 0.7827717662, -0.8086757660]])


def srgb_to_oklab(x: torch.Tensor) -> torch.Tensor:
    """(..., 3, H, W) or (N, 3) display sRGB in 0..1 -> Oklab, same layout."""
    channels_first = x.dim() >= 3
    lin = srgb_to_linear(x)
    if channels_first:
        lin = lin.movedim(-3, -1)
    lms = lin @ _M1.to(x).T
    lms = torch.sign(lms) * (lms.abs() + 1e-9) ** (1 / 3)
    lab = lms @ _M2.to(x).T
    return lab.movedim(-1, -3) if channels_first else lab
