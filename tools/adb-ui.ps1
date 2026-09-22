# =====================================================================
#  adb 界面操作助手
#  =====================================================================
#  为什么需要它：手写像素坐标做点击测试是靠不住的 —— 布局滚动、
#  状态变化都会让旧坐标失效，结果就是"点了没反应"被误判成 bug。
#  这里统一"先 dump、再按 resource-id 找节点、最后点它的中心"。
#
#  用法：
#     . .\tools\adb-ui.ps1
#     Tap-Node -Id swAudioOnly
#     Get-Checked -Id swAudioOnly
# =====================================================================

$script:Adb = "E:\dsh-toolchain\android-sdk\platform-tools\adb.exe"
$script:Dev = "/data/local/tmp/dsh-ui.xml"
$script:Loc = "$env:TEMP\dsh-ui.xml"

function Get-UiXml {
    # 必须 pull 回来再用 .NET 按 UTF-8 读。
    # 走 `adb shell cat` 的话，PowerShell 会按控制台代码页（这台机器是 GBK）
    # 解码 adb 输出的 UTF-8 字节，中文被搅乱，连带 bounds 都解析不出来。
    #
    # 关键：每次 pull 之前先删掉本地文件。
    # uiautomator 在屏幕持续动画时（典型就是视频正在播放）会 dump 失败
    # （"could not get idle state"），这时直接 pull 拿到的会是上一次的旧文件，
    # 于是读到过期的界面状态 —— 实测把"正在播放"误判成了"退回封面"。
    for ($try = 1; $try -le 3; $try++) {
        Remove-Item $script:Loc -Force -ErrorAction SilentlyContinue
        & $script:Adb shell "uiautomator dump $script:Dev >/dev/null 2>&1" | Out-Null
        Remove-Item $script:Loc -Force -ErrorAction SilentlyContinue
        & $script:Adb pull $script:Dev $script:Loc 2>&1 | Out-Null
        if (Test-Path $script:Loc) {
            return [System.IO.File]::ReadAllText($script:Loc, [System.Text.Encoding]::UTF8)
        }
        Start-Sleep -Milliseconds 400
    }
    # 拿不到就返回空串，让调用方知道"这次没读到"，而不是喂它旧数据
    return ""
}

function Find-Node {
    param(
        [string]$Id,
        [string]$Text,
        [string]$Desc,
        [string]$ClassLike,
        [switch]$Clickable
    )
    $xml = Get-UiXml
    foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
        $n = $m.Value
        if ($Clickable -and $n -notmatch 'clickable="true"') { continue }
        if ($Id -and $n -notmatch ('resource-id="[^"]*' + [regex]::Escape($Id) + '"')) { continue }
        if ($Text -and $n -notmatch ('text="' + [regex]::Escape($Text) + '"')) { continue }
        if ($Desc -and $n -notmatch ('content-desc="' + [regex]::Escape($Desc) + '"')) { continue }
        if ($ClassLike -and $n -notmatch ('class="[^"]*' + [regex]::Escape($ClassLike))) { continue }
        if ($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            return @{
                X1 = [int]$Matches[1]; Y1 = [int]$Matches[2]
                X2 = [int]$Matches[3]; Y2 = [int]$Matches[4]
                Xml = $n
            }
        }
    }
    return $null
}

function Get-NodeInfo {
    param([string]$Id)
    $node = Find-Node -Id $Id
    if (-not $node) { return $null }
    $n = $node.Xml
    $checked = if ($n -match 'checked="true"') { 'true' } else { 'false' }
    $selected = if ($n -match 'selected="true"') { 'true' } else { 'false' }
    $enabled = if ($n -match 'enabled="true"') { 'true' } else { 'false' }
    $visible = if ($n -match 'visible-to-user="(true|false)"') { $Matches[1] } else { '?' }
    return @{
        Id = $Id
        Bounds = $node
        Checked = $checked
        Selected = $selected
        Enabled = $enabled
        Visible = $visible
        WidthDp = [int](( $node.X2 - $node.X1) / 3)
        HeightDp = [int](( $node.Y2 - $node.Y1) / 3)
    }
}

function Get-Checked {
    param([string]$Id)
    $i = Get-NodeInfo -Id $Id
    if ($i) { return $i.Checked } else { return 'missing' }
}

function Tap-Center {
    param([int]$X, [int]$Y, [int]$SettleMs = 900)
    & $script:Adb shell "input tap $X $Y" | Out-Null
    Start-Sleep -Milliseconds $SettleMs
}

function Tap-Node {
    param(
        [string]$Id,
        [string]$Text,
        [string]$Desc,
        [switch]$Clickable,
        [int]$SettleMs = 900
    )
    $node = Find-Node -Id $Id -Text $Text -Desc $Desc -Clickable:$Clickable
    if (-not $node) {
        Write-Output "  [找不到节点] Id=$Id Text=$Text Desc=$Desc"
        return $false
    }
    $x = [int](($node.X1 + $node.X2) / 2)
    $y = [int](($node.Y1 + $node.Y2) / 2)
    Tap-Center -X $x -Y $y -SettleMs $SettleMs
    return $true
}

function Get-ScreenText {
    $xml = Get-UiXml
    $out = @()
    foreach ($m in [regex]::Matches($xml, 'text="([^"]+)"')) {
        $t = $m.Groups[1].Value
        if ($t -and $out -notcontains $t) { $out += $t }
    }
    return $out
}

# uiautomator 对视口外的节点报 bounds="[0,0][0,0]"。
# 拿这种节点去点击等于点在 (0,0)，会被误判成"控件没反应"。
# 所以点击前必须先把节点滚进视口。
function Scroll-To-Node {
    param(
        [string]$Id,
        [string]$Text,
        [string]$Desc,
        [int]$MaxTries = 8
    )
    for ($i = 0; $i -lt $MaxTries; $i++) {
        $node = Find-Node -Id $Id -Text $Text -Desc $Desc
        if ($node -and ($node.X2 - $node.X1) -gt 0 -and ($node.Y2 - $node.Y1) -gt 0) {
            return $node
        }
        & $script:Adb shell "input swipe 540 1600 540 900 250" | Out-Null
        Start-Sleep -Milliseconds 500
    }
    return $null
}

function Scroll-Top {
    for ($i = 0; $i -lt 6; $i++) {
        & $script:Adb shell "input swipe 540 900 540 1900 200" | Out-Null
        Start-Sleep -Milliseconds 250
    }
}

function Tap-NodeScrolled {
    param(
        [string]$Id,
        [string]$Text,
        [string]$Desc,
        [int]$SettleMs = 900
    )
    $node = Scroll-To-Node -Id $Id -Text $Text -Desc $Desc
    if (-not $node) {
        Write-Output "  [滚动后仍找不到] Id=$Id Text=$Text Desc=$Desc"
        return $false
    }
    Tap-Center -X ([int](($node.X1 + $node.X2) / 2)) -Y ([int](($node.Y1 + $node.Y2) / 2)) -SettleMs $SettleMs
    return $true
}

function Save-Shot {
    param([string]$Path)
    & $script:Adb shell "screencap -p /data/local/tmp/dsh-shot.png" | Out-Null
    & $script:Adb pull /data/local/tmp/dsh-shot.png $Path 2>&1 | Out-Null
    return $Path
}

Write-Output "adb-ui.ps1 已载入（adb: $script:Adb）"
