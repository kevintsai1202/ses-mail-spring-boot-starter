---
name: ses-mail-setup
description: 用 ses-mail-spring-boot-starter 讓 Spring Boot 透過 Amazon SES 寄信時使用：加依賴、yaml、建表、寄信與事件 Listener；在 CloudShell 建立 AWS 資源（網域驗證 DKIM／MAIL FROM、configuration set、SNS、SQS 與死信佇列、IAM、CloudWatch 信譽警報）；申請離開沙箱；上線後查額度、處理死信佇列與卡在 SENDING 的信。Use when integrating ses-mail-spring-boot-starter, setting up Amazon SES with SNS/SQS bounce and complaint events, leaving the SES sandbox, or operating the SES mail queue.
---

# ses-mail-setup

## 第一步：先問事件接收方式

動手開發或設定之前，一定先問使用者要不要接收退信、檢舉、送達事件，以及用哪種方式。不要替使用者決定，也不要沿用範例的預設值。

| 選項 | 適合 | 要建的 AWS 資源 | 應用程式開關 |
| --- | --- | --- | --- |
| Sqs（建議） | 正式電子報、退訂不能漏；停機、重新部署、處理失敗都會重試 | SNS topic、SQS 佇列與死信佇列 | `sesmail.sqs.enabled=true` |
| Webhook | 寄量小、想少管一個服務；要有公開 HTTPS 網址，停機期間的事件會遺失 | SNS topic、HTTPS 訂閱 | `sesmail.sns.enabled=true` |
| None | 只寄信，退信交給 SES 帳號層級停寄名單擋 | 不建 | 兩個都 `false` |

兩個開關同時開會收到重複事件，只能選一個。這個答案決定 `setup-ses.ps1` 的 `-Mode`、yaml 的開關，以及要不要寫 `SesEventListener`。

同一輪一併確認：SES 區域、寄件網域與寄件地址、應用程式部署在哪裡（環境變數要設在哪）、資料表是否用 Flyway 管、是否要信譽警報與通知信箱。

## 依狀況讀對應文件

| 使用者的狀況 | 讀 |
| --- | --- |
| 專案還沒加 starter，或要改設定、建表、寫寄信與事件程式 | [references/integrate.md](references/integrate.md) |
| 要建 AWS 資源、驗證網域、建金鑰、申請離開沙箱、設警報 | [references/aws-setup.md](references/aws-setup.md) |
| 已上線：查額度與沙箱狀態、死信佇列有訊息、信卡在 SENDING、停用 SES、換接收方式 | [references/operations.md](references/operations.md) |

從零導入的順序：integrate 第 1～3 節（依賴、設定、建表）→ aws-setup（DryRun → CloudShell 執行 → DNS → 金鑰 → 申請離開沙箱）→ 設環境變數並部署 → integrate 第 4～8 節（寄信、Listener、驗證）。Webhook 模式要先部署再跑腳本，見 aws-setup 的「順序」。

## 檔案

- `scripts/setup-ses.ps1`：在 AWS CloudShell 的 pwsh 執行，建立 AWS 資源並印出環境變數；`-DryRun` 只預覽，不呼叫 AWS。
- `scripts/ses-status.py`：本機查帳號狀態（唯讀），只用 Python 標準庫。
- `assets/application-sesmail.yml`：設定範本；`setup-ses.ps1` 印出的環境變數在這裡都有對應佔位。

## 規則

- 建資源前先在本機跑 `-DryRun`，把指令與政策給使用者看，同意後由使用者在 CloudShell 執行。應用程式的金鑰只有寄信權限，不要拿來建資源。
- 不要在對話中索取、印出或寫進檔案 secret access key；請使用者直接貼到部署平台。
- `AWS_SES_CONFIGURATION_SET` 要等 configuration set 建好才設；指向不存在的 configuration set 會讓寄信失敗。
- 用了 configuration set 就不要再設 SES 身分層級的 Feedback notifications，否則每封退信會收到兩次。
- 事件 listener 要冪等，SNS、SQS 都可能重送。
