# How Proview's image processing works

This describes what the code does today, step by step, with the actual numbers. Proview has three
processing paths. All of them end in the same **look** stage, so a look renders the same
everywhere.

| Path | Used for | Input | Code |
|---|---|---|---|
| Day | Normal shots | The camera's full-res JPEG | `camera/.../develop/PhotoDeveloper.kt` |
| Night | Auto mode in the dark | A burst of 6–15 RAW frames | `camera/.../night/`, `pipeline-ref/.../merge`, `.../finish` |
| Lab (test mode) | Any existing photo | One decoded image | `pipeline-ref/.../enhance/SingleImageEnhancer.kt` |

Everything numeric lives in `pipeline-ref`, a plain Kotlin/JVM module with unit tests (42 tests),
so the maths can be checked without a phone.

---

## 1. Day path

1. CameraX captures the sensor's full-resolution JPEG. The phone's own ISP has already done
   demosaic, noise reduction and sharpening.
2. The JPEG is decoded and rotated upright.
3. The **look** (section 4) is applied: 3D LUT, halation, grain.
4. It's re-encoded as JPEG at quality 95 and saved to `Pictures/Proview`.

The day path deliberately does little. The phone's ISP is good in daylight, and our gains come
from colour (the look) and from multi-frame work in low light.

---

## 2. Night path

### 2.1 When night mode turns on (`NightPlanner.kt`)
- Every 150 ms the detector reads the auto-exposure's chosen ISO × exposure time. Your EV bias is
  ignored, so dialling −EV doesn't switch night off.
- It turns **on** when that product is at least ISO 800 × 40 ms (ISO 800 at 1/25 s). It turns
  **off** below half of that. Either change has to hold for 0.7 s, so it doesn't flicker at the
  boundary.
- If auto-exposure is maxed out (ISO ≥ 3200, or the exposure time is at its limit), the scene is
  darker than the meter can say. The plan is then marked `meterSaturated` and given more
  exposure.

### 2.2 Planning the burst
- The gyroscope's RMS angular speed sets the steadiness class:
  - tripod: below 0.008 rad/s
  - steady: below 0.05 rad/s
  - otherwise handheld
- The steadiness class sets the longest safe frame:
  - tripod: 1 s
  - steady: 1/3 s
  - handheld: 1/8 s
  - 1/15 s if the scene itself is moving
- Frame count is 2.5 s ÷ frame time, clamped to 6–15 frames. Base frames are 0.7 EV under the
  meter, to protect lamps. Two short frames, 2 EV lower, are also captured.

### 2.3 Capture (`RawBurstCapture.kt`)
- CameraX hands the camera to a Camera2 session, which fires one `captureBurst`. Every frame uses
  the same manual ISO and exposure time, with focus and white balance locked and OIS on.
- Each RAW16 frame is saved with its own metadata:
  - black and white levels
  - the noise profile (S, O)
  - AWB gains
  - the lens-shading map
- The camera's colour calibration (two illuminants) is also saved. DNG copies are written only if
  the debug "save bursts" switch is on.

### 2.4 Align (`TileAligner.kt`, `Pyramid.kt`)
- Work starts from a half-resolution grey image of each frame: each 2×2 Bayer cell is averaged.
- A pyramid is built (2× then 4× steps, as in HDR+). Starting at the coarsest level, every tile finds the offset that
  best matches the reference frame. That offset is passed down and refined at each finer level.
  This is the HDR+ approach.
- Rows are processed in parallel on all cores.

### 2.5 Merge (`BurstMerger.kt`), the core of night mode
- The image is split into 32×32 tiles that overlap by half (stride 16). Each tile gets a
  raised-cosine window, so overlapping tiles add up to exactly 1 and no seams appear.
- For each alternate frame and each tile:
  1. Compute the mean squared difference *d²* to the reference, in the RAW Bayer domain.
  2. Compute the noise expected there from the sensor's model, σ² = S·signal + O.
  3. Compute the weight: `w = clamp(1 − (d² / σ² − 1.4) / 6, 0.02, 1)`.
     - A static tile differs only by noise (d²/σ² ≈ 1). It falls inside the tolerance band (1.4),
       so it gets full weight.
     - A tile where something moved differs far more, so its weight falls towards 0.02. This
       stops ghosting.
- Result = Σ w·frame ÷ Σ w per pixel. Averaging N frames cuts noise by about √N, so 9 frames
  give 3× less noise.

### 2.6 Finish (`Finisher.kt`)
1. **Exposure**: the log-average brightness of the merged image is measured. A gain brings it to
   a key of 0.12. That's low-key on purpose: night should look like night. The gain is limited
   to 1–24×.
2. **Lens shading, white balance, gain** are applied per Bayer channel. Any pixel where a channel
   could have clipped is clipped to neutral, so blown lamps go white instead of magenta.
3. **Demosaic**: Malvar-He-Cutler 5×5 linear filters, with mirrored borders that keep the Bayer
   phase.
4. **True colour**: the camera's forward/colour matrices for its two calibration illuminants are
   interpolated by the scene's colour temperature (as in the DNG spec). This gives the matrix
   *camera RGB → XYZ → sRGB*. This is the "Hasselblad true colour" base: the sensor's measured
   colour, not a stylised one.
5. **Tone**: applied to luminance only, so hues don't shift. Linear up to a shoulder, then a
   smooth roll-off that reaches exactly white at the sensor's clip point.
6. **Chroma noise reduction**: Cb/Cr are box-blurred and luma is left alone. Colour blotches go;
   detail stays.
7. **sRGB encode, dither, rotate**: float precision to the end, then ±½-LSB dither to 8 bits so
   skies don't band.
8. The **look** (section 4) is applied, then JPEG 95.

**Designed but not built yet** (from `NIGHT_MODE.md`):
- sub-pixel alignment refinement
- merging the 2 short frames into lamp highlights
- multi-scale grain-preserving luminance NR
- low-light desaturation
- final sharpening

The merged result today is clean but can be softer than it will be.

---

## 3. Lab: single-image enhancement (`SingleImageEnhancer.kt`)

The Lab is for photos that weren't shot as a burst. There's only one frame, so the noise can't be
averaged away. Each stage has to guess what is noise and what is detail. Every stage is
edge-aware (it smooths along edges, never across them) and **adaptive**: it measures the photo
first and only does what that photo needs, so a good photo is barely touched. The Lab opens on
the **Natural** look, so what you see is the enhancement itself. Film is optional.

1. **Measure the noise (σ)**: Immerkær's method. A 3×3 Laplacian-difference kernel cancels
   smooth gradients, so what's left is mostly noise. Strong edges (gradient > 0.12) are skipped.
   - It's measured at full and half resolution, and the larger value is used. Noise in edited,
     re-compressed or upscaled photos is spread over a few pixels and barely shows at full
     resolution.
   - A floor of 0.004 lets DENOISE also clean JPEG mottling.
2. **Split into luma + chroma** (BT.601 YCbCr).
3. **Luma denoise** (DENOISE): guided filter (He et al.), radius 2–3, *ε* = (2.5σ)² ×
   (0.5 + 1.5·DENOISE), blended by √DENOISE.
   - In flat areas the local variance is below *ε*, so the filter averages, which removes noise.
   - At edges the variance is far above *ε*, so the output follows the input and the edge stays
     sharp.
   - If σ > 0.015 (a really noisy photo), a second, wider pass (radius 5–7, *ε* = (1.2σ)²)
     evens out the coarse blotches the first pass leaves.
4. **Chroma denoise**: the guided filter again, radius ≥ 6 (scaled with the image), guided by the
   *denoised luma*. Colour noise looks worse than luma noise and the eye forgives soft colour,
   so this is strong. Using luma as the guide keeps colour edges lined up with real edges.
5. **Tone and colour** (TONE), in four adaptive steps:
   1. **Dehaze**: haze lifts the black point, so the darkest pixels are grey, not black. The
      0.5th percentile of min(R,G,B) is measured, and up to 1.2·TONE of that lift (above 1.5%)
      is subtracted from every channel and the range is rescaled. This is the dark-channel haze
      model with a constant transmission: contrast and colour come back. A clean photo has a
      black point near 0 and is untouched.
   2. **Shadow lift**: log-luminance is split by an edge-aware guided filter (radius 3.5% of the
      short side, *ε* 0.08) into a **base** (large-scale lighting) and detail.
      - Where the base is darker than 12% linear, it's lifted 80%·TONE of the way up.
      - A whole-image lift of up to 2 stops applies only when the photo's overall key is under
        10%.
      - Gains are always ≥ 1: this step **never darkens**. That keeps bright skies blue instead
        of the grey of the first version.
      - A soft shoulder stops lifted pixels from clipping.
   3. **Contrast curve**: a power curve on luma, anchored at the photo's own median so brightness
      doesn't shift. Its slope at the median is 1 + 0.4·TONE below and 1 + 0.12·TONE above, so it
      is gentler in the highlights and clouds keep their detail. It's applied as a ratio to RGB so
      hues don't shift. Overflow is pulled toward the new luma instead of clipped.
   4. **Vibrance** (in Oklab): muted colours gain up to 50%·TONE chroma. Already-vivid colours
      barely move, skin hues (25°–80°) get half, and near-greys stay neutral.
6. **Detail** (DETAIL):
   - **Clarity**: luma minus an edge-aware blur (guided filter, radius ~1.2% of the short side,
     *ε* 0.004) is boosted 0.9·DETAIL in the midtones. This adds depth and texture without halos
     at strong edges.
   - **Sharpening**: the finest layer (radius 1) is boosted 1.3·DETAIL.
   - Both are **cored** at the noise level (1.5σ / 2σ) and limited to ±12%. They're also scaled
     by a **texture mask** (local standard deviation between 2.5σ and 6σ + 1%). Smooth areas
     like sky, skin and walls get no boost, so their grain and JPEG blocks stay quiet.
7. **Look** (section 4). On Film, the grain is sized to the image: a 12 MP photo gets 1.6 px
   clumps, and a smaller image gets finer, quieter grain. The preview and the saved photo
   match, and the grain never looks like dirt on the preview.

The noise, shadows, contrast and clipping readouts are measured **before** the look, so Film's
grain never counts as noise.

The Lab measures before and after and shows it on screen:

| Readout | Meaning |
|---|---|
| NOISE | Change in measured σ (e.g. −60% = 60% less noise) |
| SHADOWS | Change in how many pixels are in deep shadow (luma < 8%); negative = shadows opened up |
| CONTRAST | Change in local contrast (mean deviation of luma from its 9×9 local mean) |
| CLIPPED | Share of pixels with any channel at 254–255 after processing |
| TIME | Processing time on the phone for the preview size (1600 px long edge) |

**Save** re-runs everything at up to 4096 px and writes `…_LAB.jpg` to `Pictures/Proview`.

---

## 4. Looks, halation and grain (`look/`)

- **LUT**: each look is a colour function on display sRGB, baked into a 33×33×33 3D LUT and read
  with trilinear interpolation. The same LUT is uploaded to the viewfinder shader, so the preview
  matches the photo.
- **Film** (default) is designed in Oklab, a perceptual colour space, so hue and lightness can
  be changed separately:
  - a gentle S-curve on lightness
  - greens muted and leaning teal, yellows calmer
  - reds warmer, blues deeper
  - skin hues (±6°) protected
  - very saturated colours rolled off, not clipped
  - cool shadows, warm highlights

  It's inspired by Fujifilm colour, but it's our own rendition, not a copy.
- **Natural**: identity, the calibrated true colour. **Warm/Cool**: white-balance shifts that keep
  brightness. **Mono**: red-filter-like black and white with film contrast.
- **Halation**: pixels above ~78% brightness are blurred wide (about 0.6% of the width, done at
  quarter resolution for speed). A saturating curve is applied and the result is screened back in
  red-orange (Film strength 0.30). Lamps get a soft glow. Big blown skies don't wash out, because
  the response saturates.
- **Grain**: luminance-only noise, clumped on a 1.6 px lattice so it reads as film grain rather
  than digital speckle. It's strongest in the midtones and fades in deep shadows and highlights.
  Film strength is 0.022.

---

## 5. Viewfinder shader (`FinderProcessor.kt`)

A CameraX effect runs an OpenGL ES 2.0 fragment shader on every preview frame. It applies:
- the look LUT
- slight barrel distortion
- lateral colour fringing near the edges
- softness towards the corners (field curvature)
- vignetting with a brighter centre
- a fixed ground-glass texture with faint Fresnel rings
- a little animated grain

Together these give the waist-level-finder feel. It affects only the preview. Photos aren't
distorted.

---

## 6. Rendition: RAW → photo, Hasselblad-inspired (`finish/Rendition.kt`)

This is the stage that turns calibrated scene light into the finished photo. It's shared by the
upcoming RAW day path and, later, night mode. The philosophy is **restraint**, the opposite of
typical phone processing:

| Stage | What it does |
|---|---|
| **Exposure** | Brings the log-average luminance of the middle 96% of pixels to a key of 0.16. Dark scenes stay dark: beyond +2 stops only half the push is applied, and never more than +3 in all (real darkness is night mode's job). A guard keeps unclipped highlight detail within the shoulder. |
| **Flare** | Measures the veiling black (darkest 0.1%) and removes 80% of it, capped at 0.8%, so photos aren't milky. |
| **Local lift** | Edge-aware base layer (guided filter on log luminance, quarter resolution). Regions darker than 45% of the key are lifted halfway, capped at **1 stop**, so the scene keeps its light (no flat HDR). |
| **Tone** | In log-log space: slope 1.15 at mid-grey, an extra 0.35 in the deep shadows (the toe, for deep blacks with detail), and 0.85 above mid-grey (soft, compressed highlights). Then an exponential shoulder from 0.5 that reaches white exactly at the sensor's clip. Applied to luminance as a ratio, so hues don't shift. |
| **Colour** | Calibrated sensor colour (DNG/Camera2 dual-illuminant matrices) with **no saturation boost (factor 1.0)**. Chroma eases off 45% toward pure white, as film does. Out-of-gamut colours are compressed toward grey at constant Oklab lightness and hue (bisection) instead of clipping, so bright skies and red fabric never change hue. |
| **Sharpening** | Unsharp mask, radius ~1 px, amount 0.35, cored at 1.5/255, overshoot limited to ±6%. No clarity. |

### How it was calibrated
- **Data:** 15 real RAW files from 14 cameras, all open sample files:
  - Canon, Nikon, Sony, Leica, Olympus/OM, Panasonic, a DJI drone, and an LG Nexus 5X phone DNG;
  - 6 more are public metadata test samples, plus a NASA ISS photo.
- **Reference:** every RAW file also contains the camera maker's own finished JPEG of the same
  frame, which gives same-scene pairs.
- **Metrics:** a style-statistics tool measures both sets:
  - Oklab lightness percentiles (blacks, mids, highlights),
  - chroma (mean, 90th percentile, per hue sector, in highlights),
  - local contrast,
  - clipped share.
- **Tuning:** the parameters were set so tone placement matches the makers' JPEGs while colour
  stays *more* restrained:

| | L p5 | L p50 | L p95 | Clipped | Chroma mean / p90 |
|---|---|---|---|---|---|
| Maker JPEGs | 0.277 | 0.529 | 0.765 | 0.49% | 0.033 / 0.066 |
| Proview Rendition | 0.287 | 0.523 | 0.758 | 0.30% | 0.033 / 0.070 |

- **Hasselblad targets:** these will be fitted the same way, from Hasselblad sample photos, once
  they're added.
- **Tools** (desktop, not in the app):
  - `./gradlew renderRaw -Pin=<exported RAWs> -Pout=<dir> [-Pstyle=key=…,contrast=…]` renders RAWs
    through this stage.
  - `./gradlew enhanceSamples` does the same for the Lab.
