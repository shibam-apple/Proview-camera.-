"""Datasets: paired (render -> expert edit) for pre-training, and unpaired reference photos for style."""
import os
import random
import numpy as np
import torch
from PIL import Image

EXTS = (".png", ".jpg", ".jpeg", ".tif", ".tiff")


def load_image(path: str, long_edge: int) -> torch.Tensor:
    im = Image.open(path)
    if im.mode not in ("RGB",):
        im = im.convert("RGB")
    im.thumbnail((long_edge, long_edge), Image.LANCZOS)
    a = np.asarray(im).astype(np.float32) / 255.0
    return torch.from_numpy(a).permute(2, 0, 1)


def _files(d):
    return sorted(f for f in os.listdir(d) if f.lower().endswith(EXTS))


class Paired(torch.utils.data.Dataset):
    """inputs/<name>.* are Proview renders, targets/<name>.* the edits to learn (same names)."""

    def __init__(self, inputs: str, targets: str, size: int = 384):
        tmap = {os.path.splitext(f)[0]: f for f in _files(targets)}
        self.items = [(os.path.join(inputs, f), os.path.join(targets, tmap[os.path.splitext(f)[0]]))
                      for f in _files(inputs) if os.path.splitext(f)[0] in tmap]
        if not self.items:
            raise ValueError(f"no matching names between {inputs} and {targets}")
        self.size = size

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        a, b = self.items[i]
        x = load_image(a, self.size)
        y = load_image(b, self.size)
        # Different renderers can differ by a few pixels of crop; the loss works at low resolution,
        # so just resize the target onto the input grid.
        y = torch.nn.functional.interpolate(y[None], size=x.shape[1:], mode="area")[0]
        if random.random() < 0.5:
            x, y = x.flip(-1), y.flip(-1)
        return _square(x, self.size), _square(y, self.size)


class Unpaired(torch.utils.data.Dataset):
    """Any folder of photos (inputs to restyle, or the reference style set)."""

    def __init__(self, folder: str, size: int = 384):
        self.paths = [os.path.join(folder, f) for f in _files(folder)]
        if not self.paths:
            raise ValueError(f"no images in {folder}")
        self.size = size

    def __len__(self):
        return len(self.paths)

    def __getitem__(self, i):
        x = load_image(self.paths[i], self.size)
        if random.random() < 0.5:
            x = x.flip(-1)
        return _square(x, self.size)


def _square(x: torch.Tensor, size: int) -> torch.Tensor:
    """Centre square crop at a fixed size so images batch together (the LUT is global; framing
    doesn't matter)."""
    _, h, w = x.shape
    s = min(h, w)
    t, l = (h - s) // 2, (w - s) // 2
    x = x[:, t:t + s, l:l + s]
    return torch.nn.functional.interpolate(x[None], size=(size, size), mode="area")[0]
