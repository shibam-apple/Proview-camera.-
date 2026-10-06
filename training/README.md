# Proview adaptive LUT: training on your Mac

The app's colour has two layers:
1. The **Rendition** (`pipeline-ref/.../finish/Rendition.kt`): exposure, local tone, tone curve and
   calibrated colour. It's hand-designed and needs no training.
2. An optional **adaptive LUT** learned here. A small CNN (~0.25M parameters) looks at a
   256×256 thumbnail and blends 3 learned 33³ colour LUTs into one LUT for that photo (Zeng et
   al., "Learning Image-adaptive 3D LUTs", TPAMI 2020). A LUT can only remap colours: it can't
   invent texture or alter faces, so it's safe to train on imperfect data. The phone runs it in
   plain Kotlin (`AdaptiveLut.kt`), which matches PyTorch to within 0.001 (`AdaptiveLutTest`).

## 0. Setup (Apple silicon, once)
```bash
brew install python@3.11 openjdk@17
cd Proview-camera.-/training
python3.11 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt      # PyTorch uses the Mac GPU (MPS) automatically
```

## 1. Pre-train on MIT-Adobe FiveK (learns "what a good photo looks like")
1. Download the FiveK RAW files and the **Expert C** edits from
   https://data.csail.mit.edu/graphics/fivek/. A few hundred photos are enough to start.
2. Build the pairs. Each RAW is rendered by Proview's own Rendition, exactly like the phone:
   ```bash
   python prepare_pairs.py --raw ~/fivek/raw --edits ~/fivek/expertC --out data/fivek
   ```
3. Train (about 2–4 hours for a few thousand pairs on an M-series Mac):
   ```bash
   python train.py pretrain --inputs data/fivek/inputs --targets data/fivek/targets --out runs/pre.pt --epochs 100
   ```

## 2. Style toward Hasselblad (no pairs needed)
Put 15–30 straight-out-of-camera Hasselblad JPEGs in `data/hasselblad/`. Then:
```bash
python train.py style --init runs/pre.pt --inputs data/fivek/inputs --reference data/hasselblad --out runs/style.pt --epochs 30
```
The loss compares **colour distributions** (sliced Wasserstein distance in Oklab) between the
model's outputs and the Hasselblad set, so the photos don't need to show the same scenes. An
identity term keeps the result close to the input, and a guard term blocks oversaturation
(chroma ≤ 1.15× the input's).

To learn **your own** taste instead, edit 50–100 of your phone's DNGs (Apple Photos or
darktable), build pairs with `prepare_pairs.py`, and run `pretrain` with `--init runs/style.pt`.

## 3. Check and export
```bash
python export.py runs/style.pt --check data/validation --out proview_adaptive.lut
```
Export refuses to write the model unless every validation photo passes the guardrails:
- skin hue shift ≤ 3°
- skin chroma gain ≤ 1.15×
- greys stay neutral (Oklab tint ≤ 0.012)

Then send me `proview_adaptive.lut` (about 2 MB) and I'll ship it in the app. If a model is
missing or fails, the app keeps the hand-tuned Rendition.

## Measuring a style
`python tools/style_stats.py folderA folderB` prints tone percentiles, chroma per hue,
highlight chroma, local contrast and clipping for each folder. It's the tool used to tune the
Rendition toward Hasselblad's samples.
