# Looks, film texture and the viewfinder

## Looks (`pipeline-ref/.../look/Look.kt`)
The design's five looks are colour transforms on display sRGB, baked into a 33³ 3D LUT. The same
LUT drives the live viewfinder shader and the photo pipeline, so what you frame is what you get.
Swipe sideways on the viewfinder to change look.

| Look | What it does |
|---|---|
| **Film** (default) | Our own film-style rendition in the spirit of Fujifilm colour (not a copy of any proprietary film simulation). Gentle S-curve in Oklab lightness; greens muted and leaning teal; yellows calmer; reds warmer; blues deeper; skin hue protected (±6°); highly saturated colours roll off instead of clipping; cool shadows, warm highlights. Halation and grain. |
| **Natural** | The sensor's calibrated true colour (Hasselblad philosophy). Identity. |
| **Warm / Cool** | Natural with a white-balance shift that keeps brightness. |
| **Mono** | Red-filter-like black and white, film contrast, stronger grain. |

## Film texture (`FilmRender.kt`)
- **Halation**: highlights above ~78% are blurred wide at quarter resolution (≈0.6% of the
  width) and screened back in red-orange, with a saturating response so small lamps glow and
  blown skies don't over-glow. This is the soft glow around lights.
- **Grain**: luminance-only, clumped on a 1.6 px lattice, strongest in the midtones.

## Viewfinder shader (`camera/.../finder/FinderProcessor.kt`)
A CameraX effect on the preview stream (OpenGL ES 2.0, Android 8+). PreviewView still does
rotation, cropping and the rounded clip, so the UI layout is unchanged. Per frame:
look LUT, slight barrel distortion, lateral colour fringing at the edges, field-curvature
softness towards the corners, natural vignetting with a bright centre, a static ground-glass
texture with faint Fresnel rings, and a breath of animated grain. Photos are not distorted; the
optics are a viewfinder feel only.

## Scene-reactive UI
The camera's analysis stream averages the scene colour; the top glow, focal pill and pro panel
spill take its hue (grey scenes fall back to the design's warm spill), animated over 0.7 s.

## Tests
`LookTest`, `FilmRenderTest`: Natural is identity; Film keeps greys neutral and tones monotonic,
mutes and teal-shifts greens, protects skin, never overflows; LUT matches the function within 1%;
halation is warm and local; grain keeps mean brightness.
