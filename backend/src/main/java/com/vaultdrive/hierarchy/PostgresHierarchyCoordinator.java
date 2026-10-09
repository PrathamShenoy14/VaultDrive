package com.vaultdrive.hierarchy;

import jakarta.persistence.EntityManager;

import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.PreparedStatement;
import java.util.UUID;

@Component
public class PostgresHierarchyCoordinator implements HierarchyCoordinator {

    /**
     * ASCII "VAULTDRV". This fixed value is the stable advisory-lock namespace.
     * The PostgreSQL bigint key is:
     * namespace XOR owner.mostSignificantBits XOR
     * rotateLeft(owner.leastSignificantBits, 1).
     *
     * <p>The 128-to-64-bit reduction can conservatively collide, causing only
     * extra serialization. Changing this derivation requires a coordinated
     * deployment because old and new application instances must use one key.</p>
     */
    private static final long LOCK_NAMESPACE = 0x5641554C54445256L;

    private static final String SHARED_LOCK_SQL =
            "SELECT pg_advisory_xact_lock_shared(?)";

    private static final String EXCLUSIVE_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(?)";

    /**
     * The two-int advisory-lock key space does not overlap PostgreSQL's
     * bigint advisory-lock key space used by the owner hierarchy lock.
     */
    private static final int NAMESPACE_LOCK_CLASS = 0x56444E53;

    private static final String NAMESPACE_EXCLUSIVE_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(?, ?)";

    private final EntityManager entityManager;

    public PostgresHierarchyCoordinator(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void acquireShared(UUID ownerId) {
        acquire(ownerId, SHARED_LOCK_SQL);
    }

    @Override
    public void acquireExclusive(UUID ownerId) {
        acquire(ownerId, EXCLUSIVE_LOCK_SQL);
    }

    @Override
    public void acquireNamespaceExclusive(
            UUID ownerId,
            UUID namespaceId
    ) {
        requireActiveTransaction();

        int lockKey = namespaceLockKey(ownerId, namespaceId);

        entityManager.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement(
                                 NAMESPACE_EXCLUSIVE_LOCK_SQL
                         )) {
                statement.setInt(1, NAMESPACE_LOCK_CLASS);
                statement.setInt(2, lockKey);
                statement.execute();
            }
        });
    }

    private void acquire(UUID ownerId, String sql) {
        requireActiveTransaction();

        long lockKey = lockKey(ownerId);

        // Hibernate supplies the connection enlisted in the current JPA
        // transaction, so PostgreSQL releases this xact lock with that commit
        // or rollback. No separate JDBC connection is opened here.
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setLong(1, lockKey);
                statement.execute();
            }
        });
    }

    private void requireActiveTransaction() {
        if (!TransactionSynchronizationManager
                .isActualTransactionActive()
                || !entityManager.isJoinedToTransaction()) {
            throw new IllegalStateException(
                    "Hierarchy coordination requires an active transaction"
            );
        }
    }

    static long lockKey(UUID ownerId) {
        return LOCK_NAMESPACE
                ^ ownerId.getMostSignificantBits()
                ^ Long.rotateLeft(
                        ownerId.getLeastSignificantBits(),
                        1
                );
    }

    static int namespaceLockKey(UUID ownerId, UUID namespaceId) {
        long mixed = ownerId.getMostSignificantBits()
                ^ Long.rotateLeft(
                        ownerId.getLeastSignificantBits(),
                        1
                );

        if (namespaceId != null) {
            mixed ^= Long.rotateLeft(
                    namespaceId.getMostSignificantBits(),
                    17
            );
            mixed ^= Long.rotateLeft(
                    namespaceId.getLeastSignificantBits(),
                    33
            );
        }

        return (int) (mixed ^ (mixed >>> 32));
    }
}
