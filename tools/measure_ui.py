"""
从截图像素做地面真值测量。

uiautomator dump 在 OPPO 上会返回被裁剪的窗口边界（窗口报 720dp，但 FAB
实际渲染到 735dp），所以触控尺寸不能信 dump，得直接从渲染结果里量。
"""
import sys
from PIL import Image

DENSITY = 3.0
SHOT = sys.argv[1] if len(sys.argv) > 1 else r"E:\dsh-sdktest\shots\04-result-full.png"

img = Image.open(SHOT).convert("RGB")
W, H = img.size
print(f"截图 {W}x{H}px  =  {W / DENSITY:.0f}dp x {H / DENSITY:.0f}dp  (density {DENSITY})")


def near(c, target, tol=6):
    return all(abs(c[i] - target[i]) <= tol for i in range(3))


def find_regions(target, tol=6, x0=0, y0=0, x1=None, y1=None, min_run=8):
    """按行扫描，找出目标色的连续水平区间，再纵向合并成矩形。"""
    x1 = W if x1 is None else x1
    y1 = H if y1 is None else y1
    rows = {}
    for y in range(y0, y1):
        runs, start = [], None
        for x in range(x0, x1):
            hit = near(img.getpixel((x, y)), target, tol)
            if hit and start is None:
                start = x
            elif not hit and start is not None:
                if x - start >= min_run:
                    runs.append((start, x - 1))
                start = None
        if start is not None and x1 - start >= min_run:
            runs.append((start, x1 - 1))
        if runs:
            rows[y] = runs

    rects = []
    for y, runs in rows.items():
        for (a, b) in runs:
            placed = False
            for r in rects:
                if abs(r["y1"] - y) <= 2 and not (b < r["x0"] - 4 or a > r["x1"] + 4):
                    r["x0"] = min(r["x0"], a); r["x1"] = max(r["x1"], b)
                    r["y1"] = y; r["rows"] += 1
                    placed = True
                    break
            if not placed:
                rects.append({"x0": a, "x1": b, "y0": y, "y1": y, "rows": 1})
    out = []
    for r in rects:
        w, h = r["x1"] - r["x0"] + 1, r["y1"] - r["y0"] + 1
        if r["rows"] >= 8 and w >= 24:
            out.append((r["x0"], r["y0"], r["x1"], r["y1"], w, h))
    return sorted(out, key=lambda t: -t[4] * t[5])


PRIMARY_CONTAINER = (255, 214, 223)   # #FFD6DF  选中芯片 / FAB 底
SECONDARY_CONTAINER = (255, 214, 223)

print("\n=== 主色块（#FFD6DF，选中态与 FAB 都用它）===")
for (a, b, c, d, w, h) in find_regions(PRIMARY_CONTAINER)[:8]:
    print(f"  x={a/DENSITY:6.1f}..{c/DENSITY:6.1f}dp  y={b/DENSITY:6.1f}..{d/DENSITY:6.1f}dp"
          f"   {w/DENSITY:5.1f}dp x {h/DENSITY:5.1f}dp")

print("\n=== 触控目标判定（Material 3 要求 >= 48dp）===")


def judge(label, w_dp, h_dp):
    ok = w_dp >= 48 and h_dp >= 48
    print(f"  {'OK  ' if ok else 'FAIL'} {label:<22} {w_dp:5.1f}dp x {h_dp:5.1f}dp")
    return ok


# FAB：取最靠下、最靠右的大色块
fab = None
for (a, b, c, d, w, h) in find_regions(PRIMARY_CONTAINER, x0=int(W * 0.5), y0=int(H * 0.75)):
    if fab is None or d > fab[3]:
        fab = (a, b, c, d, w, h)
if fab:
    judge("FAB 开始下载", fab[4] / DENSITY, fab[5] / DENSITY)
    print(f"       FAB 底边距屏幕底 {((H - fab[3]) / DENSITY):.1f}dp")

# 选中芯片：y 在 520..600dp 之间
print()
for (a, b, c, d, w, h) in find_regions(PRIMARY_CONTAINER, y0=int(510 * DENSITY), y1=int(600 * DENSITY))[:3]:
    judge("选中芯片", w / DENSITY, h / DENSITY)

print("\n=== 颜色直方图：底部区域（看 FAB / 导航栏）===")
from collections import Counter
cnt = Counter()
for y in range(int(H * 0.85), H, 3):
    for x in range(0, W, 3):
        cnt[img.getpixel((x, y))] += 1
for c, n in cnt.most_common(6):
    print(f"  #{c[0]:02X}{c[1]:02X}{c[2]:02X}  {n}")
