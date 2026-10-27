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
}
