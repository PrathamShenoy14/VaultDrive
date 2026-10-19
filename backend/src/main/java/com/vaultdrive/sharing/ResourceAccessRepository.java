package com.vaultdrive.sharing;

import com.vaultdrive.file.exception.FileNotFoundException;
import com.vaultdrive.folder.exception.FolderNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class ResourceAccessRepository {
    private final EntityManager entityManager;

    public ResourceAccessRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    // Only immutable owner identity is read before hierarchy coordination.
    public UUID owner(boolean folder, UUID id) {
        List<?> rows = entityManager.createNativeQuery("SELECT owner_id FROM "
                + (folder ? "folders" : "files") + " WHERE id = :id")
                .setParameter("id", id).getResultList();
        if (rows.isEmpty()) throw notFound(folder);
        return (UUID) rows.getFirst();
    }

    public ResourceAccess require(UUID actor, boolean folder, UUID id, boolean includeTrash) {
        List<?> rows = query("SELECT a.* FROM resolve_resource_access(:actor, :file, :folder, :trash) a")
                .setParameter("actor", actor).setParameter("file", folder ? null : id)
                .setParameter("folder", folder ? id : null).setParameter("trash", includeTrash)
                .getResultList();
        if (rows.isEmpty()) throw notFound(folder);
        return map((Object[]) rows.getFirst());
    }

    public void refreshForMutation(Object entity) {
        entityManager.refresh(entity);
    }

    public Query query(String sql) {
        return entityManager.createNativeQuery(sql);
    }

    public static RuntimeException notFound(boolean folder) {
        return folder ? new FolderNotFoundException("Folder not found") : new FileNotFoundException("File not found");
    }

    public static ResourceAccess map(Object[] r) {
        return new ResourceAccess((UUID) r[0], (UUID) r[1], (UUID) r[2], (String) r[3], (String) r[4],
                ((Number) r[5]).longValue(), (String) r[6], instant(r[7]), instant(r[8]),
                ResourceRole.valueOf((String) r[9]), (Boolean) r[10], (Boolean) r[11]);
    }

    public static Instant instant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof OffsetDateTime o) return o.toInstant();
        return ((Timestamp) value).toInstant();
    }
}
