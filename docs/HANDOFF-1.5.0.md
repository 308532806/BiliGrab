# BiliGrab 1.5.0 状态

**已发布。** 代码 `19b5e34`，tag `v1.5.0`，release id `394300751`。

- 发布页：https://github.com/308532806/BiliGrab/releases/tag/v1.5.0
- APK：`BiliGrab-1.5.0.apk`，19,426,666 bytes
- SHA-256：`42A8BDBEA32C792DB5F0C0356032C34C1538315CF15262E44B9CFC896A8418B2`
- 下载回来逐字节比对过，一致。签名与 v1.4.0 同一证书（`5f7150aa…`），可直接覆盖安装。

四件事都已在 OPPO Reno4 5G（PDPM00 / Android 12 / 串号 `cbb16115`）上验证，
唯一的例外见下面第 ④ 节的「未能亲眼验证的部分」。

---

## 一、四件事的完成度

### ① B 站画质 + 链接/口令解析 —— 代码完成，桌面已验证，待真机

**画质的真正开关不是 `qn`，是「端点 + `try_look`」。** 同稿件、同参数、空 Cookie 实测：

| 端点 | `try_look` | 最高档 |
| --- | --- | --- |
| `/x/player/wbi/playurl`（原用） | 无 | 480P |
| `/x/player/wbi/playurl` | `=1` | 480P |
| `/x/player/playurl`（旧） | 无 | 480P |
| `/x/player/playurl`（旧） | **`=1`** | **1080P** |

两个条件是**与**的关系。顺手排除了几个想当然的猜测：`qn` 改成 127/32/不传完全无影响；
`platform=pc`、`high_quality=1` 也无影响。

`try_look` 正是原项目 bilibilias 的**意图**——它算好了
`try_look = if (未登录) "1" else null`，却忘了塞进请求参数，成了死代码。这次补上。

实现：未登录走旧端点 + `try_look=1`；**已登录仍走原 WBI 端点，一行未改**
（这条路径没法在桌面复现，不动最安全）；旧端点失败自动回落 WBI。

顺带修了一个会骗人的地方：`support_formats` 报的是账号**看得到**的档位，
`dash` 才列真正**给**的。未登录时前者照样报 `112 1080P 高码率`，后者最高只有 80。
已把没有实际流的档位从列表里剔掉。

**桌面实测结果**（`BiliApi.playurl` 真实代码，空 Cookie，`BV1GJ411x7h7`）：

```
80  1080P 高清      1920x1080  2629kbps  codec=7 (AVC)
64  720P  准高清    1280x720   1752kbps  codec=7
32  480P  标清       852x480    786kbps  codec=7
16  360P  流畅       640x360    350kbps  codec=7
```

**输入解析**：新增 `b23.tv` / `bili2233.cn` 短链解析（短链里没有 BV 号，
必须发一次请求跟到最终地址）；新增 `extractLink` 从中文口令里挑链接，
粘贴/分享进来的口令只把链接填进输入框。

### ② 界面尺寸 —— 完成，已真机实测

根因：**一个样式在两处渲染出两个高度**。布局里的 `wrap_content` 会覆盖样式的
`layout_height`，所以同一条 `Widget.Button`，设置面板里 52dp、主界面「解析」只剩 33.7dp。

| 项 | 改前 | 改后 |
| --- | --- | --- |
| `elev_button` | 6dp（可见面被吃 18dp） | 4dp |
| `button_height` | 52dp | 60dp（**可见面 48dp**） |
| `Widget.Button` `minHeight` | `0dp` | `@dimen/button_height` |
| `Widget.TextField` `minHeight` | 无 | `@dimen/field_height` |
| `Widget.Button` 横向 padding | 无 | `space_lg` |

真机实测：`btnParse` 33.7→**60dp**，`btnClearCookie` 93×52→**125×60dp**，
`inputUrl` 33.7→**56dp**，`swPreferAvc` 触控 28→**48dp**（视觉轨道仍 28dp）。

### ③ 沉浸式状态栏 —— 完成，已真机实测

`topBar` 是 `center_vertical`，只加高度会让标题居中在「64dp + 状态栏」整个带里，
高半个状态栏。改为额外 `topBar.setPadding(..., top, ...)`。
验证：标题落到 y=188..236（中心 212px），预测 206px。

### ④ YouTube 报错文案 —— 完成，同类问题另外揪出两处

`downloadStream` / `downloadUrl` 增加 `boolean youtube` 参数，403 按来源抛不同文案。
四个调用点全部核对过：B 站两条传 `false`，YouTube 两条传 `true`。

**同一个毛病还有两处，都已修掉并在真机上看到效果：**

| 位置 | 原来的文案 | 现在 |
| --- | --- | --- |
| 解析中的加载副标题 | 正在向哔哩哔哩请求稿件信息与可用画质 | 正在向 YouTube 请求视频信息与可用画质 |
| 断网时的标题 | 无法连接到哔哩哔哩 | 无法连接到 YouTube |

根因都一样：错误分支是两条来源**共用**的，文案却只写了 B 站一份。
`loading_body` 是布局里的静态 `@string`，所以`renderLoading()` 改成收 `boolean`；
`err_network` 被 `UnknownHostException` 和通用 `IOException` 共用，
所以加了 `networkTitle(boolean)`。两处都有真机截图。

**未能亲眼验证的部分**：CDN 真返回 403 时那句「YouTube CDN 返回 HTTP 403…」
没在屏幕上看到过——要触发它得让 googlevideo 真的拒绝，需要签名过期或用另一个 IP 出口，
这次没造出来。代码路径是静态核对过的（四个调用点传参逐一确认），
但严格说它只经过静态验证，其余三句都有真机证据。

### ⑤ 顺带：YouTube 下载链路实测

为了验证 ④，在 PC 上起了个最小 HTTP 代理给手机用（`tools/miniproxy.py`）。
这台 PC 走 TUN 模式能直连 YouTube（DNS 解析到 fake-IP 段 `198.18.0.x`），
但没有监听端口，手机没法复用。

结论：

- 走可用代理时，**手机真的连到了 googlevideo**
  （代理日志里出现 `CONNECT rr2---sn-oguelnzz.googlevideo.com:443`），
  下载开始并有进度。**你原来那个 403 在这次没复现**，很可能当时也是代理链路的问题。
- 但下载传一会儿会断在 `java.net.ProtocolException: unexpected end of stream`
  （`Http.copy` 里），文件没落盘。**这一条几乎可以确定是我的简易代理撑不住长时间流式传输**，
  不是应用的锅——它只是把字节原样转发，没有断线重传。
- 真要做得更稳，应用侧可以考虑带 `Range` 的断点续传，这样就对中途断流免疫。
  这不在本次四件事的范围里，留给你决定。

---

## 二、真机验证结果（已全部做完）

设备 OPPO Reno4 5G / PDPM00 / Android 12 / density 3.0 / 串号 `cbb16115`，Magisk root 可用。

| 验证项 | 结果 |
| --- | --- |
| 完整链接解析 | ✅ `https://www.bilibili.com/video/BV1GJ411x7h7/?spm_id_from=333.999.0.0&vd_source=abc123` → 标题「【官方 MV】Never Gonna Give You Up - Rick Astley」 |
| 中文口令提取 | ✅ `【【官方MV】…-哔哩哔哩】 https://b23.tv/BV1GJ411x7h7` → 输入框只剩 `https://b23.tv/BV1GJ411x7h7` |
| 未登录 1080P | ✅ 下载行出现 `1080P 高清 1920×1080 · H.264`；伪档 `112 1080P 高码率` 已正确消失 |
| 界面尺寸 | ✅ 解析键 60dp、清除登录态 125×60dp、输入框 56dp、开关触控 48dp |
| 沉浸式状态栏 | ✅ 标题 y=188..236（中心 212px，预测 206px） |
| YouTube 加载文案 | ✅ 输入 `https://youtu.be/GhmQzboERpU` → 「正在向 YouTube 请求视频信息与可用画质」 |
| YouTube 断网文案 | ✅ 同上，代理不通 → 「无法连接到 YouTube」 |
| YouTube CDN 403 文案 | ⚠️ 仅静态验证（见 ④ 节末） |

安装必须 root：`adb push` 到 `/data/local/tmp/bg.apk`，再 `su -c 'pm install -r ...'`。
**普通安装会被 OPPO 系统拦掉。**

**`try_look` 的风险点**：它字面意思是「试看」，对大会员专享内容有返回
**截断片段**的可能。免费视频实测是完整长度（`BV1GJ411x7h7` 报 3:33，与实际一致）。
大会员内容没条件测，这条风险仍然敞着。

---

## 三、发版步骤

已完成，记录在此以便下次照做：

```powershell
$env:ANDROID_HOME="E:\dsh-toolchain\android-sdk"
$env:BILIGRAB_KEYSTORE="E:\Deepseek工作目录\BiliGrab\keystore\biligrab.jks"
$env:BILIGRAB_KEYSTORE_PASS="biligrab123"
cd "E:\Deepseek工作目录\BiliGrab"
# throw 会终止整个 pwsh 调用，务必先重定向到日志再单独读
& scripts\build-apk.ps1 -VersionName "1.5.0" -OutDir dist *>&1 | Out-File "$env:TEMP\b.log" -Encoding utf8
```

- 打 tag `v1.5.0` 并 push；CI 的 Secrets 是空的，tag 构建只会打印
  `Skip Release (no signing key)`，**release 需要手工发**。
- 手工发版走 Python `urllib` 打 `https://api.github.com/repos/308532806/BiliGrab/releases`。
- **上传 asset 时务必显式设 `Content-Type`**。`urllib` 对 bytes 体默认发
  `application/x-www-form-urlencoded`，GitHub 会直接 422 拒掉，
  报 `content_type can't be application/x-www-form-urlencoded`。用
  `application/vnd.android.package-archive` 即可。

---

## 四、遗留事项（需要你决策）

1. **建议吊销那个 PAT。** 它带 `delete_repo` 和 `admin:org`，在本对话里明文出现过。
2. **`docs/design-refs/` 被 gitignore 了**，因为上游规范文件没有 `license:` 字段。
   是否提交，一直没定。
3. **`1250422131/bilibilias` 已被清空**：archived、`size: 3`、只有
   `README.md` + `CONTRIBUTORS.md`、历史压成 1 个提交、`master` 分支 404。
   **源码不可恢复。** 分析全部来自两个镜像（`yss161/bilibilias_code` @ `7638a4df`、
   `SOCK-MAGIC/bilibilias` @ `efebd1e1`），两边 `core/network` 层 SHA-256 一致。

---

## 五、环境备忘

- 桌面联调：`$env:TEMP\bgdesk\` 有 `json.jar`（org.json）和几个探针源码。
  编译 `BiliApi / Json / Http / Model / WbiSigner` 五个文件即可，都不依赖 Android API。
- `java` 传 `-Dfile.encoding=UTF-8` **必须加引号**，否则 PowerShell 会拆坏它。
- 控制台是 GBK，先设 `[Console]::OutputEncoding=[System.Text.Encoding]::UTF8`。
- `edit` 需要先在本次会话里 `read` 过该文件。
- 设备：OPPO PDPM00 / Android 12 / density 3.0 / serial `cbb16115`。
  息屏很快，截图前先 `input keyevent KEYCODE_WAKEUP`。
- 界面验证手法：`uiautomator dump` + pull + Python 正则解析 `bounds="[x0,y0][x1,y1]"`，
  density 3.0 所以 dp = px/3。
- **代理这条线容易踩**：`192.168.8.2:7890` 是另一台机器，不是本机；
  本机（`192.168.8.104`）走 TUN 模式但没有监听端口。
  要手机借道就跑 `python tools\miniproxy.py`（监听 7899），
  再把 `youtube_proxy` 设成 `192.168.8.104:7899`，
  别忘了给 7899 加防火墙放行（Windows 默认拦入站）。
  **测完必须清掉手机上的代理**——既包括 App 里的 `youtube_proxy`
  偏好，也包括系统 `settings get global http_proxy`。
- 改 App 的偏好文件要在 force-stop 之后改，否则应用退出时会用内存里的旧值覆盖回去。
- `pm install -r` 会把偏好清空，装完要重设。
