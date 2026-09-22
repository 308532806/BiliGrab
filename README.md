# BiliGrab

> 一个**零第三方依赖**的哔哩哔哩视频下载器 Android 客户端。
> 只调用网页版自身使用的公开接口，用你自己的登录态取流，用系统 `MediaMuxer` 合成 MP4。

[![Build APK](https://github.com/308532806/BiliGrab/actions/workflows/build.yml/badge.svg)](https://github.com/308532806/BiliGrab/actions/workflows/build.yml)
![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84)
![APK Size](https://img.shields.io/badge/APK-~90%20KB-blue)
![Dependencies](https://img.shields.io/badge/dependencies-none-success)

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

- 粘贴链接 / BV 号 / av 号，或直接从 B 站 App「分享」唤起
- 自动解析标题、UP 主、封面、分 P 列表
- DASH 音视频分离下载，`MediaMuxer` 合成单文件 MP4
- 支持 **4K / 1080P60 / HDR / 杜比音频**（取决于账号权限）
- 可选仅下载音频
- 多 CDN 备用地址自动切换（主地址失败时重试 `backupUrl`）
- 前台服务 + 通知栏实时进度
- 完成后写入系统媒体库，相册直接可见
- 深色界面，自适应图标

## 技术特点

| 项 | 说明 |
| --- | --- |
| 语言 | Java 8（无 Kotlin，无需 Kotlin 编译器） |
| 依赖 | **零**。不用 AndroidX / OkHttp / Gson / RxJava |
| JSON | 系统自带 `org.json` |
| 网络 | 系统自带 `HttpURLConnection` |
| 合成 | 系统自带 `MediaMuxer` + `MediaExtractor`（不打包 ffmpeg） |
| 包体 | 约 90 KB |
| 构建 | `aapt2` + `javac` + `d8` + `zipalign` + `apksigner`，**不依赖 Gradle** |

因为不引入任何第三方库，整个 APK 只有一个 12 KB 的 `classes.dex`，
冷启动快，也没有供应链风险。

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

### 方式一：官方脚本（不需要 Gradle）

```powershell
# 依赖：JDK 17 + Android SDK build-tools 34.0.0 / platform 34
powershell -ExecutionPolicy Bypass -File scripts\build-apk.ps1 -VersionName 1.0.0
# 产物：dist\BiliGrab-1.0.0.apk
```

可用环境变量 `JAVA_HOME`、`ANDROID_HOME` 指定工具链位置。

### 方式二：Android Studio / Gradle

```bash
./gradlew :app:assembleDebug
```

### 方式三：让 GitHub 帮你编译

推一个 tag 即可，Actions 会自动构建并发布 Release：

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## 使用

1. 安装 APK。
2. （推荐）点右上角**设置**，填入 `SESSDATA` —— 不填画质上限只有 480P。

   > 获取方法：电脑浏览器登录 bilibili.com → `F12` → Application → Cookies →
   > 复制 `SESSDATA` 的值。

3. 粘贴链接或 BV 号，点**解析**。
4. 选分 P 和画质，点**开始下载**。
5. 完成后视频在 `Movies/BiliGrab/`，相册里能直接看到。

## 项目结构

```
BiliGrab/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/biligrab/app/
│   │   ├── MainActivity.java          # 界面、解析、交互
│   │   ├── DownloadService.java       # 前台服务、断点流程、通知
│   │   ├── BiliApi.java               # 接口封装与参数校验
│   │   ├── WbiSigner.java             # WBI 风控签名
│   │   ├── MuxUtil.java               # MediaMuxer 音视频合成
│   │   ├── MediaStoreSaver.java       # 写入系统媒体库
│   │   ├── Http.java                  # HttpURLConnection 封装
│   │   ├── Model.java                 # 数据模型
│   │   └── Prefs.java                 # 偏好设置
│   └── res/                           # 布局、颜色、图标
├── scripts/build-apk.ps1              # 无 Gradle 构建脚本
└── .github/workflows/build.yml        # CI 自动打包
```

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

## 免责声明

本项目**仅供个人学习、研究与技术交流**，用于备份你有权访问的内容。

- 请遵守[哔哩哔哩用户协议](https://www.bilibili.com/protocol/)及相关法律法规。
- 请勿将下载内容用于商业用途或二次传播。
- 请勿高频请求，避免对平台造成负担。
- 作者不对使用本工具产生的任何后果负责。

如果你认为本项目侵犯了你的权益，请提 Issue，会及时处理。

## 许可

MIT License，见 [LICENSE](LICENSE)。
