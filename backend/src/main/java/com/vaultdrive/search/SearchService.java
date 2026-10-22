package com.vaultdrive.search;

import com.vaultdrive.sharing.ResourceAccess;
import com.vaultdrive.sharing.ResourceAccessRepository;
import jakarta.persistence.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SearchService {
    public enum ResourceType { FILE, FOLDER }
    public enum Scope { ALL, OWNED, SHARED }
    public record Filters(String q, ResourceType resourceType, Scope scope, String mimeType,
                          Long minSizeBytes, Long maxSizeBytes, Instant createdFrom, Instant createdTo,
                          Instant modifiedFrom, Instant modifiedTo, int page, int size) {
        void validate() {
            if (q == null || q.isBlank() || q.codePointCount(0, q.length()) > 255
                    || q.codePoints().anyMatch(Character::isISOControl)
                    || scope == null || page < 0 || size < 1 || size > 100
                    || !validMime(mimeType)
                    || (minSizeBytes != null && minSizeBytes < 0)
                    || (maxSizeBytes != null && maxSizeBytes < 0)
                    || (minSizeBytes != null && maxSizeBytes != null && minSizeBytes > maxSizeBytes)
                    || !validRange(createdFrom, createdTo) || !validRange(modifiedFrom, modifiedTo)
                    || (resourceType == ResourceType.FOLDER && fileOnly())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search parameters");
            }
        }

        boolean fileOnly() { return mimeType != null || minSizeBytes != null || maxSizeBytes != null; }

        private static boolean validMime(String value) {
            if (value == null) return true;
            if (value.isBlank() || value.length() > 255 || value.codePoints().anyMatch(Character::isISOControl)) return false;
            try {
                MediaType type = MediaType.parseMediaType(value);
                return type.isConcrete() && type.getParameters().isEmpty() && value.equals(value.trim());
            } catch (IllegalArgumentException invalid) { return false; }
        }

        private static boolean validRange(Instant from, Instant to) {
            Instant earliest = Instant.parse("0001-01-01T00:00:00Z");
            Instant latest = Instant.parse("9999-12-31T23:59:59.999999Z");
            return (from == null || (!from.isBefore(earliest) && !from.isAfter(latest)))
                    && (to == null || (!to.isBefore(earliest) && !to.isAfter(latest)))
                    && (from == null || to == null || !from.isAfter(to));
        }
    }
    public record Entry(ResourceType resourceType, Object resource) {}
    public record SearchPage(List<Entry> content, int page, int size, long totalElements, int totalPages) {}
    private static final String ORDER = "lower(name) COLLATE \"C\", name COLLATE \"C\", resource_type, id";
    private final ResourceAccessRepository access;

    public SearchService(ResourceAccessRepository access) { this.access = access; }

    /** Cross-owner informational read, like Shared with me: one SQL snapshot, no multi-owner locks.
     * The existing resolver is the authority, including depth/cycle/lifecycle and parent redaction.
     * No application visibility filtering or per-result reads occur after count or pagination. */
    @Transactional(readOnly = true, timeout = 5)
    public SearchPage search(UUID actor, Filters f) {
        f.validate();
        access.query("SELECT set_config('statement_timeout', '3000', true)").getSingleResult();
        access.query("SELECT set_config('lock_timeout', '2000', true)").getSingleResult();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("actor", actor);
        params.put("pattern", "%" + f.q().replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%");
        List<String> branches = new ArrayList<>();
        if (f.resourceType() != ResourceType.FOLDER) branches.add(candidates(false));
        if (f.resourceType() != ResourceType.FILE && !f.fileOnly()) branches.add(candidates(true));
        StringBuilder predicates = new StringBuilder(" WHERE true");
        if (f.scope() == Scope.OWNED) predicates.append(" AND owner_id = :actor");
        if (f.scope() == Scope.SHARED) predicates.append(" AND owner_id <> :actor");
        filter(predicates, params, "lower(btrim(split_part(content_type, ';', 1))) = lower(:mime)", "mime", f.mimeType());
        filter(predicates, params, "size_bytes >= :minSize", "minSize", f.minSizeBytes());
        filter(predicates, params, "size_bytes <= :maxSize", "maxSize", f.maxSizeBytes());
        filter(predicates, params, "created_at >= :createdFrom", "createdFrom", f.createdFrom());
        filter(predicates, params, "created_at <= :createdTo", "createdTo", f.createdTo());
        filter(predicates, params, "updated_at >= :modifiedFrom", "modifiedFrom", f.modifiedFrom());
        filter(predicates, params, "updated_at <= :modifiedTo", "modifiedTo", f.modifiedTo());
        String sql = """
                WITH owners AS MATERIALIZED (
                    SELECT CAST(:actor AS uuid) owner_id
                    UNION SELECT owner_id FROM resource_grants WHERE grantee_user_id = :actor
                ), accessible AS MATERIALIZED (
                """ + String.join(" UNION ALL ", branches) + "), filtered AS MATERIALIZED (SELECT * FROM accessible"
                + predicates + ") SELECT a.*, totals.total FROM (SELECT count(*) total FROM filtered) totals "
                + "LEFT JOIN (SELECT * FROM filtered ORDER BY " + ORDER
                + " LIMIT :limit OFFSET :offset) a ON true ORDER BY " + ORDER;
        Query query = access.query(sql);
        params.forEach(query::setParameter);
        List<?> rows = query.setParameter("limit", f.size()).setParameter("offset", (long) f.page() * f.size())
                .getResultList();
        long total = ((Number) ((Object[]) rows.getFirst())[15]).longValue();
        List<Entry> content = new ArrayList<>();
        for (Object row : rows) {
            Object[] r = (Object[]) row;
            if (r[0] == null) continue;
            ResourceAccess resource = ResourceAccessRepository.map(r);
            ResourceType type = ResourceType.valueOf((String) r[14]);
            content.add(new Entry(type, type == ResourceType.FILE ? resource.fileResponse() : resource.folderResponse()));
        }
        return new SearchPage(content, f.page(), f.size(), total,
                (int) Math.min(Integer.MAX_VALUE, (total + f.size() - 1) / f.size()));
    }

    private static String candidates(boolean folder) {
        // Indexed name/owner selection is only a candidate reduction, never an authorization decision.
        return "SELECT " + ResourceAccessRepository.projection(folder) + ", '" + (folder ? "FOLDER" : "FILE")
                + "' resource_type FROM " + (folder ? "folders" : "files") + " t JOIN owners o ON o.owner_id = t.owner_id "
                + "CROSS JOIN LATERAL resolve_resource_access(:actor, " + (folder ? "NULL, t.id" : "t.id, NULL") + ") a "
                + "WHERE t.deleted_at IS NULL AND t.purge_requested_at IS NULL "
                + (folder ? "" : "AND t.status = 'READY' ")
                + "AND lower(t.name) LIKE lower(:pattern) ESCAPE '!'";
    }

    private static void filter(StringBuilder sql, Map<String, Object> params, String clause, String name, Object value) {
        if (value != null) { sql.append(" AND ").append(clause); params.put(name, value); }
    }
}
