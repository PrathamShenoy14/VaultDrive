package com.vaultdrive.operations;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded observations, not repair authority. Values of 101 mean 101 or more. */
@Service
public class OperationalState {
    private final EntityManager em;
    private final OperationsProperties properties;
    public OperationalState(EntityManager em, OperationsProperties properties) { this.em = em; this.properties = properties; }
    @Transactional(timeout = 10)
    public Map<String, Long> inspect() {
        em.createNativeQuery("SET LOCAL statement_timeout = '3s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout = '1s'").executeUpdate();
        var result = new LinkedHashMap<String, Long>();
        count(result, "failedPublications", "SELECT o.id FROM outbox_events o WHERE delivery_status='FAILED' AND NOT EXISTS (SELECT 1 FROM operator_actions a WHERE a.target_id=o.id AND a.action='REPLAY_EVENT' AND a.outcome='SUCCEEDED')");
        count(result, "exhaustedJobs", "SELECT id FROM background_jobs WHERE status='FAILED'");
        String age = properties.backlogAge().toSeconds() + " seconds";
        count(result, "stalledPublications", "SELECT id FROM outbox_events WHERE delivery_status IN ('PENDING','PUBLISHING') AND next_attempt_at < clock_timestamp()-interval '"+age+"'");
        count(result, "stalledJobs", "SELECT id FROM background_jobs WHERE status IN ('PENDING','RUNNING') AND next_attempt_at < clock_timestamp()-interval '"+age+"'");
        count(result, "missingJobs", "SELECT o.id FROM outbox_events o WHERE delivery_status='PUBLISHED' AND event_type IN ('FILE_PURGE_REQUESTED','FOLDER_PURGE_REQUESTED') AND published_at < clock_timestamp()-interval '"+age+"' AND NOT EXISTS (SELECT 1 FROM background_jobs j WHERE j.id=o.id)");
        count(result, "pendingNotifications", """
                SELECT o.id FROM outbox_events o
                LEFT JOIN permission_activity_events p ON p.id=o.id AND o.event_type='PERMISSION_ACTIVITY_RECORDED'
                LEFT JOIN storage_quota_events q ON q.id=o.id AND o.event_type='STORAGE_QUOTA_THRESHOLD_CROSSED'
                WHERE o.delivery_status='PUBLISHED' AND o.published_at < clock_timestamp()-:age * interval '1 second'
                  AND ((p.recipient_id IS NOT NULL AND p.recipient_id<>p.owner_id
                        AND p.event_type IN ('GRANT_CREATED','GRANT_ROLE_CHANGED','GRANT_REVOKED')) OR q.id IS NOT NULL)
                  AND NOT EXISTS (SELECT 1 FROM sharing_notifications n WHERE n.source_event_id=o.id
                      AND n.recipient_user_id=coalesce(p.recipient_id,q.owner_id))
                """.replace(":age", Long.toString(properties.backlogAge().toSeconds())));
        count(result, "quarantined", "SELECT q.body_sha256 FROM notification_quarantine q WHERE NOT EXISTS (SELECT 1 FROM operator_actions a WHERE a.target_id=q.event_id AND a.action='REPLAY_EVENT' AND a.outcome='SUCCEEDED')");
        count(result, "pendingFinalizations", "SELECT j.id FROM background_jobs j JOIN file_purge_targets t ON t.event_id=j.id LEFT JOIN file_purge_work w ON w.file_id=t.file_id WHERE j.status='SUCCEEDED' AND j.job_type='FILE_PURGE' AND w.metadata_finalized_at IS NULL AND j.completed_at < clock_timestamp()-interval '"+age+"'");
        String recoveryAge = properties.recoveryAge().toSeconds() + " seconds";
        count(result, "pendingRecovery", "SELECT id FROM (SELECT id,created_at FROM upload_sessions WHERE state='INITIATING' UNION ALL SELECT session_id,updated_at FROM multipart_completions WHERE disposition IN ('UNKNOWN','BLOCKED','STORAGE_COMPLETE') UNION ALL SELECT session_id,updated_at FROM multipart_cancellations WHERE disposition IN ('PENDING','UNKNOWN','BLOCKED') AND cause <> 'EXPIRED' UNION ALL SELECT id,created_at FROM file_copy_operations WHERE state IN ('UNKNOWN','BLOCKED','STORAGE_COMPLETE') UNION ALL SELECT id,created_at FROM folder_copy_jobs WHERE state IN ('UNKNOWN','BLOCKED')) r WHERE created_at < clock_timestamp()-interval '"+recoveryAge+"'");
        count(result, "expiredSessions", "SELECT id FROM upload_sessions WHERE decision_id IS NULL AND expires_at < clock_timestamp()-interval '"+age+"'");
        count(result, "unresolvedExpiry", "SELECT session_id FROM multipart_cancellations WHERE cause='EXPIRED' AND disposition <> 'CANCELLED'");
        return Map.copyOf(result);
    }
    private void count(Map<String, Long> result, String key, String sql) {
        result.put(key, ((Number) em.createNativeQuery("SELECT count(*) FROM (" + sql + " LIMIT 101) bounded").getSingleResult()).longValue());
    }
}
