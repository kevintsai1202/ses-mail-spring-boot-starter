<#
  測試用的假 aws CLI：把每次呼叫的參數記到 $env:FAKE_AWS_LOG，依指令回傳固定的 JSON 或 AWS 格式的錯誤。
  情境由 $env:FAKE_AWS_SCENARIO 指定：
    normal  configuration set 與事件目的地已存在；佇列、IAM 使用者、網域身分不存在
    denied  iam get-user 回 AccessDenied（權限不足不可被當成「不存在」）
  由同資料夾的 aws.cmd 轉呼叫；setup-ses.tests.ps1 會把這個資料夾放到 PATH 最前面。
#>
Add-Content -Path $env:FAKE_AWS_LOG -Value ($args -join ' ') -Encoding utf8NoBOM

<# 以 AWS CLI 的格式寫錯誤到 stderr，結束碼 254 #>
function Fail([string] $Code, [string] $Operation) {
    [Console]::Error.WriteLine("An error occurred ($Code) when calling the $Operation operation: fake")
    exit 254
}

<# 輸出 JSON，結束碼 0 #>
function Ok($Object) {
    ConvertTo-Json -InputObject $Object -Depth 5 -Compress
    exit 0
}

switch ("$($args[0]) $($args[1])") {
    'sts get-caller-identity' { Ok @{ Account = '999988887777' } }
    'sqs get-queue-url' { Fail 'AWS.SimpleQueueService.NonExistentQueue' 'GetQueueUrl' }
    'sqs create-queue' {
        $name = $args[[array]::IndexOf($args, '--queue-name') + 1]
        Ok @{ QueueUrl = "https://sqs.ap-northeast-1.amazonaws.com/999988887777/$name" }
    }
    'sesv2 get-configuration-set' { Ok @{ ConfigurationSetName = 'newsletter' } }
    'sesv2 get-configuration-set-event-destinations' { Ok @{ EventDestinations = @(@{ Name = 'ses-events' }) } }
    'sesv2 get-email-identity' { Fail 'NotFoundException' 'GetEmailIdentity' }
    'sesv2 create-email-identity' { Ok @{ DkimAttributes = @{ Tokens = @('tok1', 'tok2', 'tok3') } } }
    'iam get-user' {
        if ($env:FAKE_AWS_SCENARIO -eq 'denied') { Fail 'AccessDenied' 'GetUser' }
        Fail 'NoSuchEntity' 'GetUser'
    }
    default { Ok @{} }
}
