# =====================================================================
#  BiliGrab —— Android SDK 组件安装脚本
#
#  直接从 dl.google.com 下载官方 zip 并解压到位，不使用 sdkmanager。
#
#  为什么不用 sdkmanager？
#    · android-actions/setup-android@v3 会去装 'tools' 包，而 Google 已
#      将独立的 tools 包下架，导致 "Failed to find package 'tools'" 报错；
#    · 直接用官方 zip 更少中间环节，且不依赖任何第三方 Action。
#
#  用法：
#     powershell -ExecutionPolicy Bypass -File scripts\setup-sdk.ps1
#     powershell -ExecutionPolicy Bypass -File scripts\setup-sdk.ps1 -SdkRoot D:\android-sdk
# =====================================================================

param(
    [string]$SdkRoot = "",
    [switch]$Force
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---------------------------- 组件清单 ----------------------------
# 如需升级，改这里的 url / dir 即可。目录名必须与 build-tools 内部结构一致。
$Components = @(
    @{
        Label = 'build-tools 34.0.0'
        Url   = 'https://dl.google.com/android/repository/build-tools_r34-windows.zip'
        Dir   = 'build-tools\34.0.0'
        Probe = 'aapt2.exe'
    },
    @{
        Label = 'platform android-34'
        Url   = 'https://dl.google.com/android/repository/platform-34-ext7_r03.zip'
        Dir   = 'platforms\android-34'
        Probe = 'android.jar'
    }
)

# ---------------------------- 目标目录 ----------------------------

if ([string]::IsNullOrEmpty($SdkRoot)) {
    if ($env:ANDROID_HOME) { $SdkRoot = $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $SdkRoot = $env:ANDROID_SDK_ROOT }
    else {
        $RepoRoot = Split-Path -Parent $PSScriptRoot
        $SdkRoot = Join-Path $RepoRoot '.android-sdk'
    }
}
$SdkRoot = [System.IO.Path]::GetFullPath($SdkRoot)
New-Item -ItemType Directory -Force -Path $SdkRoot | Out-Null

$DlDir = Join-Path $SdkRoot '.downloads'
New-Item -ItemType Directory -Force -Path $DlDir | Out-Null

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host " Android SDK 组件安装" -ForegroundColor Cyan
Write-Host " 目标: $SdkRoot"
Write-Host "=============================================" -ForegroundColor Cyan

function Test-Installed($component) {
    $probe = Join-Path (Join-Path $SdkRoot $component.Dir) $component.Probe
    return Test-Path $probe
}

$failed = @()

foreach ($c in $Components) {
    Write-Host "`n>>> $($c.Label)" -ForegroundColor Yellow

    if ((Test-Installed $c) -and -not $Force) {
        Write-Host "    已安装，跳过"
        continue
    }

    $zip = Join-Path $DlDir (($c.Dir -replace '[\\;]', '-') + '.zip')

    if (-not (Test-Path $zip) -or $Force) {
        Write-Host "    下载 $($c.Url)"
        & curl.exe -sS -L --fail --retry 3 --retry-delay 3 --connect-timeout 25 -o $zip $c.Url
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $zip)) {
            Write-Host "    下载失败 (curl exit $LASTEXITCODE)" -ForegroundColor Red
            $failed += $c.Label
            continue
        }
        Write-Host ("    完成 {0:N1} MB" -f ((Get-Item $zip).Length / 1MB))
    } else {
        Write-Host ("    使用缓存 {0:N1} MB" -f ((Get-Item $zip).Length / 1MB))
    }

    # 官方 zip 里通常有一层包装目录（build-tools 是 android-14，platform 是 android-34）
    $tmp = Join-Path $DlDir ('x-' + [guid]::NewGuid().ToString('N'))
    try {
        [System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $tmp)
    } catch {
        Write-Host "    解压失败: $($_.Exception.Message)" -ForegroundColor Red
        $failed += $c.Label
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        continue
    }

    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    if (-not $inner) {
        Write-Host "    压缩包结构异常，未找到顶层目录" -ForegroundColor Red
        $failed += $c.Label
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
        continue
    }

    $dest = Join-Path $SdkRoot $c.Dir
    New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    Move-Item $inner.FullName $dest
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

    if (Test-Installed $c) {
        Write-Host "    已安装 -> $dest" -ForegroundColor Green
    } else {
        Write-Host "    安装后校验失败，缺少 $($c.Probe)" -ForegroundColor Red
        $failed += $c.Label
    }
}

# ---------------------------- 汇总 ----------------------------

Write-Host "`n=============================================" -ForegroundColor Cyan
Write-Host " 关键文件校验"
$checks = @(
    'build-tools\34.0.0\aapt2.exe',
    'build-tools\34.0.0\zipalign.exe',
    'build-tools\34.0.0\lib\d8.jar',
    'build-tools\34.0.0\lib\apksigner.jar',
    'platforms\android-34\android.jar'
)
$missing = 0
foreach ($p in $checks) {
    $full = Join-Path $SdkRoot $p
    $ok = Test-Path $full
    if (-not $ok) { $missing++ }
    Write-Host ("   [{0}] {1}" -f $(if ($ok) { 'OK' } else { '--' }), $p)
}

if ($failed.Count -gt 0 -or $missing -gt 0) {
    Write-Host "`n 安装未完成。失败组件: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}

Write-Host "`n 全部就绪：$SdkRoot" -ForegroundColor Green
Write-Host "=============================================" -ForegroundColor Cyan
exit 0
