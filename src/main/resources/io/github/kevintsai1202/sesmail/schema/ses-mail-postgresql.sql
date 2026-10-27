-- ses-mail-spring-boot-starter 的待寄佇列。與 survey-backend 的 V27 逐欄相同。
-- 用 Flyway 的專案請把本檔內容複製成自己的下一個 migration；沒用 Flyway 的可設 sesmail.queue.initialize-schema=true。
CREATE TABLE IF NOT EXISTS scheduled_mail (
    id                  BIGSERIAL PRIMARY KEY,
    batch_ref           TEXT,                                -- sendBatch 回傳的整批參照（ses-batch-*）；排程單封為 null
    recipient           TEXT        NOT NULL,                -- 收件人
    subject             TEXT        NOT NULL,                -- 主旨
    html                TEXT        NOT NULL,                -- 已個人化的完整信件 HTML
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
