package com.vaultdrive.operations;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
public class OperatorTransactions {
    private final EntityManager em;
    public OperatorTransactions(EntityManager em) { this.em=em; }
    public void beginReplay(UUID action, UUID target, String incident) {
        bounds();
        em.createNativeQuery("INSERT INTO operator_actions(id,target_id,action,incident,outcome) VALUES (:id,:target,'REPLAY_EVENT',:incident,'REQUESTED')")
                .setParameter("id",action).setParameter("target",target).setParameter("incident",incident).executeUpdate();
    }
    public void finishReplay(UUID action, boolean success) {
        bounds();
        em.createNativeQuery("UPDATE operator_actions SET outcome=:outcome WHERE id=:id AND outcome='REQUESTED'")
                .setParameter("id",action).setParameter("outcome",success?"SUCCEEDED":"UNKNOWN").executeUpdate();
    }
    public void extendJob(UUID action, UUID id, String incident, int additionalFailures) {
        if (additionalFailures < 1 || additionalFailures > 10) throw new IllegalArgumentException("Additional failures must be 1..10");
        bounds();
        // Job-only lock, then freshly read counters. No later resource locks or I/O.
        var rows = em.createNativeQuery("SELECT attempt_count,failure_count,max_attempts FROM background_jobs WHERE id=:id AND status='FAILED' FOR UPDATE")
                .setParameter("id",id).getResultList();
        if (rows.isEmpty()) throw new IllegalArgumentException("Exact exhausted job required");
        Object[] row = (Object[]) rows.getFirst();
        int budget = ((Number)row[2]).intValue();
        if (((Number)row[1]).intValue() != budget || budget+additionalFailures>1000) throw new IllegalArgumentException("Job budget cannot be extended");
        em.createNativeQuery("""
                INSERT INTO operator_actions(id,target_id,action,incident,outcome,prior_attempts,prior_failures,prior_budget,new_budget)
                VALUES (:action,:id,'EXTEND_JOB_BUDGET',:incident,'SUCCEEDED',:attempts,:failures,:budget,:newBudget)
                """).setParameter("action",action).setParameter("id",id).setParameter("incident",incident)
                .setParameter("attempts",((Number)row[0]).longValue()).setParameter("failures",budget)
                .setParameter("budget",budget).setParameter("newBudget",budget+additionalFailures).executeUpdate();
        em.createNativeQuery("UPDATE background_jobs SET status='PENDING',max_attempts=:budget,completed_at=NULL,next_attempt_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=:id AND status='FAILED'")
                .setParameter("budget",budget+additionalFailures).setParameter("id",id).executeUpdate();
    }
    private void bounds() {
        em.createNativeQuery("SET LOCAL statement_timeout = '3s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout = '1s'").executeUpdate();
    }
}
