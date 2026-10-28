#Requires -Version 7.2
<#
.SYNOPSIS
  把宣傳頁部署到 GitHub Pages（gh-pages 分支）。

.DESCRIPTION
  1. 組成完整網頁：mail-architecture.html 是 Artifact 用的片段（沒有 doctype 與 head）。
     這裡把開頭到第一個 </style> 的 title、meta、link、style 移進 head，補上 charset、viewport、
     描述與 Artifact 外框相同的基礎樣式，輸出成 index.html；頁面引用的 img/* 從 docs/images/ 複製；
     加 .nojekyll 讓 GitHub 不跑 Jekyll。
  2. 用暫時的 git worktree 把結果 commit 到 gh-pages 分支並推上 remote。保留部署歷史，不強制推送。
  3. Pages 還沒開時，以 gh api 開啟，來源為 gh-pages 分支根目錄。

.EXAMPLE
  # 只組網頁、不推送，輸出到 logs/gh-pages-site/ 檢查
  pwsh docs/promo/deploy-gh-pages.ps1 -BuildOnly

.EXAMPLE
  pwsh docs/promo/deploy-gh-pages.ps1
#>
[CmdletBinding()]
param(
    # 只組網頁、不推送
    [switch] $BuildOnly,
    # 組好的網頁放哪裡；預設 logs/gh-pages-site（已在 .gitignore）
    [string] $SiteDir,
    # 部署分支
    [string] $Branch = 'gh-pages',
    # 推送的 remote
    [string] $Remote = 'origin',
    # 附加在部署 commit 訊息最後的 trailer，例如 Co-Authored-By（選填）
    [string] $Trailer
)

$ErrorActionPreference = 'Stop'
# repo 根目錄
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..' '..')).Path
if (-not $SiteDir) { $SiteDir = Join-Path $RepoRoot 'logs' 'gh-pages-site' }

<# 執行 git，失敗就中止（不用 param 區塊，讓 -C 之類的參數原樣傳給 git） #>
function Invoke-Git {
    & git @args
    if ($LASTEXITCODE -ne 0) { throw "git $($args -join ' ') 失敗（結束碼 $LASTEXITCODE）" }
}

# ---- 1. 組成完整網頁 --------------------------------------------------------
$source = Get-Content (Join-Path $PSScriptRoot 'mail-architecture.html') -Raw -Encoding utf8
$cut = $source.IndexOf('</style>')
if ($cut -lt 0) { throw '找不到 </style>，無法分出要放進 head 的區段' }
$cut += '</style>'.Length
# 開頭的 title、meta、link、style 放 head，其餘放 body
$headPart = $source.Substring(0, $cut)
$bodyPart = $source.Substring($cut)

# 搜尋結果與社群分享用的描述
$description = '用 Amazon SES 自建大量寄信伺服器的 Spring Boot starter：電子報、廣告信都能寄，事件可選 SQS 拉取或 webhook，每千封 $0.10。'
# 與 Artifact 外框相同的基礎樣式，讓兩邊顯示一致
$reset = ':root{color-scheme:light;box-sizing:border-box;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}html{scroll-padding-top:env(safe-area-inset-top,0px)}body{margin:0;padding:0;font:14px -apple-system,BlinkMacSystemFont,sans-serif;background:#fff;color:#000}img{max-width:100%}[hidden]:not([hidden=until-found i]){display:none!important}'
# 分頁圖示：紙飛機（內嵌，免得瀏覽器去要 /favicon.ico 得到 404）
$favicon = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%230b6e77' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M22 2 11 13'/%3E%3Cpath d='M22 2 15 22l-4-9-9-4 20-7z'/%3E%3C/svg%3E"

$html = @"
<!doctype html>
<html lang="zh-Hant">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="description" content="$description">
<meta property="og:type" content="website">
<meta property="og:title" content="用 Amazon SES 自建大量寄信伺服器">
<meta property="og:description" content="$description">
<link rel="icon" href="$favicon">
<style>$reset</style>
$headPart
</head>
<body>
$bodyPart
</body>
</html>
"@

if (Test-Path $SiteDir) { Remove-Item $SiteDir -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $SiteDir 'img') -Force | Out-Null
Set-Content -Path (Join-Path $SiteDir 'index.html') -Value $html -Encoding utf8NoBOM -NoNewline
Set-Content -Path (Join-Path $SiteDir '.nojekyll') -Value '' -Encoding utf8NoBOM -NoNewline
# 頁面引用的圖片一律從 docs/images 複製，缺檔就中止
$images = [regex]::Matches($source, 'src="img/([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
foreach ($name in $images) {
    $from = Join-Path $RepoRoot 'docs' 'images' $name
    if (-not (Test-Path $from)) { throw "頁面引用的圖片不存在：$from" }
    Copy-Item $from (Join-Path $SiteDir 'img' $name)
}
Write-Host "網頁已組好：$SiteDir（圖片 $($images.Count) 張）"
if ($BuildOnly) { return }

# ---- 2. commit 到 gh-pages 並推送 --------------------------------------------
Push-Location $RepoRoot
try {
    $sourceSha = (& git rev-parse --short HEAD).Trim()
    $worktree = Join-Path ([System.IO.Path]::GetTempPath()) ('ses-mail-gh-pages-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    $remoteHasBranch = [bool](& git ls-remote --heads $Remote $Branch)
    if ($remoteHasBranch) {
        Invoke-Git fetch $Remote $Branch
        Invoke-Git worktree add -B $Branch $worktree "$Remote/$Branch"
    }
    else {
        if (& git branch --list $Branch) { throw "本機已有 $Branch 分支但 $Remote 上沒有，請先確認內容後自行刪除再重跑" }
        # 遠端還沒有分支：建立沒有歷史的孤兒分支
        Invoke-Git worktree add --orphan -b $Branch $worktree
    }
    try {
        # 換成這次組好的網頁（含 .nojekyll 這類點開頭的檔案）
        Get-ChildItem $worktree -Force | Where-Object Name -ne '.git' | Remove-Item -Recurse -Force
        Get-ChildItem $SiteDir -Force | Copy-Item -Destination $worktree -Recurse -Force
        Invoke-Git -C $worktree add -A
        & git -C $worktree diff --cached --quiet
        if ($LASTEXITCODE -eq 0) {
            Write-Host '網頁沒有變更，不需要部署'
        }
        else {
            $message = "部署宣傳頁（來源 main $sourceSha）"
            if ($Trailer) { $message += "`n`n$Trailer" }
            Invoke-Git -C $worktree commit -m $message
            Invoke-Git -C $worktree push $Remote $Branch
        }
    }
    finally {
        & git worktree remove --force $worktree
    }
}
finally {
    Pop-Location
}

# ---- 3. 開啟 GitHub Pages ------------------------------------------------------
$repo = (& gh repo view --json nameWithOwner -q .nameWithOwner).Trim()
& gh api "repos/$repo/pages" --silent 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "開啟 GitHub Pages：來源 $Branch 分支根目錄"
    & gh api -X POST "repos/$repo/pages" -f "source[branch]=$Branch" -f 'source[path]=/' --silent
    if ($LASTEXITCODE -ne 0) { throw '開啟 GitHub Pages 失敗' }
}
$url = (& gh api "repos/$repo/pages" -q .html_url).Trim()
Write-Host "網址：$url（GitHub 建置約需一、兩分鐘）"
