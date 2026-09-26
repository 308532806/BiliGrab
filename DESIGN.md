# BiliGrab 设计系统

这份文档记录界面层的设计决策。它不是风格描述，而是**规则**——每条都能对照代码检查。

设计参照两套规范：

- [pbakaus/impeccable](https://github.com/pbakaus/impeccable) —— 设计规则引擎，本项目的排版／色彩／工艺底线来自它的 `android.md`、`colorize.md`、`typeset.md`、`craft-floor.md`
- [nextlevelbuilder/ui-ux-pro-max-skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) —— 交互规范数据集（触控目标、间距、动效时长、错误处理）

---

## 2026 重构：界面体系已换成 Hyper-Neumorphic

> 这一节是 1.4.0 加的**覆盖声明**。下面第 0～12 节保留原样作为历史记录，
> 但凡与本节冲突的地方，**以 [docs/DESIGN-SYSTEM.md](docs/DESIGN-SYSTEM.md) 为准**。

1.4.0 把界面层从手写的 Material 3 整体换成了 **Hyper-Neumorphic** ——
新拟态（Neumorphic）+ HyperOS 语言 + 玻璃态三合一，纯 Java + XML 实现，
仍然零 AndroidX、零 Material Components。设计依据是上游规范仓库
[`Yang-Ya-Chao/android-design-system-skills`](https://github.com/Yang-Ya-Chao/android-design-system-skills)；
那份原文的 front-matter 里**没有 `license:` 字段**（等同于未声明授权条件），所以本项目**不复制、不提交它的原文**，
只按它描述的视觉约定重新实现，参考原文留在本机 `docs/design-refs/`（已 gitignore）。

已经被取代的部分：

| 本文档的节 | 现在是什么 |
| --- | --- |
| §1 色彩 | 不再是 `tools/gen_palette.py` 从品牌色相推导的 M3 色角色。色板是手写的 `hyper_*` / `neum_*` / `glass_*` 令牌；脚本产出的是 `m3_*` 命名，与现在的色板**不同源**，重新运行它会往色板里塞一批新的死资源（**已修**：脚本已改造为只读校验器，不再写入任何文件） |
| §2 排版 | 不再是 M3 字阶。字号仍全部走 `styles.xml` 的 `Text.*`，但换了一套数值，并**新增一档 `Text.AppBar`（22sp）** |
| §3 间距与形状 | 屏边距 16dp → **24dp**；形状阶改成「圆角与浮雕成组」：28/6、26/6、24/8、16/2、14/3、14/1.5、18/3、7/2 |
| §4 无投影的层级 | **方向完全相反**：层级现在恰恰由浮雕表达，"色调高度"那一套不再使用 |
| §6 交互（涟漪部分） | 涟漪被移除。`colorControlHighlight` / `selectableItemBackground` 压成透明，点击反馈改由 `ui/HyperosClick` 提供（scale 0.95 + 浮雕形变 + 触觉）。下载行也不再是「61dp 的实心行」，而是凸起 28/6 的厚表面（`minHeight` 48dp + 上下各 12dp 内边距） |
| §9 动效 | 增加一套统一的 `FastOutSlowInEasing = cubic-bezier(0.4, 0, 0.2, 1)`，按下 150ms / 松开 200ms |
| §11 被明确拒绝的做法 | 「玻璃拟态／模糊当装饰」这一条要重新理解：玻璃态现在是**唯一引擎（1.9.8 起）**，不再是装饰，也不再可切换。仍然不做"每个玻璃面各模糊一张背景"那种用法 —— mesh 整屏只画一份 |

**仍然成立**的部分：§0 零 AndroidX 的硬约束、§5 图标、§7 预览播放器、§8 五种互斥状态、
§10 无障碍、§12 文件布局（其中 `res/color/` 目录已空），以及末尾的自检清单 ——
自检第 1 条 `python tools/gen_palette.py` 一度失效，现已重新成立：
自检本身仍然要做，做法见 [docs/DESIGN-SYSTEM.md](docs/DESIGN-SYSTEM.md) 的自检一节。

界面层的完整说明 —— 玻璃态渲染原理、位图缓存、外扩取舍、令牌表、自定义 View 与属性、
已知陷阱与已知不一致 —— 见 **[docs/DESIGN-SYSTEM.md](docs/DESIGN-SYSTEM.md)**。

---

## 0. 硬约束：零 AndroidX

界面层不引入 Material Components，也不引入 AndroidX。Material 3 **是手写实现的**：色角色、字阶、形状阶、动效、涟漪、48dp 触控目标、edge-to-edge、Snackbar、底部表单，全部基于平台原生 API。

这不是保守，而是这个项目的立身之本：核心逻辑（`Http` / `WbiSigner` / `Json` / `BiliApi` / `Model`）不依赖任何 Android API，因此可以在桌面上直接跑 `tools/desktop-verify/TestApi.java` 做端到端联调。引入 AndroidX 会切断这条链路。

### 2026 年的修正：这条约束的适用范围

加入 YouTube 支持后，「零第三方依赖」**不再对整个应用成立**，必须说清楚边界：

| 部分 | 依赖情况 |
| --- | --- |
| B 站链路（解析 / 签名 / 下载 / 合流） | 仍然零第三方依赖 |
| 界面层 | 仍然零 AndroidX、零 Material Components |
| YouTube 链路 | 依赖 yt-dlp + youtubedl-android + Jackson + Commons IO/Compress |

之所以必须让步：**纯 Java 解析 YouTube 在技术上是做不到的**。实测拿到的 27 个格式全是 `signatureCipher`，没有明文 `url`，签名必须用 YouTube 自己的播放器 JS 去解。这不是工作量问题，是能力边界。

代价有两条，都是知情的取舍：

1. **APK 从 117 KB 变成 18.5 MB**（CPython 运行时 14.52 MB + yt-dlp 3.02 MB）。
   已经通过「只打 arm64-v8a」和「不带 ffmpeg/aria2c」压到同类应用的三分之一左右
   （Seal 是 54–66 MB）。
2. **授权从 MIT 变成 GPL-3.0**。youtubedl-android 是 GPL-3.0，链接它就意味着整体
   必须 GPL-3.0。这是法律要求，不是风格选择。

`vendor/` 是必需的构建依赖，不是可选项：源代码里的 `YouTubeEngine.java`
直接引用了 `com.yausername.youtubedl_android` 的类，而 `MainActivity` 与
`DownloadService` 又引用 `YouTubeEngine`。构建脚本检测不到就会自动拉取
（`scripts/fetch-vendor.ps1`，约 21 MB，CI 上被缓存）。

> 这里曾经写过「删掉 `vendor/` 即可退回纯 B 站版本」，那条降级路径**从来没有
> 真正走通过** —— 少了 vendor 只会在 javac 阶段以一行「javac 失败」告终。
> 第一次推到 GitHub 上时 CI 就是这么挂的。要真做成降级，得再维护一份接口
> 对齐的桩实现，而桩会随真实实现漂移；这个代价不值得，所以放弃了那个卖点。

---

## 1. 色彩

### 生成方式：手挑，不是推导

色板是一批**手挑的令牌**（`hyper_*` / `neum_*` / `glass_*`），
不是由 `tools/gen_palette.py` 从品牌色相推导出来的。

理由：新拟态的底色不是「一个色相 + 一套 tone 映射」能推导出来的东西。它由三层叠成，
每一层都不可推导 ——

1. **中性面**：卡片与页面**同色**（这套体系靠浮雕表达层级，不靠色阶差），
   所以底色必须是一组与背景同色的中性灰，而不是 M3 那种被种子色染过的 tone 阶梯；
2. **光斑浓度**：玻璃引擎的 mesh 由若干彩色光斑构成，每颗光斑的位置、半径、不透明度
   都是在**真机上看着调的** —— 最终观感取决于光斑彼此的交叠关系，笔算不出来；
3. **玻璃叠加色**：凸面 / 凹面的叠加透明度同样是实机调出来的（规范给的 18% 在真机上偏薄，
   见 `values/colors.xml` 里的注释），凹面甚至要反着压 —— 这些值取决于它落在什么容器里。

品牌色相仍然只在**一处**起作用：用户可选的 5 套主题色（`scheme_N_primary`，
见 `values/arrays.xml`）。改品牌色的做法见本文末尾「修改色板」。

> 历史：本节原来记录的是一套从种子色 `#FB7299` 推导 M3 色角色的生成器
> （`oklch → CIELAB tone → 角色映射 → 输出 colors.xml`）。那套 `m3_*` 色板已经被上面的
> 手写令牌整个取代，生成逻辑也已从 `tools/gen_palette.py` 里**删除** ——
> 脚本现在只校验，见文首「2026 重构」。

### 对比度是算出来的，不是看出来的

`tools/gen_palette.py` **只管校验，不管生成**：读现有的两套 `colors.xml` 与 `arrays.xml`，
按 WCAG 相对亮度（先把 sRGB 通道反伽马，再加权 `0.2126/0.7152/0.0722`）逐项算对比度，
输出「前景 / 背景 / 对比度 / 门槛 / 通过与否」，硬门槛不达标就以非零退出码结束：

```
正文（< 18sp）        ≥ 4.5:1
大字（≥ 18sp）        ≥ 3.0:1
非文本（图标、描边）  ≥ 3.0:1
```

门槛按**字号**分档，脚本会把「字号 ↔ 门槛」的对应关系打在输出里。
设计上刻意为之的弱对比（三级文字、状态药丸这类提示级项）单独计数、不拦退出码，
但数值照常打印 —— 那是设计自己的选择，不是脚本放水。

> **当前基线不是 0 项**：脚本报 7 项硬门槛失败 —— 浅色主题的
> `hyper_text_secondary` 落在 `hyper_background` 上只有 4.34:1（新拟态下 `neumBase`
> 就是背景色，所以这是实机数值，不是边角情况）；以及 10 套「主题色实底」里有 6 套
> 被 `HyperTheme.contrastOn()` 挑错了文字色（品牌粉 `#FB7299` 挑白字 2.64:1，
> 挑 `#111111` 则是 7.17:1）。这些是真实的色值 / 判据问题，等一次设计决定，
> **脚本没有为它们调门槛**。

> 更早的生成器时代，校验抓到过一个真实缺陷：`oklab_to_rgb()` 返回的是**线性** RGB，
> 却被直接喂给 `rgb_to_hex`。结果是整块色板严重偏暗（浅色主题的 `surface` 变成 `#020202`）。
> 值得记下的是：**对比度检查当时是全部通过的** —— 纯黑配纯白当然达标，是人工核对
> 十六进制值才发现的。所以颜色必须同时过「机器校验」和「肉眼核对」两关。
> （那两个函数随生成器一起删掉了，教训留下。）

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
唯一使用投影的元素是 Snackbar——因为它**浮在**内容之上，是真实的 z 轴关系。

> 零偏移的彩色光晕、硬阴影、渐变文字，都属于装饰性投影，本项目一律不用。

---

## 5. 图标

图标来自 Material Symbols 的填充式，**统一 24dp 网格、统一视觉重量**。全部是 `res/drawable/` 里的矢量。

**没有任何 emoji 或 Unicode 字符充当图标。** 这一条是硬性的：图标画出来，不靠字符凑。

启动图标是一个**单一符号**（箭头落进托盘），不是三个符号拼在一起。自适应图标画布 108×108，所有图形收在保证可见的 23..85 区间内；同时提供 `monochrome` 层供 Android 13+ 主题化图标使用。

通知图标是纯白剪影（系统只取 alpha 通道，带颜色的图标会显示成白方块）。

---

## 6. 交互

### 主要动作跟着内容走，不固定在屏幕角落

曾经用底部扩展 FAB 承担下载。换成「每个画质一行、行本身就是按钮」之后，FAB 就被删掉了：
画质既是选择器又是动作入口，再挂一个 FAB 只会让「我选的到底哪一行」变得含糊。

解析结果出现后各行才可点，下载中整组禁用（只留下正在下载的那一行显示进度），完成后恢复。

### 瞬时反馈用 Snackbar，不用 Toast

Toast 既不能带动作，也无法被程序主动收起，多个 Toast 还会排队堆叠。
Snackbar 出现在底部、不夺焦点、能带一个动作、新消息会顶掉旧消息。停留时长：无动作 3.2 秒，有动作 5 秒。

### 设置用底部表单，不用模态对话框

设置是一个**不需要打断用户、也不需要保护焦点**的任务。这类任务上弹模态框是反模式。底部表单同时提供关闭按钮和点击外部／返回键关闭。

### 画质用下载行，不用下拉选择器

每个可用画质各占一行，左标题（「480P 标清」）右副标题（「852×480 · H.264」），
**整行都是点击目标**。这样一步就完成了「选哪个」和「开始下」两件事。

以前是芯片选画质 + FAB 开始下载两步，而且芯片只有 36dp 高的可见药丸，
用户得瞄准才能点中。行的高度是 61dp，整行可点，不再需要瞄准。

设置面板的外观选项仍用芯片（那里是「多选一、不产生动作」的语义，芯片是对的），
换行由手写的 `FlowLayout` 负责，平台没有可换行的容器。

### 选中状态不止靠颜色

分 P 列表项的选中同时用**底色 + 标题色 + 右侧对勾图标**三重编码。色觉障碍用户同样能分辨。

### 文案：说清动作，说清怎么恢复

- 控件名称说明它的动作（「开始下载」而不是「确定」）
- 错误说明**问题**和**恢复方式**两件事：

  > 找不到这个稿件
  > 确认链接完整、稿件未被删除，或换成 BV 号再试

- 原始错误信息也展示出来，用户反馈问题时不用去翻日志

### 所有用户可见文案都在 strings.xml

代码里不出现面向用户的中文字面量 —— 包括通知栏的频道名、下载阶段文字、以及 `DownloadService` 抛出的失败原因。

### 核心层抛错误码，界面层做映射

纯 Java 核心（`Http` / `WbiSigner` / `Json` / `BiliApi` / `Model`）**刻意不依赖任何 Android API**，所以它不能调用 `getString()`。这是为了让 `tools/desktop-verify/TestApi` 能在桌面上直接跑真实接口。

解法不是把 Android 拖进核心层，而是：

1. `BiliApi` 抛出带 `code` 字段的 `ApiException`，原样保留接口返回的业务码
2. 界面层按 `code` 分派到对应的 `strings.xml` 条目

**界面绝不匹配异常消息里的中文子串来决定显示哪条错误。** 那种写法只要改一个字的文案，映射就会静默失效，而且不会有任何编译错误。

核心层异常消息里剩下的中文是给日志和桌面测试输出看的，不是界面文案。

### 一个按钮可以承担互斥的两件事

输入框尾部的按钮在**空**时是「粘贴」，**有内容**时是「清空」，图标与 `contentDescription` 一起切换。

换一个视频下载时最常见的动作是清掉旧链接，而长按输入框仍能粘贴，所以这不是把粘贴入口拿走了 —— 只是在同一个位置上放当前更可能用到的那一个。

### 视觉尺寸和可点尺寸是两件事

芯片看起来高 36dp，实际可点区域 48dp：`Widget.Chip` 的 `minHeight` 取 `touch_min`，`bg_chip.xml` 外面套一层 inset 把可见药丸上下各压 6dp。

下载行则干脆没有这层错位：`Widget.DownloadRow` 的可见底色就是整个点击区域，61dp 高、整行可点。
把可见范围和可点范围做成同一个矩形，比事后补内边距更省事，也不会漏。

不要在布局里直接写 `android:textSize="11sp"` 或 `minHeight="36dp"` 这类数值 —— 尺寸和字号一律走 `dimens.xml` / `styles.xml` 的角色令牌。审计脚本会扫描字面量。

---

## 7. 预览播放器

预览是零依赖手写的：`MediaPlayer` + `SurfaceView`，没有 ExoPlayer、没有 androidx.media3。

### 取流走 `fnval=1`，不走 DASH

播放地址用 `fnval=1` 请求，拿到的是 **`durl` 形式的渐进式 MP4，音视频已经封装在同一个文件里**。

DASH（`fnval=4048`）拿到的 `dash` 是**视频轨和音频轨分开**的：只播视频轨会没有声音，
只播音频轨会没有画面，而且没法拖动——两条流各自有自己的时间轴，要对齐得自己写同步逻辑。
预览要的是「能播能拖」，渐进式 MP4 一个 URL 就全满足了。

下载仍然用 DASH（画质更全、体积更省），预览和下载走两条不同的请求路径。

### 必须带 `Referer`，否则 403

拿到的视频 URL 直接请求会返回 **HTTP 403**；带上 `Referer: https://www.bilibili.com` 才返回 206 和正常数据。

所以不能用 `VideoView.setVideoURI(Uri)` —— 它没法附加请求头。
用 `MediaPlayer.setDataSource(Context, Uri, Map)` 把 `Referer` / `User-Agent` 传进去，这是 API 14 就有、
到 API 34 也没废弃的写法（`setVideoURI(Uri, Map)` 反而在 34 上废弃了）。

### `SurfaceView` 必须一直可见

`SurfaceView` 的 `Surface` **只在视图可见时才存在**。曾经把播放视图初始设成 `visibility="gone"`，
打算准备好后再显示 —— 结果是死锁：没有 Surface 就没有 `surfaceCreated`，
没有 `surfaceCreated` 就不会 `setDisplay()`，也就永远不会准备好。

正确做法是让 Surface 常驻可见，**用叠在它上面的封面来表达「还没在播」**。
布局里 `videoView` 在 `ivCover` 下面，顺序不能反。

### 圆角要靠 outline provider

`SurfaceView` 是独立的合成层，**不跟随父容器的 `clipToOutline`**。
只在父 `FrameLayout` 上设 `clipToOutline=true` 是没用的，视频会顶出方角。

必须给父容器设 `ViewOutlineProvider.BACKGROUND` 并配一个圆角背景：
```java
box.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
box.setClipToOutline(true);
```
`bg_preview_frame.xml` 既是占位底色，也是这里的圆角来源。

### 大播放键 64dp

封面上的播放键是 64dp（`play_button_size`），比常规的 48dp 触控下限更大 ——
它是这一屏最想让人点的东西，做成实心主色圆 + 白色三角，不叠任何半透明黑罩（那会让它显脏）。

它**不能加 `elevation`**：父容器的 `clipToOutline` 会把投影一起裁掉，加了也看不见，只会拖慢渲染。

### 失败不影响下载

预览取流失败时只在预览区下方显示一行说明，**不阻断下载**：
两条链路是独立的，预览挂了用户照样能把文件下下来。

---

## 8. 五种互斥状态

界面任何时刻只处于五种状态之一，**不存在空白屏**：

| 状态 | 表现 |
|---|---|
| 空 | 图标 + 引导文案 + 两条能力说明 |
| 加载 | 骨架屏占位（而不是转圈空等） |
| 错误 | 问题 + 恢复建议 + 原始信息 + 重试按钮 |
| 结果 | 预览区 / 标题 / 分 P / 下载行 |
| 下载中 | 当前行内：阶段文字 + 百分比 + 确定性进度条 |

预览区在结果态里始终占位。未取到视频时显示封面 + 大播放键；取到后封面撤掉、控制条展开。
高度按视频宽高比调整，但上限是屏幕高度的 60%，避免竖屏视频把下面的内容全挤出去。

---

## 9. 动效

| 场景 | 时长 | 曲线 |
|---|---|---|
| 底部表单进场 | 280ms | emphasized decelerate |
| 底部表单退场 | 180ms | accelerate |
| Snackbar 进出 | 220 / 160ms | decelerate / accelerate quad |

退场一律短于进场。**尊重系统的「移除动画」设置**：`ValueAnimator.areAnimatorsEnabled()` 为 false 时不做任何位移与淡入。

---

## 10. 无障碍

- 每一个图标按钮都有 `contentDescription`
- 输入框标签通过 `android:labelFor` 与控件关联
- 全部触控目标 ≥ 48×48dp，间隔 ≥ 8dp
- 正文对比度 ≥ 4.5:1，大字号 ≥ 3:1
- 系统返回手势／按键全程可用
- 旋转等重建后自动重新解析，用户不必再点一次

---

## 11. 被明确拒绝的做法

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

## 12. 文件布局

```
app/src/main/res/
├── values/
│   ├── colors.xml          手写维护；tools/gen_palette.py 只做对比度校验，不生成色值
│   ├── dimens.xml          4dp 网格与语义间距
│   ├── styles.xml          M3 字阶与组件样式
│   ├── themes.xml          浅色主题（含系统栏标志）
│   ├── strings.xml         全部文案
│   └── ic_launcher_background.xml
├── values-night/
│   ├── colors.xml          深色色板
│   └── themes.xml          深色主题
├── color/                  状态色（按钮／芯片／列表项图标／涟漪／开关）
├── drawable/               形状与涟漪，以及全套矢量图标
├── anim/                   表单与 Snackbar 的进出动画
├── layout/
│   ├── activity_main.xml   主界面（五种状态，含预览区）
│   ├── item_part.xml       分 P 列表项
│   ├── item_download.xml   下载行（整行可点，含行内进度）
│   ├── sheet_settings.xml  设置底部表单
│   ├── view_chip.xml       芯片（样式走 @style/Widget.Chip）
│   └── view_snackbar.xml   Snackbar
└── mipmap-anydpi-v26/      自适应启动图标（含主题图标单色层）
```

Java 侧的界面支撑组件：

- `PreviewController.java` —— 内置预览播放器（零依赖，见下节）
- `FlowLayout.java` —— 可换行的芯片容器（设置面板用）
- `Snackbar.java` —— M3 Snackbar 宿主
- `MainActivity.java` —— 状态机与全部交互
- `DownloadService.java` —— 前台服务，文案全部取自 `strings.xml`
- `BiliApi.ApiException` —— 核心层与界面层之间的错误码契约

---

## 修改色板

色板是**手写**的，直接改资源文件，不要改脚本 —— `tools/gen_palette.py` 只校验，不生成。

改品牌色：编辑 `app/src/main/res/values/arrays.xml` 与 `values-night/arrays.xml` 里的
`scheme_N_primary` / `scheme_N_primary_variant`（也就是 `hyper_primary` /
`hyper_primary_variant` 两个数组的成员），然后重新构建；改完跑一次校验对比度。

```powershell
python tools/gen_palette.py     # 校验现有两套色板（只读，不生成也不写文件）
# 输出里 "失败 0 项" 才算通过；有硬门槛失败项时退出码为 1
```

改中性面、浮雕双色、玻璃叠加色同理，直接编辑 `values/colors.xml` 与
`values-night/colors.xml`，改完同样跑一遍校验。

> `hyper_primary` 是**数组名**（`R.array.hyper_primary`），不是颜色令牌，
> 色板里没有这个色值 —— 改品牌色落点是那 10 个 `scheme_N_*` 色值。

---

## 每次改动后的自检

规范写在文档里没有用，得能被执行。下面四项每次改完界面都要重跑：

1. **对比度** —— `python tools/gen_palette.py`，要求两套主题都「失败 0 项」。
   它校验的是**已有的**色板（读 `colors.xml` 与 `arrays.xml`），**不生成**色值、也不写任何文件；
   有硬门槛失败项时退出码为 1。当前基线与已知失败项见 §1「对比度是算出来的，不是看出来的」。
2. **无字号字面量** —— 扫描 `res/` 里的 `android:textSize="数字` 与 Java 里的 `setTextSize(`，应为 0 处
3. **无死资源** —— 统计每个 `dimen` / `style` / `string` / `drawable` / `layout` 的引用数（XML 认 `@type/name` 与 `parent="..."`，Java 认 `R.type.name`），应为 0 项
4. **无硬编码文案** —— 扫描 Java 里的中文字面量。允许的例外只有纯 Java 核心层（`BiliApi` / `Json` / `Model` / `MuxUtil` / `MediaStoreSaver` / `WbiSigner`）里给日志和桌面测试看的消息

`space_2xl` 是唯一允许「引用数为 0」的令牌：它是 4dp 间距阶里的一级，留白是为了让比例完整，不是因为某处用了它。

还有一个不看代码的检查：**改完必须能装上跑一遍**。深色模式、系统「移除动画」、字体放大到最大、以及从 B 站 App 分享过来的 Intent，这四种情况各过一遍。
