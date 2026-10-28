# AWS 設定

AWS 資源由 [`scripts/setup-ses.ps1`](../scripts/setup-ses.ps1) 在 AWS CloudShell 建立。CloudShell 內建 PowerShell 7、AWS CLI v2，並沿用主控台登入者的權限。應用程式的寄信金鑰只有寄信權限，不能拿來建資源。

## 順序

1. 確定事件接收方式（SKILL.md 第一步）。
2. 本機先跑 DryRun，把會建立的資源和政策給使用者看。
3. 使用者在 CloudShell 執行腳本。
4. 到 DNS 服務商新增腳本印出的記錄，等網域驗證通過。
5. 到 IAM 主控台幫應用程式的使用者建立存取金鑰。
6. 申請離開沙箱。
7. 設環境變數、部署，照 `integrate.md` 第 8 節驗證。

Webhook 模式的順序不同：SNS 的 HTTPS 訂閱要靠應用程式自動確認，所以要先用 `SESMAIL_ENABLED=true`、`SESMAIL_SNS_ENABLED=true`、`SESMAIL_SNS_TOPIC_ARNS` 部署，再跑腳本。腳本會先 POST 網址，回 403 才建訂閱（403 代表程式收到了、偽造內容被驗簽拒絕；404 代表請求沒進到程式）。

## 執行腳本

```powershell
# 本機預覽：不呼叫 AWS，指令與 JSON 寫到 -OutDir
pwsh ./setup-ses.ps1 -Mode Sqs -IamUser my-app-ses -Domain example.com -MailFromSubdomain mail -AlertEmail ops@example.com -DryRun
```

CloudShell：主控台右上角先切到要用的區域，開 CloudShell，用 Actions → Upload file 上傳 `setup-ses.ps1`，或直接下載：

```powershell
pwsh
Invoke-WebRequest https://raw.githubusercontent.com/kevintsai1202/ses-mail-spring-boot-starter/main/skills/ses-mail-setup/scripts/setup-ses.ps1 -OutFile setup-ses.ps1
./setup-ses.ps1 -Mode Sqs -IamUser my-app-ses -Domain example.com -MailFromSubdomain mail -AlertEmail ops@example.com
```

可以重複執行：已存在的資源沿用，政策與屬性會改回腳本的設定。

| 參數 | 說明 |
| --- | --- |
| `-Mode` | `Sqs`、`Webhook`、`None`，必填 |
| `-IamUser` | 應用程式寄信用的 IAM 使用者，不存在會建立，必填 |
| `-Region` | 預設 `ap-northeast-1`，要與 `sesmail.region` 一致 |
| `-ConfigurationSet` | 預設 `ses-mail` |
| `-TopicName`、`-QueueName` | 預設都是 `ses-events`；死信佇列為 `<QueueName>-dlq` |
| `-IamPolicyName` | inline policy 名稱，預設 `ses-mail`。使用者已有自己的政策名稱時沿用，不然會多一份 |
| `-Domain`、`-MailFromSubdomain` | 選填，建立網域身分與自訂 MAIL FROM |
| `-WebhookUrl` | Webhook 模式必填，https 網址，例如 `https://app.example.com/sesmail/sns` |
| `-AlertEmail` | 選填，建立信譽警報 |
| `-DryRun`、`-AccountId`、`-OutDir` | 預覽用 |

## 腳本建立的資源

| 資源 | Sqs | Webhook | None |
| --- | --- | --- | --- |
| SNS topic `ses-events`，存取政策允許這個 configuration set 的 SES 發佈 | ✓ | ✓ | |
| SQS 死信佇列 `ses-events-dlq`（保留 14 天） | ✓ | | |
| SQS 佇列 `ses-events`（可見逾時 60 秒，處理失敗 5 次轉死信佇列，佇列政策只允許該 topic 發送） | ✓ | | |
| 訂閱：topic → 佇列，或 topic → HTTPS 網址（都不開 raw message delivery，保留 SNS 簽章） | ✓ | ✓ | |
| configuration set 與事件目的地（Bounce、Complaint、Delivery → topic） | ✓ | ✓ | |
| IAM 使用者與 inline policy：`ses:SendEmail`、`ses:GetAccount`；Sqs 模式加上該佇列的 `sqs:ReceiveMessage`、`sqs:DeleteMessage`、`sqs:ChangeMessageVisibility` | ✓ | ✓ | ✓ |
| 網域身分（有 `-Domain`）、信譽警報（有 `-AlertEmail`） | 選填 | 選填 | 選填 |

用了 configuration set 就**不要**再到 SES 身分頁設定 Feedback notifications，否則同一封退信會收到兩次。

## 網域驗證

腳本建立 Easy DKIM 身分後會印出 DNS 記錄：

- 3 筆 DKIM `CNAME`：`<token>._domainkey.<網域>` → `<token>.dkim.amazonses.com`。
- 有 `-MailFromSubdomain` 時，MAIL FROM 網域的 `MX`：`10 feedback-smtp.<區域>.amazonses.com`，以及 `TXT`：`v=spf1 include:amazonses.com ~all`。
- SPF 只檢查 MAIL FROM 網域，根網域已有的 `v=spf1` 不用改。一個網域只能有一筆 `v=spf1`，不要在根網域再加第二筆。
- DMARC：`_dmarc.<網域>` 已有就沿用，DKIM 對齊根網域就會通過；沒有的話可以先加 `v=DMARC1; p=none`。
- DNS 不在 Route 53 時，多數 DNS 服務商的「名稱」欄位只填子網域部分，不含根網域。

等主控台的身分狀態變成 Verified、DKIM 與 MAIL FROM 都是 Successful，通常幾分鐘到一小時。

## IAM 存取金鑰

IAM 主控台 → Users → 應用程式的使用者 → Security credentials → Create access key。secret 只顯示一次，直接貼到部署平台的環境變數 `AWS_SES_ACCESS_KEY_ID`、`AWS_SES_SECRET_ACCESS_KEY`。不要貼進對話、不要進版控；要更換時先建新金鑰、部署、再停用舊的。

## 離開沙箱

沙箱只能寄給已驗證的信箱，每天 200 封、每秒 1 封。SES 主控台 → Account dashboard → Request production access：

- Mail type：電子報選 Marketing，只有交易信選 Transactional。
- Website URL：讓審核者看得到訂閱方式的網址。
- Use case description：照實填寫下面的範本，`<...>` 換成實際內容。

```text
We send <content, e.g. a weekly technical newsletter in Traditional Chinese> to about <N> subscribers, <frequency>, plus transactional emails (<e.g. login links, subscription confirmation>).
All recipients opted in through <how, e.g. double opt-in on our website>. Every newsletter contains a one-click unsubscribe link, and unsubscribes are applied immediately.
Bounces and complaints are delivered to our application through an SES configuration set and Amazon SNS; permanent bounces and complaints are unsubscribed automatically. We monitor bounce and complaint rates with CloudWatch alarms.
We expect fewer than <N> emails per month.
```

通常一天內回覆。被拒絕或被要求補資料時，到 Support Center 找到這個案件，照 AWS 的回覆補充說明：名單怎麼來、退訂怎麼做、退信怎麼處理、寄送頻率與量。結果可以用 `scripts/ses-status.py` 查（「審核狀態」欄位）。

## 信譽警報

有 `-AlertEmail` 時建立 `ses-alerts` topic、email 訂閱和兩個 CloudWatch 警報（每小時最大值）：

| 警報 | 指標 | 門檻 | AWS 介入門檻 |
| --- | --- | --- | --- |
| `ses-bounce-rate-high` | `AWS/SES Reputation.BounceRate` | > 0.02（2%） | 5% 起審查帳號，10% 可能暫停寄送 |
| `ses-complaint-rate-high` | `AWS/SES Reputation.ComplaintRate` | > 0.0005（0.05%） | 0.1% 起審查帳號，0.5% 可能暫停寄送 |

- 指標值是比例，0.05 代表 5%。
- 缺資料時視為未超標（`notBreaching`）。AWS 文件建議用 `ignore`（維持原狀態）。這裡選 `notBreaching`，是因為還沒寄信時沒有資料，維持原狀態會一直停在 INSUFFICIENT_DATA。
- 訂閱信箱要點確認連結，通知才寄得出來。
