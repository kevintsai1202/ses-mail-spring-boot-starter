# 上線後的維運

## 帳號狀態與額度

本機用應用程式的寄信金鑰查詢（唯讀，只需要 `ses:GetAccount`）：

```powershell
python scripts/ses-status.py --env-file .env
```

會印出是否已離開沙箱、寄送是否啟用、帳號狀態、24 小時上限與已寄數、每秒上限，以及申請離開沙箱的審核狀態。Windows 終端機顯示亂碼時改用 `python -X utf8`。

## 死信佇列有訊息（Sqs 模式）

代表某些事件連續處理失敗 5 次，通常是 listener 拋例外或資料庫錯誤。訊息保留 14 天，過期就沒了。

1. 先看應用程式 log 找出錯誤原因並修好。
2. 在 CloudShell 看還有幾則：

   ```powershell
   aws sqs get-queue-attributes --queue-url <死信佇列網址> --attribute-names ApproximateNumberOfMessages
   ```

3. 把訊息送回原佇列重新處理：SQS 主控台選死信佇列 → Start DLQ redrive，或：

   ```powershell
   aws sqs start-message-move-task --source-arn <死信佇列 ARN>
   ```

應用程式的金鑰沒有 `sqs:GetQueueAttributes` 權限，這些操作要在 CloudShell 或主控台做。

## 卡在 SENDING 的信

派送器認領一封信後，呼叫 SES 前後程序被重啟，那一列會停在 `SENDING`。starter **不會自動重寄**：無法得知 SES 收到了沒，重複寄出比漏寄一封更糟。log 每 30 分鐘會出現一次警告。

```powershell
psql $env:DATABASE_URL -c "SELECT id, recipient, subject, updated_at FROM scheduled_mail WHERE status = 'SENDING' AND updated_at < now() - interval '10 minutes';"
```

逐筆判斷：

- 確定讀者收到了（或寧可漏寄）：`UPDATE scheduled_mail SET status = 'SENT', updated_at = now() WHERE id = <id>;`
- 確定沒收到、要補寄：`UPDATE scheduled_mail SET status = 'PENDING', next_attempt_at = now(), updated_at = now() WHERE id = <id>;`

## 停用 SES

1. `SESMAIL_ENABLED=false` 並重新部署。
2. 佇列裡還沒送出的信不會再被派送。先看有多少，再決定要不要取消：

   ```powershell
   psql $env:DATABASE_URL -c "SELECT status, count(*) FROM scheduled_mail GROUP BY status;"
   # 確定不寄了才執行：
   psql $env:DATABASE_URL -c "UPDATE scheduled_mail SET status = 'CANCELLED', updated_at = now() WHERE status = 'PENDING';"
   ```

## 換事件接收方式

- Webhook → Sqs：用 `-Mode Sqs` 重跑 `setup-ses.ps1`，設好 `SESMAIL_SQS_ENABLED=true`、`SESMAIL_SQS_QUEUE_URL` 並把 `SESMAIL_SNS_ENABLED` 改成 `false` 後部署，最後到 SNS 主控台刪掉 HTTPS 訂閱。
- Sqs → Webhook：先用 `SESMAIL_SNS_ENABLED=true` 部署，再用 `-Mode Webhook` 重跑腳本建 HTTPS 訂閱，然後把 `SESMAIL_SQS_ENABLED` 改成 `false` 並刪掉佇列的訂閱。不刪的話，事件會一直堆在佇列裡。
- 兩個開關不要同時開，否則會收到重複事件。

## 信譽與停寄名單

- SES 主控台 → Reputation metrics：退信率維持在 5% 以下、檢舉率 0.1% 以下，超過會被審查，嚴重時暫停寄送（`SesQuotaService` 的狀態會變成 `paused`）。
- 帳號層級的停寄名單（Configuration → Suppression list）：退信與檢舉過的地址會自動加入，之後寄給它們會被擋下。確認地址恢復正常後，才從名單移除：

  ```powershell
  aws sesv2 delete-suppressed-destination --email-address <信箱>
  ```
