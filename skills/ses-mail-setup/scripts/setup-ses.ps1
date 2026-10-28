#Requires -Version 7.2
<#
.SYNOPSIS
  建立 ses-mail-spring-boot-starter 需要的 AWS 資源。

.DESCRIPTION
  依 -Mode 建立退信／檢舉／送達事件的接收管線，並可選擇建立網域身分與信譽警報：
    Sqs      SES configuration set → SNS topic → SQS 佇列（含死信佇列）→ 應用程式拉取（建議）
    Webhook  SES configuration set → SNS topic → HTTPS 訂閱 → 應用程式的 /sesmail/sns
    None     不接收事件，只寄信
  最後印出要設的環境變數，對應 assets/application-sesmail.yml 的佔位。

  在 AWS CloudShell 的 pwsh 執行，需要能建立 SES、SNS、SQS、IAM、CloudWatch 資源的權限。
  可重複執行：已存在的資源沿用，政策與屬性改回本腳本的設定。
  IAM 存取金鑰不由本腳本建立（secret 只顯示一次），請到 IAM 主控台建立。

  -DryRun 只把 aws 指令記到 <OutDir>/commands.txt、JSON 文件寫到 <OutDir>，不呼叫 AWS，
  本機沒有 AWS CLI 也能跑；用來在執行前檢查會做哪些事。

.EXAMPLE
  pwsh ./setup-ses.ps1 -Mode Sqs -IamUser my-app-ses -Domain example.com -MailFromSubdomain mail -AlertEmail ops@example.com

.EXAMPLE
  pwsh ./setup-ses.ps1 -Mode Webhook -IamUser my-app-ses -WebhookUrl https://app.example.com/sesmail/sns -DryRun
#>
[CmdletBinding()]
param(
    # 事件接收方式；由使用者決定，不給預設值
    [Parameter(Mandatory)] [ValidateSet('Sqs', 'Webhook', 'None')] [string] $Mode,
    # 應用程式寄信用的 IAM 使用者；不存在會建立
    [Parameter(Mandatory)] [string] $IamUser,
    # SES、SNS、SQS、警報所在區域，要與應用程式的 sesmail.region 一致
    [string] $Region = 'ap-northeast-1',
    # 發佈事件的 configuration set
    [string] $ConfigurationSet = 'ses-mail',
    # 事件 topic 名稱，也當作事件目的地名稱
    [string] $TopicName = 'ses-events',
    # 事件佇列名稱；死信佇列為 <QueueName>-dlq
    [string] $QueueName = 'ses-events',
    # 寫進 IAM 使用者的 inline policy 名稱
    [string] $IamPolicyName = 'ses-mail',
    # 要驗證的寄件網域（選填）
    [string] $Domain,
    # 自訂 MAIL FROM 子網域，例如 mail → mail.<Domain>（選填，需搭配 -Domain）
    [string] $MailFromSubdomain,
    # webhook 模式的公開網址，例如 https://app.example.com/sesmail/sns
    [string] $WebhookUrl,
    # 信譽警報通知信箱（選填）；有給才建立警報
    [string] $AlertEmail,
    # 只記錄指令與文件，不呼叫 AWS
    [switch] $DryRun,
    # DryRun 用的帳號 ID；實際執行時由 sts 取得
    [string] $AccountId = '123456789012',
    # 指令紀錄、JSON 文件與環境變數的輸出資料夾；預設在暫存資料夾
    [string] $OutDir
)

$ErrorActionPreference = 'Stop'

# ---- 參數檢查 --------------------------------------------------------------
if ($Mode -eq 'Webhook') {
    if (-not $WebhookUrl) { throw '-Mode Webhook 需要 -WebhookUrl（應用程式 /sesmail/sns 的公開網址）' }
    if ($WebhookUrl -notmatch '^https://') { throw "-WebhookUrl 必須是 https 網址：$WebhookUrl" }
}
if ($MailFromSubdomain -and -not $Domain) { throw '-MailFromSubdomain 要搭配 -Domain' }

# 輸出資料夾：commands.txt（每個 aws 指令一行）、*.json（政策與屬性）、env.txt（環境變數）
if (-not $OutDir) {
    $OutDir = Join-Path ([System.IO.Path]::GetTempPath()) ('ses-setup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
$OutDir = [System.IO.Path]::GetFullPath($OutDir)
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
$CommandsFile = Join-Path $OutDir 'commands.txt'
Set-Content -Path $CommandsFile -Value $null -Encoding utf8NoBOM

# 步驟編號，給 Write-Step 用
$script:StepNo = 0

# ---- 共用函式 --------------------------------------------------------------

<# 印出步驟標題 #>
function Write-Step([string] $Title) {
    $script:StepNo++
    Write-Host ''
    Write-Host "== $($script:StepNo). $Title"
}

<# 把物件寫成 JSON 文件，回傳給 aws CLI 用的 file:// 參數 #>
function Write-JsonDoc([string] $Name, $Object) {
    $path = Join-Path $OutDir "$Name.json"
    ConvertTo-Json -InputObject $Object -Depth 10 | Set-Content -Path $path -Encoding utf8NoBOM
    'file://' + $path
}

<#
  執行一個 aws 指令，回傳解析後的 JSON 輸出。指令一律記到 commands.txt。
  除了 IAM（全域服務）之外都會帶 --region。
  -Probe：唯讀的存在檢查，資源不存在時回傳 $null；權限不足等其他錯誤照樣中止，不會被當成「不存在」。
  -DryRun 時不執行，回傳 $null，呼叫端改用推算出來的 ARN、網址。
#>
function Invoke-Aws {
    param(
        [Parameter(Mandatory, Position = 0)] [string] $Service,
        [Parameter(Mandatory, Position = 1)] [string] $Operation,
        [Parameter(Position = 2)] [string[]] $Arguments = @(),
        [switch] $Probe
    )
    $argList = @($Service, $Operation)
    if ($Service -ne 'iam') { $argList += @('--region', $Region) }
    $argList += $Arguments
    $line = 'aws ' + ($argList -join ' ')
    Add-Content -Path $CommandsFile -Value $line -Encoding utf8NoBOM
    Write-Host "  $line"
    if ($DryRun) { return $null }

    $errFile = New-TemporaryFile
    try {
        $stdout = & aws @argList --output json 2> $errFile.FullName
        $exitCode = $LASTEXITCODE
        $stderr = (Get-Content $errFile.FullName -Raw) ?? ''
    }
    finally {
        Remove-Item $errFile.FullName -ErrorAction SilentlyContinue
    }
    if ($exitCode -ne 0) {
        if ($Probe -and $stderr -match '\((NotFoundException|NoSuchEntity|QueueDoesNotExist|AWS\.SimpleQueueService\.NonExistentQueue)\)') {
            return $null
        }
        throw "aws $Service $Operation 失敗（結束碼 $exitCode）：$($stderr.Trim())"
    }
    $text = ($stdout -join "`n").Trim()
    if ($text) { return $text | ConvertFrom-Json }
    return [pscustomobject]@{}
}

<# 確保 SQS 佇列存在：已存在就沿用（不比對屬性），回傳佇列網址 #>
function Get-OrCreateQueue([string] $Name, [string] $ExpectedUrl) {
    $found = Invoke-Aws sqs get-queue-url @('--queue-name', $Name) -Probe
    if ($found) {
        Write-Host "  佇列 $Name 已存在，沿用"
        return $found.QueueUrl
    }
    $created = Invoke-Aws sqs create-queue @('--queue-name', $Name)
    if ($created) { return $created.QueueUrl }
    return $ExpectedUrl
}

# ---- 帳號與資源名稱 --------------------------------------------------------
Write-Host "模式：$Mode　區域：$Region　輸出：$OutDir$(if ($DryRun) { '　（DryRun，不呼叫 AWS）' })"

$caller = Invoke-Aws sts get-caller-identity
# AWS 帳號 ID；DryRun 時用 -AccountId
$Account = if ($caller) { $caller.Account } else { $AccountId }

# ARN 與佇列網址的格式固定，直接推算，DryRun 也能產生完整指令
$TopicArn = "arn:aws:sns:${Region}:${Account}:$TopicName"
$DlqName = "$QueueName-dlq"
$QueueArn = "arn:aws:sqs:${Region}:${Account}:$QueueName"
$DlqArn = "arn:aws:sqs:${Region}:${Account}:$DlqName"
$QueueUrl = "https://sqs.$Region.amazonaws.com/$Account/$QueueName"
$DlqUrl = "https://sqs.$Region.amazonaws.com/$Account/$DlqName"
$AlertsTopicName = 'ses-alerts'
$AlertsArn = "arn:aws:sns:${Region}:${Account}:$AlertsTopicName"

# ---- 網域身分 --------------------------------------------------------------
if ($Domain) {
    Write-Step "網域身分 $Domain（Easy DKIM）"
    $identity = Invoke-Aws sesv2 get-email-identity @('--email-identity', $Domain) -Probe
    if ($identity) {
        Write-Host "  $Domain 已存在，沿用"
    }
    else {
        $identity = Invoke-Aws sesv2 create-email-identity @('--email-identity', $Domain)
    }
    # DKIM token；DryRun 時還不知道，用佔位顯示
    $tokens = @('<token1>', '<token2>', '<token3>')
    if ($identity -and $identity.DkimAttributes -and $identity.DkimAttributes.Tokens) { $tokens = @($identity.DkimAttributes.Tokens) }

    $records = [System.Collections.Generic.List[string]]::new()
    foreach ($token in $tokens) { $records.Add("CNAME  $token._domainkey.$Domain  →  $token.dkim.amazonses.com") }

    if ($MailFromSubdomain) {
        # MAIL FROM 網域；MX 失敗時退回 SES 預設的 MAIL FROM，信照樣寄得出去
        $mailFrom = "$MailFromSubdomain.$Domain"
        Invoke-Aws sesv2 put-email-identity-mail-from-attributes @('--email-identity', $Domain, '--mail-from-domain', $mailFrom, '--behavior-on-mx-failure', 'USE_DEFAULT_VALUE') | Out-Null
        $records.Add("MX     $mailFrom  →  10 feedback-smtp.$Region.amazonses.com")
        $records.Add("TXT    $mailFrom  →  `"v=spf1 include:amazonses.com ~all`"")
    }
    Write-Host ''
    Write-Host '  到 DNS 服務商新增以下記錄（Route 53 以外的 DNS 服務商，名稱欄位通常只填子網域部分）：'
    $records | ForEach-Object { Write-Host "    $_" }
    Write-Host "  DMARC：_dmarc.$Domain 已有 TXT 記錄就沿用；沒有的話可先加 `"v=DMARC1; p=none`"。"
    Write-Host '  SPF 只檢查 MAIL FROM 網域，根網域已有的 v=spf1 記錄不用改，也不要再加第二筆。'
}

# ---- 事件管線 --------------------------------------------------------------
if ($Mode -ne 'None') {
    Write-Step "SNS topic $TopicName（允許 configuration set $ConfigurationSet 的 SES 發佈）"
    Invoke-Aws sns create-topic @('--name', $TopicName) | Out-Null
    # topic 存取政策：保留 AWS 預設的帳號擁有者權限，加上 SES 發佈權限
    $topicPolicy = [ordered]@{
        Version   = '2012-10-17'
        Statement = @(
            [ordered]@{
                Sid       = 'AccountOwnerAccess'
                Effect    = 'Allow'
                Principal = [ordered]@{ AWS = '*' }
                Action    = @('SNS:GetTopicAttributes', 'SNS:SetTopicAttributes', 'SNS:AddPermission', 'SNS:RemovePermission',
                    'SNS:DeleteTopic', 'SNS:Subscribe', 'SNS:ListSubscriptionsByTopic', 'SNS:Publish')
                Resource  = $TopicArn
                Condition = [ordered]@{ StringEquals = [ordered]@{ 'AWS:SourceOwner' = $Account } }
            }
            [ordered]@{
                Sid       = 'AllowSesPublish'
                Effect    = 'Allow'
                Principal = [ordered]@{ Service = 'ses.amazonaws.com' }
                Action    = 'sns:Publish'
                Resource  = $TopicArn
                Condition = [ordered]@{
                    StringEquals = [ordered]@{
                        'AWS:SourceAccount' = $Account
                        'AWS:SourceArn'     = "arn:aws:ses:${Region}:${Account}:configuration-set/$ConfigurationSet"
                    }
                }
            }
        )
    }
    Invoke-Aws sns set-topic-attributes @('--topic-arn', $TopicArn, '--attribute-name', 'Policy', '--attribute-value', (Write-JsonDoc 'topic-policy' $topicPolicy)) | Out-Null

    if ($Mode -eq 'Sqs') {
        Write-Step "死信佇列 $DlqName（保留 14 天）"
        $dlqUrlActual = Get-OrCreateQueue $DlqName $DlqUrl
        Invoke-Aws sqs set-queue-attributes @('--queue-url', $dlqUrlActual, '--attributes', (Write-JsonDoc 'dlq-attributes' ([ordered]@{ MessageRetentionPeriod = '1209600' }))) | Out-Null

        Write-Step "事件佇列 $QueueName（可見逾時 60 秒，處理失敗 5 次轉死信佇列）"
        $QueueUrl = Get-OrCreateQueue $QueueName $QueueUrl
        # 佇列政策：只允許事件 topic 發送進來
        $queuePolicy = [ordered]@{
            Version   = '2012-10-17'
            Statement = @(
                [ordered]@{
                    Sid       = 'AllowSesEventsTopic'
                    Effect    = 'Allow'
                    Principal = [ordered]@{ Service = 'sns.amazonaws.com' }
                    Action    = 'SQS:SendMessage'
                    Resource  = $QueueArn
                    Condition = [ordered]@{ ArnEquals = [ordered]@{ 'aws:SourceArn' = $TopicArn } }
                }
            )
        }
        $redrive = [ordered]@{ deadLetterTargetArn = $DlqArn; maxReceiveCount = '5' }
        $queueAttributes = [ordered]@{
            VisibilityTimeout = '60'
            Policy            = ConvertTo-Json -InputObject $queuePolicy -Depth 10 -Compress
            RedrivePolicy     = ConvertTo-Json -InputObject $redrive -Compress
        }
        Invoke-Aws sqs set-queue-attributes @('--queue-url', $QueueUrl, '--attributes', (Write-JsonDoc 'queue-attributes' $queueAttributes)) | Out-Null

        Write-Step '佇列訂閱 topic（不開 raw message delivery，保留 SNS 簽章）'
        Invoke-Aws sns subscribe @('--topic-arn', $TopicArn, '--protocol', 'sqs', '--notification-endpoint', $QueueArn) | Out-Null
    }

    if ($Mode -eq 'Webhook') {
        Write-Step "檢查 webhook：POST $WebhookUrl 應回 403（程式收到了、偽造內容被驗簽拒絕）"
        if ($DryRun) {
            Write-Host '  （DryRun 略過）'
        }
        else {
            $status = (Invoke-WebRequest -Uri $WebhookUrl -Method Post -Body '{}' -SkipHttpErrorCheck -TimeoutSec 15).StatusCode
            if ($status -ne 403) {
                Write-Host "  回應 $status。SNS 訂閱要靠 webhook 自動確認，所以應用程式要先上線："
                Write-Host '    SESMAIL_ENABLED=true'
                Write-Host '    SESMAIL_SNS_ENABLED=true'
                Write-Host "    SESMAIL_SNS_TOPIC_ARNS=$TopicArn"
                Write-Host '  設好並部署後再重跑本腳本。回 404 代表請求沒進到程式（路徑、Host 白名單或登入攔截）。'
                throw "webhook 回 $status，不是 403"
            }
            Write-Host '  回 403，webhook 已上線'
        }
        Write-Step "HTTPS 訂閱 $WebhookUrl（不開 raw message delivery）"
        Invoke-Aws sns subscribe @('--topic-arn', $TopicArn, '--protocol', 'https', '--notification-endpoint', $WebhookUrl) | Out-Null
    }

    Write-Step "configuration set $ConfigurationSet 與事件目的地（Bounce、Complaint、Delivery → $TopicName）"
    $configSet = Invoke-Aws sesv2 get-configuration-set @('--configuration-set-name', $ConfigurationSet) -Probe
    if ($configSet) {
        Write-Host "  $ConfigurationSet 已存在，沿用"
    }
    else {
        Invoke-Aws sesv2 create-configuration-set @('--configuration-set-name', $ConfigurationSet) | Out-Null
    }
    $destination = [ordered]@{
        Enabled            = $true
        MatchingEventTypes = @('BOUNCE', 'COMPLAINT', 'DELIVERY')
        SnsDestination     = [ordered]@{ TopicArn = $TopicArn }
    }
    $destinationDoc = Write-JsonDoc 'event-destination' $destination
    $existing = Invoke-Aws sesv2 get-configuration-set-event-destinations @('--configuration-set-name', $ConfigurationSet) -Probe
    $existingNames = @(if ($existing -and $existing.EventDestinations) { $existing.EventDestinations | ForEach-Object { $_.Name } })
    $operation = if ($existingNames -contains $TopicName) { 'update-configuration-set-event-destination' } else { 'create-configuration-set-event-destination' }
    Invoke-Aws sesv2 $operation @('--configuration-set-name', $ConfigurationSet, '--event-destination-name', $TopicName, '--event-destination', $destinationDoc) | Out-Null
    Write-Host '  不要再到 SES 身分頁設定 Feedback notifications，否則同一封退信會收到兩次。'
}

# ---- IAM -------------------------------------------------------------------
Write-Step "IAM 使用者 $IamUser 與 inline policy $IamPolicyName"
$user = Invoke-Aws iam get-user @('--user-name', $IamUser) -Probe
if ($user) {
    Write-Host "  $IamUser 已存在，沿用"
}
else {
    Invoke-Aws iam create-user @('--user-name', $IamUser) | Out-Null
}
# 最小權限：寄信、查額度；SQS 模式再加讀取事件佇列
$statements = @(
    [ordered]@{ Effect = 'Allow'; Action = @('ses:SendEmail', 'ses:GetAccount'); Resource = '*' }
)
if ($Mode -eq 'Sqs') {
    $statements += [ordered]@{ Effect = 'Allow'; Action = @('sqs:ReceiveMessage', 'sqs:DeleteMessage', 'sqs:ChangeMessageVisibility'); Resource = $QueueArn }
}
$iamDoc = Write-JsonDoc 'iam-policy' ([ordered]@{ Version = '2012-10-17'; Statement = $statements })
Invoke-Aws iam put-user-policy @('--user-name', $IamUser, '--policy-name', $IamPolicyName, '--policy-document', $iamDoc) | Out-Null
Write-Host "  存取金鑰請到 IAM 主控台 → Users → $IamUser → Security credentials → Create access key 建立（secret 只顯示一次）。"

# ---- 信譽警報 --------------------------------------------------------------
if ($AlertEmail) {
    Write-Step "信譽警報（退信率 > 2%、檢舉率 > 0.05%，通知寄到 $AlertEmail）"
    Invoke-Aws sns create-topic @('--name', $AlertsTopicName) | Out-Null
    Invoke-Aws sns subscribe @('--topic-arn', $AlertsArn, '--protocol', 'email', '--notification-endpoint', $AlertEmail) | Out-Null
    # 指標值是比例（0.05 = 5%）；AWS 在退信率 5%、檢舉率 0.1% 起會審查帳號，提早警告
    $alarms = @(
        @{ Name = 'ses-bounce-rate-high'; Metric = 'Reputation.BounceRate'; Threshold = '0.02' }
        @{ Name = 'ses-complaint-rate-high'; Metric = 'Reputation.ComplaintRate'; Threshold = '0.0005' }
    )
    foreach ($alarm in $alarms) {
        Invoke-Aws cloudwatch put-metric-alarm @(
            '--alarm-name', $alarm.Name, '--metric-name', $alarm.Metric, '--threshold', $alarm.Threshold,
            '--namespace', 'AWS/SES', '--statistic', 'Maximum', '--period', '3600', '--evaluation-periods', '1',
            '--comparison-operator', 'GreaterThanThreshold', '--treat-missing-data', 'notBreaching',
            '--alarm-actions', $AlertsArn, '--ok-actions', $AlertsArn) | Out-Null
    }
    Write-Host '  到信箱點 SNS 寄來的確認連結，警報通知才寄得出來。還沒寄過信時警報狀態是 INSUFFICIENT_DATA，屬正常。'
}

# ---- 環境變數 --------------------------------------------------------------
# 名稱對應 assets/application-sesmail.yml 的 ${...} 佔位
$envVars = [ordered]@{
    SESMAIL_ENABLED           = 'true'
    AWS_SES_REGION            = $Region
    AWS_SES_ACCESS_KEY_ID     = "<$IamUser 的存取金鑰 ID>"
    AWS_SES_SECRET_ACCESS_KEY = '<存取金鑰 secret>'
}
if ($Mode -eq 'Sqs') {
    $envVars['AWS_SES_CONFIGURATION_SET'] = $ConfigurationSet
    $envVars['SESMAIL_SQS_ENABLED'] = 'true'
    $envVars['SESMAIL_SQS_QUEUE_URL'] = $QueueUrl
    $envVars['SESMAIL_SNS_TOPIC_ARNS'] = $TopicArn
    $envVars['SESMAIL_SNS_ENABLED'] = 'false'
}
elseif ($Mode -eq 'Webhook') {
    $envVars['AWS_SES_CONFIGURATION_SET'] = $ConfigurationSet
    $envVars['SESMAIL_SNS_ENABLED'] = 'true'
    $envVars['SESMAIL_SNS_TOPIC_ARNS'] = $TopicArn
    $envVars['SESMAIL_SQS_ENABLED'] = 'false'
}
$envLines = @($envVars.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" })
Set-Content -Path (Join-Path $OutDir 'env.txt') -Value $envLines -Encoding utf8NoBOM

Write-Host ''
Write-Host "== 完成。在部署平台設定以下環境變數（也存在 $(Join-Path $OutDir 'env.txt')）："
$envLines | ForEach-Object { Write-Host "  $_" }
if ($Mode -ne 'None') {
    Write-Host '  AWS_SES_CONFIGURATION_SET 要在本腳本跑完後才設；指向不存在的 configuration set 會讓寄信失敗。'
}
