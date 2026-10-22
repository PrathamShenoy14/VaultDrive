package com.vaultdrive.consumer;

import com.vaultdrive.file.*;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.sharing.*;
import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.user.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(45)
class QuotaNotificationIntegrationTest {
    static final String SCHEMA = "quota_notifications_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA); r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired MultipartSessionService sessions;
    @Autowired MultipartCancellationTransactions cancellation;
    @Autowired FolderService folders;
    @Autowired GrantService grants;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired PermissionEventIngestor ingestor;
    @Autowired SharingNotificationService notifications;
    @Autowired MockMvc mvc;
    @MockitoBean ObjectStorageService storage;
    UUID owner;
    TransactionTemplate tx;

    @BeforeEach void setup() { tx = new TransactionTemplate(manager); owner = user(); limit(100); }
    UUID user() { return users.saveAndFlush(new User(UUID.randomUUID()+"@example.test", "fixture", "Quota actor")).getId(); }
    void limit(long amount) { jdbc.update("UPDATE storage_quotas SET limit_bytes=? WHERE owner_id=?", amount, owner); }
    MultipartSessionService.Session reserve(long size, String key) {
        return sessions.reserve(owner, key, new MultipartSessionService.Request(null, key, size, "text/plain"));
    }
    List<Integer> thresholds() { return jdbc.queryForList("SELECT threshold FROM storage_quota_events WHERE owner_id=? ORDER BY threshold", Integer.class, owner); }
    List<QuotaEvent> events() { return jdbc.query("SELECT id,occurred_at FROM storage_quota_events WHERE owner_id=? ORDER BY occurred_at,id",
            (r,n) -> new QuotaEvent(r.getObject(1, UUID.class), r.getTimestamp(2).toInstant()), owner); }
    SharingNotificationService.Page page(UUID recipient) { return notifications.list(recipient, null, null, 0, 100); }

    @Test void exactBoundariesJumpAndIdempotentRetries() {
        reserve(79,"a"); assertThat(thresholds()).isEmpty();
        reserve(1,"b"); assertThat(thresholds()).containsExactly(80);
        reserve(10,"c"); assertThat(thresholds()).containsExactly(80,90);
        reserve(10,"d"); reserve(10,"d"); assertThat(thresholds()).containsExactly(80,90,100);
        assertThatThrownBy(() -> reserve(1,"excess")).isInstanceOf(RuntimeException.class);
        assertThat(thresholds()).containsExactly(80,90,100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE id IN (SELECT id FROM storage_quota_events WHERE owner_id=?)", Integer.class, owner)).isEqualTo(3);
    }

    @Test void integralRoundingAndOverflowSafeCalculations() {
        limit(3); reserve(2,"two"); assertThat(thresholds()).isEmpty();
        reserve(1,"three"); assertThat(thresholds()).containsExactly(80,90,100);
        assertThat(jdbc.queryForObject("SELECT quota_threshold_reached(?,0,?,90)", Boolean.class, Long.MAX_VALUE, Long.MAX_VALUE)).isTrue();
        assertThat(jdbc.queryForObject("SELECT quota_threshold_reached(?,0,?,100)", Boolean.class, Long.MAX_VALUE-1, Long.MAX_VALUE)).isFalse();
    }

    @Test void cancellationRearmsOnlyAfterFallingStrictlyBelowAndReplayDoesNothing() {
        var eighty = reserve(80,"a"); var ten = reserve(10,"b");
        cancellation.cancel(owner,ten.id()); // exactly 80: only 90 rearms
        reserve(10,"c"); assertThat(thresholds()).containsExactly(80,90,90);
        cancellation.cancel(owner,eighty.id()); cancellation.cancel(owner,eighty.id());
        reserve(80,"d"); assertThat(thresholds()).containsExactly(80,80,90,90,90);
    }

    @Test void limitChangesCrossRearmAndHandleZeroWithoutEmptyAccountNoise() {
        reserve(70,"a"); limit(80); assertThat(thresholds()).containsExactly(80);
        limit(70); assertThat(thresholds()).containsExactly(80,90,100);
        limit(70); limit(100); limit(70); assertThat(thresholds()).containsExactly(80,80,90,90,100,100);
        limit(0); assertThat(thresholds()).hasSize(6); // already reached, no duplicate
        limit(100); limit(0); assertThat(thresholds()).hasSize(9);
        UUID empty = user(); jdbc.update("UPDATE storage_quotas SET limit_bytes=0 WHERE owner_id=?",empty);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM storage_quota_events WHERE owner_id=?",Integer.class,empty)).isZero();
        assertThatThrownBy(() -> reserve(1,"denied")).isInstanceOf(RuntimeException.class);
    }

    @Test void eligibleExpiryRearmsButRepeatedExpiryDoesNotCreateEvents() {
        var session=reserve(80,"expire");
        // Age only this fresh fixture using the established expiry-test pattern.
        tx.executeWithoutResult(s->{
            jdbc.execute("ALTER TABLE upload_sessions DISABLE TRIGGER trg_multipart_session");
            jdbc.update("UPDATE upload_sessions SET created_at=created_at-interval '25 hours', expires_at=expires_at-interval '25 hours' WHERE id=?",session.id());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");jdbc.execute("ALTER TABLE upload_sessions ENABLE TRIGGER trg_multipart_session");
        });
        var c=new MultipartCancellationTransactions.Candidate(owner,session.id());
        assertThat(cancellation.expire(c).disposition()).isEqualTo("CANCELLED");
        assertThat(cancellation.expire(c).disposition()).isEqualTo("SKIPPED");
        assertThat(thresholds()).containsExactly(80);
        reserve(80,"after-expiry");assertThat(thresholds()).containsExactly(80,80);
    }

    @Test void metadataRemovalRearmsWithoutDeletingHistoricalNotificationSource() {
        var s=reserve(80,"removed");var e=events().getFirst();
        // A historical READY fixture exercises the same accounting trigger as
        // bounded proof-backed purge removal, without a storage allocation.
        UUID historical=UUID.randomUUID();
        cancellation.cancel(owner,s.id());
        jdbc.update("INSERT INTO files(id,owner_id,name,storage_key,content_type,size_bytes,status,created_at,updated_at) VALUES (?,?,?,?,?,80,'READY',clock_timestamp(),clock_timestamp())",
                historical,owner,"historical","fixture/"+historical,"text/plain");
        jdbc.update("DELETE FROM files WHERE id=?",historical);
        reserve(80,"after-removal");assertThat(thresholds()).containsExactly(80,80,80);
        ingestor.acceptQuota(e);assertThat(page(owner).content()).hasSize(1);
    }

    @Test void editorCrossingNotifiesOnlyOwnerAndMetadataNeverConfersAuthority() throws Exception {
        UUID editor=user(), stranger=user(); UUID folder=folders.createFolder(owner,null,"shared");
        grants.create(owner,true,folder,users.findById(editor).orElseThrow().getEmail(),ResourceRole.EDITOR);
        sessions.reserve(editor,"editor",new MultipartSessionService.Request(folder,"delegated",90,"text/plain"));
        for (QuotaEvent e : events()) ingestor.acceptQuota(e);
        assertThat(page(owner).content()).hasSize(2).allSatisfy(n -> {
            assertThat(n.actionable()).isFalse(); assertThat(n.resourceId()).isNull();
            assertThat(n.resourceName()).isNull(); assertThat(n.newRole()).isNull();
            assertThat(n.quota().logicalUsageBytes()).isEqualTo(90); assertThat(n.quota().limitBytes()).isEqualTo(100);
            assertThat(n.quota().occurredAt()).isNotNull();
        });
        assertThat(page(editor).content()).isEmpty(); assertThat(page(stranger).content()).isEmpty();
        mvc.perform(get("/api/v1/notifications?eventType=STORAGE_QUOTA_THRESHOLD_CROSSED&size=1").with(jwt().jwt(j->j.subject(owner.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].quota.logicalUsageBytes").value(90))
                .andExpect(jsonPath("$.content[0].quota.threshold").isNumber())
                .andExpect(jsonPath("$.content[0].quota.limitBytes").value(100))
                .andExpect(jsonPath("$.content[0].quota.occurredAt").isString());
        UUID id=page(owner).content().getFirst().id();
        mvc.perform(patch("/api/v1/notifications/{id}/read",id).with(jwt().jwt(j->j.subject(editor.toString())))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/notifications/{id}/read",id).with(jwt().jwt(j->j.subject(owner.toString())))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/notifications/unread-count").with(jwt().jwt(j->j.subject(owner.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1));
        mvc.perform(get("/api/v1/notifications?unread=false&eventType=STORAGE_QUOTA_THRESHOLD_CROSSED").with(jwt().jwt(j->j.subject(owner.toString()))))
                .andExpect(jsonPath("$.totalElements").value(1));
        assertThatThrownBy(() -> reserve(11,"fresh-quota-required")).isInstanceOf(RuntimeException.class);
    }

    @Test void concurrentAndReorderedDeliveryKeepsHistoricalSnapshotsAndReadState() throws Exception {
        var s=reserve(100,"a"); var old=events(); cancellation.cancel(owner,s.id()); reserve(90,"b");
        var reversed=new ArrayList<>(events()); Collections.reverse(reversed);
        for (var e : reversed) ingestor.acceptQuota(e);
        UUID id=page(owner).content().getFirst().id(); notifications.markRead(owner,id);
        Instant read=page(owner).content().getFirst().readAt();
        var barrier=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Void> deliver=()->{barrier.await(5,TimeUnit.SECONDS); for(var e:old) ingestor.acceptQuota(e); return null;};
            var a=pool.submit(deliver); var b=pool.submit(deliver); a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
        }
        assertThat(page(owner).content()).hasSize(5);
        assertThat(page(owner).content().getFirst().readAt()).isEqualTo(read);
        assertThat(page(owner).content().stream().filter(n->old.stream().anyMatch(e->e.id().equals(n.sourceEventId()))))
                .allSatisfy(n->assertThat(n.quota().logicalUsageBytes()).isEqualTo(100));
    }

    @Test void rollbackOrOutboxFailureCannotCommitChargeOrSourceAndSameKeyCanRetry() {
        tx.executeWithoutResult(s->{reserve(100,"rollback"); assertThat(thresholds()).hasSize(3); s.setRollbackOnly();});
        assertThat(thresholds()).isEmpty();
        jdbc.execute("CREATE FUNCTION fail_quota_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='STORAGE_QUOTA_THRESHOLD_CROSSED' THEN RAISE EXCEPTION 'test failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_quota_outbox BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION fail_quota_outbox()");
        try { assertThatThrownBy(()->reserve(100,"retry")).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("DROP TRIGGER fail_quota_outbox ON outbox_events"); jdbc.execute("DROP FUNCTION fail_quota_outbox()"); }
        assertThat(thresholds()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT reserved_bytes FROM storage_quotas WHERE owner_id=?",Long.class,owner)).isZero();
        reserve(100,"retry"); assertThat(thresholds()).containsExactly(80,90,100);
    }

    @Test void uncommittedQuotaSourceCannotBeDelivered() throws Exception {
        try(var pool=Executors.newSingleThreadExecutor()) {
            tx.executeWithoutResult(s->{
                reserve(80,"rollback"); var event=events().getFirst();
                try { pool.submit(()->assertThatThrownBy(()->ingestor.acceptQuota(event)).isInstanceOf(InvalidNotification.class)).get(5,TimeUnit.SECONDS); }
                catch(Exception e) { throw new AssertionError(e); }
                s.setRollbackOnly();
            });
        }
        assertThat(page(owner).content()).isEmpty(); assertThat(thresholds()).isEmpty();
    }

    @Test void databaseGuardsSourceRecipientAndMonotonicReadAndIngestValidatesOutbox() {
        reserve(80,"a"); var e=events().getFirst(); ingestor.acceptQuota(e); UUID other=user();
        assertThatThrownBy(()->jdbc.update("INSERT INTO sharing_notifications(id,source_event_id,recipient_user_id,event_type) VALUES (?,?,?,'STORAGE_QUOTA_THRESHOLD_CROSSED')",UUID.randomUUID(),e.id(),other))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE storage_quota_events SET threshold=90 WHERE id=?",e.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM storage_quota_events WHERE id=?",e.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->ingestor.acceptQuota(new QuotaEvent(e.id(),Instant.EPOCH))).isInstanceOf(InvalidNotification.class);
        assertThatThrownBy(()->ingestor.acceptQuota(new QuotaEvent(UUID.randomUUID(),Instant.EPOCH))).isInstanceOf(InvalidNotification.class);
        UUID id=page(owner).content().getFirst().id(); notifications.markRead(owner,id);
        assertThatThrownBy(()->jdbc.update("UPDATE sharing_notifications SET read_at=NULL WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void concurrentReservationsSerializeThresholdState(boolean largeFirst) throws Exception {
        race(()->reserve(largeFirst?80:10,"first"),()->reserve(largeFirst?10:80,"second"));
        assertThat(thresholds()).containsExactly(80,90);
        assertThat(jdbc.queryForObject("SELECT reserved_bytes FROM storage_quotas WHERE owner_id=?",Long.class,owner)).isEqualTo(90);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void limitChangeAndReservationSerializeInBothOrders(boolean limitFirst) throws Exception {
        reserve(70,"initial"); Runnable change=()->limit(80), upload=()->reserve(10,"concurrent");
        race(limitFirst?change:upload,limitFirst?upload:change);
        assertThat(thresholds()).containsExactly(80,90,100);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancellationAndReservationObserveCommittedThresholdState(boolean cancelFirst) throws Exception {
        var s=reserve(80,"initial");Runnable cancel=()->cancellation.cancel(owner,s.id()), upload=()->reserve(10,"concurrent");
        race(cancelFirst?cancel:upload,cancelFirst?upload:cancel);
        assertThat(thresholds()).containsExactlyElementsOf(cancelFirst?List.of(80):List.of(80,90));
        assertThat(jdbc.queryForObject("SELECT reserved_bytes FROM storage_quotas WHERE owner_id=?",Long.class,owner)).isEqualTo(10);
    }

    void race(Runnable first, Runnable second) throws Exception {
        var held=new CountDownLatch(1); var release=new CountDownLatch(1); var entered=new CountDownLatch(1); var pid=new AtomicInteger();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->tx.executeWithoutResult(s->{first.run();held.countDown();await(release);}));
            assertThat(held.await(5,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(()->tx.executeWithoutResult(s->{pid.set(jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class));entered.countDown();second.run();}));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2); boolean blocked=false;
            while(System.nanoTime()<deadline) { blocked=Boolean.TRUE.equals(jdbc.queryForObject("SELECT cardinality(pg_blocking_pids(?))>0",Boolean.class,pid.get())); if(blocked) break; Thread.yield(); }
            assertThat(blocked).isTrue(); assertThat(b.isDone()).isFalse(); release.countDown();
            a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    static void await(CountDownLatch latch) { try {if(!latch.await(8,TimeUnit.SECONDS)) throw new AssertionError("Latch timeout");} catch(InterruptedException e) {Thread.currentThread().interrupt();throw new AssertionError(e);} }
}
