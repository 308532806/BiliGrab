# 签名密钥

这个目录存放 APK 的签名密钥库 `biligrab.jks`。**除本说明外，目录内容不会进版本库。**

## 为什么不能弄丢

Android 要求「覆盖安装」时新旧 APK 的签名证书一致。
密钥一旦丢失或更换，已安装旧版本的设备会直接拒绝安装新版本
（报错通常是「应用未安装」或 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`），
用户只能卸载重装 —— 本地设置和已下载记录都会一起丢掉。

所以：**生成后请立刻把 `biligrab.jks` 备份到安全的地方。**

## 默认参数

| 项 | 值 |
| --- | --- |
| 路径 | `keystore/biligrab.jks` |
| 别名 | `biligrab` |
| 口令 | `biligrab123` |
| 算法 | RSA 2048 |
| 有效期 | 10950 天（30 年） |

> 口令可以在构建时用 `-KeystorePass` 参数或 `BILIGRAB_KEYSTORE_PASS`
> 环境变量覆盖。默认口令是公开的，正式分发前建议改掉。

## 备份

只需要备份 `biligrab.jks` 这一个文件（里面有私钥）。
推荐同时记录下口令，否则文件本身也解不开。

```powershell
# 复制到别处
Copy-Item keystore\biligrab.jks D:\backup\biligrab.jks

# 顺便确认一下证书指纹，记下来
keytool -list -v -keystore keystore\biligrab.jks -storepass biligrab123 -alias biligrab
```

## 让 GitHub Actions 用同一把钥匙

本地和 CI 各签各的，会导致两边产物无法互相覆盖安装。统一办法：

1. 把密钥库转成 base64 并复制到剪贴板

   ```powershell
   $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\biligrab.jks"))
   Set-Clipboard $b64
   ```

2. 打开 `仓库 → Settings → Secrets and variables → Actions`
3. 新建 Secret：

   | 名称 | 值 |
   | --- | --- |
   | `KEYSTORE_BASE64` | 上一步复制的内容 |
   | `KEYSTORE_PASS` | 密钥库口令 |

之后 CI 构建（包括 tag 触发的 Release）就会使用同一把密钥。

未配置这两个 Secret 时，CI 会临时生成一把新钥匙，
产物只能用于测试安装，且 tag 打包时会输出一条 `::warning::` 提醒。

## 换一把密钥（不推荐）

如果确实需要更换，用户必须卸载重装。做法：

```powershell
Remove-Item keystore\biligrab.jks
# 下次构建会自动生成新的
```

## 关于本项目的定位

BiliGrab 是开源侧载应用，不走 Google Play，因此这里用的是自签名密钥，
而非 Play App Signing。任何人都可以自行生成密钥、编译并安装自己的版本 ——
这也意味着**不要用别人的 APK 覆盖安装你已装好的版本**，
除非你确认签名指纹一致。
