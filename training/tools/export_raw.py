# Export each Bayer RAW as <name>.u16 (LE, visible area, even-cropped) + <name>.json for the Kotlin harness.
import sys, os, json, rawpy, numpy as np, tifffile

def rat(v):
    return [v[i] / v[i + 1] for i in range(0, len(v), 2)]

def dng_tags(path):
    with tifffile.TiffFile(path) as t:
        for pg in t.pages:
            tags = {tg.code: tg.value for tg in pg.tags.values()}
            if 50721 in tags: return tags
    return None

src, dst = sys.argv[1], sys.argv[2]
names = {(0,1,1,2): 'RGGB', (1,0,2,1): 'GRBG', (1,2,0,1): 'GBRG', (2,1,1,0): 'BGGR'}
for f in sorted(os.listdir(src)):
    try:
        r = rawpy.imread(os.path.join(src, f))
    except Exception as e:
        print('skip', f, e); continue
    with r:
        if r.raw_pattern is None or r.raw_pattern.shape != (2, 2):
            print('skip (not 2x2 Bayer)', f); continue
        img = r.raw_image_visible
        cols = r.raw_colors_visible
        h, w = img.shape; h -= h % 2; w -= w % 2
        cell = tuple(int(c) % 3 if int(c) != 3 else 1 for c in (cols[0,0], cols[0,1], cols[1,0], cols[1,1]))
        cfa = names.get(cell)
        if cfa is None: print('skip cfa', f, cell); continue
        bl = list(r.black_level_per_channel)  # by colour index R,G,B,G2
        wb = list(r.camera_whitebalance)       # R,G,B,G2
        if wb[3] == 0: wb[3] = wb[1]
        g = wb[1]
        cm = np.array(r.rgb_xyz_matrix)[:3, :3]  # XYZ(D65) -> camera
        color = dict(illuminant1=21, illuminant2=21, colorMatrix1=[float(v) for v in cm.flatten()],
                      colorMatrix2=[float(v) for v in cm.flatten()])
        if f.lower().endswith('.dng'):
            tg = dng_tags(os.path.join(src, f))
            if tg is None: print('skip no dng tags', f); continue
            color = dict(illuminant1=int(tg[50778]), illuminant2=int(tg.get(50779, tg[50778])),
                         colorMatrix1=rat(tg[50721]), colorMatrix2=rat(tg.get(50722, tg[50721])))
            if 50964 in tg: color['forwardMatrix1'] = rat(tg[50964])
            if 50965 in tg: color['forwardMatrix2'] = rat(tg[50965])
            if 50728 in tg:
                n = rat(tg[50728]); wb = [n[1] / n[0], 1.0, n[1] / n[2], 1.0]  # R,G,B,G2 gains
            cm = np.array(color['colorMatrix1'])
        elif not np.any(cm): print('skip no matrix', f); continue
        flip = r.sizes.flip
        orient = {0: 0, 3: 180, 5: 270, 6: 90}.get(flip, 0)
        base = os.path.splitext(f)[0].replace(' ', '_').replace('(', '').replace(')', '')
        np.ascontiguousarray(img[:h, :w]).astype('<u2').tofile(os.path.join(dst, base + '.u16'))
        meta = dict(width=w, height=h, cfa=cfa,
                    black=[bl[0], bl[1], bl[3], bl[2]], white=int(r.white_level),
                    wb=[wb[0] / g, 1.0, wb[3] / g, wb[2] / g],
                    orientation=orient, **color)
        json.dump(meta, open(os.path.join(dst, base + '.json'), 'w'))
        print(f'{base}: {w}x{h} {cfa} wb={[round(x,2) for x in meta["wb"]]} black={bl} white={r.white_level} rot={orient}')
