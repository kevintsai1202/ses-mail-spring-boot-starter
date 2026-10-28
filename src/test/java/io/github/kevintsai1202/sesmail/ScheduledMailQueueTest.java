package io.github.kevintsai1202.sesmail;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 待寄佇列在真 PostgreSQL 上的行為。
 *
 * <p>派送器與取消可能同時碰同一列，正確性仰賴 {@code WHERE status = 'PENDING'} 這類條件式 UPDATE
 * 「只有一方改得到」；這個保證只存在於 SQL 本身，mock 驗不到。</p>
 *
 * <p>需要本機 PostgreSQL（預設 127.0.0.1:5433，postgres/password，可用 {@code SESMAIL_TEST_DB_*} 覆寫）。
 * JitPack 建置時以 {@code -DskipTests} 略過。</p>
 */
class ScheduledMailQueueTest {

    /** 取得環境變數，未設定時退回預設值 */
    private static String env(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    private static final String HOST = env("SESMAIL_TEST_DB_HOST", "127.0.0.1");
    private static final String PORT = env("SESMAIL_TEST_DB_PORT", "5433");
    private static final String USER = env("SESMAIL_TEST_DB_USER", "postgres");
    private static final String PASS = env("SESMAIL_TEST_DB_PASSWORD", "password");
    private static final String ADMIN_URL = "jdbc:postgresql://" + HOST + ":" + PORT + "/postgres";
    /** 獨立的資料庫名稱 */
    private static final String TEST_DB = "ses_mail_starter_test";
    private static final String TEST_URL = "jdbc:postgresql://" + HOST + ":" + PORT + "/" + TEST_DB;

    /** 測試用的「現在」 */
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 27, 8, 0, 0, 0, ZoneOffset.UTC);

    private static DriverManagerDataSource dataSource;
    private ScheduledMailQueue queue;

    /** 重建乾淨資料庫，並用套件附的 schema SQL 建表（連跑兩次，驗證 IF NOT EXISTS） */
    @BeforeAll
    static void prepare() throws SQLException {
        try (Connection c = DriverManager.getConnection(ADMIN_URL, USER, PASS);
             Statement st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + TEST_DB + " WITH (FORCE)");
            st.execute("CREATE DATABASE " + TEST_DB);
        } catch (SQLException e) {
            fail("連不到測試用 PostgreSQL（" + ADMIN_URL + "）：" + e.getMessage());
        }
        dataSource = new DriverManagerDataSource(TEST_URL, USER, PASS);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
            new ClassPathResource(ScheduledMailQueue.SCHEMA_LOCATION));
        populator.execute(dataSource);
        populator.execute(dataSource);
    }

    /** 每個測試前清空佇列 */
    @BeforeEach
    void clean() {
        queue = new ScheduledMailQueue(dataSource);
        org.springframework.jdbc.core.simple.JdbcClient.create(dataSource)
            .sql("DELETE FROM scheduled_mail").update();
    }

    /** 寫入一筆預定在 sendAt 寄出的信 */
    private long enqueue(String to, OffsetDateTime sendAt) {
        return queue.insert(null, new OutgoingMail(to, "主旨", "<p>內文</p>"), sendAt);
    }

    /** 只撈 PENDING 且到期的列，依到期時間排序 */
    @Test
    void findDueIdsOnlyReturnsDuePending() {
        enqueue("later@example.com", NOW.plusMinutes(5)); // 還沒到期
        long second = enqueue("second@example.com", NOW.minusMinutes(1));
        long first = enqueue("first@example.com", NOW.minusMinutes(2));
        long cancelled = enqueue("cancelled@example.com", NOW.minusMinutes(3));
        queue.cancel(cancelled);

        assertEquals(List.of(first, second), queue.findDueIds(NOW, 10));
    }

    /** 同一列只能被認領一次，認領時嘗試次數 +1 */
    @Test
    void claimSucceedsOnlyOnce() {
        long id = enqueue("a@example.com", NOW);

        assertEquals(1, queue.claim(id));
        assertEquals(0, queue.claim(id));
        QueuedMail claimed = queue.find(id).orElseThrow();
        assertEquals(QueuedMail.STATUS_SENDING, claimed.status());
        assertEquals(1, claimed.attempts());
        assertEquals("ses-sched-" + id, claimed.providerRef());
    }

    /** 派送器先認領後，取消就改不到；尚未認領的才取消得了 */
    @Test
    void cancelLosesToClaimAndViceVersa() {
        long claimedFirst = enqueue("a@example.com", NOW);
        long cancelledFirst = enqueue("b@example.com", NOW);

        queue.claim(claimedFirst);
        assertEquals(0, queue.cancel(claimedFirst));

        assertEquals(1, queue.cancel(cancelledFirst));
        assertEquals(0, queue.claim(cancelledFirst));
    }

    /** 送出成功、重試、失敗都只從 SENDING 轉換 */
    @Test
    void statusTransitionsFromSending() {
        long id = enqueue("a@example.com", NOW);
        assertEquals(0, queue.markSent(id, "m-0"), "未認領的列不得直接標為已寄");

        queue.claim(id);
        assertEquals(1, queue.retryLater(id, "throttled", NOW.plusSeconds(30)));
        QueuedMail retried = queue.find(id).orElseThrow();
        assertEquals(QueuedMail.STATUS_PENDING, retried.status());
        assertEquals(NOW.plusSeconds(30).toInstant(), retried.nextAttemptAt().toInstant());
        assertEquals(List.of(), queue.findDueIds(NOW, 10), "退避期間不得再被撈到");

        queue.claim(id);
        assertEquals(1, queue.markSent(id, "ses-msg-1"));
        QueuedMail sent = queue.find(id).orElseThrow();
        assertEquals(QueuedMail.STATUS_SENT, sent.status());
        assertEquals("ses-msg-1", sent.providerMessageId());

        long failing = enqueue("b@example.com", NOW);
        queue.claim(failing);
        assertEquals(1, queue.markFailed(failing, "rejected"));
        assertEquals(QueuedMail.STATUS_FAILED, queue.find(failing).orElseThrow().status());
    }

    /** 批次入列：同一個參照、同一個時間，一次寫入全部 */
    @Test
    void insertAllWritesWholeBatch() {
        int rows = queue.insertAll("ses-batch-x", List.of(
            new OutgoingMail("a@example.com", "s", "<p>a</p>"),
            new OutgoingMail("b@example.com", "s", "<p>b</p>"),
            new OutgoingMail("c@example.com", "s", "<p>c</p>")), NOW);

        assertEquals(3, rows);
        assertEquals(3, queue.countPending());
        List<Long> due = queue.findDueIds(NOW, 10);
        assertEquals(3, due.size());
        assertTrue(due.stream().allMatch(id -> "ses-batch-x".equals(queue.find(id).orElseThrow().providerRef())));
    }

    /** 批次中任一筆不合法（收件人為 null）：整批都不寫入，不會留下半批 */
    @Test
    void insertAllIsAtomic() {
        assertThrows(RuntimeException.class, () -> queue.insertAll("ses-batch-y", java.util.Arrays.asList(
            new OutgoingMail("a@example.com", "s", "<p>a</p>"),
            new OutgoingMail(null, "s", "<p>b</p>")), NOW));

        assertEquals(0, queue.countPending());
    }

    /** 卡在 SENDING 超過門檻的列另外計數 */
    @Test
    void countsStuck() {
        enqueue("a@example.com", NOW);
        long stuck = enqueue("b@example.com", NOW);
        queue.claim(stuck);

        assertEquals(1, queue.countPending());
        assertEquals(1, queue.countStuck(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)));
        assertEquals(0, queue.countStuck(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10)));
    }

    /** 退訂網址隨信存進佇列，單封與批次都一樣；沒給的是 null */
    @Test
    void keepsUnsubscribeUrl() {
        long single = queue.insert(null,
            new OutgoingMail("a@example.com", "s", "<p>a</p>", "https://example.com/u?t=a"), NOW);
        queue.insertAll("ses-batch-u", List.of(
            new OutgoingMail("b@example.com", "s", "<p>b</p>", "https://example.com/u?t=b"),
            new OutgoingMail("c@example.com", "s", "<p>c</p>")), NOW);

        assertEquals("https://example.com/u?t=a", queue.find(single).orElseThrow().unsubscribeUrl());
        List<QueuedMail> batch = queue.findDueIds(NOW, 10).stream()
            .filter(id -> id != single).map(id -> queue.find(id).orElseThrow()).toList();
        assertEquals(java.util.Set.of("https://example.com/u?t=b", "<null>"), batch.stream()
            .map(m -> m.unsubscribeUrl() == null ? "<null>" : m.unsubscribeUrl())
            .collect(java.util.stream.Collectors.toSet()));
    }

    /** 清 HTML：只清超過保留期的已寄出、已取消；失敗、待寄與較新的列都不動，整列保留 */
    @Test
    void clearHtmlOnlyAffectsOldSentAndCancelled() {
        long sent = enqueue("sent@example.com", NOW);
        queue.claim(sent);
        queue.markSent(sent, "msg-sent");
        long cancelled = enqueue("cancelled@example.com", NOW.plusDays(1));
        queue.cancel(cancelled);
        long failed = enqueue("failed@example.com", NOW);
        queue.claim(failed);
        queue.markFailed(failed, "rejected");
        long pending = enqueue("pending@example.com", NOW.plusDays(1));

        assertEquals(0, queue.clearHtml(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10)), "還在保留期內，不清");
        assertEquals(2, queue.clearHtml(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)));
        assertEquals(0, queue.clearHtml(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)), "已清過的不再計數");

        assertEquals("", queue.find(sent).orElseThrow().html());
        assertEquals("", queue.find(cancelled).orElseThrow().html());
        assertEquals("<p>內文</p>", queue.find(failed).orElseThrow().html());
        assertEquals("<p>內文</p>", queue.find(pending).orElseThrow().html());
        assertEquals(java.util.Optional.of("ses-sched-" + sent), queue.findRefByMessageId("msg-sent"),
            "清掉 HTML 後仍要能用 MessageId 對回參照");
    }

    /** 從 0.3.0 升級：舊表沒有 unsubscribe_url，套件附的 schema 要補上欄位，之後才能寫入 */
    @Test
    void schemaUpgradesTableFromV030() {
        org.springframework.jdbc.core.simple.JdbcClient jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
        jdbc.sql("DROP TABLE scheduled_mail").update();
        jdbc.sql("""
            CREATE TABLE scheduled_mail (
                id BIGSERIAL PRIMARY KEY, batch_ref TEXT, recipient TEXT NOT NULL, subject TEXT NOT NULL,
                html TEXT NOT NULL, send_at TIMESTAMPTZ NOT NULL, next_attempt_at TIMESTAMPTZ NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
                provider_message_id TEXT, error TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                CONSTRAINT scheduled_mail_status_check
                    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'CANCELLED')))
            """).update();

        new ResourceDatabasePopulator(new ClassPathResource(ScheduledMailQueue.SCHEMA_LOCATION)).execute(dataSource);

        long id = queue.insert(null, new OutgoingMail("a@example.com", "s", "<p>a</p>", "https://example.com/u"), NOW);
        assertEquals("https://example.com/u", queue.find(id).orElseThrow().unsubscribeUrl());
    }

    /** 以 SES MessageId 反查呼叫端參照：批次信回整批參照、排程信回 ses-sched-{id}、查無回空 */
    @Test
    void findsRefByMessageId() {
        queue.insertAll("ses-batch-z", List.of(new OutgoingMail("a@example.com", "s", "<p>a</p>")), NOW);
        long batchRow = queue.findDueIds(NOW, 10).get(0);
        queue.claim(batchRow);
        queue.markSent(batchRow, "msg-batch");
        long scheduled = enqueue("b@example.com", NOW);
        queue.claim(scheduled);
        queue.markSent(scheduled, "msg-sched");

        assertEquals(java.util.Optional.of("ses-batch-z"), queue.findRefByMessageId("msg-batch"));
        assertEquals(java.util.Optional.of("ses-sched-" + scheduled), queue.findRefByMessageId("msg-sched"));
        assertEquals(java.util.Optional.empty(), queue.findRefByMessageId("msg-unknown"));
    }
}
