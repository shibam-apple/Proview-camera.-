# Night mode

Status: **N1 in progress** · Target device: OnePlus 7T Pro (Snapdragon 855+, IMX586 12 MP binned RAW)

## Goal
Night photos with the character of Hasselblad night photography (user reference: a Hasselblad
X2D II moonlit forest shot, processed with HNNR):

1. **The night stays night.** Low-key, deep blacks; the scene emerges from darkness instead of
   being pushed to daylight brightness.
2. **Organic grain, not digital mush.** Residual noise is fine, luminance-only and even, like film
   grain. No blotchy or colour noise, no waxy smoothing.
3. **Smooth gradation.** Fog, skies and falloff into black never band or step.
4. **Quiet colour.** Muted, natural colour that fades as light falls, as the eye sees at night.

What is realistic on a phone: mood, grain and colour are processing choices and fully in reach.
Noise is close: the phone's brighter lens plus ~15 merged frames makes up most of a ~47x sensor
area gap. Resolution and depth of field of a 100 MP medium-format camera are not reachable.

## User experience
- In Auto, when the scene is dark for 1 s, the scene chip turns into **"Night · N s"** with a
  moon, and the suggestion line says **"Night · hold still for N s"**. Tap the chip to turn night
  off for this scene (it re-arms when the scene gets bright again).
- Press the shutter: the accent arc around the shutter fills, the disc counts down, the
  viewfinder dims and a crosshair shows steadiness (keep the dot on the cross).
- Handheld capture budget: **2.5 s** (agreed). On a tripod: up to 6 s.

## Algorithm
1. **Plan** (`camera/.../night/NightPlanner.kt`)
   - Night on when auto-exposure needs ≥ ISO 1600 at 1/30 s (or equivalent ISO × time);
     off below 60% of that; 1 s dwell both ways.
   - Steadiness from gyroscope RMS angular speed: tripod < 0.008 rad/s, steady < 0.05 rad/s.
   - Longest frame: 1 s (tripod), 1/3 s (steady, OIS), 1/8 s (handheld), 1/15 s if the scene moves.
   - Frames = budget ÷ frame time, clamped to 6–15. Base frames sit 0.7 EV under the metered
     exposure to protect light sources; 2 short frames 2 EV lower keep detail in lamps.
2. **Capture** (`camera/.../night/RawBurstCapture.kt`): the camera is handed from CameraX to a
   Camera2 session; one `captureBurst` with manual ISO/time, locked focus and white balance,
   OIS on, lens shading map on. Each RAW frame is written as a DNG with its own capture result;
   `burst.json` keeps per-frame noise profile, black/white levels, AWB gains; `gyro.json` keeps
   the gyroscope trace.
3. **Reference frame**: sharpest of the first three base frames (gradient energy + gyro).
4. **Align**: pyramid tile alignment (`pipeline-ref/.../TileAligner.kt`, done) + sub-pixel refinement.
5. **Merge**: per-tile frequency-domain robust merge weighted by the sensor noise model
   (HDR+-style); mismatching tiles are down-weighted so moving subjects don't ghost. Noise falls ~√N.
6. **Grain-preserving noise reduction**: multi-scale. Chroma noise removed almost completely,
   coarse luminance blotches strongly attenuated, the finest luminance grain kept at a
   controlled level, giving organic, film-like texture.
7. **Night white balance**: start from the camera's estimate, correct gently, keep the warmth of
   artificial light.
8. **Low-key tone**: brightness target set from the true scene light (moonlight stays moonlight),
   shadows lifted only enough to read the scene, smooth highlight roll-off around lamps; float
   precision until output, then dithered to 8 bits so gradients never band.
9. **Night colour**: luminance-dependent desaturation in very low light on top of the
   Hasselblad-style natural colour; optional **Night Mono** look.
10. Sharpen gently and encode JPEG (+ optional merged DNG).

## Speed (Snapdragon 855+)
Reference Kotlin implementation for tests → C++ on 8 cores (< 4 s after capture) → Adreno 640
GPU compute (< 2 s).

## Tests
- Synthetic night scenes with the 7T Pro noise model, random hand shake and a moving object:
  √N noise reduction, no ghosting, no clipping around lights, golden-image regression.
- **Grain test**: residual noise power concentrated at high spatial frequency, chroma noise near
  zero. **Banding test**: smooth fog gradients without steps. **Mood test**: mean brightness of a
  dark scene stays low-key.
- Real bursts saved from the phone become permanent test cases.
- Comparison: OnePlus Nightscape on the same scenes; public Vivo X200 and Hasselblad night
  samples by scene type.

## Milestones
| | Deliverable |
|---|---|
| **N1** | Night detection, capture UI, Camera2 RAW burst with metadata, "Save night bursts" debug switch. The photo is still the camera's own single frame. |
| **N2** | Align + merge + basic RAW finishing: first Proview night photos. |
| **N3** | Grain-preserving NR, night tone curve, night colour, tuning against references. |
| **N4** | GPU speed-up, short-frame highlight recovery for light sources. |

## Collecting test bursts (N1)
Library → long-press "Library" → **Save night bursts** on. Each night shot then leaves a folder
in `Download/Proview/Bursts/<time>/` with `frame_XX.dng`, `burst.json` and `gyro.json`. Zip a
folder in the Files app and share it; each burst is ~200–400 MB.
