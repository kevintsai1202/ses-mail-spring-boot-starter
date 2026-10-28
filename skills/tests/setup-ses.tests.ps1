#Requires -Version 7.2
<#
.SYNOPSIS
  setup-ses.ps1 的測試：以 -DryRun 執行，檢查產生的 aws 指令、JSON 文件與環境變數。

.DESCRIPTION
  不呼叫 AWS、不需要 AWS CLI；DryRun 若誤呼叫 aws 會直接失敗。
  用純 PowerShell 斷言（本機只有 Windows PowerShell 內建的 Pester 3.4，不另外安裝）。
  SQS 案例的資源名稱、屬性與政策對照 survey-backend 既有的 setup-ses-events.sh。

.EXAMPLE
  # 於 repo 根目錄
  pwsh -NoProfile -File skills/tests/setup-ses.tests.ps1
#>
$ErrorActionPreference = 'Stop'

# 受測腳本、yaml 範本與 skill 目錄
$SkillDir = Join-Path $PSScriptRoot '..' 'ses-mail-setup'
$Script = Join-Path $SkillDir 'scripts' 'setup-ses.ps1'
$YamlTemplate = Join-Path $SkillDir 'assets' 'application-sesmail.yml'
# DryRun 用的假帳號（AWS 文件的範例帳號）
$Account = '111122223333'

# 失敗訊息清單；全部跑完再一起回報
$script:Failures = [System.Collections.Generic.List[string]]::new()
$script:Passed = 0

<# 條件成立算通過，否則記下訊息 #>
function Assert-True([bool] $Condition, [string] $Message) {
    if ($Condition) { $script:Passed++ } else { $script:Failures.Add($Message) }
}

<# 以 JSON 字串比對兩個值（陣列順序有意義） #>
function Assert-Equal($Expected, $Actual, [string] $Message) {
    $e = ConvertTo-Json -InputObject $Expected -Depth 10 -Compress
    $a = ConvertTo-Json -InputObject $Actual -Depth 10 -Compress
    Assert-True ($e -ceq $a) "$Message`n    預期：$e`n    實際：$a"
}

<# 指令清單中要有這一行 #>
function Assert-Command([string[]] $Commands, [string] $Line, [string] $Case) {
    Assert-True ($Commands -ccontains $Line) "[$Case] 缺少指令：$Line"
}

<#
  以 -DryRun 執行腳本。
  回傳 ExitCode、Output（主控台輸出）、OutDir、Commands（aws 指令行）、Docs（JSON 文件，以檔名為鍵）、Env（環境變數）。
#>
function Invoke-DryRun([hashtable] $Params, [switch] $Real) {
    $outDir = Join-Path ([System.IO.Path]::GetTempPath()) ('ses-setup-test-' + [guid]::NewGuid().ToString('N'))
    $argList = @('-NoProfile', '-NonInteractive', '-File', $Script, '-OutDir', $outDir)
    if (-not $Real) { $argList += @('-DryRun', '-AccountId', $Account) }
    foreach ($key in $Params.Keys) { $argList += "-$key"; $argList += [string]$Params[$key] }
    $output = & pwsh @argList 2>&1 | Out-String
    $exitCode = $LASTEXITCODE

    $commandsFile = Join-Path $outDir 'commands.txt'
    [string[]] $commands = @(if (Test-Path $commandsFile) { Get-Content $commandsFile -Encoding utf8 })
    $docs = @{}
    if (Test-Path $outDir) {
        Get-ChildItem $outDir -Filter '*.json' | ForEach-Object {
            $docs[$_.BaseName] = Get-Content $_.FullName -Raw -Encoding utf8 | ConvertFrom-Json -AsHashtable
        }
    }
    $envVars = [ordered]@{}
    $envFile = Join-Path $outDir 'env.txt'
    if (Test-Path $envFile) {
        foreach ($line in Get-Content $envFile -Encoding utf8) {
            $name, $value = $line -split '=', 2
            $envVars[$name] = $value
        }
    }
    [pscustomobject]@{ ExitCode = $exitCode; Output = $output; OutDir = $outDir; Commands = $commands; Docs = $docs; Env = $envVars }
}

<# 執行一個案例；中途拋例外時記成失敗，不影響其他案例 #>
function Invoke-Case([string] $Name, [scriptblock] $Body) {
    try { & $Body } catch { $script:Failures.Add("[$Name] 執行中斷：$($_.Exception.Message)") }
}

<# yaml 範本裡 ${NAME} 或 ${NAME:預設} 的變數名稱 #>
function Get-YamlPlaceholders {
    if (-not (Test-Path $YamlTemplate)) { return @() }
    $text = Get-Content $YamlTemplate -Raw -Encoding utf8
    @([regex]::Matches($text, '\$\{([A-Z0-9_]+)(:[^}]*)?\}') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
}

<# 腳本印出的環境變數都要在 yaml 範本有對應佔位，否則設了也不會生效 #>
function Assert-EnvInYaml($Result, [string] $Case) {
    $placeholders = Get-YamlPlaceholders
    foreach ($name in $Result.Env.Keys) {
        Assert-True ($placeholders -ccontains $name) ("[$Case] 環境變數 $name 在 yaml 範本沒有對應的佔位 " + '${' + $name + '}')
    }
}

# ---------------------------------------------------------------------------
# 案例 1：SQS 模式，參數對照 setup-ses-events.sh
# ---------------------------------------------------------------------------
Invoke-Case 'Sqs' {
$case = 'Sqs'
$r = Invoke-DryRun @{ Mode = 'Sqs'; Region = 'ap-northeast-1'; ConfigurationSet = 'newsletter'; IamUser = 'survey-backend-ses'; IamPolicyName = 'ses-send-only' }
Assert-True ($r.ExitCode -eq 0) "[$case] 結束碼應為 0，實際 $($r.ExitCode)`n$($r.Output)"

$region = 'ap-northeast-1'
$topicArn = "arn:aws:sns:${region}:${Account}:ses-events"
$queueArn = "arn:aws:sqs:${region}:${Account}:ses-events"
$dlqArn = "arn:aws:sqs:${region}:${Account}:ses-events-dlq"
$queueUrl = "https://sqs.${region}.amazonaws.com/${Account}/ses-events"
$dlqUrl = "https://sqs.${region}.amazonaws.com/${Account}/ses-events-dlq"
$doc = { param($name) 'file://' + (Join-Path $r.OutDir "$name.json") }

$expected = @(
    "aws sns create-topic --region $region --name ses-events"
    "aws sns set-topic-attributes --region $region --topic-arn $topicArn --attribute-name Policy --attribute-value $(& $doc 'topic-policy')"
    "aws sqs create-queue --region $region --queue-name ses-events-dlq"
    "aws sqs set-queue-attributes --region $region --queue-url $dlqUrl --attributes $(& $doc 'dlq-attributes')"
    "aws sqs create-queue --region $region --queue-name ses-events"
    "aws sqs set-queue-attributes --region $region --queue-url $queueUrl --attributes $(& $doc 'queue-attributes')"
    "aws sns subscribe --region $region --topic-arn $topicArn --protocol sqs --notification-endpoint $queueArn"
    "aws sesv2 create-configuration-set --region $region --configuration-set-name newsletter"
    "aws sesv2 create-configuration-set-event-destination --region $region --configuration-set-name newsletter --event-destination-name ses-events --event-destination $(& $doc 'event-destination')"
    "aws iam create-user --user-name survey-backend-ses"
    "aws iam put-user-policy --user-name survey-backend-ses --policy-name ses-send-only --policy-document $(& $doc 'iam-policy')"
)
foreach ($line in $expected) { Assert-Command $r.Commands $line $case }

# 佇列要先訂閱 topic，再讓 SES 開始發佈事件
$subscribeAt = [array]::IndexOf($r.Commands, $expected[6])
$destinationAt = [array]::IndexOf($r.Commands, $expected[8])
Assert-True ($subscribeAt -ge 0 -and $destinationAt -gt $subscribeAt) "[$case] 佇列訂閱應在建立事件目的地之前"
Assert-True (-not ($r.Commands -match 'put-metric-alarm|create-email-identity')) "[$case] 沒給 -AlertEmail、-Domain 時不應建立警報或網域身分"

# 死信佇列保留 14 天
Assert-Equal '1209600' $r.Docs['dlq-attributes'].MessageRetentionPeriod "[$case] 死信佇列保留期"

# 事件佇列：可見逾時 60 秒、只允許 ses-events topic 發送、5 次後轉死信佇列
$queueAttrs = $r.Docs['queue-attributes']
Assert-Equal '60' $queueAttrs.VisibilityTimeout "[$case] 可見逾時"
$queuePolicy = $queueAttrs.Policy | ConvertFrom-Json -AsHashtable
Assert-Equal '2012-10-17' $queuePolicy.Version "[$case] 佇列政策版本"
$qs = $queuePolicy.Statement[0]
Assert-Equal 'AllowSesEventsTopic' $qs.Sid "[$case] 佇列政策 Sid"
Assert-Equal 'Allow' $qs.Effect "[$case] 佇列政策 Effect"
Assert-Equal 'sns.amazonaws.com' $qs.Principal.Service "[$case] 佇列政策 Principal"
Assert-Equal 'SQS:SendMessage' $qs.Action "[$case] 佇列政策 Action"
Assert-Equal $queueArn $qs.Resource "[$case] 佇列政策 Resource"
Assert-Equal $topicArn $qs.Condition.ArnEquals['aws:SourceArn'] "[$case] 佇列政策只允許 ses-events topic"
$redrive = $queueAttrs.RedrivePolicy | ConvertFrom-Json -AsHashtable
Assert-Equal $dlqArn $redrive.deadLetterTargetArn "[$case] 死信佇列目標"
Assert-Equal '5' $redrive.maxReceiveCount "[$case] 轉死信佇列前的接收次數"

# 事件目的地：Bounce、Complaint、Delivery → topic
$dest = $r.Docs['event-destination']
Assert-Equal $true $dest.Enabled "[$case] 事件目的地啟用"
Assert-Equal @('BOUNCE', 'COMPLAINT', 'DELIVERY') $dest.MatchingEventTypes "[$case] 事件類型"
Assert-Equal $topicArn $dest.SnsDestination.TopicArn "[$case] 事件目的地 topic"

# topic 政策：允許此 configuration set 的 SES 發佈
$topicPolicy = $r.Docs['topic-policy']
$sesStatement = @($topicPolicy.Statement | Where-Object { $_.Principal.Service -eq 'ses.amazonaws.com' })[0]
Assert-True ($null -ne $sesStatement) "[$case] topic 政策缺少 ses.amazonaws.com 的陳述"
if ($sesStatement) {
    Assert-Equal 'sns:Publish' $sesStatement.Action "[$case] topic 政策 Action"
    Assert-Equal $topicArn $sesStatement.Resource "[$case] topic 政策 Resource"
    Assert-Equal $Account $sesStatement.Condition.StringEquals['AWS:SourceAccount'] "[$case] topic 政策 SourceAccount"
    Assert-Equal "arn:aws:ses:${region}:${Account}:configuration-set/newsletter" $sesStatement.Condition.StringEquals['AWS:SourceArn'] "[$case] topic 政策 SourceArn"
}

# IAM：寄信、查額度，加上讀這個佇列
$iam = $r.Docs['iam-policy']
Assert-Equal '2012-10-17' $iam.Version "[$case] IAM 政策版本"
Assert-Equal 2 @($iam.Statement).Count "[$case] IAM 陳述數"
Assert-Equal @('ses:SendEmail', 'ses:GetAccount') $iam.Statement[0].Action "[$case] IAM SES 權限"
Assert-Equal '*' $iam.Statement[0].Resource "[$case] IAM SES 資源"
Assert-Equal @('sqs:ReceiveMessage', 'sqs:DeleteMessage', 'sqs:ChangeMessageVisibility') $iam.Statement[1].Action "[$case] IAM SQS 權限"
Assert-Equal $queueArn $iam.Statement[1].Resource "[$case] IAM SQS 資源"

# 環境變數
Assert-Equal 'true' $r.Env['SESMAIL_ENABLED'] "[$case] SESMAIL_ENABLED"
Assert-Equal $region $r.Env['AWS_SES_REGION'] "[$case] AWS_SES_REGION"
Assert-Equal 'newsletter' $r.Env['AWS_SES_CONFIGURATION_SET'] "[$case] AWS_SES_CONFIGURATION_SET"
Assert-Equal 'true' $r.Env['SESMAIL_SQS_ENABLED'] "[$case] SESMAIL_SQS_ENABLED"
Assert-Equal $queueUrl $r.Env['SESMAIL_SQS_QUEUE_URL'] "[$case] SESMAIL_SQS_QUEUE_URL"
Assert-Equal $topicArn $r.Env['SESMAIL_SNS_TOPIC_ARNS'] "[$case] SESMAIL_SNS_TOPIC_ARNS"
Assert-Equal 'false' $r.Env['SESMAIL_SNS_ENABLED'] "[$case] SQS 模式不開 webhook"
Assert-EnvInYaml $r $case

}

# ---------------------------------------------------------------------------
# 案例 2：webhook 模式
# ---------------------------------------------------------------------------
Invoke-Case 'Webhook' {
$case = 'Webhook'
$hook = 'https://app.example.com/sesmail/sns'
$r = Invoke-DryRun @{ Mode = 'Webhook'; IamUser = 'app-ses'; WebhookUrl = $hook }
Assert-True ($r.ExitCode -eq 0) "[$case] 結束碼應為 0，實際 $($r.ExitCode)`n$($r.Output)"
$region = 'ap-northeast-1'
$topicArn = "arn:aws:sns:${region}:${Account}:ses-events"
Assert-Command $r.Commands "aws sns subscribe --region $region --topic-arn $topicArn --protocol https --notification-endpoint $hook" $case
Assert-Command $r.Commands "aws sesv2 create-configuration-set --region $region --configuration-set-name ses-mail" $case
Assert-True (-not ($r.Commands -match '^aws sqs ')) "[$case] webhook 模式不應建立 SQS 佇列"
Assert-True ($r.Output -match '403') "[$case] 建訂閱前應檢查 webhook 回 403"
Assert-Equal 1 @($r.Docs['iam-policy'].Statement).Count "[$case] IAM 只需要 SES 權限"
Assert-Equal 'true' $r.Env['SESMAIL_SNS_ENABLED'] "[$case] SESMAIL_SNS_ENABLED"
Assert-Equal 'false' $r.Env['SESMAIL_SQS_ENABLED'] "[$case] SESMAIL_SQS_ENABLED"
Assert-Equal $topicArn $r.Env['SESMAIL_SNS_TOPIC_ARNS'] "[$case] SESMAIL_SNS_TOPIC_ARNS"
Assert-Equal 'ses-mail' $r.Env['AWS_SES_CONFIGURATION_SET'] "[$case] configuration set 預設名稱"
Assert-True (-not $r.Env.Contains('SESMAIL_SQS_QUEUE_URL')) "[$case] 不應輸出 SESMAIL_SQS_QUEUE_URL"
Assert-EnvInYaml $r $case

# webhook 網址必填且必須是 https
$r = Invoke-DryRun @{ Mode = 'Webhook'; IamUser = 'app-ses' }
Assert-True ($r.ExitCode -ne 0) '[Webhook] 沒給 -WebhookUrl 應失敗'
$r = Invoke-DryRun @{ Mode = 'Webhook'; IamUser = 'app-ses'; WebhookUrl = 'http://app.example.com/sesmail/sns' }
Assert-True ($r.ExitCode -ne 0) '[Webhook] -WebhookUrl 不是 https 應失敗'

}

# ---------------------------------------------------------------------------
# 案例 3：不接收事件
# ---------------------------------------------------------------------------
Invoke-Case 'None' {
$case = 'None'
$r = Invoke-DryRun @{ Mode = 'None'; IamUser = 'app-ses' }
Assert-True ($r.ExitCode -eq 0) "[$case] 結束碼應為 0，實際 $($r.ExitCode)`n$($r.Output)"
Assert-True (-not ($r.Commands -match '^aws (sns|sqs) |configuration-set')) "[$case] 不應建立 SNS、SQS 或 configuration set"
Assert-Command $r.Commands "aws iam put-user-policy --user-name app-ses --policy-name ses-mail --policy-document $('file://' + (Join-Path $r.OutDir 'iam-policy.json'))" $case
Assert-Equal 1 @($r.Docs['iam-policy'].Statement).Count "[$case] IAM 只需要 SES 權限"
Assert-True (-not $r.Env.Contains('AWS_SES_CONFIGURATION_SET')) "[$case] 指向不存在的 configuration set 會讓寄信失敗，不應輸出"
Assert-True (-not $r.Env.Contains('SESMAIL_SNS_TOPIC_ARNS')) "[$case] 不應輸出 SESMAIL_SNS_TOPIC_ARNS"
Assert-Equal 'true' $r.Env['SESMAIL_ENABLED'] "[$case] SESMAIL_ENABLED"
Assert-EnvInYaml $r $case

# 沒給模式應失敗（skill 要先問使用者，不替他選）
$r = Invoke-DryRun @{ IamUser = 'app-ses' }
Assert-True ($r.ExitCode -ne 0) '[參數] 沒給 -Mode 應失敗'

}

# ---------------------------------------------------------------------------
# 案例 4：網域身分、MAIL FROM 與信譽警報
# ---------------------------------------------------------------------------
Invoke-Case 'Domain+Alerts' {
$case = 'Domain+Alerts'
$r = Invoke-DryRun @{ Mode = 'None'; Region = 'us-east-1'; IamUser = 'app-ses'; Domain = 'example.com'; MailFromSubdomain = 'mail'; AlertEmail = 'ops@example.com' }
Assert-True ($r.ExitCode -eq 0) "[$case] 結束碼應為 0，實際 $($r.ExitCode)`n$($r.Output)"
$region = 'us-east-1'
$alertsArn = "arn:aws:sns:${region}:${Account}:ses-alerts"
Assert-Command $r.Commands "aws sesv2 create-email-identity --region $region --email-identity example.com" $case
Assert-Command $r.Commands "aws sesv2 put-email-identity-mail-from-attributes --region $region --email-identity example.com --mail-from-domain mail.example.com --behavior-on-mx-failure USE_DEFAULT_VALUE" $case
Assert-True ($r.Output -match '_domainkey\.example\.com') "[$case] 應列出 DKIM CNAME 記錄"
Assert-True ($r.Output -match [regex]::Escape("feedback-smtp.$region.amazonses.com")) "[$case] 應列出 MAIL FROM 的 MX 記錄"
Assert-True ($r.Output -match [regex]::Escape('v=spf1 include:amazonses.com ~all')) "[$case] 應列出 MAIL FROM 的 SPF 記錄"
Assert-Command $r.Commands "aws sns create-topic --region $region --name ses-alerts" $case
Assert-Command $r.Commands "aws sns subscribe --region $region --topic-arn $alertsArn --protocol email --notification-endpoint ops@example.com" $case
$alarmArgs = "--namespace AWS/SES --statistic Maximum --period 3600 --evaluation-periods 1 --comparison-operator GreaterThanThreshold --treat-missing-data notBreaching --alarm-actions $alertsArn --ok-actions $alertsArn"
Assert-Command $r.Commands "aws cloudwatch put-metric-alarm --region $region --alarm-name ses-bounce-rate-high --metric-name Reputation.BounceRate --threshold 0.02 $alarmArgs" $case
Assert-Command $r.Commands "aws cloudwatch put-metric-alarm --region $region --alarm-name ses-complaint-rate-high --metric-name Reputation.ComplaintRate --threshold 0.0005 $alarmArgs" $case

# -MailFromSubdomain 要搭配 -Domain
$r = Invoke-DryRun @{ Mode = 'None'; IamUser = 'app-ses'; MailFromSubdomain = 'mail' }
Assert-True ($r.ExitCode -ne 0) '[參數] 只給 -MailFromSubdomain 沒給 -Domain 應失敗'

}

# ---------------------------------------------------------------------------
# 案例 5：實際執行路徑（PATH 最前面放假的 aws），檢查存在判斷、錯誤處理與回傳值的使用
# ---------------------------------------------------------------------------
Invoke-Case '實際執行' {
    $fakeDir = Join-Path $PSScriptRoot 'fake-aws'
    $savedPath = $env:PATH
    $env:PATH = "$fakeDir$([System.IO.Path]::PathSeparator)$env:PATH"
    try {
        # normal：configuration set 與事件目的地已存在 → 沿用並改用 update；其他資源不存在 → 建立
        $env:FAKE_AWS_LOG = (New-TemporaryFile).FullName
        $env:FAKE_AWS_SCENARIO = 'normal'
        $r = Invoke-DryRun @{ Mode = 'Sqs'; ConfigurationSet = 'newsletter'; IamUser = 'app-ses'; Domain = 'example.com' } -Real
        $calls = @(Get-Content $env:FAKE_AWS_LOG -Encoding utf8)
        Assert-True ($r.ExitCode -eq 0) "[實際執行] 結束碼應為 0，實際 $($r.ExitCode)`n$($r.Output)"
        Assert-True ([bool]($calls -match '^sesv2 update-configuration-set-event-destination ')) '[實際執行] 事件目的地已存在時應改用 update'
        Assert-True (-not ($calls -match '^sesv2 create-configuration-set(-event-destination)? ')) '[實際執行] 已存在的 configuration set 與事件目的地不應再建立'
        Assert-True ([bool]($calls -match '^iam create-user --user-name app-ses ')) '[實際執行] IAM 使用者不存在時應建立'
        Assert-True ([bool]($calls -match '^sqs create-queue --region ap-northeast-1 --queue-name ses-events-dlq ')) '[實際執行] 佇列不存在時應建立'
        Assert-True ([bool]($calls -match ' --output json$')) '[實際執行] 應以 JSON 格式讀取輸出'
        Assert-True ($r.Output -match 'tok1\._domainkey\.example\.com') '[實際執行] 應用 AWS 回傳的 DKIM token 列出 DNS 記錄'
        Assert-Equal 'https://sqs.ap-northeast-1.amazonaws.com/999988887777/ses-events' $r.Env['SESMAIL_SQS_QUEUE_URL'] '[實際執行] 佇列網址應來自 create-queue，帳號來自 sts'
        Remove-Item $env:FAKE_AWS_LOG

        # denied：權限不足要中止，不可當成使用者不存在而去建立
        $env:FAKE_AWS_LOG = (New-TemporaryFile).FullName
        $env:FAKE_AWS_SCENARIO = 'denied'
        $r = Invoke-DryRun @{ Mode = 'None'; IamUser = 'app-ses' } -Real
        $calls = @(Get-Content $env:FAKE_AWS_LOG -Encoding utf8)
        Assert-True ($r.ExitCode -ne 0) '[實際執行] get-user 權限不足時應失敗'
        Assert-True ($r.Output -match 'AccessDenied') '[實際執行] 錯誤訊息應包含 AWS 的錯誤碼'
        Assert-True (-not ($calls -match '^iam create-user ')) '[實際執行] 權限不足時不應嘗試建立使用者'
        Remove-Item $env:FAKE_AWS_LOG
    }
    finally {
        $env:PATH = $savedPath
        Remove-Item Env:FAKE_AWS_LOG, Env:FAKE_AWS_SCENARIO -ErrorAction SilentlyContinue
    }
}

# ---------------------------------------------------------------------------
# 公開 repo：skill 內不得出現特定專案的資料
# ---------------------------------------------------------------------------
Invoke-Case '公開 repo' {
$forbidden = @('springai', 'GoDaddy', '凱文大叔', 'hahow', 'survey-backend')
# AWS 帳號 ID 只允許 AWS 文件的範例帳號；不在這裡列出真實帳號，以免測試本身洩漏
$exampleAccounts = @('111122223333', '123456789012')
if (Test-Path $SkillDir) {
    foreach ($file in Get-ChildItem $SkillDir -Recurse -File) {
        $text = Get-Content $file.FullName -Raw -Encoding utf8
        foreach ($word in $forbidden) {
            Assert-True (-not $text.Contains($word)) "[公開 repo] $($file.Name) 含有特定專案字串：$word"
        }
        foreach ($match in [regex]::Matches($text, '(?<!\d)\d{12}(?!\d)')) {
            Assert-True ($exampleAccounts -contains $match.Value) "[公開 repo] $($file.Name) 含有非範例的 12 位數字（疑似 AWS 帳號 ID）：$($match.Value)"
        }
    }
}

}

# ---------------------------------------------------------------------------
Write-Host ''
if ($script:Failures.Count -eq 0) {
    Write-Host "全部通過：$($script:Passed) 項" -ForegroundColor Green
    exit 0
}
Write-Host "失敗 $($script:Failures.Count) 項，通過 $($script:Passed) 項：" -ForegroundColor Red
$script:Failures | ForEach-Object { Write-Host "- $_" }
exit 1
