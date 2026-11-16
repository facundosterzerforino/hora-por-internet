#!/usr/bin/env python
"""Draws res/drawable/icon.png (48x48): a clock face with a small globe-blue rim.
Pure Python (zlib only), so the icon is reproducible from source."""
import math, pathlib, struct, zlib

N, SS = 48, 4  # size, supersampling

def coverage(x, y):
    """RGBA of the icon at a point (0..N)."""
    cx = cy = N / 2.0
    d = math.hypot(x - cx, y - cy)
    if d > 22.5:
        return (0, 0, 0, 0)
    if d > 19.5:
        return (33, 118, 210, 255)        # blue rim
    def seg(ax, ay, bx, by, w):
        vx, vy = bx - ax, by - ay
        t = max(0.0, min(1.0, ((x - ax) * vx + (y - ay) * vy) / (vx * vx + vy * vy)))
        return math.hypot(x - ax - t * vx, y - ay - t * vy) <= w
    for k in range(12):                    # hour marks
        a = k * math.pi / 6
        if seg(cx + 15.5 * math.sin(a), cy - 15.5 * math.cos(a),
               cx + 17.5 * math.sin(a), cy - 17.5 * math.cos(a), 1.0):
            return (60, 60, 60, 255)
    if seg(cx, cy, cx, cy - 13, 1.6) or seg(cx, cy, cx + 8.5, cy + 3, 1.9):   # 10:10-ish hands
        return (30, 30, 30, 255)
    if seg(cx, cy, cx - 9, cy + 9.5, 0.7):  # second hand
        return (210, 40, 40, 255)
    return (250, 250, 250, 255)            # face

rows = []
for j in range(N):
    row = bytearray([0])
    for i in range(N):
        acc = [0, 0, 0, 0]
        for sj in range(SS):
            for si in range(SS):
                r, g, b, a = coverage(i + (si + 0.5) / SS, j + (sj + 0.5) / SS)
                acc[0] += r * a; acc[1] += g * a; acc[2] += b * a; acc[3] += a
        a = acc[3]
        px = [round(acc[k] / a) if a else 0 for k in range(3)] + [round(a / SS / SS)]
        row += bytes(px)
    rows.append(bytes(row))

def chunk(t, d):
    return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)

png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", N, N, 8, 6, 0, 0, 0)) \
    + chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
out = pathlib.Path(__file__).resolve().parent.parent / "res" / "drawable" / "icon.png"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_bytes(png)
print(out, len(png), "bytes")
