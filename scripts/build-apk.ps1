# =====================================================================
#  BiliGrab —— 不依赖 Gradle 的极简 APK 构建脚本
#
#  只使用 Android SDK build-tools 自带的 aapt2 / d8 / zipalign / apksigner，
#  因此无需下载 AGP、Kotlin 插件、AndroidX 等数百 MB 依赖。
#
#  用法：
#     powershell -ExecutionPolicy Bypass -File scripts\build-apk.ps1
#     powershell -ExecutionPolicy Bypass -File scripts\build-apk.ps1 -VersionName 1.1.0
# =====================================================================

param(
    [string]$VersionName = "1.0.0",
    [int]$VersionCode = 1,
    [string]$AppPackage = "com.biligrab.app",
    [string]$OutDir = "",
    [switch]$KeepIntermediate
)

# 注意：不能用 'Stop'。javac / d8 / zipalign 会把提示信息写到 stderr，
# 而 Windows PowerShell 5.1 会把原生命令的 stderr 当作终止错误。
# 这里统一靠 $LASTEXITCODE 判断成败。
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

# ---------------------------- 路径 ----------------------------

$RepoRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $RepoRoot "app\src\main\AndroidManifest.xml"))) {
    throw "未找到 app/src/main/AndroidManifest.xml，请确认脚本位于仓库的 scripts/ 目录下。"
}

$Toolchain = if ($env:BILIGRAB_TOOLCHAIN) { $env:BILIGRAB_TOOLCHAIN } else { "E:\dsh-toolchain" }
$JdkHome   = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { Join-Path $Toolchain "jdk17" }
$SdkRoot   = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $Toolchain "android-sdk" }
$BuildTools = Join-Path $SdkRoot "build-tools\34.0.0"
$AndroidJar = Join-Path $SdkRoot "platforms\android-34\android.jar"

# 构建在纯 ASCII 的临时目录里进行，避免中文路径在 .bat 包装脚本里被转码
$WorkRoot = Join-Path ([System.IO.Path]::GetTempPath()) "biligrab-build"
$SrcCopy  = Join-Path $WorkRoot "src"
$OutBuild = Join-Path $WorkRoot "out"

if ([string]::IsNullOrEmpty($OutDir)) {
    $OutDir = Join-Path $RepoRoot "dist"
}

$Aapt2     = Join-Path $BuildTools "aapt2.exe"
$Zipalign  = Join-Path $BuildTools "zipalign.exe"
$D8        = Join-Path $BuildTools "d8.bat"
$Javac     = Join-Path $JdkHome "bin\javac.exe"
$JavaExe   = Join-Path $JdkHome "bin\java.exe"
$Jar       = Join-Path $JdkHome "bin\jar.exe"
$Keytool   = Join-Path $JdkHome "bin\keytool.exe"
# apksigner.bat 在 PowerShell 下转发参数有坑，直接调用 jar 更可靠
$Apksigner = Join-Path $BuildTools "lib\apksigner.jar"

# ---------------------------- 前置检查 ----------------------------

function Require($path, $what) {
    if (-not (Test-Path $path)) { throw "缺少 $what：$path" }
}

Require $Javac     "JDK"
Require $Aapt2     "aapt2"
Require $D8        "d8"
Require $Zipalign  "zipalign"
Require $Apksigner "apksigner"
Require $AndroidJar "android.jar (platform 34)"

$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host " BiliGrab APK 构建  v$VersionName ($VersionCode)" -ForegroundColor Cyan
Write-Host " 仓库    : $RepoRoot"
Write-Host " 工作区  : $WorkRoot"
Write-Host " 输出    : $OutDir"
Write-Host "=============================================" -ForegroundColor Cyan

# ---------------------------- 准备干净工作区 ----------------------------

if (Test-Path $WorkRoot) { Remove-Item $WorkRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $SrcCopy, $OutBuild | Out-Null

Copy-Item (Join-Path $RepoRoot "app") -Destination $SrcCopy -Recurse -Force

$Manifest = Join-Path $SrcCopy "app\src\main\AndroidManifest.xml"
$ResDir   = Join-Path $SrcCopy "app\src\main\res"
$JavaDir  = Join-Path $SrcCopy "app\src\main\java"

# 源仓库的 manifest 不带 package 属性（AGP 8 要求用 namespace），
# 但 aapt2 link 必须从 manifest 读取包名，因此构建时临时注入。
$mfText = [System.IO.File]::ReadAllText($Manifest, [System.Text.Encoding]::UTF8)
if ($mfText -notmatch '<manifest[^>]*\spackage\s*=') {
    $mfText = $mfText -replace '(<manifest\s+xmlns:android="[^"]*")', ('$1' + "`r`n    package=""$AppPackage""")
    [System.IO.File]::WriteAllText($Manifest, $mfText, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "    已向 manifest 注入 package=$AppPackage"
}

# ---------------------------- 1. aapt2 compile ----------------------------

Write-Host "`n[1/7] 编译资源 (aapt2 compile)" -ForegroundColor Yellow
$ResZip = Join-Path $OutBuild "res.zip"
& $Aapt2 compile --dir $ResDir -o $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

# ---------------------------- 2. aapt2 link ----------------------------

Write-Host "`n[2/7] 链接资源 (aapt2 link)" -ForegroundColor Yellow
$BaseApk = Join-Path $OutBuild "base.apk"
$GenDir  = Join-Path $OutBuild "gen"
New-Item -ItemType Directory -Force -Path $GenDir | Out-Null

& $Aapt2 link `
    -o $BaseApk `
    -I $AndroidJar `
    --manifest $Manifest `
    --java $GenDir `
    --version-code $VersionCode `
    --version-name $VersionName `
    $ResZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

# ---------------------------- 3. javac ----------------------------

Write-Host "`n[3/7] 编译 Java 源码 (javac --release 8)" -ForegroundColor Yellow
$ClassesDir = Join-Path $OutBuild "classes"
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null

$Sources = @(Get-ChildItem $JavaDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$Sources += @(Get-ChildItem $GenDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host "    源文件 $($Sources.Count) 个"

& $Javac -encoding UTF-8 --release 8 -nowarn -Xlint:none `
    "-J-Duser.language=en" "-J-Duser.country=US" `
    -cp $AndroidJar -d $ClassesDir @Sources
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

# ---------------------------- 4. d8 ----------------------------

Write-Host "`n[4/7] 生成 DEX (d8)" -ForegroundColor Yellow
$ClassesJar = Join-Path $OutBuild "classes.jar"
& $Jar cf $ClassesJar -C $ClassesDir .
if ($LASTEXITCODE -ne 0) { throw "jar 打包失败" }

$DexDir = Join-Path $OutBuild "dex"
New-Item -ItemType Directory -Force -Path $DexDir | Out-Null

& $D8 --min-api 26 --lib $AndroidJar --output $DexDir $ClassesJar
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

$DexFile = Join-Path $DexDir "classes.dex"
Require $DexFile "classes.dex"

# ---------------------------- 5. 组包 ----------------------------

Write-Host "`n[5/7] 组装 APK" -ForegroundColor Yellow
$UnsignedApk = Join-Path $OutBuild "unsigned.apk"
Copy-Item $BaseApk $UnsignedApk -Force

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($UnsignedApk, 'Update')
try {
    $existing = $zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' }
    foreach ($e in $existing) { $zip.Entries.Remove($e) | Out-Null }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, $DexFile, 'classes.dex', [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

# ---------------------------- 6. zipalign ----------------------------

Write-Host "`n[6/7] 对齐 (zipalign)" -ForegroundColor Yellow
$AlignedApk = Join-Path $OutBuild "aligned.apk"
& $Zipalign -f -p 4 $UnsignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

# ---------------------------- 7. 签名 ----------------------------

Write-Host "`n[7/7] 签名 (apksigner)" -ForegroundColor Yellow
$KsPath = Join-Path $WorkRoot "biligrab.jks"
$KsPass = "biligrab123"
$KsAlias = "biligrab"

if (-not (Test-Path $KsPath)) {
    Write-Host "    生成调试密钥库"
    & $Keytool -genkeypair -v `
        -keystore $KsPath `
        -alias $KsAlias `
        -keyalg RSA -keysize 2048 -validity 10950 `
        -storepass $KsPass -keypass $KsPass `
        -dname "CN=BiliGrab, OU=OpenSource, O=BiliGrab, L=Beijing, ST=Beijing, C=CN" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool 生成密钥失败" }
}

if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }
$FinalApk = Join-Path $OutDir "BiliGrab-$VersionName.apk"

& $JavaExe -jar $Apksigner sign `
    --ks $KsPath --ks-key-alias $KsAlias `
    --ks-pass "pass:$KsPass" --key-pass "pass:$KsPass" `
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true `
    --out $FinalApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner 签名失败" }

Write-Host "`n校验签名：" -ForegroundColor Yellow
& $JavaExe -jar $Apksigner verify --verbose $FinalApk

if (-not $KeepIntermediate) {
    Remove-Item (Join-Path $WorkRoot "out") -Recurse -Force -ErrorAction SilentlyContinue
}

$size = [math]::Round((Get-Item $FinalApk).Length / 1KB, 1)
Write-Host "`n=============================================" -ForegroundColor Green
Write-Host " 构建成功！" -ForegroundColor Green
Write-Host " 产物: $FinalApk  ($size KB)" -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Green

# 供 CI 使用
if ($env:GITHUB_OUTPUT) {
    "apk=$FinalApk" | Out-File -FilePath $env:GITHUB_OUTPUT -Append -Encoding utf8
}
