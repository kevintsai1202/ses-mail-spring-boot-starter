# 整合 starter

前提：Java 21、Spring Boot 3.5、PostgreSQL（待寄佇列 `scheduled_mail` 存在應用程式自己的資料庫）。Webhook 模式另外需要 `spring-boot-starter-web`。

## 1. 加依賴（JitPack）

先查最新版本。tag 不加 v，例如 `0.4.0`；SQS 拉取需要 0.3.0 以上，一鍵退訂標頭與 HTML 清理需要 0.4.0 以上。

```powershell
git ls-remote --tags https://github.com/kevintsai1202/ses-mail-spring-boot-starter
```

Maven：

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.github.kevintsai1202</groupId>
  <artifactId>ses-mail-spring-boot-starter</artifactId>
  <version><!-- 上面查到的 tag --></version>
</dependency>
```

Gradle：`repositories { maven { url 'https://jitpack.io' } }`，`implementation 'com.github.kevintsai1202:ses-mail-spring-boot-starter:<tag>'`。

## 2. 設定

把 [`assets/application-sesmail.yml`](../assets/application-sesmail.yml) 合併進 `application.yml`。依第一步問到的事件接收方式設定：

| 模式 | `SESMAIL_SQS_ENABLED` | `SESMAIL_SNS_ENABLED` | `SESMAIL_SNS_TOPIC_ARNS` | `AWS_SES_CONFIGURATION_SET` |
| --- | --- | --- | --- | --- |
| Sqs | `true` | `false` | 必填 | 必填 |
| Webhook | `false` | `true` | 必填 | 必填 |
| None | `false` | `false` | 留空 | 留空 |

- 環境變數只有在 yaml 寫了對應的 `${...}` 佔位才會生效。不要只靠 Spring 的寬鬆綁定：`SESMAIL_SQS_QUEUE_URL` 會被對到 `sesmail.sqs.queue.url`，對不上 `queue-url`。
- `topic-arns` 只在開了任一種接收方式時檢查，None 模式留空可以正常啟動。
- `from` 的網域要先在 SES 驗證通過。
- `max-send-rate` 不要高於 SES 帳號的每秒上限：沙箱是 1，離開沙箱後看 `scripts/ses-status.py` 的「每秒上限」。
- 存取金鑰兩把都空白時，starter 改用 AWS SDK 預設憑證鏈；部署在 AWS 上可以改用 IAM role，不放金鑰。

## 3. 建表

用 Flyway：把 schema 複製成下一個 migration（`<tag>` 換成依賴的版本，`V<n>` 換成下一個版號）。

```powershell
$tag = '<tag>'
Invoke-WebRequest "https://raw.githubusercontent.com/kevintsai1202/ses-mail-spring-boot-starter/$tag/src/main/resources/io/github/kevintsai1202/sesmail/schema/ses-mail-postgresql.sql" -OutFile src/main/resources/db/migration/V<n>__ses_mail_queue.sql
```

沒用 Flyway：設 `SESMAIL_INITIALIZE_SCHEMA=true`，啟動時以 `IF NOT EXISTS` 建表。

升級 starter 時看 README 的「升級注意」，schema 有變就要補 migration，而且要**先加 migration 再升版**。例如 0.4.0 加了 `unsubscribe_url` 欄位，沒補的話第一筆入列就會失敗。

## 4. 寄信

```java
@Service
class NewsletterService {
    private final SesMailClient mail;

    NewsletterService(SesMailClient mail) { this.mail = mail; }

    void demo() {
        // 交易信：同步寄出，回傳 SES MessageId
        mail.send("reader@example.com", "登入連結", "<p>...</p>");

        // 群發：整批寫入佇列（全有或全無），回傳 ses-batch-*，約一個派送間隔內送出
        String batchRef = mail.enqueueBatch(List.of(new OutgoingMail("a@example.com", "第 1 期", "<p>...</p>")));

        // 排程與取消：只有還沒被派送器認領的信取消得了
        String ref = mail.schedule(new OutgoingMail("b@example.com", "第 2 期", "<p>...</p>"),
                Instant.parse("2026-11-01T01:00:00Z"));
        boolean cancelled = mail.cancel(ref);
    }
}
```

派送器在應用程式裡跑：程式停機時排程信不會寄出，恢復後補寄已過時間的信。

### 一鍵退訂（電子報、廣告信必做）

Gmail、Yahoo 要求每天寄超過 5,000 封的寄件者，行銷信要支援一鍵退訂（RFC 8058）。群發與排程信帶上退訂網址，starter 寄出時會加 `List-Unsubscribe` 與 `List-Unsubscribe-Post: List-Unsubscribe=One-Click`：

```java
new OutgoingMail(to, subject, html, unsubscribeUrlFor(to)); // 例如 https://example.com/unsubscribe?email=…&token=…
```

- 網址要 https、只能有可列印 ASCII（中文先做 URL 編碼）、最多 993 字；不合規的在建立時就拋例外。
- 網址裡放簽章過的 token，別人不能拿 email 猜出退訂連結。
- 應用程式要在同一個網址補上 `POST`：內容是 `List-Unsubscribe=One-Click`，不要求登入或 CSRF token、不轉址、重複送也回成功。Gmail 也可能用 `GET` 開同一個網址，原本的退訂頁照常即可。
- 內文仍要有看得到的退訂連結；交易信 `send()` 不加這兩個標頭。
- 上線後寄一封給自己，看 `DKIM-Signature` 的 `h=` 有沒有包含 `List-Unsubscribe` 與 `List-Unsubscribe-Post`（RFC 8058 要求被 DKIM 簽到）。

## 5. 寄送結果

宣告 `DeliveryListener` bean，方法都有預設實作，只覆寫需要的：

```java
@Component
class MailLogUpdater implements DeliveryListener {
    @Override
    public void onSent(String ref, String recipient, String messageId) {
        // 記下 SES MessageId，之後的事件可以對回來
    }

    @Override
    public void onFailed(String ref, String recipient, String error) {
        // 重試 5 次仍失敗，或位址被拒、網域未驗證等不可重試的錯誤
    }
}
```

## 6. 退信、檢舉、送達事件（Sqs、Webhook 模式）

None 模式跳過這一節。

```java
@Component
class MailEvents implements SesEventListener {
    @Override public void onBounce(SesBounce bounce) {
        // 只有永久退信要停寄；暫時退信記 log 即可
        if (bounce.permanent()) bounce.recipients().forEach(this::unsubscribe);
    }
    @Override public void onComplaint(SesComplaint complaint) {
        complaint.recipients().forEach(this::unsubscribe);
    }
    @Override public void onDelivery(SesDelivery delivery) {
        delivery.recipients().forEach(r -> markDelivered(delivery.ref(), r));
    }
}
```

- 實作要冪等：SNS、SQS 都可能重送同一個事件。
- `ref()`：佇列寄出的信是 `enqueueBatch`／`schedule` 回傳的參照，交易信是 `send` 回傳的 MessageId。
- listener 拋例外時，Sqs 模式保留訊息重試，5 次後進死信佇列；Webhook 模式只記 log。

## 7. 額度

`SesQuotaService.snapshot()` 回傳狀態（`sandbox`、`paused`、`healthy`…）、24 小時上限、已寄、佇列待寄與 `remaining()`。每次呼叫都會打 SES API，要自己快取。

## 8. 驗證

1. 部署後寄一封交易信給自己。在 Gmail 的「顯示原始郵件」確認 SPF、DKIM、DMARC 都是 PASS。
2. 用 `enqueueBatch` 寄 1～2 封給自己，約一分鐘內要收到。
3. 有接事件時：到 SES 主控台「Send test email」，configuration set 選建好的那個，收件人分別填 `bounce@simulator.amazonses.com`、`complaint@simulator.amazonses.com`、`success@simulator.amazonses.com`，log 應出現對應事件。模擬信箱不在名單上，不會有人被退訂。
