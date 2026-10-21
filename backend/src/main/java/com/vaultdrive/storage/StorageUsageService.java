package com.vaultdrive.storage;

import com.vaultdrive.hierarchy.HierarchyCoordinator;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** PostgreSQL logical accounting. No provider observations or cleanup authority. */
@Service
@Transactional(timeout = 10)
public class StorageUsageService {
    private final EntityManager em;
    private final HierarchyCoordinator hierarchy;

    public StorageUsageService(EntityManager em, HierarchyCoordinator hierarchy) {
        this.em = em;
        this.hierarchy = hierarchy;
    }

    public record Usage(long limitBytes, long usedBytes, long reservedBytes, long availableBytes,
            long uncertainStorageBytes, long uncertainStorageAllocations, long unknownSizeAllocations) { }
    public record Reconciliation(UUID ownerId, long recordedUsedBytes, long recordedReservedBytes,
            long derivedUsedBytes, long derivedReservedBytes, boolean repaired) { }

    public Usage get(UUID owner) {
        bounds();
        // One statement snapshot. Uncertain allocations can overlap outstanding reservations.
        Object[] r = (Object[]) em.createNativeQuery("""
                SELECT q.limit_bytes,q.used_bytes,q.reserved_bytes,
                    greatest(0, q.limit_bytes::numeric-q.used_bytes-q.reserved_bytes),
                    coalesce(x.bytes,0),x.allocations,x.unknown_sizes
                FROM storage_quotas q CROSS JOIN LATERAL (
                    SELECT sum(a.quota_size_bytes) AS bytes,count(*) AS allocations,
                        count(*) FILTER (WHERE a.quota_size_bytes IS NULL) AS unknown_sizes
                    FROM file_storage_objects a WHERE a.owner_id=q.owner_id AND a.write_started
                        AND NOT EXISTS (SELECT 1 FROM files f WHERE f.id=a.file_id AND f.status='READY')
                ) x WHERE q.owner_id=:owner
                """).setParameter("owner", owner).getSingleResult();
        return new Usage(number(r[0]), number(r[1]), number(r[2]), number(r[3]),
                number(r[4]), number(r[5]), number(r[6]));
    }

    /** Internal keyset enumeration only. Call reconcile separately per owner, without an outer transaction. */
    public List<UUID> candidates(UUID after, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Page size must be 1 through 100");
        bounds();
        @SuppressWarnings("unchecked") List<UUID> rows = em.createNativeQuery("SELECT owner_id FROM storage_quotas "
                + "WHERE cast(:after AS uuid) IS NULL OR owner_id > :after ORDER BY owner_id LIMIT :limit")
                .setParameter("after", after).setParameter("limit", limit).getResultList();
        return List.copyOf(rows);
    }

    /** Explicit internal operation, never exposed as an owner/Editor HTTP mutation. */
    public Reconciliation reconcile(UUID owner, boolean repair) {
        bounds();
        hierarchy.acquireExclusive(owner);
        Object[] recorded = (Object[]) em.createNativeQuery("SELECT used_bytes,reserved_bytes FROM storage_quotas "
                + "WHERE owner_id=:owner FOR UPDATE").setParameter("owner", owner).getSingleResult();
        // Fresh statement after coordination: all supported lifecycle writers take hierarchy first.
        Object[] derived = (Object[]) em.createNativeQuery("""
                SELECT coalesce(sum(size_bytes) FILTER (WHERE status='READY'),0),
                    coalesce(sum(size_bytes) FILTER (WHERE status='UPLOADING'),0)
                FROM files WHERE owner_id=:owner
                """).setParameter("owner", owner).getSingleResult();
        boolean changed = number(recorded[0]) != number(derived[0]) || number(recorded[1]) != number(derived[1]);
        if (repair && changed) em.createNativeQuery("UPDATE storage_quotas SET used_bytes=:used,reserved_bytes=:reserved WHERE owner_id=:owner")
                .setParameter("used", number(derived[0])).setParameter("reserved", number(derived[1])).setParameter("owner", owner).executeUpdate();
        return new Reconciliation(owner, number(recorded[0]), number(recorded[1]), number(derived[0]), number(derived[1]), repair && changed);
    }

    private static long number(Object value) { return new java.math.BigDecimal(value.toString()).longValueExact(); }
    private void bounds() {
        em.createNativeQuery("SET LOCAL statement_timeout='3s'").executeUpdate();
        em.createNativeQuery("SET LOCAL lock_timeout='2s'").executeUpdate();
    }
}
