# =====================================================================
#  BiliGrab —— 拉取 YouTube 引擎所需的第三方二进制到 vendor/
#
#  为什么不把这些文件提交进仓库：
#    它们合计约 17 MB（CPython 运行时 + Kotlin 标准库 + yt-dlp 本体），
#    提交进去会让仓库体积膨胀一个数量级，而且这些内容都能从公开源重新拉到。
#
#  为什么单独一个脚本而不是把下载散落在构建脚本里：
#    这样 vendor/ 可以被 CI 缓存住，也能手工重跑而不必触发一次完整构建。
#
#  它是一份**必需的**构建依赖：源代码里的 YouTubeEngine.java 直接引用了
#  com.yausername.youtubedl_android 的类，少了 vendor/ 的话 javac 一定失败。
#  所以 build-apk.ps1 检测不到 vendor/ 时会自动调用本脚本。
#
#  用法：
#     powershell -ExecutionPolicy Bypass -File scripts\fetch-vendor.ps1
#     powershell -ExecutionPolicy Bypass -File scripts\fetch-vendor.ps1 -Force   # 强制重下
# =====================================================================

param(
    # youtubedl-android 的版本。它由 Seal 的作者维护（原始作者 yausername 的
    # 版本停留在 2025-11，且没有 Maven Central 发布）。
    [string]$YouTubeDlVersion = "0.18.1",
    [string]$KotlinVersion = "2.0.21",
    [string]$AnnotationsVersion = "24.1.0",
    # 库的传递依赖。版本号取自 library 的 POM，不要随手升级：
    # jackson 2.11 与本库的 API 用法是配套的。
    [string]$JacksonVersion = "2.11.1",
    [string]$CommonsIoVersion = "2.5",
    [string]$CommonsCompressVersion = "1.12",
    # 只打这一种 ABI。ARM64 覆盖了 2017 年以后几乎所有中高端安卓机，
    # 保留四种 ABI 会让原生库部分从 14.5 MB 涨到 56 MB。
    [string]$Abi = "arm64-v8a",
    # 内置 yt-dlp 本体的目标版本。设置里那个「应用内更新」是运行时的，
    # 这里决定的是 APK 出厂自带的兜底版本 —— 拉不到就保留 AAR 自带的那份。
    [string]$YtDlpVersion = "2026.08.19",
    [switch]$Force
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Vendor   = Join-Path $RepoRoot "vendor"
$LibsDir  = Join-Path $Vendor "libs"
$JniDir   = Join-Path $Vendor "jni\$Abi"
$ResRaw   = Join-Path $Vendor "res\raw"
$Cache    = Join-Path $Vendor ".cache"

$Maven = "https://repo1.maven.org/maven2"
$YtdlGroup = "$Maven/io/github/junkfood02/youtubedl-android"

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host " BiliGrab vendor 拉取" -ForegroundColor C
Write-Host " youtubedl-android $YouTubeDlVersion   ABI=$Abi" -ForegroundColor C
Write-Host " 目标: $Vendor" -ForegroundColor C
Write-Host "=============================================" -ForegroundColor Cyan

foreach ($d in @($Vendor, $LibsDir, $JniDir, $ResRaw, $Cache)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
}

function Fetch($url, $dest) {
    if ((Test-Path $dest) -and -not $Force) {
        Write-Host ("  已有 " + (Split-Path -Leaf $dest)) -ForegroundColor DarkGray
        return $true
    }
    try {
        Write-Host ("  下载 " + (Split-Path -Leaf $dest)) -ForegroundColor Gray
        Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing -TimeoutSec 600
        return $true
    } catch {
        Write-Host ("  失败: " + $_.Exception.Message) -ForegroundColor Red
        return $false
    }
}

# ---------------------------- 1. AAR ----------------------------

$aarName = "library-$YouTubeDlVersion.aar"
$aarPath = Join-Path $Cache $aarName
if (-not (Fetch "$YtdlGroup/library/$YouTubeDlVersion/$aarName" $aarPath)) {
    throw "拉取 youtubedl-android AAR 失败。检查网络，或确认版本号 $YouTubeDlVersion 是否存在。"
}

$sizeMb = [math]::Round((Get-Item $aarPath).Length / 1MB, 2)
Write-Host "  AAR $sizeMb MB" -ForegroundColor DarkGray

# ---------------------------- 2. 拆 AAR ----------------------------

$extract = Join-Path $Cache "aar-extract"
if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
$zipCopy = Join-Path $Cache "aar.zip"
Copy-Item $aarPath $zipCopy -Force
Expand-Archive $zipCopy -DestinationPath $extract -Force
Remove-Item $zipCopy -Force

# classes.jar -> libs/
$srcClasses = Join-Path $extract "classes.jar"
if (-not (Test-Path $srcClasses)) { throw "AAR 里没有 classes.jar，结构可能变了。" }
Copy-Item $srcClasses (Join-Path $LibsDir "youtubedl-android.jar") -Force

# ---------------------------- 2b. common 构件 ----------------------------
# library 的 POM 把 common 列为 runtime 依赖，它只有两个类
# （SharedPrefsHelper、ZipUtils），但缺了它同样会在运行时 NoClassDefFoundError。
$commonAar = Join-Path $Cache "common-$YouTubeDlVersion.aar"
if (-not (Fetch "$YtdlGroup/common/$YouTubeDlVersion/common-$YouTubeDlVersion.aar" $commonAar)) {
    throw "拉取 youtubedl-android common 失败。"
}
$commonExtract = Join-Path $Cache "common-extract"
if (Test-Path $commonExtract) { Remove-Item $commonExtract -Recurse -Force }
$commonZip = Join-Path $Cache "common.zip"
Copy-Item $commonAar $commonZip -Force
Expand-Archive $commonZip -DestinationPath $commonExtract -Force
Remove-Item $commonZip -Force
Copy-Item (Join-Path $commonExtract "classes.jar") (Join-Path $LibsDir "youtubedl-common.jar") -Force

# ---------------------------- 2c. 库的传递依赖 ----------------------------
# library 的 classes.jar 里有 8 个类不在自身、Android SDK 或 kotlin-stdlib 里：
#   com.fasterxml.jackson.databind.{JsonNode, ObjectMapper, node.ArrayNode}
#   org.apache.commons.compress.archivers.zip.{ZipArchiveEntry, ZipArchiveInputStream, ZipFile}
#   org.apache.commons.io.{FileUtils, IOUtils}
#
# 其中 jackson 是硬性的：YoutubeDL 的**静态初始化块**里就 new 了一个
# ObjectMapper，所以哪怕一行都不调用，只要碰到这个类就会
# NoClassDefFoundError（真机上已经踩到了）。
#
# POM 里还声明了 androidx.appcompat 和 androidx.core，但实测这两个 jar 对
# androidx/* 的引用数是 **0** —— 那是给示例应用用的，本工程不需要，
# 也就不必为了它把整个 AndroidX 树拖进来。
$transitives = @(
    @("com/fasterxml/jackson/core/jackson-databind/$JacksonVersion", "jackson-databind"),
    @("com/fasterxml/jackson/core/jackson-core/$JacksonVersion",     "jackson-core"),
    @("com/fasterxml/jackson/core/jackson-annotations/$JacksonVersion", "jackson-annotations"),
    @("commons-io/commons-io/$CommonsIoVersion",                     "commons-io"),
    @("org/apache/commons/commons-compress/$CommonsCompressVersion", "commons-compress")
)
foreach ($t in $transitives) {
    $artifact = $t[1]
    $file = "$artifact-$($t[0].Split('/')[-1]).jar"
    if (-not (Fetch "$Maven/$($t[0])/$file" (Join-Path $LibsDir $file))) {
        throw "拉取 $artifact 失败。"
    }
}

# jni/<abi>/*.so -> jni/<abi>/
$srcJni = Join-Path $extract "jni\$Abi"
if (-not (Test-Path $srcJni)) {
    $have = (Get-ChildItem (Join-Path $extract "jni") -Directory -ErrorAction SilentlyContinue |
             ForEach-Object { $_.Name }) -join ", "
    throw "AAR 里没有 $Abi 的原生库。可用的有: $have"
}
Get-ChildItem $srcJni -File | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $JniDir $_.Name) -Force
    Write-Host ("  " + $_.Name + "  " + [math]::Round($_.Length / 1MB, 2) + " MB") -ForegroundColor DarkGray
}

# res/raw/ytdlp -> res/raw/   （yt-dlp 本体，运行时可通过 updateYoutubeDL 更新）
$srcYtdlp = Join-Path $extract "res\raw\ytdlp"
if (Test-Path $srcYtdlp) {
    Copy-Item $srcYtdlp (Join-Path $ResRaw "ytdlp") -Force
    Write-Host ("  res/raw/ytdlp  " + [math]::Round((Get-Item (Join-Path $ResRaw "ytdlp")).Length / 1MB, 2) + " MB") -ForegroundColor DarkGray
}

# ---------------------------- 2d. yt-dlp 本体 ----------------------------
# AAR 自带的 yt-dlp 往往落后几个月。这里在同一个脚本里顺手拉一份官方最新版，
# 直接覆盖 vendor/res/raw/ytdlp —— 失败不致命：保留 AAR 自带版本，运行时
# 仍可经设置里的「应用内更新」再拉。
$ytdlpCache = Join-Path $Cache "ytdlp-$YtDlpVersion"
if (-not (Fetch "https://github.com/yt-dlp/yt-dlp/releases/download/$YtDlpVersion/yt-dlp" $ytdlpCache)) {
    Write-Host "  yt-dlp $YtDlpVersion 拉取失败，保留 AAR 自带版本。" -ForegroundColor Yellow
} else {
    $head = [IO.File]::ReadAllBytes($ytdlpCache)[0..1]
    if ($head[0] -ne 0x23 -or $head[1] -ne 0x21) {
        Write-Host "  yt-dlp 下载内容不是 #! 开头的 zipapp，保留 AAR 自带版本。" -ForegroundColor Yellow
    } else {
        Copy-Item $ytdlpCache (Join-Path $ResRaw "ytdlp") -Force
        Write-Host ("  res/raw/ytdlp  " + $YtDlpVersion + "  " + [math]::Round((Get-Item (Join-Path $ResRaw "ytdlp")).Length / 1MB, 2) + " MB") -ForegroundColor DarkGray
    }
}

# res/values 刻意不复制。
# AAR 的 res/values/values.xml 只有一行
# <string name="app_name">youtubedl-android</string>，
# 与本应用的 app_name 同名，aapt2 合并资源时会直接
# "resource 'string/app_name' has a conflicting value" 失败。
# 而库里只引用了 R.raw.ytdlp，那份 strings 对运行时毫无作用。

# ---------------------------- 3. Kotlin 标准库 ----------------------------

# youtubedl-android 是 Kotlin 写的，类里引用了 kotlin.* 类型，
# 所以运行时必须有 kotlin-stdlib。annotations 是它的传递依赖。
$kotlinJar = Join-Path $LibsDir "kotlin-stdlib.jar"
if (-not (Fetch "$Maven/org/jetbrains/kotlin/kotlin-stdlib/$KotlinVersion/kotlin-stdlib-$KotlinVersion.jar" $kotlinJar)) {
    throw "拉取 kotlin-stdlib 失败。"
}
$annJar = Join-Path $LibsDir "annotations.jar"
if (-not (Fetch "$Maven/org/jetbrains/annotations/$AnnotationsVersion/annotations-$AnnotationsVersion.jar" $annJar)) {
    throw "拉取 annotations 失败。"
}

# ---------------------------- 4. 汇总 ----------------------------

Write-Host "`n---------------------------------------------" -ForegroundColor Cyan
Write-Host " vendor/ 内容" -ForegroundColor Yellow

$total = 0
Get-ChildItem $Vendor -Recurse -File | Where-Object { $_.FullName -notlike "*\.cache\*" } | ForEach-Object {
    $rel = $_.FullName.Substring($Vendor.Length + 1)
    Write-Host ("  {0,-42} {1,10:N0} B" -f $rel, $_.Length) -ForegroundColor Gray
    $total += $_.Length
}
Write-Host ("  {0,-42} {1,10:N0} B  ({2:N2} MB)" -f "合计（不含缓存）", $total, ($total / 1MB)) -ForegroundColor Yellow

# 缓存目录不小（AAR 56 MB），构建不需要它
if (-not $Force) {
    Remove-Item $Cache -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "`n完成。现在可以构建带 YouTube 能力的 APK：" -ForegroundColor Green
Write-Host "  powershell -File scripts\build-apk.ps1 -VersionName 1.3.0" -ForegroundColor Green
