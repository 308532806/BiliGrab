"""生成 BiliGrab 启动图标 PNG。

只使用 Python 标准库（zlib + struct），不依赖 Pillow。
图形定义在 108x108 坐标系里，与 res/drawable/ic_launcher_foreground.xml 保持一致。

用法: python tools/make_icons.py <res 目录>
"""
import os
import struct
import sys
import zlib

# ---------- PNG 编码 ----------

def write_png(path, w, h, rgba_rows):
    """rgba_rows: list[bytearray]，每行 w*4 字节。"""
    raw = bytearray()
    for row in rgba_rows:
        raw.append(0)          # filter type 0 (None)
        raw += row

    def chunk(tag, data):
        out = struct.pack('>I', len(data)) + tag + data
        out += struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)
        return out

    blob = b'\x89PNG\r\n\x1a\n'
    blob += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    blob += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    blob += chunk(b'IEND', b'')

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'wb') as f:
        f.write(blob)


# ---------- 几何 ----------

def point_in_poly(x, y, pts):
    inside = False
    n = len(pts)
    j = n - 1
    for i in range(n):
        xi, yi = pts[i]
        xj, yj = pts[j]
        if (yi > y) != (yj > y):
            xcross = (xj - xi) * (y - yi) / (yj - yi) + xi
            if x < xcross:
                inside = not inside
        j = i
    return inside


def point_in_rounded_rect(x, y, w, h, r):
    if x < 0 or y < 0 or x > w or y > h:
        return False
    cx = min(max(x, r), w - r)
    cy = min(max(y, r), h - r)
    dx = x - cx
    dy = y - cy
    return dx * dx + dy * dy <= r * r


# 与 ic_launcher_foreground.xml 相同的图形
PLAY = [(32, 38), (32, 68), (56, 53)]
STEM = [(63, 34), (70, 34), (70, 50), (63, 50)]
ARROW = [(53, 49), (80, 49), (66.5, 64)]
BASE = [(32, 72), (80, 72), (80, 78), (32, 78)]

FOREGROUND = [PLAY, STEM, ARROW, BASE]

BG = (251, 114, 153)      # #FB7299
FG = (255, 255, 255)

SS = 4                    # 每像素 4x4 超采样


def render(size):
    """渲染一张 size x size 的图标。"""
    rows = []
    inv = 108.0 / size
    for py in range(size):
        row = bytearray()
        for px in range(size):
            bg_hits = 0
            fg_hits = 0
            for sy in range(SS):
                for sx in range(SS):
                    x = (px + (sx + 0.5) / SS) * inv
                    y = (py + (sy + 0.5) / SS) * inv
                    if not point_in_rounded_rect(x, y, 108, 108, 24):
                        continue
                    bg_hits += 1
                    for poly in FOREGROUND:
                        if point_in_poly(x, y, poly):
                            fg_hits += 1
                            break
            total = SS * SS
            if bg_hits == 0:
                row += bytes((0, 0, 0, 0))
                continue
            a_bg = bg_hits / total
            # 前景在背景之上做 alpha 合成
            f = fg_hits / bg_hits if bg_hits else 0.0
            r = BG[0] * (1 - f) + FG[0] * f
            g = BG[1] * (1 - f) + FG[1] * f
            b = BG[2] * (1 - f) + FG[2] * f
            row += bytes((int(r + 0.5), int(g + 0.5), int(b + 0.5), int(a_bg * 255 + 0.5)))
        rows.append(row)
    return rows


DENSITIES = [
    ('mipmap-mdpi', 48),
    ('mipmap-hdpi', 72),
    ('mipmap-xhdpi', 96),
    ('mipmap-xxhdpi', 144),
    ('mipmap-xxxhdpi', 192),
]


def main():
    if len(sys.argv) < 2:
        print('usage: make_icons.py <res-dir>')
        return 1
    res = sys.argv[1]
    for folder, size in DENSITIES:
        out = os.path.join(res, folder, 'ic_launcher.png')
        write_png(out, size, size, render(size))
        print('wrote %s (%dx%d)' % (out, size, size))
    return 0


if __name__ == '__main__':
    sys.exit(main())
