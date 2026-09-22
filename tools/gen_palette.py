#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BiliGrab 调色板生成器
=====================

从品牌种子色推导出一套完整的 Material 3 色角色，并生成 Android 资源文件。

为什么要有这个脚本，而不是手写 hex：
  1. impeccable 的 colorize.md 要求「不要只靠肉眼，要校验计算出的对比度」；
  2. M3 的角色是一套映射关系（tone -> role），手写必然漂移；
  3. 种子色一旦调整，整站重新推导只需几秒。

色空间选择 OKLCH：
  - 感知均匀，调整亮度不会明显改变色相；
  - 近黑近白处自动降 chroma，避免极端亮度下的"荧光感"。

M3 的 tone 定义在 CIELAB 的 L* 上，而 OKLab 的 L 对中性色恰好等于
相对亮度的立方根，因此可以直接换算：
      Y = lstar_to_Y(tone),  L_ok = Y ** (1/3)

输出：
  app/src/main/res/values/colors.xml        (浅色)
  app/src/main/res/values-night/colors.xml  (深色)
"""

import math
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

# ----------------------------------------------------------------------
# 颜色空间换算
# ----------------------------------------------------------------------

def srgb_to_linear(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def linear_to_srgb(c):
    return c * 12.92 if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055


def hex_to_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


def rgb_to_hex(r, g, b):
    f = lambda v: max(0, min(255, int(round(v * 255))))
    return "#%02X%02X%02X" % (f(r), f(g), f(b))


def rgb_to_oklab(r, g, b):
    r, g, b = srgb_to_linear(r), srgb_to_linear(g), srgb_to_linear(b)
    l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
    l_, m_, s_ = l ** (1 / 3), m ** (1 / 3), s ** (1 / 3)
    return (
        0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_,
        1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_,
        0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_,
    )


def oklab_to_rgb(L, a, b):
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    return (
        +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    )


def hex_to_oklch(h):
    L, a, bb = rgb_to_oklab(*hex_to_rgb(h))
    C = math.hypot(a, bb)
    H = math.degrees(math.atan2(bb, a)) % 360
    return L, C, H


def lstar_to_y(tone):
    """CIELAB L* -> 相对亮度 Y。"""
    if tone <= 8:
        return tone / 903.3
    return ((tone + 16) / 116) ** 3


def in_gamut(r, g, b, eps=1e-4):
    return all(-eps <= v <= 1 + eps for v in (r, g, b))


def gamut_clip_chroma(L, C, H, steps=24):
    """在该亮度与色相下，二分出 sRGB 内可用的最大 chroma。"""
    lo, hi = 0.0, C
    for _ in range(steps):
        mid = (lo + hi) / 2
        rad = math.radians(H)
        r, g, b = oklab_to_rgb(L, mid * math.cos(rad), mid * math.sin(rad))
        if in_gamut(r, g, b):
            lo = mid
        else:
            hi = mid
    return lo


def tone_to_hex(tone, C, H):
    """tone(0-100, L*) + 目标 chroma/色相 -> 落在 sRGB 内的 hex。

    注意：oklab_to_rgb 返回的是**线性** RGB，必须再做一次伽马编码才是 sRGB。
    漏掉这一步会让整份调色板明显偏暗（线性值被当成 sRGB 使用）。
    """
    L = lstar_to_y(tone) ** (1 / 3)
    C = min(C, gamut_clip_chroma(L, C, H))
    rad = math.radians(H)
    lin = oklab_to_rgb(L, C * math.cos(rad), C * math.sin(rad))
    srgb = [linear_to_srgb(max(0.0, min(1.0, v))) for v in lin]
    return rgb_to_hex(*srgb)


# ----------------------------------------------------------------------
# WCAG 对比度
# ----------------------------------------------------------------------

def rel_luminance(h):
    r, g, b = [srgb_to_linear(c) for c in hex_to_rgb(h)]
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(fg, bg):
    a, b = rel_luminance(fg), rel_luminance(bg)
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


# ----------------------------------------------------------------------
# 由种子色推导整套 tonal palette
# ----------------------------------------------------------------------

SEED = "#FB7299"          # B 站品牌粉

_seedL, _seedC, _seedH = hex_to_oklch(SEED)

# 主色：保留种子色的色相，chroma 略作收敛以保证整条 ramp 可用
H_PRIMARY = _seedH
C_PRIMARY = min(_seedC, 0.20)

# 次要色：同色相、低彩度 —— M3 用它做柔和的强调
H_SECONDARY = _seedH
C_SECONDARY = 0.055

# 第三色：色相偏移 60°，用于平衡主色
H_TERTIARY = (_seedH + 60) % 360
C_TERTIARY = 0.115

# 中性色：带极淡的品牌色相，让灰阶与品牌同源（colorize: 让中性色服务整体）
H_NEUTRAL = _seedH
C_NEUTRAL = 0.006
C_NEUTRAL_VARIANT = 0.020

# 错误色：偏冷的正红，不与品牌粉混淆
H_ERROR = 27.0
C_ERROR = 0.19

PALETTES = {
    "primary": (C_PRIMARY, H_PRIMARY),
    "secondary": (C_SECONDARY, H_SECONDARY),
    "tertiary": (C_TERTIARY, H_TERTIARY),
    "neutral": (C_NEUTRAL, H_NEUTRAL),
    "neutralVariant": (C_NEUTRAL_VARIANT, H_NEUTRAL),
    "error": (C_ERROR, H_ERROR),
}

TONES = [0, 4, 6, 10, 12, 17, 20, 22, 24, 30, 40, 50, 60, 70, 80, 87, 90, 92, 94, 95, 96, 98, 99, 100]

T = {name: {t: tone_to_hex(t, c, h) for t in TONES} for name, (c, h) in PALETTES.items()}


def pick(pal, tone):
    return T[pal][tone]


# ----------------------------------------------------------------------
# M3 色角色映射
# ----------------------------------------------------------------------

LIGHT = {
    "primary":                  ("primary", 40),
    "on_primary":               ("primary", 100),
    "primary_container":        ("primary", 90),
    "on_primary_container":     ("primary", 10),
    "secondary":                ("secondary", 40),
    "on_secondary":             ("secondary", 100),
    "secondary_container":      ("secondary", 90),
    "on_secondary_container":   ("secondary", 10),
    "tertiary":                 ("tertiary", 40),
    "on_tertiary":              ("tertiary", 100),
    "tertiary_container":       ("tertiary", 90),
    "on_tertiary_container":    ("tertiary", 10),
    "error":                    ("error", 40),
    "on_error":                 ("error", 100),
    "error_container":          ("error", 90),
    "on_error_container":       ("error", 10),
    "surface_dim":              ("neutral", 87),
    "surface":                  ("neutral", 98),
    "surface_bright":           ("neutral", 98),
    "surface_container_lowest": ("neutral", 100),
    "surface_container_low":    ("neutral", 96),
    "surface_container":        ("neutral", 94),
    "surface_container_high":   ("neutral", 92),
    "surface_container_highest": ("neutral", 90),
    "on_surface":               ("neutral", 10),
    "surface_variant":          ("neutralVariant", 90),
    "on_surface_variant":       ("neutralVariant", 30),
    "outline":                  ("neutralVariant", 50),
    "outline_variant":          ("neutralVariant", 80),
    "inverse_surface":          ("neutral", 20),
    "inverse_on_surface":       ("neutral", 95),
    "inverse_primary":          ("primary", 80),
    "surface_tint":             ("primary", 40),
}

DARK = {
    "primary":                  ("primary", 80),
    "on_primary":               ("primary", 20),
    "primary_container":        ("primary", 30),
    "on_primary_container":     ("primary", 90),
    "secondary":                ("secondary", 80),
    "on_secondary":             ("secondary", 20),
    "secondary_container":      ("secondary", 30),
    "on_secondary_container":   ("secondary", 90),
    "tertiary":                 ("tertiary", 80),
    "on_tertiary":              ("tertiary", 20),
    "tertiary_container":       ("tertiary", 30),
    "on_tertiary_container":    ("tertiary", 90),
    "error":                    ("error", 80),
    "on_error":                 ("error", 20),
    "error_container":          ("error", 30),
    "on_error_container":       ("error", 90),
    "surface_dim":              ("neutral", 6),
    "surface":                  ("neutral", 6),
    "surface_bright":           ("neutral", 24),
    "surface_container_lowest": ("neutral", 4),
    "surface_container_low":    ("neutral", 10),
    "surface_container":        ("neutral", 12),
    "surface_container_high":   ("neutral", 17),
    "surface_container_highest": ("neutral", 22),
    "on_surface":               ("neutral", 90),
    "surface_variant":          ("neutralVariant", 30),
    "on_surface_variant":       ("neutralVariant", 80),
    "outline":                  ("neutralVariant", 60),
    "outline_variant":          ("neutralVariant", 30),
    "inverse_surface":          ("neutral", 90),
    "inverse_on_surface":       ("neutral", 20),
    "inverse_primary":          ("primary", 40),
    "surface_tint":             ("primary", 80),
}


def resolve(mapping):
    return {k: pick(*v) for k, v in mapping.items()}


LIGHT_C = resolve(LIGHT)
DARK_C = resolve(DARK)


# ----------------------------------------------------------------------
# 对比度校验 —— 不靠肉眼
# ----------------------------------------------------------------------

# (前景, 背景, 最低要求, 说明)
CONTRAST_CHECKS = [
    ("on_surface",           "surface",                    4.5, "正文 / 表面"),
    ("on_surface",           "surface_container",          4.5, "正文 / 卡片"),
    ("on_surface",           "surface_container_high",     4.5, "正文 / 高浮层"),
    ("on_surface_variant",   "surface",                    4.5, "次要文字 / 表面"),
    ("on_surface_variant",   "surface_container",          4.5, "次要文字 / 卡片"),
    ("on_primary",           "primary",                    4.5, "主按钮文字"),
    ("on_primary_container", "primary_container",          4.5, "主色容器文字"),
    ("on_secondary_container", "secondary_container",      4.5, "次色容器文字"),
    ("on_error_container",   "error_container",            4.5, "错误容器文字"),
    ("on_error",             "error",                      4.5, "错误按钮文字"),
    ("error",                "surface",                    3.0, "错误色 / 表面（图形）"),
    ("primary",              "surface",                    3.0, "主色 / 表面（图形）"),
    ("outline",              "surface",                    3.0, "轮廓线 / 表面（非文本 3:1）"),
    ("on_surface",           "surface_container_lowest",   4.5, "正文 / 最低层"),
]


def run_checks(name, palette):
    print("\n" + "=" * 68)
    print(" 对比度校验 — %s" % name)
    print("=" * 68)
    print(" %-6s %-30s %-26s %s" % ("结果", "前景 / 背景", "比值", "用途"))
    print("-" * 68)
    fails = 0
    for fg, bg, minimum, label in CONTRAST_CHECKS:
        ratio = contrast(palette[fg], palette[bg])
        ok = ratio >= minimum
        if not ok:
            fails += 1
        print(" %-6s %-30s %6.2f : 1  (>=%.1f)  %s"
              % ("PASS" if ok else "FAIL", "%s / %s" % (fg, bg), ratio, minimum, label))
    # AAA 增强检查（仅报告，不作为失败）
    print("-" * 68)
    print(" 参考：正文对表面对比度 >= 7:1 为 AAA")
    for fg, bg in [("on_surface", "surface"), ("on_surface", "surface_container")]:
        print("   %-6s %.2f : 1" % (fg + "/" + bg, contrast(palette[fg], palette[bg])))
    print("-" * 68)
    print(" 失败项：%d" % fails)
    return fails


# ----------------------------------------------------------------------
# 生成 Android 资源
# ----------------------------------------------------------------------

HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!--
  由 tools/gen_palette.py 自动生成，请勿手改。
  种子色 %s，色空间 OKLCH，映射遵循 Material 3 色角色。
  重新生成：python tools/gen_palette.py
-->
<resources>
""" % SEED

FOOTER = "</resources>\n"


def emit(palette, title):
    lines = [HEADER, "", "    <!-- %s -->" % title, ""]
    for key in LIGHT.keys():          # 键顺序一致，便于 diff
        lines.append('    <color name="m3_%s">%s</color>' % (key, palette[key]))
    lines.append("")
    # 图标底色等非角色常量
    return "\n".join(lines) + FOOTER


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(here)
    res = os.path.join(repo, "app", "src", "main", "res")

    fails = run_checks("浅色主题", LIGHT_C) + run_checks("深色主题", DARK_C)

    print("\n" + "=" * 68)
    print(" 种子色推导结果")
    print("=" * 68)
    print(" 种子      : %s" % SEED)
    print(" OKLCH     : L=%.3f  C=%.3f  H=%.1f" % (_seedL, _seedC, _seedH))
    print(" 主色色相  : %.1f   应色色相: %.1f   第三色色相: %.1f"
          % (H_PRIMARY, H_SECONDARY, H_TERTIARY))

    light_dir = os.path.join(res, "values")
    dark_dir = os.path.join(res, "values-night")
    os.makedirs(light_dir, exist_ok=True)
    os.makedirs(dark_dir, exist_ok=True)

    with open(os.path.join(light_dir, "colors.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(emit(LIGHT_C, "浅色主题 — Material 3 色角色"))
    with open(os.path.join(dark_dir, "colors.xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(emit(DARK_C, "深色主题 — Material 3 色角色"))

    print("\n 已写入：")
    print("   app/src/main/res/values/colors.xml")
    print("   app/src/main/res/values-night/colors.xml")

    # 附一份人类可读的令牌表
    print("\n" + "=" * 68)
    print(" 角色令牌")
    print("=" * 68)
    print(" %-26s %-10s %-10s" % ("角色", "浅色", "深色"))
    print("-" * 68)
    for k in LIGHT.keys():
        print(" %-26s %-10s %-10s" % ("m3_" + k, LIGHT_C[k], DARK_C[k]))

    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
