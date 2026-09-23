# BiliGrab 1.5.0 状态

代码已提交 `1f6649c`，**只差真机验证就能发版**。

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

### ④ YouTube 403 文案 —— 完成，已静态验证

`downloadStream` / `downloadUrl` 增加 `boolean youtube` 参数，403 按来源抛不同文案。
四个调用点全部核对过：B 站两条传 `false`，YouTube 两条传 `true`。

---

## 二、卡在哪

**手机从 USB 上掉线了。** 不只是 adb 找不到——系统 `Get-PnpDevice` 里
根本没有 OPPO/Android 设备。需要重新插上 USB（并确认 USB 调试仍开着）。

恢复后要做的真机验证：

1. 未登录解析一个多档位视频，**确认下载行里出现 1080P**（本次最核心的改动）
2. 粘贴一段中文口令，确认输入框里只出现链接
3. 触发一次 YouTube 下载，确认 403 报的是 YouTube 文案而不是 B 站文案

然后才是打 tag 和发版。

**`try_look` 的风险点**：它字面意思是「试看」，对大会员专享内容有返回
**截断片段**的可能。免费视频实测是完整长度。真机上确认一次时长对不对。

---

## 三、发版步骤（验证通过后照做）

```powershell
$env:ANDROID_HOME="E:\dsh-toolchain\android-sdk"
$env:BILIGRAB_KEYSTORE="E:\Deepseek工作目录\BiliGrab\keystore\biligrab.jks"
$env:BILIGRAB_KEYSTORE_PASS="biligrab123"
cd "E:\Deepseek工作目录\BiliGrab"
# throw 会终止整个 pwsh 调用，务必先重定向到日志再单独读
& scripts\build-apk.ps1 -VersionName "1.5.0" -OutDir dist *>&1 | Out-File "$env:TEMP\b.log" -Encoding utf8
```

- 装真机必须 root：`adb push` 到 `/data/local/tmp/bg.apk`，再 `su -c 'pm install -r ...'`。
  **普通安装会被 OPPO 系统拦掉。**
- 打 tag `v1.5.0` 并 push；CI 的 Secrets 是空的，tag 构建只会打印
  `Skip Release (no signing key)`，**release 需要手工发**。
- 手工发版走 Python `urllib` 打 `https://api.github.com/repos/308532806/BiliGrab/releases`。

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
- 当前设备：OPPO PDPM00 / Android 12 / density 3.0 / serial `cbb16115`（**当前离线**）。
