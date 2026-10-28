-- ses-mail-spring-boot-starter 的待寄佇列。
-- 用 Flyway 的專案請把本檔內容複製成自己的下一個 migration；沒用 Flyway 的可設 sesmail.queue.initialize-schema=true。
CREATE TABLE IF NOT EXISTS scheduled_mail (
    id                  BIGSERIAL PRIMARY KEY,
    batch_ref           TEXT,                                -- sendBatch 回傳的整批參照（ses-batch-*）；排程單封為 null
    recipient           TEXT        NOT NULL,                -- 收件人
    subject             TEXT        NOT NULL,                -- 主旨
    html                TEXT        NOT NULL,                -- 已個人化的完整信件 HTML；寄出超過保留期後清成空字串
    unsubscribe_url     TEXT,                                -- 一鍵退訂網址（0.4.0 起）；沒有為 null
    send_at             TIMESTAMPTZ NOT NULL,                -- 預定寄出時間（立即群發為入列當下）
    next_attempt_at     TIMESTAMPTZ NOT NULL,                -- 下次可嘗試時間（暫時性錯誤退避用）
    status              TEXT        NOT NULL DEFAULT 'PENDING',
    attempts            INT         NOT NULL DEFAULT 0,      -- 已嘗試次數（認領時 +1）
    provider_message_id TEXT,                                -- 送出後 SES 回傳的 MessageId
    error               TEXT,                                -- 最近一次錯誤摘要
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),  -- 最近一次狀態變更（判斷卡在 SENDING 用）
    CONSTRAINT scheduled_mail_status_check
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'CANCELLED'))
);

-- 派送器只撈到期的 PENDING
CREATE INDEX IF NOT EXISTS idx_scheduled_mail_due ON scheduled_mail (next_attempt_at, id) WHERE status = 'PENDING';

-- 0.3.0 起：退信／送達事件以 SES MessageId 反查參照
CREATE INDEX IF NOT EXISTS idx_scheduled_mail_message_id ON scheduled_mail (provider_message_id);

-- 0.4.0 起：一鍵退訂網址。CREATE TABLE IF NOT EXISTS 不會替既有的表加欄位，升級要靠這行
ALTER TABLE scheduled_mail ADD COLUMN IF NOT EXISTS unsubscribe_url TEXT;

-- 0.4.0 起：定期清掉已寄出信件的 HTML；只索引還沒清的列，清完就不占空間
CREATE INDEX IF NOT EXISTS idx_scheduled_mail_html_purge ON scheduled_mail (updated_at) WHERE html <> '';
