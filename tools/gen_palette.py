#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BiliGrab 色板校验器
====================

这个脚本**不生成任何色值，也不写任何文件**。它是只读的。

历史（很重要，别改回去）
------------------------
2026 重构之前，色板是由本脚本从品牌种子色 #FB7299 推导出来的 M3 色角色，
颜色名是 `m3_*`，脚本会直接覆写：
    app/src/main/res/values/colors.xml
    app/src/main/res/values-night/colors.xml

重构之后色板改成了**手写**的 `hyper_*` / `neum_*` / `glass_*` 令牌
（新拟态的底色不是一个色相能推导出来的，见 DESIGN.md §1）。
于是脚本原来的产出与真实色板**完全不同源**：再跑一次只会往资源里塞一批
`m3_*` 死资源，并把真实色板整个覆盖掉。

所以它被重新定位成**对比度校验器**：读真实色板，算 WCAG 对比度，逐项报告，
有硬性不达标就以非零退出码结束。**校验能力留下了，生成能力删掉了。**

被校验的文件（全部只读）
------------------------
    app/src/main/res/values/colors.xml          浅色令牌
    app/src/main/res/values-night/colors.xml    深色令牌
    app/src/main/res/values/arrays.xml          浅色 5 套主题色
    app/src/main/res/values-night/arrays.xml    深色 5 套主题色

两套亮度公式，别混用
--------------------
1) **WCAG 相对亮度** —— 本脚本的 `relative_luminance()`。
   必须先把 sRGB 通道**反伽马**（线性化：c <= 0.04045 时 c / 12.92，否则
   ((c + 0.055) / 1.055) ** 2.4），再加权 0.2126R + 0.7152G + 0.0722B。
   对比度 = (L亮 + 0.05) / (L暗 + 0.05)。
   **不能**拿 0-255 的通道原值直接加权算对比度 —— 那样伽马没还原，得到的
   比值是错的（普遍偏低，会把不达标的说成达标）。

2) **感知亮度** —— `perceptual_brightness()`，即 0.299R + 0.587G + 0.114B，
   直接吃 0-255 的通道原值。这是 `java/.../ui/HyperTheme.java` 里
   `contrastOn()` 用的公式，阈值 160，只负责在一堆背景上**挑**深字还是白字。
   它**不是**对比度公式，不能拿它度量可读性 —— 它挑出来的那对颜色好不好读，
   仍然要用公式 (1) 去算。本脚本复现 `contrastOn()` 的唯一目的，就是把
   "它实际会挑的那对颜色"拿去算真对比度。

退出码
------
    0  两套色板都没有硬门槛失败项（提示级项照常打印，不影响退出码）
    1  至少一项硬门槛失败

用法
----
    python tools/gen_palette.py
"""

import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


# ----------------------------------------------------------------------
# 资源解析 —— 只读，从不写回
# ----------------------------------------------------------------------

COLOR_RE = re.compile(
    '<color\\s+name="([A-Za-z0-9_]+)"\\s*>\\s*(#[0-9A-Fa-f]{6,8})\\s*</color>')


def parse_colors(path):
    """从资源文件里抽出 {name: '#RRGGBB' / '#AARRGGBB'}。

    只认「内容为空、值是 6/8 位十六进制」这种最常见写法。
    颜色引用（`@color/...`）不解析 —— 真出现说明色板被间接了一层，得人来看。
    """
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    return {m.group(1): m.group(2).upper() for m in COLOR_RE.finditer(text)}


def parse_schemes(path):
    """抽出 scheme_N_primary / scheme_N_variant，返回 [(索引, primary, variant)]。"""
    colors = parse_colors(path)
    idx = sorted(int(m.group(1))
                 for m in (re.fullmatch(r"scheme_(\d+)_primary", k) for k in colors)
                 if m)
    return [(i, colors["scheme_%d_primary" % i], colors.get("scheme_%d_variant" % i))
            for i in idx]


# ----------------------------------------------------------------------
# 颜色与对比度
# ----------------------------------------------------------------------

def parse_hex(h):
    """'#RRGGBB' / '#AARRGGBB' -> (r, g, b, a)，通道 0-255，alpha 0-1。"""
    h = h.lstrip("#")
    if len(h) == 8:
        a = int(h[0:2], 16) / 255.0
        h = h[2:]
    else:
        a = 1.0
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a


def rgb_of(h):
    r, g, b, _ = parse_hex(h)
    return r, g, b


def rgb_str(h):
    r, g, b = rgb_of(h)
    return "#%02X%02X%02X" % (r, g, b)


def to_hex(rgb):
    return "#%02X%02X%02X" % tuple(max(0, min(255, int(round(v)))) for v in rgb)


def srgb_to_linear(c):
    """sRGB -> 线性光。这一步（反伽马）是对比度算对的前提。"""
    c = c / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def relative_luminance(h):
    """WCAG 2.1 相对亮度，输入色值本身。"""
    r, g, b = rgb_of(h)
    return (0.2126 * srgb_to_linear(r)
            + 0.7152 * srgb_to_linear(g)
            + 0.0722 * srgb_to_linear(b))


def contrast(fg, bg):
    """WCAG 对比度 = (亮 + 0.05) / (暗 + 0.05)。两色都必须是实色（无 alpha）。"""
    a, b = relative_luminance(fg), relative_luminance(bg)
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


def perceptual_brightness(h):
    """旧的感知亮度公式。

    2026 重构后 HyperTheme.contrastOn() **已经不再用这个公式**（改为直接比较
    两个候选色的 WCAG 对比度）。这个函数与下面的 CONTRAST_ON_THRESHOLD 保留下来，
    只为了在输出里显示"旧判据会挑哪一边"，方便回看当初错在哪 —— 它不再参与判定。
    """
    r, g, b = rgb_of(h)
    return 0.299 * r + 0.587 * g + 0.114 * b


CONTRAST_ON_THRESHOLD = 160


def contrast_on(h):
    """复现 HyperTheme.contrastOn()：在两个候选色里取对比度更高的那个。

    旧实现是 `感知亮度 > 160 ? #111111 : #FFFFFF`，在 #FB7299 上算出 159.4，
    差 0.6 判给了白字 —— 白字 2.64:1，而黑字有 7.17:1。
    现在的实现不做估算：两个候选色都是已知的，直接各算一遍取高的。
    """
    ink = contrast(h, "#111111")
    paper = contrast(h, "#FFFFFF")
    return "#111111" if ink >= paper else "#FFFFFF"


def contrast_on_legacy(h):
    """旧判据，仅用于输出对照。不要用它做判定。"""
    return "#111111" if perceptual_brightness(h) > CONTRAST_ON_THRESHOLD else "#FFFFFF"


def flatten(fg, bg):
    """把带 alpha 的 fg 叠到实色 bg 上，返回近似的实色。

    按 sRGB 通道直接线性插值 —— 平台默认的 8bit 混色就在 sRGB 空间做，
    不还原伽马；这里要的是"屏幕上看起来是什么色"，所以跟着平台走。
    """
    r, g, b, a = parse_hex(fg)
    br, bgc, bb = rgb_of(bg)
    return to_hex((br + a * (r - br), bgc + a * (g - bgc), bb + a * (b - bb)))


def with_alpha_15(h):
    """实色 -> 15% 透明（0x26 = 38/255 ≈ 14.9%，与 MainActivity.applyPill 一致）。"""
    r, g, b = rgb_of(h)
    return "#26%02X%02X%02X" % (r, g, b)


# ----------------------------------------------------------------------
# 门槛
# ----------------------------------------------------------------------

# WCAG 2.1 AA：正文 < 18sp（或加粗 < 14sp）要 4.5:1；
# 大字（>= 18sp，即 Text.TitleLarge 及以上；或 >= 14sp 加粗）3:1；
# 非文本（图标、边界、焦点环）3:1。
TH_BODY = 4.5
TH_LARGE = 3.0
TH_NONTEXT = 3.0

THRESHOLD_LEGEND = [
    ("正文 < 18sp（Text.Body* / Text.Label* / Text.TitleSmall）", TH_BODY),
    ("大字 >= 18sp（Text.TitleLarge / Text.AppBar）", TH_LARGE),
    ("非文本（图标、描边、边界）", TH_NONTEXT),
]

# 严重级别：
#   HARD      硬门槛 —— 计入「失败 N 项」，并决定退出码
#   ADVISORY  提示级 —— 设计上有意为之的弱对比，单独计数，不影响退出码
SEV_HARD = "HARD"
SEV_ADVISORY = "ADVISORY"


# ----------------------------------------------------------------------
# 校验项：[1] 表面与文字
# ----------------------------------------------------------------------
#
# 字号不是猜的，取该颜色在 styles.xml / layout 里的真实落点：
#   hyper_text_primary   -> Text.BodyLarge 16sp / Text.LabelLarge 14sp
#   hyper_text_secondary -> Text.BodyMedium 14sp / Text.BodySmall 12sp / FieldLabel 12sp
#   hyper_text_tertiary  -> textColorHint + bg_sheet_handle（见下）
#
# 硬门槛项 / 提示级项的划分依据写在每项的注释里。
def surface_checks(palette, label):
    rows = [
        (SEV_HARD, TH_BODY, "hyper_text_primary", "hyper_background",
         "正文，Text.BodyLarge 16sp", ""),
        (SEV_HARD, TH_BODY, "hyper_text_primary", "hyper_card",
         "正文落在卡片上", ""),
        (SEV_HARD, TH_BODY, "hyper_text_secondary", "hyper_card",
         "次要文字，Text.BodyMedium 14sp", ""),
        # 这一项才是**实机上的真实数字**：新拟态引擎下 HyperTheme.neumBase() 返回的是
        # hyper_background（卡片与页面同色是这套体系的核心约定），所以次要文字绝大多数
        # 时候是落在背景色上而不是 hyper_card 上。上面那项 hyper_card 只是玻璃引擎的分支。
        (SEV_HARD, TH_BODY, "hyper_text_secondary", "hyper_background",
         "次要文字，Text.BodySmall 12sp / FieldLabel 12sp",
         "新拟态下 neumBase = hyper_background，这一项才是实机数值"),
        # ---- 提示级：三级文字 ----
        #
        # hyper_text_tertiary 在设计上是**弱提示**，只有两个落点：
        #   1) android:textColorHint —— 输入框占位符
        #   2) drawable/bg_sheet_handle.xml —— 底部表单的 4dp 拖拽把手（非文本）
        # 都不承载信息主体，所以按「提示级」记，门槛放到 3:1（非文本那一档）。
        #
        # 注意：WCAG 1.4.3 对占位符文本仍然要求 4.5:1，所以这一项就算过了 3:1
        # 也**不算合规**，只是"可以接受"。数值照打，提不提是设计决策，
        # 脚本不替人做决定 —— 这里只是不拿它拦退出码。
        (SEV_ADVISORY, TH_NONTEXT, "hyper_text_tertiary", "hyper_background",
         "三级文字 / hint，刻意弱提示，非正文",
         "设计例外：提示级门槛 3:1；WCAG 对占位符仍要求 4.5:1"),
        (SEV_ADVISORY, TH_NONTEXT, "hyper_text_tertiary", "hyper_card",
         "三级文字 / hint 落在卡片上",
         "设计例外：提示级门槛 3:1；WCAG 对占位符仍要求 4.5:1"),
    ]
    # 把令牌名解析成实际色值（缺令牌直接报错，别静默跳过）
    resolved = []
    for severity, threshold, fg_name, bg_name, usage, note in rows:
        for name in (fg_name, bg_name):
            if name not in palette:
                raise KeyError("%s 缺少令牌 %s" % (label, name))
        resolved.append((severity, threshold, fg_name, bg_name, usage, note))
    return resolved


# ----------------------------------------------------------------------
# 输出
# ----------------------------------------------------------------------

LINE = "=" * 78
THIN = "-" * 78


def header(title):
    print()
    print(LINE)
    print(" " + title)
    print(LINE)


def print_legend():
    print(" 门槛与字号对应（WCAG 2.1 AA）：")
    for label, threshold in THRESHOLD_LEGEND:
        print("   %-52s >= %.1f:1" % (label, threshold))
    print()
    print(" 两套亮度公式（别混用）：")
    print("   对比度 -> WCAG 相对亮度：先做 sRGB 反伽马，再按 0.2126/0.7152/0.0722 加权")
    print("   contrastOn() -> 感知亮度 0.299/0.587/0.114，阈值 %d，" % CONTRAST_ON_THRESHOLD)
    print("                   只用来挑黑字还是白字，**不参与**对比度计算")


def report_surfaces(palette, label):
    print(" %-4s %-40s %7s %6s  %s"
          % ("结果", "前景 / 背景", "对比度", "门槛", "字号 / 用途"))
    print(THIN)
    hard = adv = 0
    for severity, threshold, fg_name, bg_name, usage, note in surface_checks(palette, label):
        fg, bg = palette[fg_name], palette[bg_name]
        ratio = contrast(fg, bg)
        ok = ratio >= threshold
        if not ok:
            if severity == SEV_HARD:
                hard += 1
            else:
                adv += 1
        if ok:
            mark = "PASS"
        else:
            mark = "FAIL" if severity == SEV_HARD else "提示"
        print(" %-4s %-40s %6.2f:1 %5.1f  %s"
              % (mark, "%s / %s" % (fg_name, bg_name), ratio, threshold, usage))
        print("      %s  %s" % ("hard" if severity == SEV_HARD else "advisory",
                                note if note else "硬门槛"))
    return hard, adv


def report_schemes(schemes, label):
    """每套主题色做实底时，配 contrastOn() 会挑出的文字色。

    真实落点：设置面板里的主题色芯片（view_chip.xml = Text.LabelLarge 14sp bold）。
    底色由 NeumorphicDrawable.colors() 刷成 scheme_N_primary，文字色由
    MainActivity 调 HyperTheme.contrastOn() 决定。14sp bold < 18sp，
    所以按正文门槛 4.5:1 校验。
    """
    print(" 主题色是用户可选的实底芯片，文字色由 contrastOn() 决定。")
    print(" contrastOn() 现在的判据：把 #111111 与 #FFFFFF 各算一遍 WCAG 对比度，取高的那个。")
    print(" 它挑出的那一侧仍不达标时，说明这套主题色配任何黑/白字都读不清 —— 那是色值的问题。")
    print(THIN)
    print(" %-4s %-26s %-9s %7s %6s  %s"
          % ("结果", "主题色", "文字色", "对比度", "门槛", "判据"))
    print(THIN)
    hard = 0
    for i, primary, _variant in schemes:
        text = contrast_on(primary)
        ratio = contrast(text, primary)
        ok = ratio >= TH_BODY
        if not ok:
            hard += 1
        print(" %-4s %-26s %-9s %6.2f:1 %5.1f  取对比度高的一侧 -> %s"
              % ("PASS" if ok else "FAIL",
                 "scheme_%d_primary %s" % (i, rgb_str(primary)), text, ratio, TH_BODY,
                 text))
        if not ok:
            alt = "#FFFFFF" if text == "#111111" else "#111111"
            print("      另一侧 %s 只有 %.2f:1 —— 两侧都不达 4.5:1，这套主题色配黑白字都读不清，"
                  % (alt, contrast(alt, primary)))
            print("      需要调色值本身，不是调 contrastOn()。")
        else:
            legacy = contrast_on_legacy(primary)
            if legacy != text:
                print("      （旧判据会挑成 %s：%.2f:1 —— 2026 重构前这里配错过）"
                      % (legacy, contrast(legacy, primary)))
    return hard


def report_pills(schemes, card):
    """状态药丸：主色 15% 叠在卡片色上，文字仍是主色。

    真实落点：MainActivity.applyPill() —— 底色 (primary & 0x00FFFFFF) | 0x26000000
    （0x26 = 38/255 ≈ 15%），文字色 = primary 本身。
    代码注释写明「药丸是标签而不是按钮，不需要那么强的对比」，属设计例外，
    所以按提示级 3:1 记，不拦退出码。
    """
    print(" applyPill()：底色 = primary 15%%，文字 = primary。卡片色 %s。" % rgb_str(card))
    print(" 代码注释：「药丸是标签而不是按钮，不需要那么强的对比」→ 提示级 3:1。")
    print(THIN)
    print(" %-4s %-22s %-12s %-9s %7s %6s"
          % ("结果", "主题色", "叠色后", "文字色", "对比度", "门槛"))
    print(THIN)
    adv = 0
    for i, primary, _variant in schemes:
        composited = flatten(with_alpha_15(primary), card)
        ratio = contrast(primary, composited)
        ok = ratio >= TH_NONTEXT
        if not ok:
            adv += 1
        print(" %-4s %-22s %-12s %-9s %6.2f:1 %5.1f"
              % ("PASS" if ok else "提示",
                 "scheme_%d %s" % (i, rgb_str(primary)), composited, rgb_str(primary),
                 ratio, TH_NONTEXT))
    return adv


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repo = os.path.dirname(here)
    res = os.path.join(repo, "app", "src", "main", "res")

    p_light = os.path.join(res, "values", "colors.xml")
    p_dark = os.path.join(res, "values-night", "colors.xml")
    p_light_s = os.path.join(res, "values", "arrays.xml")
    p_dark_s = os.path.join(res, "values-night", "arrays.xml")
    for path in (p_light, p_dark, p_light_s, p_dark_s):
        if not os.path.isfile(path):
            print("找不到资源文件：%s" % path)
            return 1

    exact = {"浅色主题": (parse_colors(p_light), parse_schemes(p_light_s)),
             "深色主题": (parse_colors(p_dark), parse_schemes(p_dark_s))}

    header("BiliGrab 色板校验器 —— 只读，不生成也不写入任何色值")
    print(" 校验对象（真实色板）：")
    print("   values/colors.xml          %-40s %d 个色值"
          % ("(浅色)", len(exact["浅色主题"][0])))
    print("   values-night/colors.xml    %-40s %d 个色值"
          % ("(深色)", len(exact["深色主题"][0])))
    print("   values/arrays.xml          %-40s %d 套主题色"
          % ("(浅色)", len(exact["浅色主题"][1])))
    print("   values-night/arrays.xml    %-40s %d 套主题色"
          % ("(深色)", len(exact["深色主题"][1])))
    print()
    print_legend()

    total_hard = 0
    total_adv = 0
    for label in ("浅色主题", "深色主题"):
        palette, schemes = exact[label]

        header("[%s] 1. 表面与文字" % label)
        hard, adv = report_surfaces(palette, label)
        total_hard += hard
        total_adv += adv

        header("[%s] 2. 主题色实底 + contrastOn() 挑出的文字色" % label)
        total_hard += report_schemes(schemes, label)

        header("[%s] 3. 状态药丸（主色 15%% 叠在卡片色上）" % label)
        total_adv += report_pills(schemes, palette["hyper_card"])

    header("汇总")
    print(" 硬门槛失败项：%d" % total_hard)
    print(" 提示级项（设计例外，不影响退出码）：%d" % total_adv)
    print()
    if total_hard:
        print(" 失败 %d 项 —— 有项不达 WCAG AA 硬门槛，退出码 1。" % total_hard)
        print(" 要修先分清：是改色值，还是改这一项的适用范围。不要为了跑绿而放水。")
    else:
        print(" 失败 0 项 —— 两套色板的硬门槛全部通过，退出码 0。")
        if total_adv:
            print(" 提示级项请人工过一眼（脚本不替设计做决定）。")
    print()
    return 1 if total_hard else 0


if __name__ == "__main__":
    sys.exit(main())
