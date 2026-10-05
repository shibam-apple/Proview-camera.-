# Proview Camera — Product & Engineering Spec

Status: **Draft v0.1** · Platform: **Android** · UI: see [`UI_SPEC.md`](UI_SPEC.md)

---

## 1. Product

Proview is a **point-and-shoot camera** whose image quality comes from **computational photography**.
You open it, point, tap, and get a photo that looks like it came from a flagship camera phone.
Nothing needs setting.

- **Opens in Auto.** Every shot is multi-frame: HDR, noise reduction and detail recovery are always on.
- **P / A / S / M** are one swipe away on the mode dial for people who want control (as designed).
- **Quality target:** come as close as possible to the **Vivo X200** in blind side-by-side tests.
- **Colour target:** a **Hasselblad-style natural colour** rendition.
- **Speed target:** no shutter lag, an instant thumbnail, and the final photo in about a second.

### Non-goals for v1
Video, RAW editing, cloud sync, social features.

---

## 2. Honest limits

- Vivo's and Hasselblad's tuning runs on **private ISPs and NPUs**, with per-sensor factory calibration.
  Their algorithms are **not public**, and a third-party app can't run them.
- Proview builds **its own pipeline** and tunes it **by measurement** against reference photos
  (§9). "Close to Vivo" and "Hasselblad-like colour" are measured goals, not claims.
- A third-party app only sees what **Camera2** exposes. Results will vary by phone and will be
  best on phones that support RAW capture.

---

## 3. Device support ("any camera phone")

The app checks each phone at runtime and uses the best path it supports:

| Tier | Requirement | Pipeline |
|---|---|---|
| **A** | Camera2 `FULL`/`LEVEL_3` + `RAW` capability | RAW burst merge (best quality) |
| **B** | Full-resolution YUV burst | YUV burst merge |
| **C** | `LIMITED` / `LEGACY` | Single frame + single-image enhancement |

- A **per-device profile table** holds sensor calibration, the noise model, colour matrices,
  lens quirks and tier overrides. Unknown phones fall back to values derived from Camera2 metadata.
- minSdk **26**, targetSdk = latest stable.

---

## 4. Capture

- **Zero-shutter-lag (ZSL) ring buffer**, about 1.6 s of frames kept in memory while the
  viewfinder runs. The tap selects frames that already exist, so there is no lag.
- **Moment** (from the design): 9 frames spanning −1.2 s … +0.4 s around the press, with an
  automatically picked **Best frame** (sharpness, eyes open, smiles, no motion blur).
  You can scrub to another frame for 3 s before it's committed.
- **Focal rail 24 / 35 / 50 / 85 mm**: switches between the phone's physical lenses, with
  crop plus **multi-frame super-resolution** for in-between focal lengths.
- **Tap to focus, hold to lock AE/AF, Eye AF**, as in the design.

---

## 5. Image pipeline

The RAW path is described here. The YUV path (Tier B) mirrors it from step 3 onwards.

1. **Exposure planning.** Meter the scene and underexpose to protect highlights. Choose the frame
   count and per-frame exposure time from scene brightness and hand/subject motion
   (gyroscope + optical flow).
2. **Reference frame.** Pick the sharpest early frame, weighted by face/eye quality. Moment's
   Best frame uses the same scorer.
3. **Alignment.** Coarse-to-fine tile alignment on a Gaussian pyramid (16×16 tiles, sub-pixel at
   the finest level).
4. **Merge.** Robust frequency-domain temporal merge (Wiener-style, per tile) driven by the
   per-device noise model. Moving content is rejected, so there is no ghosting.
5. **RAW finishing.** Black level, lens-shading correction, white balance (learned AWB with
   skin/grey-world priors), demosaic.
6. **Colour science (Hasselblad target)** — see §6.
7. **Tone.** Local tone mapping (exposure fusion), soft highlight roll-off, film-like global contrast.
8. **ML enhancement.** Learned denoise/detail on the merged image. Semantic segmentation
   (sky, skin, foliage, subject) feeds colour and tone decisions.
9. **Simulated aperture** (design's f/1.4–f/8 "Blur"). Depth estimation plus a lens-accurate bokeh
   renderer. Phone lenses have a fixed aperture, so this is computed.
10. **Looks** (design): Natural, Warm, Cool, Mono, Film, applied as 3D LUTs after colour science
    and previewed live in the viewfinder.
11. **Output.** JPEG/HEIF, plus an optional DNG of the merged result ("RAW + JPEG" in the design).
    EXIF records the real shot settings.

**Night.** When light is low, Auto switches to longer bursts with longer exposures through the
same pipeline. The AI suggestion line tells the user.

---

## 6. Colour science

Goal: a natural, true-to-life rendition with accurate skin tones, restrained saturation and
smooth gradations, in the spirit of **Hasselblad Natural Colour**.

1. **Per-sensor calibration.** Use the colour-checker shots to fit a camera→XYZ matrix per
   illuminant (D65, D50, A, F). Interpolate by estimated colour temperature.
2. **Scene-referred working space** (linear, wide gamut).
3. **"Proview Natural" transform.** A 3D LUT plus hue/saturation curves, fitted to reference photos
   of colour charts and real scenes from a Hasselblad-colour phone. Includes:
   - skin-tone protection (hue band locked, saturation limited);
   - sky blues kept from turning cyan; foliage kept from turning neon;
   - gentle saturation roll-off near the gamut edge.
4. **Output transform** to sRGB / Display P3.

**Measured by:** ΔE2000 on chart patches (target: mean ≤ 3 against the reference), skin-patch ΔE,
and blind preference tests on real scenes.

---

## 7. Speed

| Budget (mid-range phone) | Target |
|---|---|
| Shutter lag | ≈ 0 ms (ZSL) |
| Thumbnail on screen | < 300 ms |
| Final 12 MP photo, Tier A | < 1.5 s |
| Final 12 MP photo, Tier B | < 1.0 s |
| Viewfinder with live look | 30 fps |

How:
- Image maths in **C++ (NDK)** on the **GPU** with **Vulkan compute** (OpenGL ES 3.1 fallback).
- ML on the **NPU/GPU** via **LiteRT** (TensorFlow Lite) delegates, with models quantised to INT8/FP16.
- Processing runs in a background service, so you can keep shooting while earlier photos finish.
- A slower **Kotlin/JVM reference implementation** of every stage is kept, so tests can check
  the fast version produces the same results.

---

## 8. Architecture

```
:app              Jetpack Compose UI, ViewModels, navigation
:camera           Camera2 session, ZSL ring buffer, device tiers & profiles
:pipeline-native  C++ / Vulkan implementation of the pipeline (JNI)
:pipeline-ref     Pure Kotlin/JVM reference pipeline (no Android deps; runs in CI)
:color            Calibration, LUT fitting tools, colour-science transforms
:ml               LiteRT models: denoise, segmentation, depth, frame scoring
:testdata         Sample bursts, chart shots, reference photos (Git LFS)
```

---

## 9. Testing & measurement

**Automatic (CI, every push)**
- Unit tests on synthetic images: a known shift gives alignment error < 0.5 px; known noise
  gives a merged SNR gain ≈ √N.
- Golden tests on sample bursts: PSNR/SSIM against stored results, noise in flat patches,
  % clipped highlights, sharpness. A regression fails the build.
- GPU vs reference parity tests.
- Colour tests: ΔE2000 on chart shots.
- Screenshot tests: each Compose screen pixel-compared against the design renders.
- A **debug APK** is published as a download on every push.

**Benchmark set**
- The same scenes (daylight, backlight, indoor, night, portrait, landscape, skin tones) shot on
  a **Vivo X200** (quality reference), a **Hasselblad-colour phone** (colour reference) and
  Proview on the test phones.
- Scored with the metrics above plus blind side-by-side ratings, tracked per release.

**Feedback loop**
- A debug setting saves the raw burst and metadata JSON with each shot. Bursts shared back
  become new test cases in `:testdata`.

---

## 10. Milestones

**Version 1.0 ships with both the full algorithm and the full UI working well.** There is no
public release of a half-finished app. The milestones below are internal builds, each installable
from CI as a debug APK on the test phone, and v1.0 is released only when the release gate passes.

| | Internal build |
|---|---|
| **M0** | This spec; module skeleton; CI producing a debug APK |
| **M1** | UI built to `UI_SPEC.md`; ZSL capture; single-frame photos saved |
| **M2** | Burst align + merge (reference implementation) with golden tests |
| **M3** | GPU port; speed budgets met on the test phone |
| **M4** | Colour science + looks; first benchmark round |
| **M5** | ML denoise, night mode, Moment best-frame |
| **M6** | Simulated aperture (bokeh), super-resolution zoom |
| **M7** | Tuning across device tiers against the benchmark set |

### v1.0 release gate
Every item must pass on the test phone:
- **UI:** every screen passes the screenshot tests against the design, and every gesture and
  animation in `UI_SPEC.md` works.
- **Algorithm:** every pipeline stage in §5 is on in Auto; blind tests prefer Proview over the
  stock OnePlus camera, or rate it equal, on the benchmark scenes; colour ΔE target met (§6).
- **Speed:** every budget in §7 met.
- **Stability:** no crashes in a 500-shot soak test; no golden-test regressions.

---

## 11. Test phone & references

- **Test phone:** the user's **OnePlus**. Speed budgets and first tuning target this phone.
- **Colour reference:** OnePlus phones from the 9 series onwards are co-developed with
  Hasselblad, so the test phone's stock camera can also provide Hasselblad-colour reference shots.
- **Quality reference:** Vivo X200 shots for the benchmark set (source still to be found).
- **Repo visibility:** public for now.

## 12. Open questions

1. The exact OnePlus **model**. It decides which lenses, RAW support and Hasselblad colour we get.
2. A source of **Vivo X200** reference shots: borrowing a phone, or the same scenes from public samples.
