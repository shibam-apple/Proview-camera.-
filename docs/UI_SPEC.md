# Proview Camera — UI Spec

Source of truth: the design canvas **Camera App**, copied into [`design/`](design/):
`Camera.dc.html`, `Library.dc.html`, `Detail.dc.html` (live canvas:
https://claude.ai/artifact/4NN2f92ELGonmW8wZNecps).
The app must match these **exactly**. When this document and the design files disagree, the
design files win. Approved deviations are listed in §8.

All measurements are on a **390 × 844** reference frame. In Compose, 1 design px = 1 dp, and
layouts scale proportionally on other screen sizes.

---

## 1. Design tokens

### Colour
| Token | Value | Use |
|---|---|---|
| `bg` | `#0A0A0B` | Screen background |
| `text1` | `#F5F5F7` | Primary text and icons |
| `text2` | `rgba(245,245,247,.62)` | Secondary text |
| `text3` | `rgba(245,245,247,.38)` | Tertiary text, idle tick ring |
| `line` | `rgba(245,245,247,.16)` | Hairlines |
| `accent` | `#F0832A` | Shutter, selection, focus, needles |
| `accentAlt` | `#E8553D` · `#F2C14E` · `#EDE7DA` | Alternative accents (theme option) |
| `onAccent` | `#17120A` | Text and icons on accent fills |
| `glass` (camera) | `rgba(28,28,30,.42)`, blur 30, saturate 1.6, 0.5 px border `rgba(255,255,255,.18)` | Pills, panels |
| `glass` (library/detail) | `rgba(24,21,19,.5)`, blur 26, saturate 1.7, 1 px border `rgba(255,255,255,.12)`, inset top highlight `rgba(255,255,255,.2)`, shadow `0 10 30 rgba(0,0,0,.28)` | Buttons, badges |

### Type
Two families: **Jost** for numerals and the instrument panel (`--fn`), **Inter** for UI text (`--f`).

| Role | Spec |
|---|---|
| `t-disp` | Jost 600, 64/0.9, −0.02em, tabular numerals (used at 58, 30, 22 px) |
| `t-pre` | Jost 500, 34/1, opacity .7 (prefix "f/", "1/", used at 26 px) |
| `t-val` | Jost 600, 17/1, tabular |
| `t-leg` | Jost 600, 14/1, +0.08em (mode dial labels) |
| `t-ttl` | Inter 600, 15/1 |
| `t-body` | Inter 500, 13/1.2 |
| `t-cap` | Inter 500, 11/1, +0.02em, tabular |
| `t-over` | Inter 600, 10/1, +0.1em, uppercase |

### Motion
- **Press:** scale to .94 (camera) or .9 (library/detail), spring `cubic-bezier(.3,1.6,.5,1)` over .22 s.
- **Value tick:** `tickA/B`, .24 s, a 10 px rise with fade in.
- Every keyframe, duration and curve in `Camera.dc.html` is part of the spec (boot brackets,
  scan line, shutter blink/slap/bloom, focus hunt, AE/AF lock ring, row reveals, notch, toast…).

### Haptics
Taken from the design's `vibrate()` calls: value detent 4 ms, mode change 7–8 ms, shutter
`[10,40,14]`, AE/AF lock 16 ms, Auto→pro switch `[8,50,10,40,6]`, shortcut reassign `[8,30,8]`.
On Android, use `HapticFeedbackConstants` / `VibrationEffect` equivalents.

---

## 2. Camera screen (`Camera.dc.html`)

Layers from back to front:

1. **Ambient backdrop:** the live preview blurred 46 px, desaturated and dimmed, scaled 1.15,
   filling the screen. A bottom gradient darkens to `rgba(8,8,9,.82)`. A warm top glow
   (`topGlow`) follows exposure.
2. **Top bar** (left 22, right 16, top 48, height 40):
   - mode name (`t-ttl`) over a sub-label (`t-cap`, `text2`), e.g. "Aperture / You set blur";
   - **focal pill**, min-width 84 × 40, radius 20, glass, showing `24 mm`. Tap moves to the next lens.
3. **Viewfinder** (left/right 12, top 94, radius 36, black, shadow `0 24 70 rgba(0,0,0,.5)`).
   - Height **592 in Auto, 350 in P/A/S/M**, animated over .5 s.
   - Overlays: rule-of-thirds grid (shown while touching or when Grid is on), level indicator,
     Eye-AF tracker (40 px, breathing), focus brackets (64 px), AE/AF lock (72 px rounded box
     plus "AE · AF LOCK" tag), and a black blink on capture.
   - **AI suggestion line** at top 8, height 44: accent sparkle icon plus a `t-body` hint
     (e.g. "Golden hour. Try the Warm look"). Tap applies it, then the line shows "Applied".
   - **Toast** at top 56: glass, height 30, radius 15.
   - **Auto only:** scene chip (glass, height 30: dot plus "Landscape · Golden hour") at bottom-left;
     frame counter `[ 299 ]` at bottom-right.
   - **Pro only:** histogram (glass 104 × 50, radius 14, 16 bars) at bottom-left.
   - **Focal rail** (right 10, vertically centred, 56 × 200, radius 28): ticks every 10 px,
     majors at 24/35/50/85, an accent notch. Supports drag, fling with inertia, snapping to lenses
     and keyboard input.
   - **Moment sheet** (glass, inset 12, height 104, radius 26): title "Moment · Best frame",
     time offset, a 9-frame strip (40 px tall, the selected frame scaled 1.2 with a white ring,
     a dot under the best frame) and a 3 s drain bar.
4. **Pro instrument panel** (left 12, top 448, 366 × 238, radius 34, glass with warm spill).
   Shown in P/A/S/M only; it enters with a spring from 46 px below at scale .93.
   - Row 1 (top 12): **WB** 62×40 · **Focus** 74×40 · **ISO** 164×40 (ISO numeral 30 px).
   - Row 2 (top 58): **Aperture** hero 168×88 (`f/` plus a 58 px numeral, "APERTURE" overline,
     dot scale) · **Shutter** hero 168×88 (`1/` plus numeral, "SHUTTER").
   - Tapping a selected control again turns the hero row into a **detail ruler**: name, hint,
     a scrolling value carousel (22 px) and a fine-control strip (ticks, an EV box or a focus scale).
     A "Film look" chip appears for ISO/WB, and a close button.
   - Row 3 (top 161): **EV meter**, 170 px scale from −3 to +3 with an accent needle;
     **EV comp** button 76×36.
   - Row 4 (top 198): mode letter 62×36 (accent), **Eye AF** 36×36, **Grid** 36×36,
     frame counter `[ 299 ]` (20 px).
   - Hairlines at y 53 / 150 / 194, plus vertical dividers.
   - Boot animation on entry: corner brackets lock on, a scan line passes, rows reveal one by one.
5. **Mode dial** (left 22, top 692, 346 × 40, radius 20, glass): **Auto · P · A · S · M**.
   Segment offsets 3/79/145/211/277 and widths 76/66/66/66/66. A white thumb that you can drag,
   with detents and haptics.
6. **Bottom row:**
   - **Library thumbnail** 56×56 at (28, 752), radius 16. It pops in after each capture.
   - **Shutter** 88 px at (151, 736): outer ring 1.5 px at 78% white, inner accent disc (inset 9).
     In pro modes with Moment on, a spinning dotted accent ring is added. A dotted tick ring
     (100 px) rotates 30° per shot. In Moment, the disc shows a check mark.
   - **Two shortcut pills** 46 px at x 258 and 316, y 757. Tap toggles; **hold for 500 ms**
     cycles the function: Eye AF, Lock, Moment, Grid, Histo, Peaking, Level.

### Values
| Control | Steps |
|---|---|
| Aperture | f/1.4, 1.8, 2.8, 4, 5.6, 8 (simulated, see SPEC §5.9) |
| Shutter | 1/60, 1/125, 1/250, 1/500, 1/1000 |
| ISO | 100, 200, 400, 800, 1600, 3200 |
| White balance | Auto, Sun, Cloud, Warm |
| Focus | AF, ∞, 10 m, 5 m, 3 m, 2 m, 1 m |
| EV | −3 … +3 in 1/3 steps |
| Looks | Natural, Warm, Cool, Mono, Film |
| Focal | 24, 35, 50, 85 mm |

### Mode behaviour
| Mode | You set | Auto sets |
|---|---|---|
| Auto | nothing | everything (pro panel hidden) |
| P | EV | aperture + shutter |
| A | aperture | shutter |
| S | shutter | aperture |
| M | aperture + shutter (+ ISO) | — (EV disabled; the meter shows the result) |

Swiping an auto-controlled value switches to **M**, seeded with the values Auto had chosen.

### Gestures
- Viewfinder: **tap** to focus; **hold 480 ms** to lock AE/AF; **swipe sideways ≥ 50 px**
  to change the look; **swipe up/down** for exposure, one 1/3-stop click (with a haptic tick)
  every 22 px, up = brighter, value shown in the toast; tap while locked to unlock.
- Focal pill: tap for the next lens; **drag on the number** (right or up = longer), 36 px per lens.
- Panel: swipe up, down or sideways anywhere on it to change the selected value.
  The step sizes (px per step) are aperture 20, shutter 20, ISO 24, WB 28, focus 24, EV 12.
- Focal rail: drag 70 px per lens, fling with a 260 ms decay, tap to jump.

---

## 3. Library screen (`Library.dc.html`)

- Header: "Library" Inter 600 34 px (−0.02em), date overline at the right. Padding 20, height 56, top 54.
- Grid: 2 columns, gap 12, padding 16/12/150. Tiles have a **5:6** aspect, radius 34 and a
  1 px inner ring.
  - Glass heart button 36 px (top-left 12); time (15 px, top-right); frame number (28 px,
    bottom-left 34); meta "f/1.8 · 1/250" (10 px); glass **RAW** badge (height 24, bottom-right).
  - Entry animation: `facePop` .5 s, staggered.
- Bottom bar (bottom 30): a glass segmented control **All · RAW · Faves** (each segment 84×44,
  with an accent slider on a spring) and a 60 px camera button.
- Swipe sideways ≥ 60 px over the grid to change the filter.

## 4. Photo detail screen (`Detail.dc.html`)

- Nav (height 44): glass back button 44 px, centred time (17 px) with "5 OCT · IMG 0148"
  overline, glass "more" button 44 px.
- Photo: fills the space, radius 36, top fade; glass "RAW + JPEG" chip (height 30) at bottom-left 16.
- **Ring complications** (4 columns, 76 px rings, stroke 6, track 14% white, accent arc
  animated with `ringIn` .9 s, staggered by .08 s): **Blur** f/1.8 · **Freeze** 1/250 ·
  **ISO** 100 · **White** 5600K.
- Actions: **"Shoot this look"** (flex, 56 px tall, accent, `onAccent` text) loads the photo's
  settings into the camera; **favourite** 56 px glass (heart pops to 1.45×); **share** 56 px glass.

---

## 5. Navigation
Camera ↔ Library (thumbnail / camera button) → Detail (tile) → back to Library, or
"Shoot this look" → Camera.

## 6. Accessibility
Keep every label from the design (`aria-label`s become `contentDescription`s), slider
semantics for the rail and rulers, 44 dp minimum touch targets, and focus outlines
(2 px `#F5F5F7`, 3 px offset) for keyboard and D-pad users.

## 7. Implementation notes (Compose)
- Glass: `RenderEffect` blur on API 31+; below that, a translucent fill with no blur.
- Fonts: Jost and Inter bundled as resources.
- Spring curves: map each `cubic-bezier` to a `CubicBezierEasing` or a matching `spring()`.
- Live looks and WB in the viewfinder are rendered with a GPU shader, not view filters.

## 8. Approved deviations from the design
1. The app **opens in Auto** (the design opens in Aperture). User decision.
2. SF Pro (iOS-only) is replaced with **Inter**.
3. CSS backdrop effects are mapped to the Android equivalents in §7.
