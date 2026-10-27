package io.github.kevintsai1202.sesmail;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 待寄佇列（資料表 scheduled_mail）的存取，以 JdbcClient 實作。
 *
 * <p><b>為什麼不用 JPA</b>：函式庫若帶 JPA 實體，就得用 {@code @EntityScan} 註冊，
 * 而那會蓋掉應用程式本身的實體掃描；也會逼每個使用的專案都裝 JPA。</p>
 *
 * <p>所有狀態轉換都是「帶舊狀態條件」的 UPDATE、回傳影響列數：派送器認領（PENDING → SENDING）
 * 與取消（PENDING → CANCELLED）可能同時碰同一列，只有一方改得到，另一方拿到 0 就知道自己輸了。
 * 不要改成「先讀再寫」。每個方法都是單一 SQL 敘述，在呼叫端已有交易時會加入該交易。</p>
 */
public class ScheduledMailQueue {

    /** 套件附的建表 SQL 在 classpath 上的位置 */
    public static final String SCHEMA_LOCATION = "io/github/kevintsai1202/sesmail/schema/ses-mail-postgresql.sql";

    /** 單次批次入列的上限：整批是一條 INSERT，參數數量受 PostgreSQL 65535 限制 */
    static final int MAX_BATCH = 5000;

    /** 查詢列欄位 */
    private static final String COLUMNS =
        "id, batch_ref, recipient, subject, html, status, attempts, provider_message_id, next_attempt_at";

    /** 資料列轉成 {@link QueuedMail} */
    private static final RowMapper<QueuedMail> ROW_MAPPER = (rs, i) -> new QueuedMail(
        rs.getLong("id"), rs.getString("batch_ref"), rs.getString("recipient"),
        rs.getString("subject"), rs.getString("html"), rs.getString("status"),
        rs.getInt("attempts"), rs.getString("provider_message_id"),
        rs.getObject("next_attempt_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    /** 以應用程式的 DataSource 建立（不會在建構時連線） */
    public ScheduledMailQueue(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** 寫入一封信，回傳佇列 id；到期時間初始等於預定寄出時間 */
    public long insert(String batchRef, OutgoingMail mail, OffsetDateTime sendAt) {
        return jdbc.sql("""
                INSERT INTO scheduled_mail (batch_ref, recipient, subject, html, send_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """)
            .params(batchRef, mail.to(), mail.subject(), mail.html(), sendAt, sendAt)
            .query(Long.class).single();
    }

    /**
     * 整批寫入：同一個參照、同一個預定時間。
     *
     * <p>組成一條多列 INSERT，本身就是原子操作：任一列不合法（例如收件人為 null）整批都不寫入，
     * 不會留下半期電子報在佇列裡；也不需要另外建交易管理器。</p>
     *
     * @return 寫入列數
     */
    public int insertAll(String batchRef, List<OutgoingMail> mails, OffsetDateTime sendAt) {
        if (mails.isEmpty()) {
            return 0;
        }
        if (mails.size() > MAX_BATCH) {
            throw new IllegalArgumentException("單次入列最多 " + MAX_BATCH + " 封，請由呼叫端切批");
        }
        StringBuilder sql = new StringBuilder(
            "INSERT INTO scheduled_mail (batch_ref, recipient, subject, html, send_at, next_attempt_at) VALUES ");
        List<Object> params = new ArrayList<>(mails.size() * 6);
        for (int i = 0; i < mails.size(); i++) {
            OutgoingMail mail = mails.get(i);
            sql.append(i == 0 ? "" : ", ").append("(?, ?, ?, ?, ?, ?)");
            params.add(batchRef);
            params.add(mail.to());
            params.add(mail.subject());
            params.add(mail.html());
            params.add(sendAt);
            params.add(sendAt);
        }
        return jdbc.sql(sql.toString()).params(params).update();
    }

    /** 到期的待寄信 id（依到期時間、id 排序） */
    public List<Long> findDueIds(OffsetDateTime now, int limit) {
        return jdbc.sql("""
                SELECT id FROM scheduled_mail
                WHERE status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY next_attempt_at, id
                LIMIT ?
                """)
            .params(now, limit)
            .query(Long.class).list();
    }

    /** 讀一列 */
    public Optional<QueuedMail> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM scheduled_mail WHERE id = ?")
            .param(id).query(ROW_MAPPER).optional();
    }

    /** 認領：PENDING → SENDING，嘗試次數 +1；回傳 1 才可以寄 */
    public int claim(long id) {
        return jdbc.sql("""
                UPDATE scheduled_mail SET status = 'SENDING', attempts = attempts + 1, updated_at = now()
                WHERE id = ? AND status = 'PENDING'
                """).param(id).update();
    }

    /** 取消：只有還在 PENDING 的列取消得了；已被認領或已寄出回傳 0 */
    public int cancel(long id) {
        return jdbc.sql("""
                UPDATE scheduled_mail SET status = 'CANCELLED', updated_at = now()
                WHERE id = ? AND status = 'PENDING'
                """).param(id).update();
    }

    /** 送出成功：SENDING → SENT，記下 MessageId */
    public int markSent(long id, String messageId) {
        return jdbc.sql("""
                UPDATE scheduled_mail SET status = 'SENT', provider_message_id = ?, error = NULL, updated_at = now()
                WHERE id = ? AND status = 'SENDING'
                """).params(messageId, id).update();
    }

    /** 暫時性錯誤：SENDING → PENDING，延後到 nextAttemptAt 再試 */
    public int retryLater(long id, String error, OffsetDateTime nextAttemptAt) {
        return jdbc.sql("""
                UPDATE scheduled_mail SET status = 'PENDING', error = ?, next_attempt_at = ?, updated_at = now()
                WHERE id = ? AND status = 'SENDING'
                """).params(error, nextAttemptAt, id).update();
    }

    /** 放棄：SENDING → FAILED */
    public int markFailed(long id, String error) {
        return jdbc.sql("""
                UPDATE scheduled_mail SET status = 'FAILED', error = ?, updated_at = now()
                WHERE id = ? AND status = 'SENDING'
                """).params(error, id).update();
    }

    /** 還沒送出的封數（PENDING）；額度計算要扣掉，因為 SES 的已寄量還看不到它們 */
    public long countPending() {
        return jdbc.sql("SELECT count(*) FROM scheduled_mail WHERE status = 'PENDING'")
            .query(Long.class).single();
    }

    /** 卡在 SENDING 且最後變更早於 before 的列數（程序中斷的跡象，需人工確認） */
    public long countStuck(OffsetDateTime before) {
        return jdbc.sql("SELECT count(*) FROM scheduled_mail WHERE status = 'SENDING' AND updated_at < ?")
            .param(before).query(Long.class).single();
    }
}
