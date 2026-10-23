package com.vaultdrive.operations;

import com.vaultdrive.consumer.RetainedEventReplay;
import com.vaultdrive.file.*;
import com.vaultdrive.user.*;
import com.vaultdrive.storage.*;
import org.junit.jupiter.api.*;
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
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"vaultdrive.admission.downloads.concurrency=1"})
@AutoConfigureMockMvc @ActiveProfiles("test") @DirtiesContext @Timeout(40)
class OperationalHardeningIntegrationTest {
    static final String SCHEMA="operational_hardening_"+UUID.randomUUID().toString().replace("-","");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema="+SCHEMA);
        r.add("spring.flyway.schemas",()->SCHEMA);r.add("spring.flyway.default-schema",()->SCHEMA);
    }
    @Autowired JdbcTemplate jdbc;@Autowired OperatorTransactions operations;@Autowired OperationalState state;
    @Autowired RetainedEventReplay replay;@Autowired MultipartSessionService sessions;@Autowired MultipartCancellationService cancellation;
    @Autowired UserRepository users;@Autowired PlatformTransactionManager manager;@Autowired MockMvc mvc;
    @Autowired RequestAdmission admission;
    @Autowired com.vaultdrive.outbox.OutboxWriter writer;
    @Autowired com.vaultdrive.job.BackgroundJobService jobs;
    @MockitoBean ObjectStorageService storage;
    UUID owner;
    @BeforeEach void setup(){owner=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","fixture","Owner")).getId();}
    UUID failedJob(){
        UUID id=UUID.randomUUID();jdbc.update("""
                INSERT INTO background_jobs(id,job_type,aggregate_type,aggregate_id,status,attempt_count,failure_count,max_attempts,
                    last_attempt_at,completed_at,progress_version,claim_progress_version,last_failure_code)
                VALUES (?,'FILE_PURGE','FILE',?,'FAILED',10,10,10,clock_timestamp(),clock_timestamp(),7,7,'WORK_FAILED')
                """,id,UUID.randomUUID());return id;
    }
    @Test void exhaustedJobReceivesFiniteAuditedTrancheWithoutResetOrStorage() {
        UUID id=failedJob();assertThat(state.inspect().get("exhaustedJobs")).isPositive();
        operations.extendJob(UUID.randomUUID(),id,"INC-42",2);
        var row=jdbc.queryForMap("SELECT status,attempt_count,failure_count,max_attempts,progress_version,claim_progress_version,claim_token,completed_at FROM background_jobs WHERE id=?",id);
        assertThat(row).containsEntry("status","PENDING").containsEntry("attempt_count",10L).containsEntry("failure_count",10)
                .containsEntry("max_attempts",12).containsEntry("progress_version",7L).containsEntry("claim_progress_version",7L)
                .containsEntry("claim_token",null).containsEntry("completed_at",null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operator_actions WHERE target_id=?",Integer.class,id)).isEqualTo(1);
        assertThatThrownBy(()->operations.extendJob(UUID.randomUUID(),id,"INC-42",2)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage);
    }
    @Test void databaseRejectsUnauditedRearmCounterResetAndAuditMutation() {
        UUID id=failedJob();
        assertThatThrownBy(()->jdbc.update("INSERT INTO operator_actions(id,target_id,action,incident,outcome) VALUES (?,?,'EXTEND_JOB_BUDGET','INC-shape','SUCCEEDED')",UUID.randomUUID(),id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE background_jobs SET max_attempts=11 WHERE id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE background_jobs SET status='PENDING',completed_at=NULL WHERE id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE background_jobs SET failure_count=0 WHERE id=?",id)).isInstanceOf(RuntimeException.class);
        operations.extendJob(UUID.randomUUID(),id,"INC-43",1);
        assertThatThrownBy(()->jdbc.update("DELETE FROM operator_actions WHERE target_id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE operator_actions SET incident='changed' WHERE target_id=?",id)).isInstanceOf(RuntimeException.class);
    }
    @Test void failedAuditRollsBackWholeBudgetExtension() {
        UUID id=failedJob();assertThatThrownBy(()->operations.extendJob(UUID.randomUUID(),id,"not an incident code",1)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT max_attempts FROM background_jobs WHERE id=?",Integer.class,id)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operator_actions WHERE target_id=?",Integer.class,id)).isZero();
    }
    @Test void extendingBudgetRetainsCheckpointWhenWorkerReclaimsAndRejectsOldToken() {
        UUID id=failedJob();operations.extendJob(UUID.randomUUID(),id,"INC-resume",1);
        // Claim only this fresh fixture's type/due position in this private schema.
        jdbc.update("UPDATE background_jobs SET next_attempt_at=clock_timestamp()+interval '1 hour' WHERE status='PENDING' AND id<>?",id);
        var claim=jobs.claimNext(com.vaultdrive.job.JobType.FILE_PURGE).orElseThrow();
        assertThat(claim.id()).isEqualTo(id);
        assertThat(jdbc.queryForMap("SELECT attempt_count,failure_count,progress_version,claim_progress_version FROM background_jobs WHERE id=?",id))
                .containsEntry("attempt_count",11L).containsEntry("failure_count",10).containsEntry("progress_version",7L).containsEntry("claim_progress_version",7L);
        var stale=new com.vaultdrive.job.ClaimedJob(id,claim.jobType(),claim.aggregateType(),claim.aggregateId(),UUID.randomUUID(),claim.claimExpiresAt(),claim.attemptCount());
        assertThat(jobs.complete(stale)).isFalse();assertThat(jobs.retry(claim)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM background_jobs WHERE id=?",String.class,id)).isEqualTo("FAILED");
    }
    @Test void purgeReplayCreatesOriginalJobAndPreservesProgressOnDuplicates() {
        var event=com.vaultdrive.outbox.OutboxEvent.filePurgeRequested(owner,UUID.randomUUID(),java.time.Instant.now());
        new TransactionTemplate(manager).executeWithoutResult(s->writer.append(event));
        jdbc.update("UPDATE outbox_events SET delivery_status='PUBLISHED',published_at=clock_timestamp() WHERE id=?",event.getId());
        replay.replay(event.getId());jdbc.update("UPDATE background_jobs SET progress_version=7 WHERE id=?",event.getId());
        replay.replay(event.getId());
        assertThat(jdbc.queryForObject("SELECT progress_version FROM background_jobs WHERE id=?",Long.class,event.getId())).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE id=?",Integer.class,event.getId())).isEqualTo(1);verifyNoInteractions(storage);
    }
    @Test void concurrentOperatorsDoNotApplyTwoExtensionsToOneExhaustion() throws Exception {
        UUID id=failedJob();var start=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        Callable<Boolean> extend=()->{start.await();try{operations.extendJob(UUID.randomUUID(),id,"INC-race",1);return true;}catch(IllegalArgumentException expected){return false;}};
        try{var a=executor.submit(extend);var b=executor.submit(extend);start.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
        finally{start.countDown();executor.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT max_attempts FROM background_jobs WHERE id=?",Integer.class,id)).isEqualTo(11);
    }
    UUID quotaEvent(){
        jdbc.update("UPDATE storage_quotas SET limit_bytes=10 WHERE owner_id=?",owner);
        sessions.reserve(owner,UUID.randomUUID().toString(),new MultipartSessionService.Request(null,"quota",8,"text/plain"));
        return jdbc.queryForObject("SELECT id FROM storage_quota_events WHERE owner_id=?",UUID.class,owner);
    }
    @Test void stalledNotificationDeliveryIsVisibleWithoutAutomaticallyReplayingIt() {
        UUID event=quotaEvent();jdbc.update("UPDATE outbox_events SET delivery_status='PUBLISHED',published_at=clock_timestamp()-interval '6 minutes' WHERE id=?",event);
        assertThat(state.inspect().get("pendingNotifications")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sharing_notifications WHERE source_event_id=?",Integer.class,event)).isZero();
        replay.replay(event);assertThat(state.inspect().get("pendingNotifications")).isZero();verifyNoInteractions(storage);
    }
    @Test void operatorReplayOfFailedAndQuarantinedSourcePreservesUuidAndReadState() {
        UUID event=quotaEvent();jdbc.update("UPDATE outbox_events SET delivery_status='FAILED',attempt_count=10,last_attempt_at=clock_timestamp(),last_failure_code='BROKER_ERROR' WHERE id=?",event);
        jdbc.update("INSERT INTO notification_quarantine(body_sha256,event_id,reason_code) VALUES (?,?, 'UNSUPPORTED_TYPE')","a".repeat(64),event);
        UUID action=UUID.randomUUID();operations.beginReplay(action,event,"INC-44");replay.replay(event);operations.finishReplay(action,true);
        jdbc.update("UPDATE sharing_notifications SET read_at=clock_timestamp() WHERE source_event_id=?",event);
        var read=jdbc.queryForObject("SELECT read_at FROM sharing_notifications WHERE source_event_id=?",java.sql.Timestamp.class,event);
        replay.replay(event);assertThat(jdbc.queryForObject("SELECT read_at FROM sharing_notifications WHERE source_event_id=?",java.sql.Timestamp.class,event)).isEqualTo(read);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sharing_notifications WHERE source_event_id=?",Integer.class,event)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM outbox_events WHERE id=?",Integer.class,event)).isEqualTo(10);
        assertThat(state.inspect().get("quarantined")).isZero();verifyNoInteractions(storage);
    }
    @Test void replayRejectsUnknownAndUnpublishedSourcesAndRetainsUncertainAudit() {
        UUID event=quotaEvent();assertThatThrownBy(()->replay.replay(event)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->replay.replay(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        UUID action=UUID.randomUUID();operations.beginReplay(action,event,"INC-45");operations.finishReplay(action,false);
        assertThatCode(()->operations.finishReplay(action,true)).doesNotThrowAnyException(); // terminal audit remains UNKNOWN
        assertThat(jdbc.queryForObject("SELECT outcome FROM operator_actions WHERE id=?",String.class,action)).isEqualTo("UNKNOWN");
    }
    @Test void expiryRestartsAndDuplicatesUseExistingDurableDecisionWithoutStorage() {
        var session=sessions.reserve(owner,"expiry",new MultipartSessionService.Request(null,"expires",8,"text/plain"));
        new TransactionTemplate(manager).executeWithoutResult(s->{
            jdbc.execute("ALTER TABLE upload_sessions DISABLE TRIGGER trg_multipart_session");
            jdbc.update("UPDATE upload_sessions SET created_at=created_at-interval '25 hours',expires_at=expires_at-interval '25 hours' WHERE id=?",session.id());
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");jdbc.execute("ALTER TABLE upload_sessions ENABLE TRIGGER trg_multipart_session");
        });
        var props=new MultipartExpiryConfiguration.Properties(true,1,java.time.Duration.ofSeconds(1));
        try(var first=new MultipartExpiryConfiguration.Poller(cancellation,props,new ProcessingSignals())){first.poll();}
        UUID decision=jdbc.queryForObject("SELECT decision_id FROM upload_sessions WHERE id=?",UUID.class,session.id());
        try(var restart=new MultipartExpiryConfiguration.Poller(cancellation,props,new ProcessingSignals())){restart.poll();restart.poll();}
        assertThat(jdbc.queryForObject("SELECT decision_id FROM upload_sessions WHERE id=?",UUID.class,session.id())).isEqualTo(decision);
        assertThat(jdbc.queryForObject("SELECT status FROM files WHERE id=?",String.class,session.fileId())).isEqualTo("FAILED");verifyNoInteractions(storage);
    }
    @Test void healthRemainsPublicWhileReadinessFailsClosedAndAdmissionPrecedesDatabaseWork() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/health/ready")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("DOWN"));
        try(var held=admission.acquire(RequestAdmission.Lane.DOWNLOADS)){
            mvc.perform(get("/api/v1/files/{id}/download",UUID.randomUUID()).with(jwt().jwt(j->j.subject(owner.toString()))))
                    .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","1")).andExpect(jsonPath("$.error").value("TOO_MANY_REQUESTS"));
        }
        mvc.perform(get("/api/v1/files/{id}/download",UUID.randomUUID()).with(jwt().jwt(j->j.subject(owner.toString())))).andExpect(status().isNotFound());
        assertThat(admission.counts().get(RequestAdmission.Lane.DOWNLOADS).active()).isZero();verifyNoInteractions(storage);
    }
}
