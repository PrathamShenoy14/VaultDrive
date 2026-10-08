package com.vaultdrive.hierarchy;

import java.util.UUID;

/**
 * Coordinates access to one owner's folder/file hierarchy.
 *
 * <p>Locks are transaction-scoped. Callers must already be inside the
 * transaction that performs the protected reads and writes.</p>
 */
public interface HierarchyCoordinator {

    void acquireShared(UUID ownerId);

    void acquireExclusive(UUID ownerId);
}
