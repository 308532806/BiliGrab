# BiliGrab 设计系统

这份文档记录界面层的设计决策。它不是风格描述，而是**规则**——每条都能对照代码检查。

设计参照两套规范：

- [pbakaus/impeccable](https://github.com/pbakaus/impeccable) —— 设计规则引擎，本项目的排版／色彩／工艺底线来自它的 `android.md`、`colorize.md`、`typeset.md`、`craft-floor.md`
- [nextlevelbuilder/ui-ux-pro-max-skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) —— 交互规范数据集（触控目标、间距、动效时长、错误处理）

---

## 0. 硬约束：零第三方依赖

整个 APK 只有 **107 KB**，不引入 Material Components，也不引入 AndroidX。

这意味着 Material 3 **是手写实现的**：色角色、字阶、形状阶、动效、涟漪、48dp 触控目标、edge-to-edge、Snackbar、底部表单，全部基于平台原生 API。

这不是保守，而是这个项目的立身之本：核心逻辑（`Http` / `WbiSigner` / `Json` / `BiliApi` / `Model`）不依赖任何 Android API，因此可以在桌面上直接跑 `tools/desktop-verify/TestApi.java` 做端到端联调。引入 AndroidX 会切断这条链路。

---

## 1. 色彩

### 生成方式

色板由 `tools/gen_palette.py` 从单一品牌色相**推导**得出，而不是手挑。

```
种子 #FB7299  →  OKLCH(L=0.726, C=0.171, H=4.5)
              →  按 M3 的色调映射生成全部色角色
              →  输出 values/colors.xml 与 values-night/colors.xml
```

推导链路：色调（CIELAB L\*）→ 相对亮度 Y → OKLab 明度 `L = Y^(1/3)` → 色域映射后的 oklch→srgb。

**角色映射**（M3 规范值）：

| 角色 | 浅色 | 深色 |
|---|---|---|
| primary | tone 40 | tone 80 |
| on_primary | tone 100 | tone 20 |
| primary_container | tone 90 | tone 30 |
| surface | neutral 98 | neutral 6 |
| surface_container | neutral 94 | neutral 12 |
| surface_container_high | neutral 92 | neutral 17 |
| on_surface | neutral 10 | neutral 90 |
| on_surface_variant | neutral_variant 30 | neutral_variant 80 |
| outline | neutral_variant 50 | neutral_variant 60 |

### 对比度是算出来的，不是看出来的

生成器内置完整的 WCAG 校验，**两套主题各 0 项失败**才算通过：

```
正文 / 背景         ≥ 4.5:1
大字号 / 背景       ≥ 3.0:1
控件、图标、焦点环  ≥ 3.0:1
outline / surface   ≥ 3.0:1   ← 边界可见性
```

> 这条校验抓到过一个真实缺陷：`oklab_to_rgb()` 返回的是**线性** RGB，却被直接喂给 `rgb_to_hex`。结果是整块色板严重偏暗（浅色主题的 `surface` 变成 `#020202`）。
> 值得记下的是：**对比度检查当时是全部通过的**——纯黑配纯白当然达标。是人工核对十六进制值才发现的。
> 所以颜色必须同时过「机器校验」和「肉眼核对」两关。

### 深色不是浅色反转

深色主题是**独立设计**的：表面取 neutral tone 6，层级靠 tone 递进（6 → 12 → 17），系统栏图标翻转为浅色。
浅色主题则取 tone 98 作为表面，系统栏图标保持深色。

### 中性色带品牌偏色

neutral 与 neutral_variant 都从品牌色相**微调**而来，而不是纯灰。所以整个界面有一层几乎察觉不到的玫瑰底，色彩是聚合的而不是拼贴的。

---

## 2. 排版

字号**全部**来自 Material 3 字阶，代码里不存在手写的 `12f` / `13f` / `15f` 这类数字。

| 角色 | 字号 / 行高 | 用途 |
|---|---|---|
| HeadlineSmall | 24 / 32 | 空状态主标题 |
| TitleLarge | 22 / 28 | 页面标题、视频标题 |
| TitleMedium | 16 / 24 | 分区标题 |
| TitleSmall | 14 / 20 | 列表主标题、开关标题 |
| BodyLarge | 16 / 24 | 输入框内容 |
| BodyMedium | 14 / 22 | 正文、元信息 |
| BodySmall | 12 / 18 | 辅助说明、错误修复建议 |
| LabelLarge | 14 / 20 | 按钮、芯片 |
| LabelMedium | 12 / 16 | 字段标签 |

- 单位**一律 sp**，跟随系统字体缩放（Android 上写 px 是硬的可用性缺陷）
- 角色名描述**用途**而非数值，所以换字阶不影响调用点
- 行高通过 `android:lineHeight` 给出；API 26/27 上该属性被忽略，会优雅退化，不会崩

---

## 3. 间距与形状

4dp 网格，全部走 `dimens.xml` 的语义名：

| 名称 | 值 | 用途 |
|---|---|---|
| space_xs … space_3xl | 4 / 8 / 12 / 16 / 20 / 24 / 32 | 基础刻度 |
| screen_margin | 16 | 屏幕左右留白 |
| content_gap | 24 | 内容块之间 |
| section_gap | 32 | 分区之间 |
| title_gap | 12 | 标题与所属内容之间 |
| touch_min | 48 | 最小触控目标 |
| touch_gap | 8 | 触控目标最小间隔 |

**标题上方的留白永远大于下方**（`section_gap` 32 > `title_gap` 12），让标题靠近它所统领的内容。

形状阶：`shape_xs` 4 / `shape_sm` 8 / `shape_md` 12 / `shape_lg` 16 / `shape_xl` 28 / `shape_full` 999。

---

## 4. 无投影的层级

层级由**色调高度**表达，不是投影：

```
surface (98)  →  surface_container (94)  →  surface_container_high (92)
```

卡片用 `surface_container_high`，没有 `elevation`。
唯一使用投影的元素是 FAB（`elevation=3dp`）和 Snackbar——因为它们**浮在**内容之上，是真实的 z 轴关系。

> 零偏移的彩色光晕、硬阴影、渐变文字，都属于装饰性投影，本项目一律不用。

---

## 5. 图标

图标来自 Material Symbols 的填充式，**统一 24dp 网格、统一视觉重量**。全部是 `res/drawable/` 里的矢量。

**没有任何 emoji 或 Unicode 字符充当图标。** 这一条是硬性的：图标画出来，不靠字符凑。

启动图标是一个**单一符号**（箭头落进托盘），不是三个符号拼在一起。自适应图标画布 108×108，所有图形收在保证可见的 23..85 区间内；同时提供 `monochrome` 层供 Android 13+ 主题化图标使用。

通知图标是纯白剪影（系统只取 alpha 通道，带颜色的图标会显示成白方块）。

---

## 6. 交互

### 一个界面只有一个主要动作

主操作是底部的**扩展 FAB**，也只有它。解析结果出现后 FAB 才可用，下载中隐藏，完成后恢复。

### 瞬时反馈用 Snackbar，不用 Toast

Toast 既不能带动作，也无法被程序主动收起，多个 Toast 还会排队堆叠。
Snackbar 出现在底部、不夺焦点、能带一个动作、新消息会顶掉旧消息。停留时长：无动作 3.2 秒，有动作 5 秒。

### 设置用底部表单，不用模态对话框

设置是一个**不需要打断用户、也不需要保护焦点**的任务。这类任务上弹模态框是反模式。底部表单同时提供关闭按钮和点击外部／返回键关闭。

### 画质用芯片，不用下拉选择器

芯片组可换行、一眼看全所有选项、一次点击完成选择。手写的 `FlowLayout` 负责换行（平台没有可换行的容器）。

### 选中状态不止靠颜色

分 P 列表项的选中同时用**底色 + 标题色 + 右侧对勾图标**三重编码。色觉障碍用户同样能分辨。

### 文案：说清动作，说清怎么恢复

- 控件名称说明它的动作（「开始下载」而不是「确定」）
- 错误说明**问题**和**恢复方式**两件事：

  > 找不到这个稿件
  > 确认链接完整、稿件未被删除，或换成 BV 号再试

- 原始错误信息也展示出来，用户反馈问题时不用去翻日志

---

## 7. 五种互斥状态

界面任何时刻只处于五种状态之一，**不存在空白屏**：

| 状态 | 表现 |
|---|---|
| 空 | 图标 + 引导文案 + 两条能力说明 |
| 加载 | 骨架屏占位（而不是转圈空等） |
| 错误 | 问题 + 恢复建议 + 原始信息 + 重试按钮 |
| 结果 | 封面 / 标题 / 分 P / 画质 / 仅音频 / FAB |
| 下载中 | 阶段文字 + 百分比 + 确定性进度条 |

封面使用固定高度，图片加载不会造成布局跳动。

---

## 8. 动效

| 场景 | 时长 | 曲线 |
|---|---|---|
| 底部表单进场 | 280ms | emphasized decelerate |
| 底部表单退场 | 180ms | accelerate |
| Snackbar 进出 | 220 / 160ms | decelerate / accelerate quad |

退场一律短于进场。**尊重系统的「移除动画」设置**：`ValueAnimator.areAnimatorsEnabled()` 为 false 时不做任何位移与淡入。

---

## 9. 无障碍

- 每一个图标按钮都有 `contentDescription`
- 输入框标签通过 `android:labelFor` 与控件关联
- 全部触控目标 ≥ 48×48dp，间隔 ≥ 8dp
- 正文对比度 ≥ 4.5:1，大字号 ≥ 3:1
- 系统返回手势／按键全程可用
- 旋转等重建后自动重新解析，用户不必再点一次

---

## 10. 被明确拒绝的做法

以下都是 AI 生成界面的典型痕迹，本项目一条都没有：

- 等大的「图标 + 标题 + 正文」卡片当页面骨架
- 卡片套卡片
- 标题上方的眉标／小字标签
- 01 / 02 / 03 式分节编号
- 用模态框处理不需要打断的任务
- 渐变文字
- 玻璃拟态／模糊当装饰
- 硬阴影、零偏移彩色光晕
- 彩色左边框
- 用 emoji 或 Unicode 字符当图标系统
- 用进度环／迷你图代替真实内容
- 等宽字体当风格装饰

---

## 11. 文件布局

```
app/src/main/res/
├── values/
│   ├── colors.xml          由 tools/gen_palette.py 生成，勿手改
│   ├── dimens.xml          4dp 网格与语义间距
│   ├── styles.xml          M3 字阶与组件样式
│   ├── themes.xml          浅色主题（含系统栏标志）
│   ├── strings.xml         全部文案
│   └── ic_launcher_background.xml
├── values-night/
│   ├── colors.xml          深色色板
│   └── themes.xml          深色主题
├── color/                  状态色（按钮／芯片／列表项／涟漪／开关）
├── drawable/               形状与涟漪，以及全套矢量图标
├── anim/                   表单与 Snackbar 的进出动画
├── layout/
│   ├── activity_main.xml   主界面（五种状态）
│   ├── item_part.xml       分 P 列表项
│   ├── sheet_settings.xml  设置底部表单
│   └── view_snackbar.xml   Snackbar
└── mipmap-anydpi-v26/      自适应启动图标
```

Java 侧的界面支撑组件：

- `FlowLayout.java` —— 可换行的芯片容器
- `Snackbar.java` —— M3 Snackbar 宿主
- `MainActivity.java` —— 状态机与全部交互

---

## 修改色板

```powershell
python tools/gen_palette.py            # 重新生成两套色板并跑对比度校验
# 输出里 "失败 0 项" 才算通过
```

改品牌色编辑 `tools/gen_palette.py` 顶部的种子值，然后重新构建。
