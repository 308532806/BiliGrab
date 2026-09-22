# BiliGrab 设计系统 —— Hyper-Neumorphic

> 本文档记录 **1.4.0 起**界面层使用的设计系统：Hyper-Neumorphic（新拟态 + HyperOS 语言 + 玻璃态三合一）。
> 它取代了 1.3.0 及以前那套手写的 Material 3 —— 旧规则仍留在 [DESIGN.md](../DESIGN.md)，
> 但其中的**色彩、排版、间距与形状、层级表达、动效**几节已经由本文档覆盖，见 DESIGN.md 的「2026 重构」小节。

---

## 0. 上游与授权边界

设计依据来自上游开源规范仓库
[`Yang-Ya-Chao/android-design-system-skills`](https://github.com/Yang-Ya-Chao/android-design-system-skills)：

| 文件 | 作用 |
| --- | --- |
| `hyper-neumorphic.md` | 双引擎主规范（新拟态 + 玻璃态），本项目的**主要依据** |
| `neumorphism.md` | 纯新拟态的对照参考 |
| `glassmorphism.md` | 纯玻璃态的对照参考 |

### 为什么不复制原文

那三份规范文件的 front-matter 里**只有 `name:` 与 `description:` 两个字段，`license:` 字段是空的**
（等同于未声明授权条件）。所以本项目的做法是：

- **不复制、不提交上游原文**，也不把它的代码当成代码来源；
- 只按规范描述的视觉约定，用**纯 Java + XML 在本项目里重新实现**（上游是 Jetpack Compose，
  本项目零 AndroidX，两者不能共用代码）；
- 参考原文只作为本地资料存在 `docs/design-refs/`，该目录**已写进 `.gitignore`，不会进入仓库**。

上游给的是 Compose 库（`LocalAppSkin`、`NeumorphicModifiers`、`AppSpacing`、`Type.kt` 这些 API）。
本文记录的是**等价实现**：参数对齐，代码重写。

---

## 1. 双引擎

| 引擎 | 取值 | 默认 | 「表面怎么画」 |
| --- | --- | --- | --- |
| 新拟态 | `Prefs.SKIN_NEUMORPHISM` = 0 | ✅ | 双色浮雕阴影（凸起 / 凹陷），不透明表面 |
| 玻璃态 | `Prefs.SKIN_GLASS` = 1 | | 整屏 mesh + 半透明叠加 + 方向光 + 渐变描边 |

核心约定：**两者共用同一套尺寸、圆角、动效与交互参数**，只有表面怎么画不同。
布局、样式、自定义 View、点击反馈全都是同一份 —— 加一套引擎不需要第二套 XML。

- 存储键：`Prefs.KEY_SKIN` = `"ui_skin"`，默认值 `SKIN_NEUMORPHISM`。
- 切换入口：设置面板「外观」卡片里的**「视觉引擎」**一行（`@string/settings_skin`，
  两个选项 `settings_skin_neumorphism` / `settings_skin_glass`）。
- 切换行为：`setSkin()` → `sheet.dismiss()` → `recreate()`。引擎决定的是一整棵树怎么画，
  不是单个控件的属性，所以整屏重建而不是局部刷新。
- 判断收在一处：

  ```java
  // HyperTheme
  public static boolean isGlass(Context ctx) {
      return new Prefs(ctx).skin() == Prefs.SKIN_GLASS;
  }
  ```

`NeumorphicSurface.apply()` 与 `NeumAttr.apply()` 都会把引擎开关透传给 `NeumorphicDrawable`，
所以布局里的 `neu*` 属性在两套引擎下都成立。

---

## 2. 渲染原理

`ui/NeumorphicDrawable` 是整套体系里**唯一**负责「厚度」的类，底下只有两个原生 API：
`android.graphics.BlurMaskFilter` 与 `Canvas.drawRoundRect`。上游规范虽然是 Compose 写的，
但它的 `NeumorphicModifiers` 底下用的也是这两个，所以纯 Java 可以 1:1 复刻，不需要任何第三方库。

### 凸起 `CONVEX`

1. 在形状矩形上画一个**右下偏移 `+e/2`** 的实心圆角矩形，颜色 `neum_dark`（投影），`BlurMaskFilter(NORMAL)`，半径 `e`；
2. 再画一个**左上偏移 `-e/2`** 的实心圆角矩形，颜色 `neum_light`（高光），同样模糊；
3. 最后把底色 `baseColor` 填在最上层，**只让边缘那一圈阴影露出来**。

光源固定在左上，全应用统一，不随控件在屏幕上的位置变化。

### 凹陷 `CONCAVE` —— 不是把凸起反过来

凹陷走的是完全另一套画法：**STROKE + 模糊，画在内容之上**，形成内阴影。

1. 先铺底色；
2. 描边宽 `2e`（以路径为中心），模糊半径 `e`；
3. 暗色描边往**左上**偏 `-e/2`（`neum_dark` 取 0.8 倍 alpha）—— 模拟被挖掉的边缘挡光；
4. 亮色描边往**右下**偏 `+e/2`（`neum_light`）—— 模拟坑底受光。

方向和凸起正好相反，这是凹陷感成立的关键。
**把凸起的两个颜色对调是错的**：那样画出来是"贴上去的亮斑"，因为凹陷的本质是
「边缘挡住了光」，不是「光照在了另一个方向」。这一点是实机截图对比后定下来的。

### 数值

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `BLUR_FACTOR` | `1.0` | 模糊半径 = elevation |
| 阴影偏移 | `0.5 × elevation` | 由 `e * 0.5f` 推出，不是独立参数 |
| `BLEED_FACTOR` | `1.5` | 形状相对 Drawable 边界的四向内缩倍数 |
| `CONVEX` / `CONCAVE` | `0` / `1` | 与 `attrs.xml` 里 `neuStyle` 的枚举值一一对应 |
| 圆角上限 | `min(w, h) / 2` | 防止过大的 `neuRadius` 画崩 |

---

## 3. 为什么用位图缓存

模糊是整条渲染链上最贵的操作。如果每次 `onDraw` 都重画，一个列表滚起来立刻掉帧。

**也可以走 `View.setLayerType(LAYER_TYPE_SOFTWARE)`** —— 那是最省事的做法，系统会保证
`BlurMaskFilter` 可用。但代价是**整棵子树退回软件渲染**，滚动直接遭殃。所以这里不用它。

实际做法是把阴影**预渲染进一张位图**：

- 缓存键 = `(宽度, 高度, 缩放系数)`，尺寸和状态不变就直接 `drawBitmap` blit；
- 只有 `style` / `radius` / `elevation` / 颜色 / `pressFactor` 变化时才 `invalidateCache()` 重画；
- **宽度超过 720 px 时按半分辨率渲染**（`scale = 0.5f`）再放大 —— 模糊本来就看不出一像素的细节，
  半分辨率把位图内存和渲染时间都降到四分之一；
- `Bitmap.createBitmap` 抛 `OutOfMemoryError` 时**放弃阴影而不是崩溃**：宁可没有阴影，也不能因为阴影把应用拖垮。

代价是**每个浮雕实例一张 ARGB_8888 位图**。换来的是硬件渲染路径不受影响。

---

## 4. 外扩（bleed）

阴影必然画到形状之外，而 **View 的 background 会被裁剪到 View 的边界**。
所以形状要在四个方向内缩 `bleed = 1.5 × elevation`，把这段空间让给阴影；
`NeumorphicSurface.apply()` 负责把这段内缩**补进 padding**，免得内容贴着阴影。

```java
// NeumorphicSurface.apply()
v.setBackground(d);
int bleed = Math.round(d.bleedPx());
v.setPadding(v.getPaddingLeft() - oldBleed + bleed, /* … */);
```

重复调用是安全的：先扣掉上一次补的 `oldBleed` 再补新的，不会随调用次数累积。

**已知取舍：控件的可见尺寸比布局声明的尺寸小一圈。**
`elev_card = 6dp` 的卡片，形状实际是 `宽/高 - 18dp`（两侧各 1.5 × 6dp）。
这是新拟态物理隐喻的必然结果 —— 不做外扩，阴影会被裁成直角，看起来像"贴了一张纸"。
需要形状精确等于布局尺寸时用 `noInset()` 关掉内缩（`NeumorphicControls` 里的开关轨道、
滑块槽、圆盘滑块都这么做，它们本来就由控件自己定位）。

---

## 5. 玻璃引擎

### 整屏只画一份 mesh

`ui/GlassMeshDrawable` 挂在**根容器**上（`MainActivity.applyNeumorphicTheme()` 里
`root.setBackground(new GlassMeshDrawable(isDark))`），**整屏只挂一个**。

这是整个玻璃引擎最重要的约定：所有玻璃面（卡片、按钮、输入框、芯片）**只做半透明叠加**，
底下的光斑自己透出来。**如果每个玻璃面各画一份 mesh，光斑会在每个面上重复一遍** ——
整屏看起来像贴满了彩色贴纸，而不是"透过玻璃看同一片背景"。

mesh 的构成：底色 + N 个径向渐变光斑（位置与半径都用相对比例表示，所以任意尺寸下构图一致）
+ 顶部一道极淡的竖向渐变给整屏一点方向感。深浅两套是分别调的，不是同一套换个底色：

| | 底色 | 光斑数 | 光斑来源 |
| --- | --- | --- | --- |
| 浅色 | `#F3EFEA`（暖白） | 5 | `MI_BLUE_40 #4C8EFF` / `MI_GREEN_40 #6DD400` / `MI_BLUE_80 #ADC9FF` / `MI_GREEN_80 #B5F37F` + 一层白 |
| 深色 | `#0F1320` | 4 | 同四支品牌色，不透明度压低一档 |

浓度是照着实机截图调下来的：按品牌色原值铺上去会变成一片高饱和渐变，
玻璃卡片（浅色下只有 36% 的白）反而被背景压住。

### 玻璃面画什么

`NeumorphicDrawable.drawGlass()` 里依次是：

1. **半透明叠加** —— 凸面用 `glass_tint_convex`，凹面用 `glass_tint_concave`；
2. **方向光**（裁剪到圆角矩形内，否则渐变会溢出圆角）：
   - **凸面**：顶部一道亮边（`0x38FFFFFF` → 透明，到 `0.45h` 为止）—— 受光面；
   - **凹面**：顶部一道内阴影（`0x42000000` → 透明，到 `0.78h`）+ 底部一道亮边
     （`0x55FFFFFF` → 透明，从 `h` 往上到 `0.55h`）—— 被里面的边缘挡光；
3. **渐变描边** —— 上 `glass_border_hi` 下 `glass_border_lo`，宽 `max(1, 1dp)`，模拟玻璃边缘厚度。

两条实机验证过的结论：

- **凹凸在玻璃态下靠方向光区分，不是靠明暗。** 只平铺一层暗色的话，凹陷会变成一块
  没有深度的灰色方块 —— 尤其当它落在一个不透明容器里（例如设置面板），背后根本没有网格可透。
  所以凹面的叠加压得很轻（浅色 `#0A000000`，4%），深度完全交给方向光。
- **叠加色必须比规范值更实。** 上游给的是 `#2EFFFFFF`（18% 白），实机偏薄：网格一旦有颜色，
  18% 的白不足以把卡片从背景里托出来。提到 36%（`#5CFFFFFF`）之后卡片才有"磨砂玻璃"的实体感，
  同时仍然透得过光斑。

玻璃态下 `bleedPx()` 返回 `0`：不画外投影，也就不需要外扩。

---

## 6. 无涟漪

主题两处把系统的水波纹掐掉（`values/themes.xml`、`values-night/themes.xml` 的 `Theme.BiliGrab`）：

```xml
<item name="android:colorControlHighlight">@android:color/transparent</item>
<item name="android:selectableItemBackground">@null</item>
<item name="android:selectableItemBackgroundBorderless">@null</item>
```

新拟态里「表面」是一块有厚度的实体，涟漪是水面上的效果，两者观感冲突，所以整棵界面里
不出现 `RippleDrawable`。

点击反馈全部由 `ui/HyperosClick` 提供：

| 参数 | 值 |
| --- | --- |
| 按下缩放 | `0.95` |
| 按下时长 | 150ms |
| 松开时长 | 200ms |
| 浮雕厚度变化 | `4/6`（6dp → 4dp 那一档） |
| 插值器 | `PathInterpolator(0.4, 0, 0.2, 1)` = `FastOutSlowInEasing` |
| 触觉（普通点击） | `HapticFeedbackConstants.TEXT_HANDLE_MOVE` |
| 触觉（开关切换） | `HapticFeedbackConstants.LONG_PRESS` |

两点实现细节：

- **缩放幅度必须小。** 压得更狠会让阴影穿帮 —— 阴影是按原尺寸算的，形状缩了阴影没缩。
- **玻璃态只缩放，不做浮雕形变**（`if (nd.isGlass()) return;`）。玻璃面没有厚度可压。
- 触觉一律带 `FLAG_IGNORE_GLOBAL_SETTING`，避免被系统的"关闭触觉"静默吃掉。
- 开关的触觉挂在 `setOnTouchListener` 而不是 `OnCheckedChangeListener` 上 ——
  后者是调用方的回调，不能在这里抢走；回调返回 `false`，切换行为不受影响。

`NeumorphicControls.dressSwitch()` 里还有一处必须记住的细节：关掉系统着色时要
`setThumbTintList(null)` / `setTrackTintList(null)`，**必须传 `null` 而不是 `Color.TRANSPARENT`** ——
设成透明色会把滑块整个涂透明，直接看不见。

---

## 7. 令牌表

以下数值全部取自 `res/values/` 下的实际文件，改动请改文件，不要在布局里写新的字面量。

### 7.1 间距（`values/dimens.xml`）

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `space_xs` | 4dp | 基础刻度 |
| `space_sm` | 8dp | 基础刻度 |
| `space_md` | 12dp | 基础刻度 |
| `space_lg` | 16dp | 基础刻度 |
| `space_xl` | 24dp | 基础刻度（= `screen_margin` 同一档） |
| `space_2xl` | 32dp | 基础刻度（= `section_gap` 同一档） |
| `space_3xl` | 48dp | 基础刻度 |
| `screen_margin` | 24dp | 屏幕左右留白（旧版是 16dp） |
| `card_gap` | 12dp | 卡片之间 |
| `card_padding` | 16dp | 卡片内边距 |
| `card_inner_gap` | 12dp | 卡片内元素间距 |
| `section_gap` | 32dp | 区块之间 |
| `content_gap` | 24dp | 内容块之间 |
| `title_gap` | 12dp | 标题与所属内容之间 |
| `touch_min` | 48dp | 任何可点区域的下限 |
| `touch_gap` | 8dp | 相邻可点目标的最小间隙 |

### 7.2 圆角与浮雕高度 —— 成组使用

**这两列必须成组使用**：圆角越大的层级配的浮雕也越厚，单改其中一个会破坏整套界面的厚度一致性
（小圆角配大面积柔和阴影是不成立的 —— 阴影越软，形状越要圆，否则看着像"没对齐的方盒子"）。

| 用途 | 圆角令牌 | 值 | 浮雕令牌 | 值 |
| --- | --- | --- | --- | --- |
| 页面卡片 | `radius_card` | 28dp | `elev_card` | 6dp |
| 按钮 | `radius_button` | 26dp | `elev_button` | 6dp |
| 对话框 / 底部面板 | `radius_dialog` | 24dp | `elev_dialog` | 8dp |
| 输入框 | `radius_field` | 16dp | `elev_field` | 2dp |
| 芯片 | `radius_chip` | 14dp | `elev_chip` | 3dp |
| 开关轨道 | `radius_switch_track` | 14dp | `elev_switch` | 1.5dp |
| 图标按钮 | `radius_icon_button` | 18dp | `elev_icon_button` | 3dp |
| 滑块槽 | 代码里取 `7dp`（= 14dp 轨道高的一半） | — | `elev_slider_track` | 2dp |
| 药丸 / 全圆 | `radius_pill` | 999dp | — | — |

> 滑块槽没有专属的 `radius_*` 令牌，`NeumorphicControls.dressSeekBar()` 里写的是 `radius(7)`
> 配 `elevation(2)`，也就是 **7/2**。

其余浮雕档位：

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `elev_button_pressed` | 4dp | 按下态（`HyperosClick.PRESSED_DEPTH = 4f/6f` 对应的档位） |
| `elev_button_disabled` | 1.5dp | 禁用态 |
| `neum_bleed` | 10dp | 声明的最大外扩量（实际外扩由 `BLEED_FACTOR × elevation` 算，见第 4 节） |

**浮雕高度不是 Material 的 elevation**：它同时决定模糊半径（= 该值）和阴影偏移（= 0.5 × 该值）。

### 7.3 字号（`values/styles.xml` 的 `Text.*`）

| 样式 | 字号 | 字重 | 字距 | 行距 | 用途 |
| --- | --- | --- | --- | --- | --- |
| `Text.Display` | 36sp | bold | 0 | ×1.15 | 空态大标题 |
| `Text.HeadlineSmall` | 32sp | bold | 0 | ×1.15 | 页面级大标题 |
| `Text.AppBar` | 22sp | bold | 0 | — | 顶栏标题（本项目**新增**的一档，理由见第 9 节） |
| `Text.TitleLarge` | 18sp | bold | 0 | ×1.25 | 卡片标题、视频标题 |
| `Text.TitleMedium` | 16sp | bold | 0 | — | 次级标题 |
| `Text.TitleSmall` | 14sp | bold | 0.01 | — | 行标题、画质标签 |
| `Text.BodyLarge` | 16sp | normal | — | +4dp | 正文 |
| `Text.BodyMedium` | 14sp | normal | — | +3dp | 说明文字（次要色） |
| `Text.BodySmall` | 12sp | normal | — | +3dp | 帮助文字（次要色） |
| `Text.LabelLarge` | 14sp | bold | — | — | 按钮文字 |
| `Text.LabelMedium` | 12sp | normal | — | — | 元信息、徽标（次要色） |
| `Text.Code` | 12sp | normal（monospace） | — | — | 版本号、原始错误信息 |

`Text` 是公共父样式，给出 `hyper_text_primary`；其余 `Text.*` 靠**名字前缀**隐式继承它。
字重刻意只用三档（Bold / Normal / monospace），新拟态的背景已经足够"吵"。

### 7.4 颜色（`values/colors.xml` 与 `values-night/colors.xml`）

浅深两套是**分别设计**的，不是机械反转。

| 令牌 | 浅色 | 深色 | 含义 |
| --- | --- | --- | --- |
| `hyper_background` | `#F2F4F8` | `#1A1B1E` | 页面底色 |
| `hyper_card` | `#FFFFFF` | `#1E1E1E` | 卡片色（新拟态下**不**用于卡片底面） |
| `hyper_gradient_top` | `#EAF1FF` | `#0D1117` | 页面渐变（当前无引用） |
| `hyper_gradient_mid` | `#F5F6FB` | `#12161C` | 同上 |
| `hyper_gradient_bottom` | `#FFFFFF` | `#1A1E25` | 同上 |
| `hyper_text_primary` | `#111111` | `#F5F5F5` | 主文字 |
| `hyper_text_secondary` | `#6F7280` | `#B3B3B3` | 次要文字 |
| `hyper_text_tertiary` | `#B5B5C0` | `#808080` | 三级文字 / hint |
| `hyper_success` | `#32BB78` | `#30D158` | 成功 |
| `hyper_warning` | `#FFB300` | `#FFB74D` | 警告 |
| `hyper_error` | `#FF5252` | `#FF8A80` | 错误 |
| `hyper_border` | `#E5E5E5` | `#2D2D2D` | 描边 |
| `hyper_divider` | `#F0F0F0` | `#262626` | 分隔（也是关态开关轨道的底色） |
| `hyper_icon_bg` | `#F2F4F8` | `#2A2A2A` | 图标底板 |
| `hyper_badge_bg` | `#E8F4FF` | `#0A2A4A` | 徽标底 |
| `hyper_badge_text` | `#007AFF` | `#0A84FF` | 徽标文字（XML 兜底，运行时为主色） |
| **`neum_light`** | `#FFFFFF` | `#26282C` | **浮雕左上高光** |
| **`neum_dark`** | `#D1D9E6` | `#0D0E11` | **浮雕右下投影** |
| `glass_mesh_base` | `#F3EFEA` | `#0F1320` | 玻璃引擎的浮雕底色 |
| `glass_tint_convex` | `#5CFFFFFF` | `#1FFFFFFF` | 玻璃凸面叠加 |
| `glass_tint_concave` | `#0A000000` | `#24000000` | 玻璃凹面叠加 |
| `glass_border_hi` | `#CCFFFFFF` | `#8CFFFFFF` | 渐变描边上缘 |
| `glass_border_lo` | `#1F000000` | `#1FFFFFFF` | 渐变描边下缘 |
| `glass_inner_highlight` | `#80FFFFFF` | `#73FFFFFF` | 预留（当前由方向光渐变直接给值） |
| `glass_outer_shadow` | `#33737D99` | `#47000000` | 预留（玻璃态当前不画外投影） |
| `hyper_scrim` | `#66000000` | `#99000000` | 遮罩 |
| `hyper_edit_overlay` | `#CC1F2937` | `#CC0F1115` | 编辑态叠加 |
| `hyper_notification_icon` | `#FFFFFF` | `#FFFFFF` | 通知图标（纯白剪影） |

`neum_light` / `neum_dark` 是整个体系的地基，**缺一不可**：一个模拟光源，一个模拟厚度。
深色模式下必须换成各自的反向值（比背景略亮 / 略暗），沿用白色高光会让浮雕整个消失。
两色的对比度都很低，这是**故意的** —— 浮雕靠这点微差成立，拉大就变成硬阴影了。

### 7.5 主题色（`values/arrays.xml` + `values-night/arrays.xml`）

| # | 名称 | 浅色 primary / variant | 深色 primary / variant |
| --- | --- | --- | --- |
| 0 | 樱花粉（默认） | `#FB7299` / `#E04B78` | `#FF9DBA` / `#E87899` |
| 1 | 默认蓝 | `#007AFF` / `#0062CC` | `#0A84FF` / `#0070E0` |
| 2 | 深海蓝 | `#1652A8` / `#0E3F87` | `#4A87DE` / `#3A6DB5` |
| 3 | 薄荷绿 | `#32BB78` / `#26965F` | `#4FD98A` / `#3CB672` |
| 4 | 薰衣草紫 | `#8E7CF0` / `#6F5CD8` | `#A99BF7` / `#8F7FE0` |

- 索引顺序在两个文件里**必须完全一致**，也与 `Prefs.ui_primary` 的取值一一对应。
- 深色下统一**提亮一档**，与规范里 `#007AFF → #0A84FF` 的处理方式一致。
- 主色是**运行时**读取的（`HyperTheme.primary()` 走 `obtainTypedArray`），
  因为主题资源是编译期固定的，两者无法共存；XML 里需要主色的地方统一写
  `@color/scheme_0_primary` 兜底，运行时由 `MainActivity` 覆盖。
- 前景色由 `HyperTheme.contrastOn()` 按**感知亮度**（`0.299R + 0.587G + 0.114B`）取黑或白，
  阈值是 **160 而不是 128** —— 这 5 套主色的亮度分布在 150 附近聚得最密，把分界推高一点，
  浅色主色才不会被判成深色。等权平均会把中蓝判成亮色配黑字，实际几乎看不清。

### 7.6 组件尺寸（`values/dimens.xml`）

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `top_app_bar_height` | 64dp | 顶栏 |
| `icon_size` / `icon_size_sm` / `icon_size_xs` | 24 / 20 / 16dp | 图标 |
| `button_height` | 52dp | 按钮（比 `touch_min` 高一档，压到 48dp 会显得扁） |
| `field_height` | 56dp | 输入框 |
| `chip_height` | 32dp | 芯片可见高度 |
| `chip_inset` | 8dp | 芯片可点区域比可见药丸上下各内缩 |
| `play_button_size` | 64dp | 封面大播放键 |
| `fab_size` | 48dp | 预留（当前界面没有 FAB） |
| `progress_height` | 4dp | 进度条 |
| `switch_width` / `switch_height` / `switch_thumb` | 52 / 28 / 20dp | 开关（滑块实际按 22dp 画，见第 12 节） |
| `slider_track_height` / `slider_thumb` | 14 / 32dp | 滑块 |
| `stroke` | 1dp | 描边 |
| `cover_height` | 180dp | 封面占位高度 |
| `parts_list_max_height` | 240dp | 分 P 列表最大高 |

---

## 8. 圆角与浮雕成组

一句话规则：**圆角与浮雕是一组参数，不要单改其中一个。**

理由不是"好看"，而是物理一致性：新拟态的形状高度=阴影的模糊半径与偏移量，
一个 28dp 圆角的卡片配 2dp 的浮雕，阴影会缩在角里出不来，看起来像"描了一圈灰边"；
反过来一个 14dp 的芯片配 8dp 浮雕，阴影会盖住它自己。

成组的对应关系见 7.2 表。目前代码里的组合是：

```
28/6 页面卡片      26/6 按钮        24/8 对话框、底部面板
16/2 输入框        14/3 芯片        14/1.5 开关轨道
18/3 图标按钮      7/2  滑块槽      pill/6 播放键与图标底板
```

上游 `UI-BRIEF.md` 里把滑块槽记作 `18/2`，与代码实际的 `7/2` 不一致 —— 以代码为准（见第 12 节）。

---

## 9. 排版：为什么额外加了 `Text.AppBar`

字阶表见 7.3。相对上游规范，本项目**多了一档 `Text.AppBar`（22sp Bold）**，这是一次有意的修正：

规范把 32sp 的 `headlineMedium` 同时给了「页面大标题」和「顶栏标题」两个角色。
但**一个屏幕只能有一个视觉焦点**：顶栏和页面标题都用 32sp 时，两者互相打架，层级反而消失了。

还有一条纯排版的原因：**中文 32sp 在 360dp 宽的屏幕上放不下 9 个字** ——
9 × 32sp = 288dp，而屏幕可用宽度只有 360 − 24 × 2 = 312dp，再加上顶栏右侧的设置按钮，
必然断行。把顶栏降到导航层级之后，32sp 才真正独占「页面大标题」这一档。

---

## 10. 自定义 View 与自定义属性

### 属性（`values/attrs.xml`，`declare-styleable name="Neumorphic"`）

| 属性 | 类型 | 取值 | 说明 |
| --- | --- | --- | --- |
| `neuStyle` | enum | `convex`=0 / `concave`=1 / `none`=2 | 凸起、凹陷，或完全不画（默认 `none`） |
| `neuRadius` | dimension | `@dimen/radius_*` | 圆角 |
| `neuElevation` | dimension | `@dimen/elev_*` | 浮雕高度 |
| `neuFill` | color | 任意色 | 覆盖底色；留空用主题的 `neumBase`（**新拟态默认卡片与页面同色**） |
| `neuPress` | boolean | — | 装上 `HyperosClick` 的无涟漪点击（只做视觉反馈） |
| `neuStroke` | boolean | — | 主色描边，预留给聚焦态（当前实现未读取） |
| `neuGlassFlat` | boolean | — | 玻璃态描边开关，预留给卡片类（当前实现未读取） |

### 自定义 View（`com.biligrab.downloader.ui`）

| 用这个 | 替代 |
| --- | --- |
| `NeuLayout` | `LinearLayout`（垂直线性容器） |
| `NeuFrame` | `FrameLayout`（帧容器，也是 Snackbar 的悬浮锚点） |
| `NeuText` | `TextView` |
| `NeuButton` | `Button` |
| `NeuEdit` | `EditText`（新拟态的输入框是**凹陷**的） |
| `NeuImage` | `ImageView` |
| `NeuImageButton` | `ImageButton` |

这 7 个类**每个只有构造函数**，都是把 XML 里的 `neu*` 属性转交给 `NeumAttr`。
之所以要这么多同构子类，是因为新拟态要能落在任意一种基础控件上，而 Java 没有
"给已有类追加行为"的手段。

### 外观写在 `@style/Widget.*` 里

**布局里不铺浮雕参数，只引样式。** 外观定义在 `values/styles.xml`：

```xml
<style name="Widget.Card" parent="">
    <item name="neuStyle">convex</item>
    <item name="neuRadius">@dimen/radius_card</item>
    <item name="neuElevation">@dimen/elev_card</item>
</style>
```

布局只要：

```xml
<com.biligrab.downloader.ui.NeuLayout
    style="@style/Widget.Card"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical" />
```

`Widget.*` 一览：

| 样式 | 浮雕 | 用在 |
| --- | --- | --- |
| `Widget.Card` | 凸 28 / 6 | 页面卡片，整套界面的基本容器 |
| `Widget.Card.Concave` | 凹 28 / 2 | 被"挖进去"的容器（分段控件底槽） |
| `Widget.Button` | 凸 26 / 6，高 52dp | 按钮；配 `.Filled` / `.Outlined` / `.Tonal` 定文字色 |
| `Widget.IconButton` | 凸 18 / 3，48dp | 图标按钮 |
| `Widget.TextField` | 凹 16 / 2，高 56dp | 输入框 |
| `Widget.Chip` | 凸 14 / 3 | 芯片 |
| `Widget.DownloadRow` | 凸 28 / 6 | 下载行（整行就是按钮） |
| `Widget.PlayButton` | 凸 pill / 6，64dp | 封面大播放键 |
| `Widget.IconPlate` | 凸 pill / 6 | 空态的图标底板 |
| `Widget.ListItem` | 无 | 卡片里的行（**行内不再叠浮雕**） |
| `Widget.FieldLabel` / `Widget.SectionTitle` / `Widget.SeekBar` | 无 | 标签、区块标题、进度条尺寸 |

两条硬规则：所有 `Widget.*` 的 `parent` 一律为空（避免继承到系统的波纹与 Material 状态背景）；
卡片"里面"的行是平的 —— 在卡片上再叠一层浮雕会让厚度语义失效。

---

## 11. 一个已知陷阱：`NeumAttr.apply` 会覆盖 `android:background`

`Neu*` 系列 View 的构造函数是这样写的：

```java
public NeuLayout(Context context, AttributeSet attrs) {
    super(context, attrs);            // ← 先跑：这里已经把 XML 的 android:background 设进去了
    NeumAttr.apply(this, context, attrs);   // ← 后跑：这里 v.setBackground(d) 把它顶掉
}
```

`super()` 先于 `NeumAttr.apply()` 执行，而 `NeumorphicSurface.apply()` 内部调的是
`v.setBackground(d)`。所以**只要同时写了 `android:background` 和 `neu*` 属性，
前景那个 drawable 会被静默丢弃**，不会报错、不会有编译警告，只是不生效。

规则：**弹性表面（带 `neu*` 的 View）不要同时写 `android:background`。**
确实需要一层底图时，把它套在一个不带 `neu*` 的 `NeuFrame` 里。

### 由这个陷阱反推出来的一个刻意决定

**模态面板 `sheet_settings.xml` 的根容器不带任何 `neu*` 属性**，它用一张不透明的
`@drawable/bg_bottom_sheet`（圆角仍取 `radius_dialog`）。原因：

> 桌面是模态表面，背后只有一层压暗的界面，没有任何值得透出来的内容。
> 如果给它装浮雕，**玻璃引擎会把它变成半透明**，背后的视频封面直接透上来，设置就没法读了。

这是实机验证出来的结论，不是推测。

---

## 12. 与文档/施工说明已知不一致的地方

以下是照代码核对时发现的偏差，**以代码为准**，记在这里免得下一个人再对一遍：

| 项 | 文档 / 施工说明怎么写 | 代码实际是什么 |
| --- | --- | --- |
| 滑块槽的圆角与浮雕 | `UI-BRIEF.md` 写 `18/2` | `NeumorphicControls.dressSeekBar()` 是 `radius(7)` / `elevation(2)`，即 **7/2** |
| 开关滑块直径 | `dimens.xml` 的 `switch_thumb` = 20dp，注释写"滑块 20" | `NeumorphicControls.dressSwitch()` 用 `intrinsic(22, 22)`，代码注释的理由是"20 容易在两端戳出轨道之外" |
| 开关/滑块的尺寸来源 | 应引用 `radius_switch_track` / `elev_switch` / `slider_track_height` / `switch_width` / `switch_height` 等令牌 | 这些值在 Java 里是**字面量**（52、28、14、1.5f、2），令牌本身没有任何引用 |
| 进度条实现 | `styles.xml` 的 `Widget.SeekBar` 注释指向 `ui/NeumorphicSeekBar` | **没有这个类**；滑块由 `NeumorphicControls.dressSeekBar()` 换 drawable 实现 |
| `neuStroke` / `neuGlassFlat` | `attrs.xml` 里声明了用途 | `NeumAttr` 只读前 5 个属性，这两个**从未被读取** |
| 换肤时凹面的玻璃叠加色 | 应与 `apply()` 一致，按凹凸分别取色 | `NeumorphicSurface.refreshTheme()` **一律传 `glassTintConvex`**，凹面刷新后会退回凸面的叠加色 |
| `StaggerEnter` | 分段入场动画（1000ms，0/150/250/350/450ms） | 类存在、参数正确，但 `MainActivity` 只 import 了它，**没有任何调用点**，动画目前不会发生 |
| 色板生成 | `DESIGN.md` 说 `values/colors.xml` 由 `tools/gen_palette.py` 生成、"勿手改" | 脚本产出的是 `m3_*` 命名的 M3 色角色，而现在的 `colors.xml` 是手写的 `hyper_*` / `neum_*` / `glass_*` 令牌 —— 两者不同源，**重新跑脚本会往色板里塞一批新的死资源** |
| 死令牌 | `DESIGN.md` 的自检要求"每个 dimen / color 的引用数 ≥ 1"（只允许 `space_2xl` 为 0） | 当前有 16 个 dimen 与 7 个 color 没有任何引用，见下表 |

当前引用数为 0 的令牌：

```
dimen: neum_bleed, slider_track_height, slider_thumb, switch_thumb, switch_thumb_margin,
       switch_width, switch_height, elev_button_pressed, elev_button_disabled, elev_switch,
       elev_slider_track, radius_switch_track, elev_dialog, chip_inset, stroke, fab_size
color: hyper_gradient_top, hyper_gradient_mid, hyper_gradient_bottom,
       glass_inner_highlight, glass_outer_shadow, hyper_edit_overlay, hyper_notification_icon
```

其中 `elev_*` 与开关/滑块那几个是"值对了但没走令牌"，可以直接把 `NeumorphicControls` 里的
字面量换成 `R.dimen.*` 消掉；`neum_bleed`、`glass_inner_highlight`、`glass_outer_shadow`
是设计阶段留下的规格位，要么接上要么删掉。

---

## 13. 文件清单

`app/src/main/java/com/biligrab/downloader/ui/` 下每个类一句话：

| 文件 | 一句话 |
| --- | --- |
| `NeumorphicDrawable.java` | 整套体系里唯一负责"厚度"的类：用 `BlurMaskFilter` + `drawRoundRect` 画凸起/凹陷/玻璃面，内含按尺寸缓存的位图与半分辨率降采样。 |
| `NeumorphicSurface.java` | 把浮雕应用到任意 `View` 的静态工具：扣/补 bleed padding、同步主题色、`refreshTree()` 换肤刷新、`focusRing()` 主色描边。 |
| `NeumorphicControls.java` | 把系统 `Switch` / `SeekBar`"浮雕化"：只换 drawable 不重写控件，保留 `OnSeekBarChangeListener` / `OnCheckedChangeListener` 与可访问性语义。 |
| `NeumAttr.java` | 读 `neuStyle` / `neuRadius` / `neuElevation` / `neuFill` / `neuPress` 并转交 `NeumorphicSurface`；`Neu*` 系列 View 构造时调用。 |
| `HyperTheme.java` | 颜色与引擎判断的唯一入口：语义色、浮雕三色、玻璃令牌、5 套主题色、`contrastOn()` 感知亮度取前景。 |
| `HyperosClick.java` | HyperOS 无涟漪点击：0.95 缩放 + 浮雕厚度形变 + 触觉反馈，并给出全局共用的 `FAST_OUT_SLOW_IN` 插值器。 |
| `GlassMeshDrawable.java` | 玻璃引擎的整屏 mesh 背景（底色 + 4/5 个径向光斑 + 顶部渐变），只挂在根容器上。 |
| `StaggerEnter.java` | 分段入场动画的参数与编排（1000ms / 0-150-250-350-450ms / 60dp 上移 / 0.92 缩放）；**当前无调用点**。 |
| `NeuLayout.java` | `LinearLayout` + 浮雕能力（仅构造函数转交 `NeumAttr`）。 |
| `NeuFrame.java` | `FrameLayout` + 浮雕能力。 |
| `NeuText.java` | `TextView` + 浮雕能力。 |
| `NeuButton.java` | `Button` + 浮雕能力。 |
| `NeuEdit.java` | `EditText` + 浮雕能力（凹陷）。 |
| `NeuImage.java` | `ImageView` + 浮雕能力。 |
| `NeuImageButton.java` | `ImageButton` + 浮雕能力。 |

配套资源：

| 文件 | 内容 |
| --- | --- |
| `values/colors.xml` / `values-night/colors.xml` | 表面、文字、语义、浮雕双色、玻璃令牌、遮罩 |
| `values/dimens.xml` | 间距、圆角、浮雕高度、触控、组件尺寸 |
| `values/styles.xml` | `Text.*` 字阶 + `Widget.*` 组件外观（浮雕参数写在这里） |
| `values/attrs.xml` | `declare-styleable Neumorphic` |
| `values/arrays.xml` / `values-night/arrays.xml` | 5 套主题色 + 名字数组 |
| `values/themes.xml` / `values-night/themes.xml` | 无涟漪主题、edge-to-edge、深色覆盖 |
| `layout/*.xml` | 全部换成 `com.biligrab.downloader.ui.Neu*` |

---

## 14. 每次改动后的自检

1. **两套引擎都要跑一遍** —— 新拟态和玻璃态是同一条代码路径的两个分支，
   只测一个很容易漏掉玻璃态的分支（例如凹面的叠加色）。
2. **浅色 + 深色都要跑** —— `neum_light` / `neum_dark` 在深色下是反向值，换错浮雕会整个消失。
3. **不要同时写 `android:background` 和 `neu*`** —— 见第 11 节。
4. **滑块与开关不在 `refreshTree` 的覆盖范围内** —— 换肤后要另外调
   `NeumorphicControls.refresh(switch)` / `refresh(seekBar)`。
5. **不要把主色写死在 XML 里** —— 主色是运行时的，XML 只用 `@color/scheme_0_primary` 兜底。
6. **圆角与浮雕成组改** —— 见第 8 节。
