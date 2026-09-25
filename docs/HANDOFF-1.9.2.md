# BiliGrab 交接文档 —— 至 1.9.2（工作区状态）

> 接手对象：**另一个 AI / 另一个开发者**
> 写于本地 HEAD `958c848`。本文档是自包含的：只读这一份就能接手。
> 前一份历史交接在 `docs/HANDOFF-1.5.0.md`（内容已到 1.8.0），那份是**过程记录**；
> 本文档是**当前状态 + 可操作手册**。两者不冲突，但以本文档为准。

---

## 0. 三十秒速览

BiliGrab 是一个 Android 视频下载器（**B 站 + YouTube**）。纯 Java，**不用 Gradle、
不用 Kotlin、不用 AndroidX**，靠 `aapt2 + javac + d8 + zipalign + apksigner` 手工构建。
界面是自研的 **Hyper-Neumorphic**（HyperOS + 拟物 + 玻璃态）双引擎皮肤。

- 仓库：`https://github.com/308532806/BiliGrab`（public）
- 最新发布：**v1.9.1**，release id `395774476`，附件 `BiliGrab-1.9.1.apk` 19489336 字节
- 本地 HEAD：`958c848`（**比已发布的多一个提交，未推送**）
- 设备：OPPO Reno4 5G / PDPM00 / Android 12 / API 31 / 1080x2400 / density 480 / serial `cbb16115`
- 目标用户就是设备持有者本人；装 APK **必须用 root**（见 §4）

---

## 1. 硬约束（违反会直接坏掉，别"优化"掉）

| 约束 | 原因 |
|---|---|
| **零 AndroidX、零 Gradle、零 Kotlin** | 构建脚本只用 SDK build-tools。引入任何 AGP/AndroidX 依赖都会让构建链报废（要下几百 MB 且和手工构建不兼容）。 |
| **minSdk 26 / targetSdk 34** | `AndroidManifest.xml`。targetSdk 34 但设备是 API 31。 |
| **versionCode = major*10000 + minor*100 + patch** | `1.9.2 → 10902`。由 `build-apk.ps1` 从 `-VersionName` 推导。**必须单调递增**，否则覆盖安装被系统拒。 |
| **签名密钥不能换** | `keystore/biligrab.jks`（gitignore，密码见 §7）。CI 若没配 `KEYSTORE_BASE64` 会用临时钥匙签，那种产物**不能让老用户升级**——workflow 已加保护：没正式钥匙就跳过 Release。 |
| **构建必须在纯 ASCII 路径下进行** | `aapt2` 在中文路径下会失败。脚本自动把源码拷到 `%TEMP%\biligrab-build\` 再构建，**别绕过这个拷贝**。 |
| **安装必须 root** | 用户明确要求：`记得用root权限安装apk，不然会被oppo系统拦截`。 |

---

## 2. 构建与安装（照抄即可）

### 2.1 构建

```powershell
Set-Location "E:\Deepseek工作目录\BiliGrab"
$env:ANDROID_HOME="E:\dsh-toolchain\android-sdk"
$env:BILIGRAB_KEYSTORE="E:\Deepseek工作目录\BiliGrab\keystore\biligrab.jks"
$env:BILIGRAB_KEYSTORE_PASS="biligrab123"
& scripts\build-apk.ps1 -VersionName "1.9.2" -OutDir dist *> "E:\Deepseek工作目录\build.log"
```

**坑（重要）**：`*> file` 重定向后**必须另起一次调用**去读日志。脚本内部 `throw` 会杀掉
整个调用、什么输出都不返回，看起来像"没反应"。读日志：

```powershell
Get-Content "E:\Deepseek工作目录\build.log" -Encoding utf8 | Select-Object -Last 15
```

JDK 17 在 `E:\dsh-toolchain\jdk17`（**不在 PATH 上**，必须靠 `JAVA_HOME`，
而脚本自己会兜底到这个路径）。`JAVA_HOME` 为空是正常的。

### 2.2 设备安装（root）

```powershell
$adb="E:\dsh-toolchain\android-sdk\platform-tools\adb.exe"   # adb 不在 PATH
& $adb -s cbb16115 push "dist\BiliGrab-1.9.2.apk" /data/local/tmp/bg.apk
& $adb -s cbb16115 shell "su -c 'pm install -r /data/local/tmp/bg.apk && rm -f /data/local/tmp/bg.apk'"
# 校验装上去的和本地一致：
& $adb -s cbb16115 shell "su -c 'sha256sum /data/app/~~*/com.biligrab.downloader-*/base.apk'"
```

**降级安装会失败**（`INSTALL_FAILED_VERSION_DOWNGRADE`）。这时才需要：

```powershell
& $adb -s cbb16115 shell "su -c 'pm uninstall com.biligrab.downloader'"
```

> ⚠️ **`pm uninstall` 有两个副作用，我都踩过**：
> 1. **清掉 `shared_prefs`**（登录态、画质、连接数、皮肤全没了）
> 2. **清掉 SAF 目录授权**（`/data/system/urigrants.xml` 里的记录被删）
>
> 第 2 点应用能自愈：`StorageDir.verifyCustom()` 会检测到授权失效，日志打
> `自选目录授权已失效，退回默认目录`，然后退回 MediaStore 保存 —— **下载不会失败，
> 但文件不会落到用户选的 MyGrab 目录**。用户会以为"设置丢了"。所以**尽量别 uninstall**。

---

## 3. Git 状态（**接手前必读**，有坑）

### 3.1 本地和 origin/main 的历史是分叉的 —— 但内容一致

```
本地 HEAD     958c848
origin/main   097de46  ← 这是**过期的**跟踪引用
真实远端 main bdb516be  ← 通过 API 查到的实际值
```

看起来吓人（`ahead 24, behind 16`），但**已核实内容完全相同**：
用 GitHub API 拉远端 tree `b6dcbdf8…`，和本地 `a6a2740` 的 tree 逐 blob 比对
→ **126 个 blob，仅远端 / 仅本地 / 内容不同 的集合全是空的**。

原因：历史被 rebase 过（同样提交信息的提交在两边的 SHA 不同）。
**所以别做 merge，也别做 rebase 去"消除分叉"**，那只会制造冲突。

### 3.2 推送必须走 API，不能用 `git push`

这台 PC 连不上 `github.com:443`（DNS 能解析、TCP 不通），但 `api.github.com` 通。

```powershell
$env:BILIGRAB_PAT="<见 §7>"
python tools\push-via-api.py            # 默认基线 HEAD~1
python tools\push-via-api.py <tag> <tag说明> <基线提交>
python tools\release-apk.py             # 上传 Release 附件（走 uploads.github.com）
python tools\move-tag.py <tag> <sha>    # tag 建错位置时移它
```

脚本做的事 = 手工执行一遍 `git push`：建 blob → 建 tree → 建 commit → 移动 ref。
**前提：父提交必须已在远端**，否则会失败。

Python 直连 api.github.com 会 `SSL: UNEXPECTED_EOF` —— 必须挂代理
`http://127.0.0.1:7897`（见 §5.4）。

### 3.3 需要推送时

本地比远端多 `958c848`（1080P 研究结论 + 合流分段计时）。推送它 = 让远端 main 前进到
这个提交，然后按需发 1.9.2。

---

## 4. 设备操作手册

### 4.1 自动化驱动（可靠配方）

```powershell
$adb="E:\dsh-toolchain\android-sdk\platform-tools\adb.exe"
& $adb -s cbb16115 shell "su -c 'rm -rf /sdcard/MyGrab/*; rm -rf /data/data/com.biligrab.downloader/files/*; logcat -c'"
& $adb -s cbb16115 shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard"
& $adb -s cbb16115 shell "am force-stop com.biligrab.downloader"
& $adb -s cbb16115 shell "monkey -p com.biligrab.downloader -c android.intent.category.LAUNCHER 1"
Start-Sleep -Seconds 7
& $adb -s cbb16115 shell "input tap 540 512"          # 链接输入框
& $adb -s cbb16115 shell "input text 'BV1GJ411x7h7'"
& $adb -s cbb16115 shell "input tap 540 807"          # 解析
Start-Sleep -Seconds 15
& $adb -s cbb16115 shell "input swipe 540 1800 540 900 300"
& $adb -s cbb16115 shell "input tap 840 211"          # 下载管理
```

**画质行是像素定位的，不用 `uiautomator dump`**（这台 ROM 上 dump 出来的 bounds
是过期/偏移的，会点错）。行间距 220 px。已有脚本：

```
C:\Users\Administrator\AppData\Local\Temp\bgcheck\bench_conns.py
    python bench_conns.py <quality> <conns...> [--reps N]
    # 例：python bench_conns.py 1080 1 4 8 --reps 3
    # 自动定位下载行、解析 logcat、打印中位数与区间
C:\Users\Administrator\AppData\Local\Temp\bgcheck\tap1080.py   <连接数>   单次跑
C:\Users\Administrator\AppData\Local\Temp\bgcheck\restore_prefs.py        还原 prefs
```

### 4.2 截图（权威验证手段）

`adb exec-out screencap -p` 是唯一可信的界面验证方式。

**PowerShell 的 `>` 会把 PNG 写坏**（CRLF 转换）。必须用 Python：

```python
import subprocess
ADB=r'E:\dsh-toolchain\android-sdk\platform-tools\adb.exe'
with open(r'E:\Deepseek工作目录\screens\x.png','wb') as f:
    subprocess.run([ADB,'-s','cbb16115','exec-out','screencap','-p'],stdout=f,timeout=60)
```

### 4.3 prefs 直接改（绕过 UI，用于配环境）

```powershell
# 改完必须是 10281:10281 或原属主，写成 system:system 应用读不到
& $adb -s cbb16115 shell "su -c 'ls -la /data/data/com.biligrab.downloader/shared_prefs/'"
```

当前设备上的实际值（供对照）：

```xml
<string name="download_tree_uri">content://com.android.externalstorage.documents/tree/primary%3AMyGrab</string>
<int name="prefer_qn" value="64" />
<string name="download_tree_label">MyGrab</string>
<boolean name="prefer_avc" value="true" />
<string name="buvid3">7F498E75-501E-0CB7-AEB9-0AAF13948A6230884infoc</string>
<int name="ui_primary" value="2" />      <!-- 深海蓝 -->
<int name="ui_skin" value="1" />         <!-- 玻璃态 -->
<int name="download_connections" value="4" />
```

> `download_tree_uri` 还写着 MyGrab，但**授权已被 uninstall 抹掉** —— 应用下次下载会
> 自愈退回默认目录并清掉这个记录。想恢复 MyGrab 需在设置里重选一次。

全部 prefs 键（`Prefs.java`）：
`sessdata` `login_uname` `buvid3` `prefer_qn` `prefer_avc` `theme_mode`
`youtube_proxy` `ui_skin` `ui_primary` `download_tree_uri` `download_tree_label`
`download_connections`

### 4.4 OPPO 的坑：通知权限弹窗会吃掉自动化点击

**这台机是 Android 12（API 31），而 `POST_NOTIFICATIONS` 是 Android 13 才引入的权限。**
所以：

- `pm grant … POST_NOTIFICATIONS` → `IllegalArgumentException: Unknown permission`
- 应用里 `requestPermissionsIfNeeded()` 的 `SDK_INT >= TIRAMISU` 判断**永不触发**
- 那个弹窗是 **ColorOS 自己弹的**，不是应用弹的

它一旦出现就会吃掉所有脚本点击，然后脚本每轮白等超时（我因此浪费过一整轮 600 秒）。

**已修复**（用 root 设 appop，shell 单独设会被 SELinux 拒）：

```powershell
& $adb -s cbb16115 shell "su -c 'cmd appops set com.biligrab.downloader POST_NOTIFICATION allow'"
& $adb -s cbb16115 shell "su -c 'cmd appops get com.biligrab.downloader'"   # 应为 POST_NOTIFICATION: allow
```

`pm uninstall` 会重置它 —— **uninstall 之后必须重设**。

### 4.5 日志

```powershell
& $adb -s cbb16115 shell "logcat -G 24M"       # 环缓冲会淘汰，先加大
& $adb -s cbb16115 shell "logcat -c"           # 动作前立刻清
& $adb -s cbb16115 shell "logcat -d --pid=$((& $adb -s cbb16115 shell pidof com.biligrab.downloader))"
```

应用进程重启会让之前的日志失效 —— 重新读 `pidof`。

---

## 5. 网络 / 代理环境

1. **PC 可用代理**：`127.0.0.1:7897`
2. **侧路由 `192.168.8.2:7890` 已永久关机** —— 不要再试
3. `adb reverse` 隧道**每次 USB 重连都会失效**
4. **Python 访问 GitHub API 必须挂代理**，否则 `SSL: UNEXPECTED_EOF_WHILE_READING`
5. 手机 WiFi：`wlan0` 192.168.8.102，链路 866 Mbps，RSSI −32。**链路不是瓶颈**
   （实测跑到 29–32 MB/s ≈ 232–256 Mbps）

```python
import urllib.request
op = urllib.request.build_opener(urllib.request.ProxyHandler({'https':'http://127.0.0.1:7897'}))
```

---

## 6. 架构与文件地图

### 6.1 源码（`app/src/main/java/com/biligrab/downloader/`）

| 文件 | 大小 | 职责 |
|---|---|---|
| `MainActivity.java` | 99 KB | 主界面：解析、画质列表、设置表、皮肤切换 |
| `DownloadService.java` | 83 KB | **下载核心**：前台服务、多连接、并行双轨、续传、进度 |
| `YouTubeEngine.java` | 43 KB | YouTube 引擎（yt-dlp / youtubedl-android 桥接） |
| `DownloadsActivity.java` | 41 KB | 下载管理页（`exported="false"`） |
| `MuxUtil.java` | 38 KB | MP4 合流（`MediaExtractor` + `MediaMuxer`），含校验 |
| `BiliApi.java` | 30 KB | B 站 API：playurl、WBI 签名、DASH 流选择 |
| `Parallel.java` | 26 KB | 多连接分段下载 + 断点台账（Journal） |
| `StorageDir.java` | 21 KB | 落盘：SAF 自选目录 / MediaStore / 公共目录 |
| `PreviewController.java` | 19 KB | 预览播放 |
| `DownloadTask.java` | 16 KB | 任务模型 + 进度上报 |
| `LoginActivity.java` | 15 KB | 应用内登录（WebView 装官方登录页） |
| `WorkDir.java` | 5.5 KB | 工作目录管理 |
| `Prefs.java` | 8.3 KB | 全部偏好项 |
| `ui/*.java` (15 个) | — | Hyper-Neumorphic 皮肤引擎 |

（根目录 `com/biligrab/downloader/` 共 23 个 Java 文件，`ui/` 下 15 个。）

皮肤引擎关键文件：`HyperTheme`（取色）、`NeumorphicDrawable`（拟物绘制）、
`NeumorphicSurface`、`GlassMeshDrawable`（玻璃）、`NeumAttr`（属性解析）、
`Neu*`（各控件包装）。

### 6.2 资源

- `res/layout/activity_main.xml` 36 KB、`sheet_settings.xml` 23 KB（最大的两个）
- `res/values/strings.xml` 26 KB —— **用户可见文案都在这，改文案改这里**
- `res/values/attrs.xml` —— 皮肤自定义属性
- `res/values-night/` —— 深色覆盖
- `res/xml/network_security_config.xml`

### 6.3 文档

| 文件 | 用途 |
|---|---|
| `README.md` | 面向用户。⚠️ **已滞后**：构建示例还写着 `-VersionName 1.4.0`（实际已到 1.9.x），"检查尚未全绿"那句也停在 1.4.0 之后。接手时值得一轮校订。 |
| `DESIGN.md` | 设计系统（39 KB，含"被明确拒绝的做法"） |
| `docs/DESIGN-SYSTEM.md` | 同上，更细 |
| `docs/HANDOFF-1.5.0.md` | **历史过程记录**（写到 1.8.0），含大量早期踩坑 |
| `docs/research-1080p-speed.md` | **1080P 加速结论**（本轮产出） |
| `docs/release-notes-1.9.1.md` 等 | 各版发布说明，正文直接用作 Release body |
| `docs/design-refs/` | 上游规范原文，**gitignore**（授权不明，只作本地参考） |

---

## 7. 凭据（**注意：需要轮换**）

| 项 | 值 | 状态 |
|---|---|---|
| GitHub PAT | `[REDACTED EXPOSED GITHUB TOKEN]` | ⚠️ **仍有效，且权限含 `delete_repo` + `admin:org` —— 应当吊销/收窄**（这个待办已积压多轮）。作用域 `admin/maintain/push` 于 `308532806/BiliGrab` |
| 签名密钥 | `keystore/biligrab.jks`，storepass/keypass `biligrab123`，alias `biligrab` | 已 gitignore |
| B 站测试视频 | BV `BV1GJ411x7h7`（Never Gonna Give You Up），cid `137649199`，213 秒 | — |
| 测试账号 | 未登录（匿名）。`accept_quality=[112,80,64,32,16]`，匿名 dash 最高 qn 80 | 112/120 是大会员档，被剔进 `lockedQualities` |

**无 GitHub CI Secret `KEYSTORE_BASE64`** → workflow 会跳过 Release（设计如此）。

---

## 8. 已知的领域知识（省下重新摸索的时间）

### 8.1 下载性能（本轮实测，**已定论**）

**B 站 CDN 按连接限速**，所以多连接是主要加速手段。连接数不是越多越好：

视频轨 66.53 MB / 69759161 字节，每档 3 次取中位数：

| 连接数 | 视频轨中位 | 速度中位 | 区间 |
|---|---|---|---|
| 1 | 3909 ms | 17.02 MB/s | 15.9–19.2 |
| 4 | 2287 ms | **29.09 MB/s** | 27.7–29.5 |
| 8 | 2261 ms | 29.42 MB/s | 28.3–31.2 |
| 16 | 4654 ms | 14.29 MB/s | 14.3–21.8（含一次 2.07 MB/s 崩坏） |

**结论：4 ≈ 8，16 明显更差。** `connectionsFor()` 里的
`MAX_TOTAL_CONNECTIONS / 2 = 4` **已经是最优，不要去"修"它**。

> 这一条推翻了一个曾经看起来很有道理的假设（"音轨只要 2 块却占 4 条额度，
> 应按各轨待下块数分配总预算"）。**单次测量支持它，重复测量否掉了它。**
> 教训：**这个网络上单次测量不可信，必须重复取中位数**（同一配置曾跑出
> 1.43 与 14.04 MB/s，差 10 倍）。**PC 侧测量更不可信**（PC 同一条流只有 2.70 MB/s，
> 设备 29 MB/s）—— **不要用 PC 测 B 站 CDN**。

**Range 支持**：全档位（qn 80/64/32/16 + 全部音轨）`Range: bytes=0-0` → **206**。
URL 里没有 `range=` 查询参数（`hasRangeParam()` 专门防这个）。**所以高清晰度不影响
多连接可用性** —— 4K（qn 112/120）走同一条路径，只是需要大会员 cookie 拿直链。

**合流是 1080P 的主要成本**（~4.8 s，占 ~47%）。加分段计时后量出：取样本只占 ~0.4 s
（**不是 I/O 瓶颈**，预读无意义）、写样本 ~2.5 s、`advance()`/时间戳等 ~1.8 s。
合流耗时**几乎不随清晰度变化**，因为样本数固定：**视频 5306 + 音频 9952 = 15258 个**。
优化它对所有画质都划算。**尚未动手。**

**音轨尾部延迟**：音轨只有 5.16 MB，`CHUNK = 4 MiB` 只切出 **2 块**，最多用 2 条连接。
10 次里有 2 次音轨反而比视频慢（~5.2 s），那时总耗时由音轨决定。切细块可缓解，收益约 6%。

**探针无法移除**：`BiliApi.readStream` 对 DASH **从不设置 `Model.Stream.size`**
（实测每个 qn 都是 `size=None`），所以 `sizeHint` 恒为 0，yt-dlp 给的只是
`filesize_approx`。`downloadStream` 里那个"已完整"的捷径对 B 站是**死代码**。
探针成本 267–373 ms/轨（两轨并发）。曾试过 `trustHint` —— **实测无效，已移除**
（不留下误导性的空操作）。

### 8.2 界面

**Hyper-Neumorphic** = HyperOS + 拟物（Neumorphism）+ 玻璃（Glassmorphism）。
双引擎共用尺寸/圆角/交互，只有"表面怎么画"不同：

- `AppSkin.NEUMORPHISM`（`SKIN_NEUMORPHISM = 0`，默认）
- `SKIN_GLASS = 1`（玻璃态）

品牌主色是樱花粉 `#FB7299`。主题色共 5 个（见 `Prefs.PRIMARY_*`），当前设备选深海蓝（2）。

**已修的一个真机缺陷**：玻璃态下主色面（如深海蓝）上的按钮文字变全白 —— 1.9.0 修的。

**impeccable 技能**装在 `E:\Deepseek工作目录\.dsh\skills\impeccable\`（52 文件）。
**它的检测器只认 `css`/`html`/`svelte`/`astro`，对 Android XML/Java 完全盲** ——
它返回 `[]` 意思是"不适用"，**不是"合规"**。别被误导。`impeccable context` 退出码 1
（`NO_PRODUCT_MD`）是预期行为。

### 8.3 已修的重要缺陷（别改回去）

**416 续传误报**（1.9.1 修）：暂停时若某条轨已完整，恢复会打
`服务端未接受续传（HTTP 416），从零开始`，然后三个备用 URL 全失败。修法是在
`downloadUrl` 里**放在 `if (startAt > 0) { … startAt = 0L; }` 之前**处理 416
（放之后是原 bug，因为那行会把 `startAt` 清零）：

```java
if (code == RANGE_NOT_SATISFIABLE && startAt > 0 && startAt == dst.length()) {
    // 起点 == 文件长度 ⇒ 这段已经取完了
    …
    return;
}
```

> 验证时**别用 `-match "416"`** —— 会误命中 `[阶段耗时] … 合计 3416ms`。
> 要匹配 `HTTP 416` 或周边文案。

**音频轨被判死**（1.7.1 修）：`OplusMPEG4Writer` 遇到时间戳贴在前一帧上的异常样本会
判死整条轨 → MP4 无声 / 仅音频文件损坏。`copyTrack` 里按"典型间距的一半"丢弃异常样本，
**但只对 AAC 强制**（Opus/Vorbis 帧长本来就不固定，强卡会丢好帧）。

**控制流铁律**：外层条件**无法区分**"被自己用户中止"和"因为同伴死了而中止" ——
只有内层线程知道。所以**中止决策必须写在 worker 体内部**（`vAborted`/`aAborted`
用 `instanceof Http.AbortedException` 判）。

### 8.4 设备稳定性

手机重启已定位为 **`netd` 崩溃**，不是硬件：
`persist.sys.system.abnormalboot = …zygote reboot by netd`，
`/system/etc/init/netd.rc` 里 `onrestart restart zygote`。触发条件未坐实。
ROM 缺陷：`system_server` 每 ~12 s 打一次 `TerribleFailure`
（`Inconsistent config_wifi_framework_resources: rssi2=…`）。

---

## 9. 未完成 / 未验证（接手可从这里挑）

### 9.1 代码任务

1. **合流提速**（收益最大，所有画质都吃）。已量出瓶颈：写样本 ~2.5 s + 计数之外 ~1.8 s，
   取样本只 ~0.4 s。方向是减少 15258 次 `writeSampleData` 调用 / 减少 `advance()` 与
   时间戳读取的开销。**先确认瓶颈是 codec、调用本身还是 I/O，别猜。**
2. **音轨细块**：把 `CHUNK = 4 MiB` 按轨调整（音轨用更小块），缓解 5.16 MB 只切 2 块的
   尾部延迟。中位数收益 ~6%。
3. **推送 `958c848`**（本地领先远端一个提交）。
4. **决定是否提交 `docs/design-refs/`**（当前 gitignore —— 上游 license 字段为空，
   只作本地参考）。倾向保持 ignore。

### 9.2 从未验证的路径

- **兄弟中止路径**（一条轨真失败导致另一条中止）从未在设备上跑过
- **删除一个正在下载中的任务**
- **YouTube 单连接 A/B 基线**（设备侧）
- **暂停恢复时"视频轨"（而非音频轨）已完成**的情形
- **大会员 4K（qn 112/120）真实 cookie**——需要账号，目前拿不到
- **未登录 480P 稳定拉取 + 登出后干净降级**
- `Probe` 的多轨遍历 vs `deepmp4.py`

### 9.3 运维待办

- ⚠️ **吊销或收窄那个 PAT**（权限过大，见 §7）
- 设备 `POST_NOTIFICATIONS` appop 已设为 allow（uninstall 后需重设）
- 设备上 `download_tree_uri` 指向已失效的 MyGrab 授权（自愈会清掉；要恢复需手动重选）
- **设备当前装的是 1.9.2（带合流分段计时的仪表版），不是发布版**。发布版是 1.9.1。
  要恢复干净状态就重装 `dist\BiliGrab-1.9.1.apk`。

---

## 10. 工作流约定（这个项目的既有习惯）

1. **提交信息用中文**，说明"为什么"而不只是"改了什么"。
2. **每个版本写 `docs/release-notes-<版本>.md`**，正文直接作为 GitHub Release body。
3. **发布流程**：build → root 安装到设备 → 真机验证 → commit → `push-via-api.py`
   → `push-via-api.py <tag>` → `release-apk.py` → 用 API 核对
   `draft=False`、tag 没脱钩、附件大小对。
4. **提交信息里不能有裸 `<<<`**（PowerShell 解析错误）—— 用 `git commit -F 文件`。
5. **`edit`/`write` 工具要求先 `read` 过**那个文件（同会话内）。
6. **每个 `pwsh` 调用是全新进程**，不保留 cwd/变量 —— 用 `workdir` 参数或每次 `Set-Location`。
7. 控制台输出中文会乱码 —— **写 UTF-8 文件再用 `Get-Content -Encoding utf8` 读**。

### 10.1 构建不可复现（重要）

**同一个源码重编，APK 的 SHA-256 会变**（zip 时间戳）。所以：

- 校验"改动是否还原"要看**源码层**（`git diff --stat` 为空），**不能比 APK 哈希**
- 只有**同一个 APK 文件**的哈希才可比（例如发布的附件 vs 本地 dist）

---

## 11. 交接检查清单

接手第一步建议照做：

```powershell
# 1. 确认源码干净
Set-Location "E:\Deepseek工作目录\BiliGrab"; git status --short; git log --oneline -3

# 2. 确认设备在线、根可用、appop 没被重置
$adb="E:\dsh-toolchain\android-sdk\platform-tools\adb.exe"
& $adb devices
& $adb -s cbb16115 shell "su -c 'id'"
& $adb -s cbb16115 shell "su -c 'cmd appops get com.biligrab.downloader | grep POST_NOTIFICATION'"

# 3. 确认工具链
Test-Path "E:\dsh-toolchain\android-sdk\build-tools\34.0.0\aapt2.exe"
Test-Path "E:\dsh-toolchain\jdk17\bin\javac.exe"

# 4. 确认代理（Python 访问 GitHub API 需要）
Test-NetConnection 127.0.0.1 -Port 7897 -InformationLevel Quiet
```

四项全绿就可以直接干活。

---

## 12. 我不该做的事（留给接手者避免重蹈）

1. **不要在这个网络上做单次性能测量** —— 必须重复取中位数。
2. **不要用 PC 测 B 站 CDN** —— 差 10 倍。
3. **不要为了"更充分利用连接"提高并发上限** —— 已证伪。
4. **不要用 `pm uninstall` 清场**，除非必须 —— 会丢 prefs + SAF 授权。
5. **不要相信 `uiautomator dump`** —— 像素定位截图。
6. **不要用 PowerShell `>` 存 PNG**。
7. **不要在中文路径下直接跑 aapt2**。
8. **不要比 APK 哈希来验证源码还原**。
9. **不要为了让 impeccable 通过而改 Android 资源** —— 它看不懂。
10. **不要再尝试从 `哔哩哔哩_8.9.0.apk` 里"提取模块"来白嫖大会员 2K/4K。**
    这个请求已被反复拒绝（前后五次，包括改述成"开发测试"、声称忘记密码+手机丢失、
    时间压力、以及两次动用急救暗号）。**4K 的正路是：需要大会员账号；若账号不可用，
    官方申诉渠道是「实名信息 + 历史订单」。** 不要重新打开这个话题。

---

*文档结束。有疑问先读 `docs/HANDOFF-1.5.0.md` 找历史上下文，再读本节 §8 的领域知识。*
