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
averaged away. Each stage has to guess what is noise and what is detail. Every stage below is
edge-aware, meaning it smooths along edges, never across them.

1. **Measure the noise (σ)**: Immerkær's method. The image is convolved with a 3×3 Laplacian
   difference kernel that cancels smooth gradients, so what's left is mostly noise. Pixels on
   strong edges (gradient > 0.12) are skipped so edges don't count as noise. Every later stage
   is set from this σ, so a clean photo is barely touched and a noisy one is cleaned hard.
2. **Split into luma + chroma** (BT.601 YCbCr).
3. **Luma denoise: guided filter** (He et al.), radius 2, *ε* = (2.5σ)² × (0.5 + 1.5·DENOISE).
   - In flat areas the local variance is below *ε*, so the filter averages, which removes noise.
   - At edges the variance is far above *ε*, so the output follows the input and the edge stays
     sharp.
   - The DENOISE knob blends between original and filtered and also raises *ε*.
4. **Chroma denoise**: the guided filter again, radius 6, guided by the *denoised luma*. Colour
   noise is much worse than luma noise and the eye forgives soft colour, so this is strong.
   Using luma as the guide keeps colour edges lined up with real edges.
5. **Local tone mapping** (TONE knob):
   - Take log-luminance and split it into a **base** layer (large-scale lighting: a guided filter
     with radius 2% of the image and ε 0.15) and a **detail** layer (everything else).
   - Compress only the base, by 0.45·TONE, around the image's key, with a nudge of the key
     towards middle grey (0.16). This lifts dark regions and holds back bright ones.
   - Add the detail back unchanged, so texture keeps its contrast while the overall range
     shrinks. This is what HDR-style "shadow recovery without a flat look" means.
   - The per-pixel gain is limited to 0.4–6×. The same soft highlight shoulder as the night path
     stops highlights clipping.
   - Colour is scaled by the same gain, so saturation is kept.
6. **Sharpening** (DETAIL knob): an unsharp mask with radius 1 (two box passes ≈ Gaussian).
   - It's **cored at 2σ**: differences smaller than the noise level are ignored, so it sharpens
     edges, not grain.
   - Amount = 1.2 × DETAIL.
7. **Look** (section 4).

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
