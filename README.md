# BiliGrab

> 哔哩哔哩 + YouTube 视频下载器 Android 客户端。
> B 站部分**零第三方依赖**，只调用网页版自身使用的公开接口，用你自己的登录态取流，
> 用系统 `MediaMuxer` 合成。YouTube 部分由内嵌的 yt-dlp 提供。
> 内置预览播放器，选好画质再下载。

[![Build APK](https://github.com/308532806/BiliGrab/actions/workflows/build.yml/badge.svg)](https://github.com/308532806/BiliGrab/actions/workflows/build.yml)
![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84)
![APK Size](https://img.shields.io/badge/APK-~18.5%20MB-blue)
![B站部分依赖](https://img.shields.io/badge/bilibili%20deps-none-success)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

---

## 为什么会有这个项目

2026 年 1 月 28 日，哔哩哔哩向 GitHub 上的接口文档项目
[`SocialSisterYi/bilibili-API-collect`](https://github.com/SocialSisterYi/bilibili-API-collect)
发出《侵权告知函》，认定系统性地收集并公开传播非公开接口、调用逻辑与风控机制
超出正常技术交流范畴。该仓库已于 2026-01-30 归档。

随后产生了一连串连锁反应：

| 项目 | 星标 | 状态 |
| --- | --- | --- |
| `SocialSisterYi/bilibili-API-collect` | 20.2k | 已归档（2026-01-30） |
| `nilaoda/BBDown` | 13.9k | 已归档（2026-05-14） |
| `1250422131/bilibilias` | 1.8k | 已归档（2026-07-06），仓库内容清空 |

`BILIBILIAS` 作者在 README 中的原话是：

> 项目本身依旧存在较高的合规风险……自本说明发布后，BILIBILIAS 不会再通过公开仓库、
> 群聊、私发、网盘、镜像或其他渠道继续分发、维护或支持相关版本、代码、补丁、脚本及技术方案。

**注意：停更是合规与法律层面的主动选择，不是技术故障。** 这解释了一个常见疑惑 ——
为什么旧版 APK 的下载功能至今仍然正常：

1. **纯客户端架构。** 核心解析逻辑全在设备上跑，作者没有服务端参与取流，
   所以「停服」这件事根本不存在。
2. **接口仍然在线。** 它调用的是网页版自己也在用的 `player/wbi/playurl`，
   只要 B 站自己的网页还能播，这个接口就必须活着。
3. **没有远程熔断开关。** 作者没有加入「服务器下发指令禁用下载」的逻辑。
4. **合成不依赖云端。** ffmpeg 二进制直接打包在 APK 里，合并音视频完全离线。
5. **普通稿件没有 DRM。** 只有番剧/付费内容才走 Widevine，UGC 视频是裸流。

BiliGrab 是从零重写的独立实现，**没有复用 BILIBILIAS 的任何代码**，
也不会去复活那个已经被作者明确关闭的项目。它存在的前提是：接口是网页版公开在用的，
用户用自己的账号为自己备份自己有权观看的内容。

---

## 功能

**哔哩哔哩**

- 粘贴链接 / BV 号 / av 号，或直接从 B 站 App「分享」唤起
- 自动解析标题、UP 主、封面、分 P 列表
- **内置预览播放器**：解析完直接在应用里播放，可拖动到任意位置，确认是要下载的那个再下
- 每个可用画质一行，**整行就是下载按钮**，行内显示阶段、百分比与进度条
- DASH 音视频分离下载，`MediaMuxer` 合成单文件 MP4
- 支持 **4K / 1080P60 / HDR / 杜比音频**（取决于账号权限）
- 仅音频导出为 `m4a`（跳过视频流）
- 多 CDN 备用地址自动切换（主地址失败时重试 `backupUrl`）

**YouTube**

- 粘贴 `youtube.com` / `youtu.be` 链接即可，与 B 站共用同一套界面
- 画质从 **144P 到 2160P（4K）**，每一行直接标出**文件体积**和编码
- 高画质自动选用正确的容器：1440P / 2160P 只有 VP9 / AV1，会封装成 **WebM**；
  1080P 及以下走 H.264 + AAC 的 **MP4**
- **应用内更新解析引擎**：YouTube 的接口变化频繁，内置的 yt-dlp 迟早会失效，
  设置里一键拉取官方最新版（实测 2025.11.12 → 2026.08.19）
- 预览播放同样可用（经本地转发走代理）

**通用**

- 前台服务 + 通知栏实时进度
- 完成后写入系统媒体库，相册 / 音乐播放器直接可见
- Hyper-Neumorphic 界面：新拟态 / 玻璃态**双引擎**可切换，5 套主题色，浅色 / 深色 / 跟随系统三选一
- 自适应启动图标（含 Android 13+ 主题化图标）

---

## YouTube 支持是怎么做的

### 为什么不是自己解析

先说结论：**纯 Java 解析 YouTube 是做不到的**，这不是工作量问题。

实测拿到的 27 个格式**全部是 `signatureCipher`，没有一个明文 `url`**。
签名要用 YouTube 自己的播放器 JS 去解，而 Android 上没有 JS 引擎能直接用。
InnerTube 的 `/youtubei/v1/player` 走网页端身份会被判定为 `UNPLAYABLE`，
换 Android / TVHTML5 客户端身份则直接 `HTTP 400`。

所以这里选了 **yt-dlp**（192k stars，最新版单个二进制的下载量就有 327 万次），
通过 [`youtubedl-android`](https://github.com/youtube-dl-android/youtubedl-android)
把 CPython 运行时和 yt-dlp 一起打包进 APK。

### 体积账

| 部分 | 大小 |
| --- | --- |
| CPython 运行时（仅 arm64-v8a） | 14.52 MB |
| yt-dlp 本体 | 3.02 MB |
| 其余（含原来的 B 站逻辑） | ~1 MB |
| **合计** | **18.5 MB** |

两个刻意的取舍：

- **只打包 arm64-v8a。** 四个 ABI 全打是 56.47 MB，而 2026 年的设备几乎都是 arm64。
- **不带 ffmpeg 和 aria2c。** 单是 ffmpeg 的 AAR 就 132.91 MB。
  合流这活本来就有系统 `MediaMuxer` 在做，没有必要为了「统一」再塞一份 ffmpeg。

对比同类应用（Seal 54–66 MB、YTDLnis 同量级），18.5 MB 属于明显更小的。

### 一个必须知道的限制

**YouTube 在中国大陆无法直连，必须先在设置里填代理。**
该设置只作用于 YouTube —— B 站仍然直连，走代理只会更慢，还可能触发风控。

代理是传给 yt-dlp 命令行（`--proxy`）的，因为 Python 的 urllib **不认**
Android 的全局 HTTP 代理。

预览播放还有一层特殊处理：`MediaPlayer` 由 native 的 NuPlayer 驱动，
**同样不认应用层代理**，直连 googlevideo 会一直卡在 `prepareAsync`。
所以预览会在本地起一个转发（`LocalRelay`），让 MediaPlayer 连 `127.0.0.1`，
由应用经代理把字节搬回来。为此 manifest 里给回环地址开了一个明文例外
（`network_security_config.xml`），其余域名仍然全部禁止明文。

## 界面

**1.4.0 起界面层换成 Hyper-Neumorphic** —— 新拟态（Neumorphic）+ HyperOS 语言 + 玻璃态
三合一的设计系统，纯 Java + XML 手写，仍然零 AndroidX、零 Material Components。

- **双引擎可切换** —— 设置面板「外观 → 视觉引擎」里选「新拟态」或「玻璃态」。
  两者共用同一套尺寸、圆角、动效与交互参数，**只有「表面怎么画」不同**：
  新拟态用 `BlurMaskFilter` 画双色浮雕阴影（凸起 / 凹陷两套画法），
  玻璃态用整屏 mesh + 半透明叠加 + 方向光（mesh 只画一份，否则光斑会在每个面上重复）。
- **5 套主题色** —— 樱花粉（默认，保留原本的品牌种子色 `#FB7299`）/ 默认蓝 / 深海蓝 /
  薄荷绿 / 薰衣草紫，深浅模式各一套值。**风格照规范走，主色作为可变量保留品牌。**
- **无涟漪** —— 主题把 `colorControlHighlight` / `selectableItemBackground` 压成透明，
  点击反馈改由 `ui/HyperosClick` 提供：scale 0.95，150ms 按下 / 200ms 松开，
  `cubic-bezier(0.4, 0, 0.2, 1)`，配 `TextHandleMove` 触觉（开关用 `LONG_PRESS`）。
- **圆角与浮雕成组** —— 两者是一组参数，单改其中一个会破坏整套界面的厚度一致性：

  | 用途 | 圆角 / 浮雕 | 用途 | 圆角 / 浮雕 |
  | --- | --- | --- | --- |
  | 页面卡片 | 28dp / 6dp | 输入框 | 16dp / 2dp |
  | 按钮 | 26dp / 6dp | 芯片 | 14dp / 3dp |
  | 对话框、底部面板 | 24dp / 8dp | 开关轨道 | 14dp / 1.5dp |

  浮雕高度不是 Material 的 elevation：它同时决定模糊半径与阴影偏移（= 0.5 × 该值）。

完整的设计规则、渲染原理、令牌表（间距 / 圆角 / 浮雕 / 字号 / 颜色）与已知取舍，
见 **[docs/DESIGN-SYSTEM.md](docs/DESIGN-SYSTEM.md)**。
1.3.0 及以前的 Material 3 规则仍留在 **[DESIGN.md](DESIGN.md)**，
其中色彩、排版、间距与形状、层级表达几节已被取代。

几条具体的实现取向：

- **整行就是按钮** —— 每个画质一行、整行（`minHeight` 48dp，实际高约 72dp）可点，不需要瞄准小控件
- **零依赖手写播放器** —— `MediaPlayer` + `SurfaceView`，预览走 `fnval=1` 的渐进式 MP4
  （音视频已封装在一起，才有声音、才能拖）；下载仍走 DASH，画质更全
- **全部文案在 `strings.xml`** —— 包括通知栏频道名和下载失败的每一条原因
- **核心层与界面层用错误码对话** —— `BiliApi` 不依赖任何 Android API（桌面端才能直接跑真实接口联调），
  它抛出带 `code` 的异常，界面按 `code` 映射成文案。界面**不匹配错误消息里的中文子串**，
  否则改一个字的文案就会让映射静默失效
- **输入框尾部按钮一钮两用** —— 空时是「粘贴」，有内容时变「清空」
- **无死资源** —— `dimen` / `style` / `string` / `drawable` / `layout` 的引用数都有检查，见 DESIGN.md 末尾。
  当前设计系统仍记录有未引用令牌及实现/文档差异；清单见
  [docs/DESIGN-SYSTEM.md](docs/DESIGN-SYSTEM.md) 的「已知不一致」一节

## 技术特点

| 项 | 说明 |
| --- | --- |
| 语言 | Java 8（无 Kotlin 源码，不需要 Kotlin 编译器） |
| B 站链路的依赖 | **零**。不用 AndroidX / OkHttp / Gson / RxJava |
| JSON | 系统自带 `org.json` |
| 网络 | 系统自带 `HttpURLConnection` |
| 合成 | 系统自带 `MediaMuxer` + `MediaExtractor`（**不打包 ffmpeg**） |
| 预览 | 系统自带 `MediaPlayer` + `SurfaceView`（不打包 ExoPlayer） |
| 包体 | 约 18.5 MB —— 其中 17.5 MB 是 YouTube 引擎 |
| 构建 | `aapt2` + `javac` + `d8` + `zipalign` + `apksigner`，**不依赖 Gradle** |

B 站那部分代码一个第三方库都不用，`classes.dex` 只有约 98 KB。
18.5 MB 里 17.5 MB 是 YouTube 引擎的运行时（CPython + yt-dlp），
那部分是被迫引进的 —— 理由见下面「为什么不是自己解析」。

**注意整张表描述的是运行时形态。** 构建时仍然需要 vendor/ 里的 jar
参与编译，详见「许可」一节。

## 它是怎么工作的

```
用户输入 BV 号
   │
   ├─► GET /x/web-interface/view        → 标题、封面、分 P、cid
   │
   ├─► GET /x/web-interface/nav         → 取 wbi_img.img_url / sub_url
   │      └─► 按 MIXIN_KEY_ENC_TAB 重排 → mixin_key（32 位）
   │
   ├─► GET /x/player/wbi/playurl
   │        ?bvid=&cid=&qn=&fnval=4048&fourw=1&wts=&w_rid=MD5(query+mixin_key)
   │      └─► dash.video[] / dash.audio[]（含 baseUrl + backupUrl）
   │
   ├─► 流式下载 video.m4s（带 Referer，走 CDN 直链）
   ├─► 流式下载 audio.m4s
   │
   └─► MediaExtractor → MediaMuxer → output.mp4 → MediaStore
```

`fnval` 位掩码说明：`16`=DASH、`64`=HDR、`128`=4K、`256`=杜比音频、
`512`=杜比视界、`1024`=8K、`2048`=AV1。

## 编译

### 本地构建

```powershell
# 1) 准备工具链（幂等，可重复执行；会从 dl.google.com 直接下载官方 zip）
powershell -ExecutionPolicy Bypass -File scripts\setup-sdk.ps1

# 2) 编译（另需 JDK 17）
# 当前源码版本：1.9.2（versionCode 10902）
powershell -ExecutionPolicy Bypass -File scripts\build-apk.ps1 -VersionName 1.9.2
# 产物：dist\BiliGrab-1.9.2.apk
```

首次构建会自动调用 `scripts/fetch-vendor.ps1` 拉取 YouTube 引擎
（约 21 MB，之后复用）。可用环境变量 `JAVA_HOME`、`ANDROID_HOME` 指定工具链位置。
`setup-sdk.ps1` 不走 `sdkmanager`，而是直接解压官方 zip，少一层出错的可能。

### 让 GitHub 帮你编译

推一个 tag 即可，Actions 会自动构建并发布 Release：

```bash
git tag v1.9.2 && git push origin v1.9.2
```

> 想让 CI 产物和本地发布的 APK 签名一致（用户才能原地升级），
> 需要先配置 `KEYSTORE_BASE64` Secret —— 见下一节。

### 关于 Gradle

**本项目不支持 Gradle 构建**，`build.gradle.kts` 一类的文件已经删除。

原先确实放过一套，但它只维护到 1.1.1 就再没动过，而且**缺四样必需的东西**：
vendor 的 jar 不在 classpath 上、原生库没进 `jniLibs`、`res/raw/ytdlp` 没进资源、
`com.yausername.youtubedl_android` 的 R 类无处生成（脚本构建靠的是 aapt2 的
`--extra-packages`）。它编译不过，而且仓库里根本没有 `gradlew` 包装器 ——
README 里那条 `./gradlew :app:assembleDebug` 从来没跑通过。

留着一个构建不了的文件比没有更糟，所以删掉了。要恢复 Android Studio 支持，
得先把上面四项配齐并在真机上验证过。

## 签名密钥

**这一节很重要**，直接决定用户能不能覆盖安装升级。

签名密钥默认保存在 `keystore/biligrab.jks`（已在 `.gitignore` 中）。
首次构建会自动生成，并打印一段 base64。**请立刻备份这个文件。**

- 密钥丢失后，已安装旧版本的设备**无法安装新版本** —— Android 会以「签名不匹配」拒绝
- 换一把密钥意味着所有用户必须卸载重装

### 让 CI 产出同样签名的 APK

本地和 CI 各用一把钥匙的话，两边产物无法互相覆盖安装。
把本地密钥库转成 base64 存进仓库 Secret 即可统一：

```powershell
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\biligrab.jks"))
Set-Clipboard $b64   # 已复制到剪贴板
```

然后到 `仓库 → Settings → Secrets and variables → Actions` 新建两个 Secret：

| Secret 名称 | 值 |
| --- | --- |
| `KEYSTORE_BASE64` | 上一步复制的内容 |
| `KEYSTORE_PASS` | 密钥库口令（默认 `biligrab123`，建议改掉） |

未配置时 CI 会临时生成一把新钥匙，产物仅供测试安装；
tag 触发 Release 时会给出 `::warning::` 提示。

## 使用

### 哔哩哔哩

1. 安装 APK。
2. （推荐）点右上角**设置**，填入 `SESSDATA` —— 不填画质上限只有 480P。

   > 获取方法：电脑浏览器登录 bilibili.com → `F12` → Application → Cookies →
   > 复制 `SESSDATA` 的值。

3. 粘贴链接或 BV 号，点**解析**。
4. 选分 P 和画质，点**开始下载**。
5. 完成后视频在 `Movies/BiliGrab/`，相册里能直接看到。

### YouTube

1. 先在**设置 → 代理**里填 `host:port`。**这一步不能跳过**，
   YouTube 在中国大陆无法直连。该设置只影响 YouTube。
2. 粘贴 `youtube.com/watch?v=...`、`youtu.be/...` 或 `youtube.com/shorts/...`，点**解析**。
3. 画质列表从 144P 到 2160P，每行都标着体积。点一行开始下载。
4. 完成后视频在 `Movies/BiliGrab/`，音频在 `Music/BiliGrab/`。

> **引擎会过期，这是知道的前提。** yt-dlp 内置的是打包时的快照，
> YouTube 的接口变化以周计。真遇到解析失败，去**设置 → 更新引擎**拉最新版。
> 更新前请确认代理可用 —— 更新走的是 `github.com`。

## 项目结构

```
BiliGrab/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/biligrab/downloader/
│   │   ├── MainActivity.java          # 界面状态机与交互
│   │   ├── DownloadService.java       # 前台服务、下载流程、通知
│   │   ├── BiliApi.java               # B 站接口封装与参数校验
│   │   ├── WbiSigner.java             # WBI 风控签名
│   │   ├── YouTubeEngine.java         # yt-dlp 调用、更新、结果解析
│   │   ├── LocalRelay.java            # 预览用的本地代理转发
│   │   ├── MuxUtil.java               # MediaMuxer 音视频合成（MP4 / WebM）
│   │   ├── MediaStoreSaver.java       # 写入系统媒体库
│   │   ├── Http.java                  # HttpURLConnection 封装
│   │   ├── Json.java                  # org.json 包装
│   │   ├── Model.java                 # 数据模型
│   │   ├── Prefs.java                 # 偏好设置
│   │   ├── FlowLayout.java            # 可换行的芯片容器
│   │   ├── Snackbar.java              # Snackbar 宿主（底部即时反馈）
│   │   └── ui/                        # Hyper-Neumorphic 界面层（1.4.0 起）
│   │       ├── NeumorphicDrawable.java    # 浮雕 / 玻璃面的绘制引擎（含位图缓存）
│   │       ├── NeumorphicSurface.java     # 把浮雕应用到任意 View（补 bleed padding）
│   │       ├── NeumorphicControls.java    # 系统 Switch / SeekBar 的「浮雕化」
│   │       ├── NeumAttr.java              # 读 neu* 属性并转交 NeumorphicSurface
│   │       ├── HyperTheme.java            # 颜色与引擎判断的唯一入口
│   │       ├── HyperosClick.java          # 无涟漪点击（缩放 + 形变 + 触觉）
│   │       ├── GlassMeshDrawable.java     # 玻璃引擎的整屏 mesh（只挂根容器）
│   │       ├── StaggerEnter.java          # 分段入场动画参数
│   │       └── Neu*.java                  # 7 个带浮雕能力的基础控件
│   └── res/
│       ├── xml/network_security_config.xml   # 仅放行回环地址的明文例外
│       └── ...                        # 色板、字阶、布局、矢量图标（含 values-night）
├── vendor/                            # YouTube 引擎（由脚本下载，gitignore）
│   ├── libs/                          # youtubedl-android 及其运行时依赖
│   ├── jni/arm64-v8a/                 # CPython 运行时
│   └── res/raw/ytdlp                  # yt-dlp 本体
├── scripts/
│   ├── setup-sdk.ps1                  # 下载 Android SDK 组件
│   ├── fetch-vendor.ps1               # 拉取并裁剪 YouTube 引擎（21.66 MB）
│   └── build-apk.ps1                  # 无 Gradle 构建脚本
├── tools/
│   ├── gen_palette.py                 # 色板对比度校验器（只读，不生成色值；色板本身手写维护）
│   └── desktop-verify/                # 桌面端接口联调测试
├── keystore/                          # 签名密钥（gitignore，请自行备份）
├── docs/
│   └── DESIGN-SYSTEM.md               # Hyper-Neumorphic 设计系统（1.4.0 起）
├── DESIGN.md                          # 1.3.0 及以前的设计规则（部分已被取代）
└── .github/workflows/build.yml        # CI 自动打包
```

`vendor/` 不入库（约 21 MB 二进制），但它是**必需的构建依赖** ——
源代码里的 `YouTubeEngine.java` 直接引用了 `com.yausername.youtubedl_android`
的类，没有它 `javac` 一定失败。构建脚本检测不到就会**自动调用**
`scripts/fetch-vendor.ps1` 拉取；CI 上这一步被缓存住了，只下第一次。

## 常见问题

**Q：提示 `code=-101 账号未登录`？**
去设置里填 `SESSDATA`。

**Q：提示 `code=-352 风控校验失败`？**
风控拦截。等几分钟重试，或换一个 `SESSDATA`。程序会为未登录状态自动获取 `buvid3` 指纹降低触发概率。

**Q：提示「合成结果缺少视频轨」？**
某些 HEVC/AV1 流在旧设备上无法封装。关掉设置里的「优先 H.264」再试，或直接选 H.264 画质。

**Q：能下番剧 / 付费内容吗？**
不能。那些走 Widevine DRM，本工具不涉及也不尝试绕过 DRM。

**Q：为什么下载的是 m4s 而不是 mp4？**
内部确实分两次下载 `.m4s`（DASH 规范），合成后输出的就是标准 MP4，用户拿到的永远是单个 mp4 文件。

**Q：YouTube 链接解析失败，提示连接超时？**
没填代理。YouTube 在中国大陆无法直连，去设置里填 `host:port`。

**Q：YouTube 下 4K 得到的是 `.webm` 而不是 `.mp4`？**
这是必然的，不是 bug。YouTube 的 1440P 和 2160P 只有 VP9 与 AV1，没有 H.264。
而系统封装器不接受 VP9 进 MP4（实测 `addTrack` 直接抛
`IllegalStateException: Failed to add the track to the muxer`），
所以高画质封装成 WebM（VP9 + Opus）。1080P 及以下有 H.264，走 MP4。

想要 MP4 就选 1080P —— 那也是绝大多数场景下最合适的一档。

**Q：YouTube 提示「解析引擎已过期」，但更新失败？**
先确认代理填对了。更新走的是 `github.com`，同样需要代理。
另外 GitHub 对单个出口 IP 有频率限制，遇到 403 过几分钟再试。

## 免责声明

本项目**仅供个人学习、研究与技术交流**，用于备份你有权访问的内容。

- 请遵守[哔哩哔哩用户协议](https://www.bilibili.com/protocol/)、
  [YouTube 服务条款](https://www.youtube.com/t/terms)及相关法律法规。
- 请勿将下载内容用于商业用途或二次传播。
- 请勿高频请求，避免对平台造成负担。
- 作者不对使用本工具产生的任何后果负责。

如果你认为本项目侵犯了你的权益，请提 Issue，会及时处理。

## 许可

**GPL-3.0**，见 [LICENSE](LICENSE)。

### 为什么不是 MIT

BiliGrab 原本以 MIT 授权。加入 YouTube 支持后**必须**改为 GPL-3.0，原因不在选择，
而在依赖的传染性：YouTube 解析依赖
[`youtubedl-android`](https://github.com/youtube-dl-android/youtubedl-android)，
它以 GPL-3.0 发布，而 GPL-3.0 要求链接它的作品整体以 GPL-3.0 分发。

B 站那部分代码本身依然是自成一体的，但**整个应用**现在是 GPL-3.0。
如果你只想要 B 站下载功能并希望保持 MIT，那需要自己动手：把
`YouTubeEngine.java` 与 `LocalRelay.java` 删掉，同时移除 `MainActivity` 与
`DownloadService` 里对它们的调用。B 站那部分代码本身与 YouTube 无关，
删干净之后整体回到 MIT 是成立的 —— 但**构建脚本不会替你完成这件事**。

> 早先的版本里写着「删掉 `vendor/` 即可自动退回纯 B 站版本」，那是错的：
> 少了 vendor 只会在 javac 阶段以一行没有指向性的「javac 失败」告终
> （CI 上确实就是这么挂的）。要做到自动降级，得有第二份接口对齐的桩实现，
> 而桩会随真实实现漂移。与其维护一个对不上的东西，不如把 vendor 当成
> 普通依赖 —— 缺了就拉，拉不动就报一条说得清的错。

### 第三方组件

| 组件 | 授权 | 用途 |
| --- | --- | --- |
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Unlicense | YouTube 解析 |
| [youtubedl-android](https://github.com/youtube-dl-android/youtubedl-android) | GPL-3.0 | 在 Android 上运行 CPython 与 yt-dlp |
| [CPython](https://www.python.org/) | PSF-2.0 | 运行时 |
| [Jackson](https://github.com/FasterXML/jackson) | Apache-2.0 | youtubedl-android 的硬依赖 |
| [Apache Commons IO / Compress](https://commons.apache.org/) | Apache-2.0 | 同上 |
