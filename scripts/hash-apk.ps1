# 对本机构建产物（APK / AAB）算 SHA-256。详见 docs/可复现构建.md
# 用法：
#   .\scripts\hash-apk.ps1
#   .\scripts\hash-apk.ps1 app\build\outputs\apk\release\xxx.apk

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Paths
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

function Get-Targets {
    if ($Paths -and $Paths.Count -gt 0) {
        return $Paths
    }
    $out = @()
    foreach ($dir in @(
        "app\build\outputs\apk",
        "app\build\outputs\bundle",
        "release"
    )) {
        if (Test-Path $dir) {
            $out += Get-ChildItem -Path $dir -Recurse -File -Include *.apk,*.aab -ErrorAction SilentlyContinue
        }
    }
    return $out | Sort-Object FullName
}

$targets = @(Get-Targets)
if ($targets.Count -eq 0) {
    Write-Error "未找到 APK/AAB。先 assemble，或传入路径。"
}

foreach ($t in $targets) {
    $item = if ($t -is [System.IO.FileInfo]) { $t } else { Get-Item -LiteralPath $t }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $item.FullName).Hash.ToLowerInvariant()
    $rel = Resolve-Path -LiteralPath $item.FullName -Relative
    "{0}  {1}  {2}" -f $hash, $item.Length, $rel
}
