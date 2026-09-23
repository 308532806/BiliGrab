# BiliGrab 1.7.0 状态

> 本文件记录 1.5.0 起的进展，最新的在最前。

## 1.7.0 —— 自定义下载目录与下载管理页

### 一句话

**进度条不动的根因是「总量算成了 0」，不是「网络慢」；顺带补上了自选
下载目录和独立的管理页，并在真机上逐项验过暂停 / 继续 / 取消 / 重试 /
删除记录 / 删除记录和文件 / 清除已完成。**

### 用户报的两个问题

> 这个版本下载 YouTube 视频不会报错了，但是没法自定义下载目录，
> 且明明从 vpn 中看见有速度，但下载的进度条不会动。

### 进度条不动的根因

旧 `downloadUrl` 只把 `Content-Length` 当总量：

```java
long expected = c.getContentLengthLong();     // 经代理时这里是 -1 / 0
```

而经代理取 `googlevideo` 的响应是 **chunked、不带 Content-Length**。
于是 `expected == 0`，`pct` 死锁在分档基准值（5 / 50）上不再变，
而字节确实在流动 —— 这正是「VPN 里看得到速度、进度条不动」的成因。
`task.videoSize` / `audioSize` 明明已经解析出来了，却从没被当兜底用过。

现在 `resolveTotal()` 按可信度依次取：

| 顺序 | 来源 | 说明 |
|---|---|---|
| 1 | `Content-Range` 的总长 | 最权威，206 时才有 |
| 2 | `Content-Length` | 200 时直接可用；206 时要补 `startAt` |
| 3 | 解析阶段拿到的 `sizeHint` | 接口给的体积，前两者都没有时兜底 |

进度全部来自真实写入字节数，不再是估算。

### 新增：自定义下载目录

设置页新增「下载位置」卡片，走 SAF 目录选择器（`ACTION_OPEN_DOCUMENT_TREE`），
B 站与 YouTube 共用同一个目录。

- **必须 `takePersistableUriPermission`**，否则授权只在本次进程有效，
  重启后拿到的是一个没有权限的 URI，写入会静默失败。
- 选完立即回读标签，卡片显示「当前：MyGrab（自选）」；
  默认为「Movies/BiliGrab（默认）」。
- `StorageDir` 统一三种落盘方式：SAF 文档 URI、MediaStore、公共目录。
  删除时也按 `content://` / 绝对路径分别处理。
- 默认目录仍分开放：视频进 `Movies/BiliGrab`、音频进 `Music/BiliGrab`，
  这样相册和音乐播放器能直接扫到。**自选目录则统一放进去** ——
  用户选了一个目录，就是希望东西在那里，不该再拆两处。

### 新增：下载管理页

`DownloadsActivity`：实时进度条、真实速度、剩余时间、
暂停 / 继续 / 取消 / 重试 / 删除 / 清除已完成。

设计上几个刻意的取舍：

- **每条任务独立工作目录** `filesDir/work/<id>/`，带 `signature` 文件。
  旧实现所有任务共用一个 `work/` 目录，任务开始和结束都 `clearDir`，
  所以「暂停」根本无从实现 —— 半成品会被下一次任务清掉。
  续传只在签名一致时进行：换了画质还往旧文件里写，拼出来的是坏文件。
- **暂停 / 取消的检查放在每个 64 KB 块写完之后**，不是之前。
  块写到一半就中断会让文件尾部不完整，续传接上去仍然是坏的。
- **速度用 3 秒滑动窗口**，不是「总字节 ÷ 总耗时」——
  后者在换服务器或网络抖动时会把瞬时速度算成一个平滑的假值。
- **总量未知时进度条置为不确定态**（`percent()` 返回 −1），
  而不是画一条从 0 慢慢爬的假进度。
- **暂停后立刻把速度清零**，否则界面上留着一个「2.9 MB/s」，
  看起来还在下。

### 真机上才暴露的五个缺陷

这几个都不是读代码能看出来的，全部由真机操作 + 日志定位：

1. **`MediaMuxer.stop()` 对已经写完整的文件报错。**

   OPPO 的 `OplusMPEG4Writer` 抛：
   ```
   E/MediaMuxer: stop() err: -1007
   E/MediaMuxer-JNI: Error during stop:-1007
   E/MPEG4Writer: Stop() called but track is not started or stopped
   java.lang.IllegalStateException: Error during stop(), muxer would have stopped already
   ```
   但把产物拉回来用 Python 逐 box 解析：`moov` 完整（463,100 字节）、
   2 条轨、视频 30,829 个样本、`1024 + 3192 + 69102821 + 463100`
   正好等于文件大小，播放器也能播。**文件是好的，是 `stop()` 在撒谎。**

   改为：`stop()` 的异常先记下不抛，`release()` 之后自己扫 box 树验收
   （`moov` 存在 + `mdat` 非空 + `hdlr` 声明了需要的轨），
   结构成立就按成功处理。

   **验证必须放在 `release()` 之后**：`moov` 是在收尾阶段追加到文件尾的，
   有些实现在 `release()` 里才真正 flush。在 `stop()` 一返回就去读，
   很可能读到「还没有 moov 的半成品」，把正常完成的封装判成失败 ——
   那比不验证更糟。

   另外第一版验收用 `MediaExtractor`，结果它在这台 OPPO 上对同一个好文件
   也返回「没有轨」。拿一个同样不可靠的组件去给另一个组件的产物背书，
   只是把失败从「写不出来」换成「读不出来」。现在自己解析 box 结构，
   `MediaExtractor` 只作为加分项。

2. **MediaStore 撞名时把 SQLite 报错直接冒给用户。**
   ```
   UNIQUE constraint failed: files._data (code 2067 SQLITE_CONSTRAINT_UNIQUE)
   ```
   异常类型是 `RuntimeException`，而当时只 `catch (IOException)`，
   于是它穿透到顶层，任务被标成失败、留下一个孤儿文件。
   改为自动加序号重试（`视频 (2).mp4`），确实写不进去时给一句
   人能看懂、且信息量对的话。

3. **单 P 视频的文件名变成「标题 - 标题」。**
   `displayName()` 原来只用 `!partTitle.equals(title)` 去重，
   但 B 站单 P 视频的分 P 名常常只差一点标点或空白，
   于是拼出 `…Rick Astley - Never Gonna Give You Up - Rick Astley.mp4`。
   改为相等、包含、被包含三种情况都不重复拼。

4. **合成阶段打不断。**
   点「暂停」会先弹一句「已暂停」，任务却继续跑完并变成「已完成」——
   同一个动作给出两个相反的答案。根因是合成在 `MuxUtil` 里跑，
   从不检查停止标志。现在每写一个样本查一次，取消时抛
   `Http.AbortedException`，上层按「用户要停」处理而不是「合成失败」。

5. **合成失败的提示用错了画质名函数。**
   哔哩哔哩任务调的是 `YouTubeEngine.describeHeight(task.videoHeight)`，
   而 B 站任务的 `videoHeight` 可能是 0，于是 480P 的视频显示成
   「（MP4 容器 / 未知画质 画质）」—— 既认不出是哪一档，
   又重复了「画质」二字。改用任务自己记下的 `subtitle`。

### 界面上两处自相矛盾的地方

- 删除**正在下载**的任务时，弹窗说「已经下载完成」，而卡片上写着
  「下载中」。现在如实说「正在下载，删除会先停止它」。
- 取消确认框两个按钮都叫「取消」：左边是关掉弹窗、右边是执行取消，
  用户点哪个都是猜。右边改为「停止下载」。

### 删除的语义

| 动作 | 记录 | 文件 | 说明 |
|---|---|---|---|
| 只删记录 | 删除 | 保留 | 明说「文件会保留」 |
| 删除记录和文件 | 删除 | 删除 | SAF 目录也能删掉 |
| 取消 | 保留（可重试） | 清掉残留 | 卡片显示「已取消，残留已清理」 |

**删除正在下载的任务必须分两步**：先让 service 停下来，
等它回报终态，再动文件。否则下载线程会在删完之后继续往工作目录写，
用户看到「已删除」而磁盘上留着几十 MB 没人认领的碎片。

同理，取消之后要把字节数归零 —— 否则卡片显示
「已取消 · 已暂停 · 71.2 MB / 71.2 MB」，而汇总是「共占用 0 B」，
同一个屏幕上两个数字互相矛盾。

### 怎么验的

全部在 OPPO Reno4 5G（PDPM00 / Android 12 / root）上逐项操作：

| 项目 | 结果 |
|---|---|
| 进度条与速度 | `16 MB / 66.5 MB`、`2 MB/s`、`还剩 25 秒` 实时变化 |
| 暂停 | `audio.part` / `video.part` 保留，`output.mp4` 停止增长 |
| 合成中暂停 | `output.mp4` 冻结在 65,034,444 字节，界面显示「已暂停」 |
| 继续 | 从断点接上，最终「已完成」并落盘 |
| 自选目录（B 站） | 存到 `MyGrab/字幕君交流场所 - P1.mp4` |
| 自选目录（YouTube） | 经代理存到 `MyGrab/Me at the zoo.mp4` |
| 只删记录 | 记录消失，文件保留 |
| 删除记录和文件 | SAF 文件一并删除 |
| 取消（下载中 / 已暂停） | 残留清空，记录留为「已取消」，可「重试」 |
| 重试 | 从零重下并成功落盘 |
| 清除已完成 | 记录清空，文件保留 |
| 重装后 | 记录与「已暂停」状态都在（`pm install -r` 不动 files/） |

产物都拉回本地用 box 解析器校验过：`vide avc1` 轨样本数、时长
（34:15 → `184974417 / 90000`）与源视频一致。

### 关于 YouTube 测试环境

YouTube 的解析受**出口 IP 信誉**影响，不是播放器客户端的问题。
这次测试用的是一条临时中继：PC 上 `127.0.0.1:7897` 只监听本机，
写了个纯转发脚本把它开到局域网，手机设代理为 `<PC_LAN_IP>:7899`。

**测完已经撤掉**：中继进程结束（无监听端口）、防火墙规则删除、
App 内的代理设置清空（`youtube_proxy` 为空串）。用户的 7897 全程未动。

### 还没验的

- **大会员 4K / 8K / 真彩**：从未拿真实 SESSDATA 试过。
  已知的是这几档走账号权限，不是代码能绕的（见 1.5.x 的记录）。
- **YouTube 首次 403 的成因**仍然没有定论。这次全程没复现，
  换档重试是防御性的；候选解释还是 PO-token 门控的格式，
  或 googlevideo 签名绑定了请求出口 IP。

## 1.6.1 —— 修掉「写了但从没生效」的 403 重试

### 一句话

**v1.5.2 加的 403 换档重试，靠措辞去认错误，两种真实文案一个都命不中，
从上线起就没执行过。现已改为认状态码本身，并在真机上验证到它真的触发、
真的恢复了。**

### 修的是什么

那张特征词表和真实报错的对照：

| 实际报错 | 表里写的 | 命中 |
|---|---|---|
| `YouTube CDN 返回 HTTP 403。` | `http error 403` | ✗ |
| `Unexpected response code for CONNECT: 403` | （没有对应项） | ✗ |

`http 403` ≠ `http error 403`；后者更是既没有 `http` 也没有 `forbidden`。

改成 `hasStatus(m, "403") \|\| hasStatus(m, "410")` —— 两侧不许是数字，
免得命中 `1403` 这类无关数字。措辞类特征词降级为兜底保留。
410 一并认下是因为 googlevideo 地址过期回的就是 Gone，
而「重新解析换条新地址」正是那种情况该做的事。

### 怎么验的

没有靠读代码下结论 —— 写了 `tools/relay-with-403.py` 主动注入故障，
把发往**媒体流主机**的 CONNECT 掐掉一次回真 403，逼出重试路径。

修完真机实测：

```
17:58:07  YouTube 直链 host=rr4---sn-oguelnze.googlevideo.com  头=0  UA=(无)
17:58:07  java.io.IOException: Unexpected response code for CONNECT: 403
17:58:07  视频流失败（…），改用播放器客户端 web_embedded,tv,mweb 重新解析后重试
17:58:29  解析完成：…（换档后重新解析）
17:58:29  YouTube 直链 host=…  头=4  UA=Mozilla/5.0 … Chrome/150.0.0.0
17:58:30  下载成功，落盘 636,212 字节
```

注入器那边的账也对得上：

```
> 放行媒体流：CONNECT manifest.googlevideo.com:443      ← 解析阶段，放行
X 掐掉 #1：   CONNECT rr4---sn-oguelnze.googlevideo.com:443   ← 下载第 1 次
> 放行媒体流：CONNECT rr4---sn-oguelnze.googlevideo.com:443   ← 重试后放行
```

**注意换档改变了请求形态**：档位 0 是 `头=0 UA=(无)`，
档位 1 是 `头=4 UA=Chrome/150`。这正是重试有意义的原因 ——
不是把同一个请求重发一遍，而是换一套客户端身份重新要地址。

### 已经确认的事（1.6.0 之后新查到的）

1. **PC 上有个能用的代理出口。** `127.0.0.1:7897`（Clash 一类，TUN 模式），
   出口 IP `216.23.121.26`。**这条出口对 YouTube 是干净的** —— 实测 yt-dlp
   经它下载 format 160 / 251 都拿到了完整字节，没有 403。

2. **我确认了 `visionos` 客户端本身没问题。** 之前推测「没有 JS 运行时 →
   落到 `_DEFAULT_JSLESS_CLIENTS` → 只剩 visionos → 403」这个链条，
   只在**出口 IP 被风控**时才成立。PC 上的 yt-dlp **同样没有 JS 运行时**
   （`JS runtimes: none`），但下载正常。所以：
   - **解析失败 = 出口 IP 信誉问题**（手机之前那两个代理就是这样）
   - **下载 403 = 另一回事**，需要单独解释

3. **YouTube 全链路第一次在真机上跑通了。**
   `https://youtu.be/jNQXAC9IVRw` → 解析成功（默认客户端一次过）→
   下载 → 合流 → 落盘 `Me at the zoo.mp4`，636,212 字节。
   日志：`YouTube 直链 host=rr4---sn-oguelnze.googlevideo.com 头=0`

4. **`tools/lan-proxy-relay.py`** —— 把 `127.0.0.1:7897` 转发到局域网
   （`0.0.0.0:7899`）。手机填 `192.168.8.104:7899` 即可用上 PC 那条干净出口。
   不改用户的代理配置，用完关掉。

5. **`tools/relay-with-403.py`** —— 同上，但会把发往**媒体流主机**的
   CONNECT 掐掉前 N 次，回一个真 403。用来逼出重试路径。

   > 踩过的坑：第一版只匹配 `googlevideo.com`，结果掐中的是
   > `manifest.googlevideo.com` —— 那是 yt-dlp **解析阶段**取 HLS 清单用的。
   > 下载连的是 `rr3---sn-oguesndl.googlevideo.com`。掐错地方的后果是
   > 解析照常成功（yt-dlp 对清单取不到会优雅降级），但下载一次就过，
   > 重试路径完全没被碰到 —— 测试等于白做。
   > 现在按 `TARGET_EXCLUDE = "manifest."` 排除掉了。

### 那条 bug 的现场记录（已修，留档）

注入 403 之后，异常确实从 `downloadYouTubeStream` 抛出来了，但**没有重试**：

```
java.io.IOException: Unexpected response code for CONNECT: 403
    at DownloadService.downloadUrl(DownloadService.java:557)
    at DownloadService.downloadStream(DownloadService.java:490)
    at DownloadService.downloadYouTubeStream(DownloadService.java:355)
```

对照 `YouTubeEngine.clientRelated()` 原来的匹配表：

```java
"http error 403", "403 forbidden", "forbidden",
```

**三个都没命中。** 更糟的是用户的原始报错也命不中 ——
`Http.errorSnippet` 那条路径产出的是 `YouTube CDN 返回 HTTP 403。`，
而表里写的是 `http error 403`。也就是说 **v1.5.2 加的下载侧换档重试，
在两种真实文案下都不会触发**。

修法与验证见本文档顶部 1.6.1 一节。
**教训：靠措辞去认错误，漏掉的写法只会越来越多；状态码本身才是稳定的。**

### 复现步骤（下一轮直接照做）

```powershell
# 1. 起转发器（干净出口）
& $py "E:\Deepseek工作目录\BiliGrab\tools\lan-proxy-relay.py"        # 7899

# 2. 或起故障注入版（逼重试）
& $py "E:\Deepseek工作目录\BiliGrab\tools\relay-with-403.py" 7899 1

# 3. 加防火墙规则（需要管理员）
netsh advfirewall firewall add rule name="BiliGrab lan relay 7899" `
      dir=in action=allow protocol=TCP localport=7899

# 4. 手机 side：写 prefs 时属主必须是 10280:10280
#    （写成 system:system 会让应用读不到，日志报
#     "Attempt to read preferences file ... without permission"）
#    youtube_proxy = 192.168.8.104:7899

# 5. 分享 https://youtu.be/jNQXAC9IVRw → 等解析 → 点 240P 那一行

# 6. 收尾：删防火墙规则、清掉手机上的 youtube_proxy、关转发器
```

`prefer_qn` 是 360P 那一档（16）还是 80 都不影响这个测试，
YouTube 那边的 `quality` 就是高度。

### 还没做的

* **B 站 4K / 真彩 / 8K 仍受大会员账号权限限制。** 1.6.0 加了应用内登录，
  **登录一个大会员账号后四档能否真的下载，还没验证过** —— 之前所有结论
  都是匿名态下测的（结论：`support_formats` 会广告 4K，但匿名 `dash.video`
  永远不超过 id 80）。这是 item 1 唯一还能推进的地方。

* 用户手上那个原始症状（**解析成功、下载恒 403**）**始终没有复现**。
  在这条干净出口上，下载一次就过。修好的重试是防御性的 ——
  它现在能救回「第一次被拒」的情况，但**为什么会第一次被拒，仍未坐实**。
  两个候选解释都还只是候选：
  1. 部分被风控的 IP 上，某些格式需要 PO token；
  2. googlevideo 签名与请求出口 IP 绑定，解析后换节点会让签名失效。

---

## 1.6.0 —— 应用内登录

**已发布。** tag `v1.6.0`。

### 改了什么

账号登录从「粘贴 SESSDATA」换成「在应用内登录 B 站账号」。

原来那个输入框要求用户自己去浏览器开 F12，翻到 Application → Cookies，
从里面挑出 SESSDATA 复制进来。这个流程有两个问题，而且都不小：

1. **取错了不会报错。** 少复制一个字符，应用只会表现为「明明登录了却还是 480P」。
   用户没有任何办法自查 —— 界面上没有一处能区分「没登录」和「登录了但值不对」。
2. **它把一件本该一键完成的事，变成了要先懂开发者工具。**

### 为什么不是「账号密码表单」，而是官方登录页

用户要的是账号密码登录。**但这条路自己实现不了**，这不是偷懒：

```
POST passport.bilibili.com/x/passport-login/web/login
  username=13800000000 & password=deadbeef & keep=0
  token= & challenge= & validate= & seccode=
→ HTTP 200  code=-105  message=验证码错误
```

服务端要求同时带上极验的 `challenge` / `validate` / `seccode`，
而这三样只能由极验的前端算出来。不接打码服务就没有合法途径拿到。

**原项目也没做。** 两个镜像（`yss161/bilibilias_code`、`SOCK-MAGIC/bilibilias`）
的登录相关文件完全一致，一共 13 个，没有一个是账号密码：

```
ui/login/LoginScreen.kt              ← 选平台
ui/login/QRCodeLoginScreen.kt        ← 扫码
ui/login/CookieLoginScreen.kt        ← 粘贴 Cookie
core/data/.../QRCodeLoginRepository.kt
```

也就是说，原项目用的是**扫码**，而"粘贴 Cookie"它本来也有 ——
我们现在这个反而是从它那儿沿袭来的。

### 采用的方案：把官方登录页装进 WebView

`https://passport.bilibili.com/login` 是一个单页应用（返回的 HTML 只有 948 字节，
内容全靠 JS 渲染），所以必须在 WebView 里跑。这样：

* **账号密码登录**：界面底部的「账号密码登录」直接可用（已真机截图确认）
* **手机短信登录**：默认页就是
* **扫码登录**：也在同一个页面里
* **验证码由 B 站自己的页面处理** —— 这正是自己实现走不通的那一环

登录完成后抓 `CookieManager` 里的 SESSDATA，先用 `x/web-interface/nav`
验一次（`isLogin` + `uname`）再保存。**这一步不能省**：存一份无效的
SESSDATA 比不存更糟 —— 界面会显示「已登录」，画质还是 480P。

### 真机验证

| 项目 | 结果 |
| --- | --- |
| 登录页在 WebView 里渲染 | ✅ 手机号登录/注册 页完整呈现 |
| 「账号密码登录」入口可用 | ✅ 账号 / 密码 / 忘记密码 / 登录 一应俱全 |
| **Cookie 抓取通路** | ✅ 日志：`WebView Cookie 罐：buvid3, b_nut, _uuid, buvid4` |
| `exported=false` 生效 | ✅ adb 直接 `am start` 被 SecurityException 拒绝 |
| SESSDATA 输入框已移除 | ✅ 界面里找不到 |
| B 站下载回归 `BV1GJ411x7h7` | ✅ 14,728,731 字节 |
| B 站 4K 大会员提示 | ✅ |
| **实际登录一次** | ⚠️ 未能验证 —— 需要真实账号，这是用户的动作 |

Cookie 罐那一行是关键证据：它证明 `CookieManager.getCookie()` 确实读得到
WebView 里的 Cookie。既然 `buvid3` 之类的匿名 Cookie 能读到，
SESSDATA 出现时同样能读到。

> 那一行**只记 Cookie 名字，不记值**。SESSDATA 本身就是密码等价物，
> 写进 logcat 等于把账号摊开 —— 别的应用读得到 logcat。

### 顺带修掉的一个隐患

`commitSettings` 原来每次关闭设置面板都会把输入框里的内容写回 SESSDATA。
用户改了一半没提交，反而会把好的登录态覆盖掉。登录态现在由
`LoginActivity` 登录成功后立即保存，不再经过「面板关闭」这个动作。

---

## 1.5.2

**已发布。** tag `v1.5.2`。

### 找到了 YouTube 403 的真正根因：播放器客户端

前一版猜的是「请求头不对」。**猜错了。** 真正的机制在这儿：

```
yt_dlp/extractor/youtube/_video.py
    _DEFAULT_CLIENTS        = visionos, web
    _DEFAULT_AUTHED_CLIENTS = web_embedded, tv_downgraded, web
    _DEFAULT_JSLESS_CLIENTS = visionos          ← 只有这一个
```

那份代码里写着：

```python
default_clients = (
    self._DEFAULT_AUTHED_CLIENTS if self.is_authenticated
    else self._DEFAULT_JSLESS_CLIENTS if not js_runtime_available
    else self._DEFAULT_CLIENTS
)
```

**本应用没有 JS 运行时（Deno / Node）**，所以永远落到 `_DEFAULT_JSLESS_CLIENTS`，
而那一项**只有 `visionos` 一个客户端**。被 YouTube 风控的机房 IP 上，
这个客户端给出的流地址是带 PO token 校验的 ——

> **画质列表照常列出，一下载就 403**，而 CDN 报的还是「签名失效」。

这正好是用户报的现象。上一版的请求头改动仍然是对的（yt-dlp 声明 `http_headers`
就该照用），但它**不是** 403 的成因 —— 实测也印证了：改之前用可用代理下载时
并没有 403。

（客户端名单是从设备上实际在跑的 yt-dlp 2026.08.19 里读出来的，
不是凭记忆写的。注意 `vendor/res/raw/ytdlp` 里那个是 2025.11.12，
`ensureReady` 会把它更新掉，`noBackupFilesDir` 在 `pm install -r` 后依然保留。）

### 两项修复

**① 解析阶段：播放器客户端阶梯**

```
null → web_embedded,tv,mweb → tv_simply,android_vr,web_safari → all
```

先试默认（干净 IP 上最快），**只有失败原因确实与客户端有关时才往下走**。
视频是私有的、已删除的，四个组合全试一遍只是白白多等几十秒。

**② 下载阶段：403 后换档重解析**

403 不等于「这个视频下不了」，只等于「这一档客户端给的地址不能用」。
所以某条流被 CDN 拒了之后，会换下一档客户端重新解析，把视频轨和音频轨的
地址**一起**换掉（同档客户端给出的两条轨是配套的，只换一条会不搭），
然后重试 —— 不动用户选的那一档画质。

### 顺手修掉的一个误导

阶梯全试完还是失败时，原来报的是**最后一次**的错。实测中
`player_client=all` 在同一个被风控的 IP 上返回的是 `Video unavailable`，
而视频本身明明没问题 —— 用户会以为视频坏了，跑去换链接，白费功夫。
现在改成报**第一次**（默认客户端）的判断，也就是真正有话可说的那句：

> YouTube 判定这个网络出口是机器人，拒绝了本次解析。
> 通常是因为代理 / VPN 的出口 IP 落在机房地址段（机场、云主机多半如此）。
> 换一个住宅 IP 的节点再试。

### 真机验证

| 项目 | 结果 |
| --- | --- |
| 客户端阶梯确实依次重试 | ✅ 日志逐档记录，共 4 次尝试 |
| 失败时报的是风控那句（不是「视频不可用」） | ✅ |
| 中文解释能读到 | ✅ |
| B 站 4K 大会员提示（未登录） | ✅ 未被本轮改动影响 |
| B 站下载回归 `BV1GJ411x7h7` 360P | ✅ 14,728,731 字节 |
| **下载 403 后换档重试** | ⚠️ **未能验证** —— 见下 |

### 为什么下载那条仍未能验证

`192.168.8.2:7890` 和 `:8080` 都能连上 YouTube（HTTP 200），
但**两个出口的 IP 都被 YouTube 风控**，四个客户端一律被拦，
连解析都过不去 —— 自然走不到下载那一步，也就不可能复现 403。

局域网全段扫描（254 主机 × 24 端口）找到的"开放端口"是误报：
几台主机的**所有**端口都答 SYN，那是中间设备代答，不是代理。

**所以下载侧的换档重试是照机制写的，但没有跑通过一次。** 这点必须说清楚。

---

# 1.5.1

> 以下是 1.5.0 与 1.5.1 的进展。

## 1.5.1（最新）

**已发布。** tag `v1.5.1`，release id `394318046`。
https://github.com/308532806/BiliGrab/releases/tag/v1.5.1

- APK `BiliGrab-1.5.1.apk`，19,426,666 bytes
- SHA-256 `1BC08C5D46017681C025540E78D23E198B6C3AE77704EB0D13054108D9234C99`
- 远端 main HEAD `8150bb3a`，本地 `c57a64a` —— **内容一致、SHA 不同**，原因见下

### ① B 站 4K / 真彩 → 查清了，是大会员权限

**结论：4K / HDR 真彩 / 杜比视界 / 8K 需要大会员账号。** 未登录拿不到，
原项目 bilibilias 同样拿不到。不是本应用的缺陷。

实测四个热门稿件 × 四种组合（旧端点/WBI × 有无 `try_look`），结果完全一致：

```
support_formats 报：120=4K 超高清, 116=1080P 60帧, 80=1080P 高清, ...
dash 实给        ：最高 [16, 32, 64, 80]  →  1080P
```

服务端对匿名会话**广告 4K 但拒绝发放**。`qn=127` / `fnval=4048` / `fourk=1` /
`fnver=0` 本来就都发着，和原项目一字不差。

原项目「看起来支持 4K」是因为它不裁剪 —— 把 `support_formats` 看到的档位
全列出来，选了下不到就静默回退。1.5.0 剔掉了这些行（留着是骗人），
但**只剔不说**会让人以为应用不支持 4K，这是 1.5.0 的疏漏。

现在 `PlayInfo.lockedQualities` 记下被剔掉的档位，界面明确说：

> 本稿件支持 4K 超高清、1080P 高码率，需要大会员账号才能下载。当前最高只能拿到 1080P 高清

### ② YouTube 403 → 找到两个缺陷，但根因仍未坐实

- **确凿**：`YouTubeEngine` 从不读 yt-dlp 为每个 format 声明的 `http_headers`，
  下载时用 `Http.open` 里那套**为 B 站准备的**桌面 Chrome UA 打 googlevideo。
  现已原样带上（视频/音轨分开存，两者常来自不同 itag）。
- **可能**：无条件发 `Range: bytes=0-`。部分 googlevideo 直链把区间写进签名
  （查询串里的 `&range=`），再叠一个 `Range` 会因区间与签名不符而 403，
  且报的正是「签名失效」。现只在地址自身不带 `range` 时才发。

**但都没能真机验证** —— 实测时 PC 的代理软件已关闭、手机 VPN 也没有 tun 接口，
两边都连不上 YouTube。

**而且有一条反证**：改之前的实测里，用可用代理时下载是能开始的（跑到 5% 才断），
并没有 403。说明 UA 不匹配并非唯一成因，**出口 IP 那条链路同样可疑**。
→ **403 的根因仍然开放。**

下一轮怎么定位：出 403 时日志会打印直链主机、头数、是否自带 `range`、
实际 User-Agent，以及 **CDN 响应体**（响应体是区分「签名过期 / IP 不符 /
客户端不匹配」的唯一证据）。

```powershell
adb logcat -d -s BiliGrab:* | Select-String "YouTube 直链"
```

### 真机验证

| 项目 | 结果 |
| --- | --- |
| 4K 大会员提示文案 | ✅ `BV1J7hE6aEDQ`，未登录 |
| 档位列表无幻影 4K 行 | ✅ |
| B 站下载回归 `BV1GJ411x7h7` 360P | ✅ 完整落盘 14,728,731 字节 |
| YouTube 403 修复 | ⚠️ 未能验证 |

### 推送到 GitHub 的备用路径

**`github.com:443` 在境内经常连不上（DNS 能解析，TCP 不通），但 `api.github.com`
和 `uploads.github.com` 通常是通的。** 这时 `git push` 走不了，用
`tools/push-via-api.py`：它用 Git Data API 手工做一遍 push 做的事
（建 blob → 建 tree → 建 commit → 移动 ref → 建 tag）。

副作用：服务端重新构造的对象，**SHA 与本地不同**（内容一致）。
脚本因此从**远端 main HEAD** 取父提交，而不是本地 `HEAD~1`。
GitHub 恢复后 `git fetch && git reset --hard origin/main` 即可对齐。

---

# 1.5.0

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
