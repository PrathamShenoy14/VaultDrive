package com.vaultdrive.contract;

import com.vaultdrive.common.exception.ApiError;
import com.vaultdrive.file.dto.FileResponse;
import com.vaultdrive.folder.dto.FolderResponse;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;

/** Test-only export of the actual MVC mapping registry and record DTOs. No public docs endpoint. */
final class OpenApiDocument {
    final Map<String, Object> schemas = new TreeMap<>();
    private final Map<String, Object> paths = new TreeMap<>();
    private static final Set<String> OPTIONAL = Set.of("folderId", "parentFolderId", "destinationFolderId", "contentType", "expiresAt");

    Map<String, Object> generate(RequestMappingHandlerMapping mappings) {
        schema(ApiError.class);
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.vaultdrive")) return;
            if (mapping.getMethodsCondition().getMethods().isEmpty())
                throw new IllegalStateException("API contract requires explicit HTTP methods: " + handler);
            for (String pattern : mapping.getPatternValues()) {
                List<String> expanded = pattern.contains("{type:files|folders}")
                        ? List.of(pattern.replace("{type:files|folders}", "files"), pattern.replace("{type:files|folders}", "folders"))
                        : List.of(pattern);
                for (String path : expanded) for (RequestMethod method : mapping.getMethodsCondition().getMethods())
                    operation(path, method.name().toLowerCase(Locale.ROOT), handler);
            }
        });
        return obj("openapi", "3.1.0", "info", obj("title", "VaultDrive MVP API", "version", "2026-10-23",
                        "description", "Frozen backend contract. See frontend-integration.md and api-contract-freeze.md. Browser HTTPS/CORS/clock verification remains OPEN."),
                "servers", List.of(obj("url", "/", "description", "Same-origin HTTPS API proxy")),
                "security", List.of(obj("bearerAuth", List.of())), "paths", paths,
                "components", obj("securitySchemes", obj("bearerAuth", obj("type", "http", "scheme", "bearer", "bearerFormat", "JWT"),
                        "refreshCookie", obj("type", "apiKey", "in", "cookie", "name", "__Host-vaultdrive-refresh"),
                        "shareToken", obj("type", "apiKey", "in", "header", "name", "X-VaultDrive-Share-Token")), "schemas", schemas));
    }

    @SuppressWarnings("unchecked")
    private void operation(String path, String verb, HandlerMethod handler) {
        String group = handler.getBeanType().getSimpleName();
        String name = handler.getMethod().getName();
        String id = group.replace("Controller", "") + "_" + name + (path.contains("/grants") ? (path.contains("/folders/") ? "_folder" : "_file") : "");
        var op = obj("operationId", id, "tags", List.of(group.replace("Controller", "")),
                "description", authorization(group, path, name));
        boolean publicLink = path.contains("/public/");
        boolean anonymous = publicLink || group.equals("HealthController") || group.equals("AuthController") && !name.equals("logout");
        if (anonymous) op.put("security", publicLink ? List.of(obj("shareToken", List.of())) : List.of());
        if (group.equals("AuthController") && Set.of("refresh", "logout").contains(name))
            op.put("security", List.of(name.equals("logout") ? obj("bearerAuth", List.of(), "refreshCookie", List.of()) : obj("refreshCookie", List.of())));
        List<Object> parameters = new ArrayList<>();
        for (Parameter p : handler.getMethod().getParameters()) {
            if (p.isAnnotationPresent(RequestBody.class)) {
                op.put("requestBody", obj("required", true, "content", obj("application/json", obj("schema", schema(p.getParameterizedType())))));
                continue;
            }
            String in = null, field = p.getName(), defaultValue = null;
            boolean required = true;
            if (p.isAnnotationPresent(PathVariable.class)) {
                PathVariable a = p.getAnnotation(PathVariable.class); in = "path";
                if (!a.value().isEmpty()) field = a.value();
                if (field.equals("type") && path.contains("/grants")) continue;
            } else if (p.isAnnotationPresent(RequestParam.class)) {
                RequestParam a = p.getAnnotation(RequestParam.class); in = "query"; required = a.required();
                if (!a.value().isEmpty()) field = a.value();
                if (!a.defaultValue().equals(ValueConstants.DEFAULT_NONE)) { defaultValue = a.defaultValue(); required = false; }
            } else if (p.isAnnotationPresent(RequestHeader.class)) {
                RequestHeader a = p.getAnnotation(RequestHeader.class); in = "header"; field = a.value(); required = a.required();
                // The apiKey scheme already declares this header; generating a second argument risks duplicates.
                if (publicLink && field.equals("X-VaultDrive-Share-Token")) continue;
            }
            if (in == null) continue;
            var s = new LinkedHashMap<>(schema(p.getParameterizedType()));
            if (field.equals("If-Match")) required = true;
            if (field.equals("page") || field.equals("marker")) s.put("minimum", 0);
            if (field.equals("size") || field.equals("limit")) { s.put("minimum", 1); s.put("maximum", 100); }
            if (field.equals("q")) { s.put("minLength", 1); s.put("maxLength", 255); s.put("description", "Nonblank literal substring, no ISO controls; length counts Unicode code points"); }
            if (field.equals("minSizeBytes") || field.equals("maxSizeBytes")) s.put("minimum", 0);
            if (field.equals("Idempotency-Key")) { s.put("pattern", "^[!-~]{1,128}$"); }
            if (defaultValue != null) s.put("default", p.getType() == int.class ? Integer.valueOf(defaultValue) : defaultValue);
            parameters.add(obj("in", in, "name", field, "required", required, "schema", s));
        }
        if (group.equals("AuthController") && name.equals("login")) parameters.add(obj("in", "header", "name", "X-VaultDrive-Client", "required", true, "schema", obj("type", "string", "const", "1")));
        if (!parameters.isEmpty()) op.put("parameters", parameters);
        var responses = new TreeMap<String, Object>();
        Type body = handler.getMethod().getGenericReturnType();
        if (handler.getMethod().getReturnType() == ResponseEntity.class) body = ((ParameterizedType) body).getActualTypeArguments()[0];
        boolean binary = path.endsWith("/download");
        for (int status : successes(group, name)) {
            var response = obj("description", status == 202 ? "Accepted or nonterminal retained state; inspect body and follow the recovery protocol" : "Success");
            if (binary) response.put("content", obj(publicLink ? "application/octet-stream" : "*/*", obj("schema", obj("type", "string", "format", "binary"))));
            else if (body != void.class && body != Void.class) response.put("content", obj("application/json", obj("schema", schema(body))));
            var headers = new TreeMap<String, Object>();
            if (group.equals("AuthController") && Set.of("login", "refresh", "logout").contains(name)) {
                headers.put("Set-Cookie", obj("description", "Secure HttpOnly SameSite=Strict Path=/ host-only refresh cookie; cleared only on successful logout", "schema", obj("type", "string")));
                if (!name.equals("logout")) headers.put("X-VaultDrive-CSRF", obj("schema", obj("type", "string")));
            }
            if (status == 202 || group.equals("MultipartSessionController") && Set.of("reserve","initiate","complete","recover","cancel","recoverCancellation").contains(name)
                    || group.equals("EmptyFileController") && name.equals("create")
                    || group.equals("FolderCopyController") || group.equals("FileCopyController") && !name.equals("get"))
                headers.put("Location", obj("schema", obj("type", "string"), "description", "Relative retained status URI"));
            if (group.equals("SharingController") && Set.of("create", "update").contains(name)) headers.put("ETag", obj("schema", obj("type", "string"), "description", "Quoted grant version"));
            if (group.contains("CopyController") || group.equals("DeletionOperationController") || name.startsWith("permanentlyDelete"))
                headers.put("Retry-After", obj("schema", obj("type", "string"), "description", "2 on nonterminal states; deletion FAILED omits the hint"));
            if (binary) {
                headers.put("Content-Disposition", obj("schema", obj("type", "string"), "description", "attachment; encoded UTF-8 filename"));
                headers.put("Content-Length", obj("schema", obj("type", "integer", "format", "int64", "minimum", 0)));
                response.put("description", "Attachment stream; authenticated MIME uses declaration/fallback, public links always octet-stream. Interrupted streams may truncate after headers; no JSON envelope is possible after commit.");
            }
            if (!headers.isEmpty()) response.put("headers", headers);
            responses.put(Integer.toString(status), response);
        }
        // Broad shared failures are possible before controller admission; specific 4xx details live in the workflow contract.
        for (int status : publicLink ? new int[]{404,406,429,500,502,503} : new int[]{400,401,403,404,405,406,409,412,415,428,429,500,502,503}) {
            if ((status == 412 || status == 428) && !(group.equals("SharingController") && name.equals("update"))) continue;
            if (status == 502 && !binary || status == 429 && group.equals("HealthController")) continue;
            if (responses.containsKey(Integer.toString(status))) continue;
            var error = obj("description", errorDescription(status), "content", obj("application/json", obj("schema", schema(ApiError.class))));
            if (status == 429) error.put("headers", obj("Retry-After", obj("schema", obj("type", "string", "const", "1"))));
            responses.put(Integer.toString(status), error);
        }
        if (group.equals("HealthController") && name.equals("ready")) responses.put("503", obj("description", "Readiness DOWN", "content", obj("application/json", obj("schema", schema(body)))));
        op.put("responses", responses);
        ((Map<String,Object>) paths.computeIfAbsent(path, k -> new TreeMap<>())).put(verb, op);
    }

    private static int[] successes(String group, String name) {
        if (name.equals("permanentlyDeleteFile") || name.equals("permanentlyDeleteFolder")) return new int[]{202};
        if (group.equals("AuthController")) return new int[]{name.equals("register") ? 201 : name.equals("logout") ? 204 : 200};
        if (group.equals("MultipartSessionController")) return name.equals("reserve") ? new int[]{201} : Set.of("initiate","complete","recover","cancel","recoverCancellation").contains(name) ? new int[]{200,202} : new int[]{200};
        if (group.equals("FileCopyController") || group.equals("FolderCopyController")) return name.equals("get") ? new int[]{200} : new int[]{200,202};
        if (group.equals("EmptyFileController")) return name.equals("create") ? new int[]{200,202} : new int[]{200};
        if (Set.of("trashFile","deleteFolder","revoke","markRead").contains(name)) return new int[]{204};
        if (name.equals("createFolder") || name.equals("create")) return new int[]{201};
        return new int[]{200};
    }

    private static String authorization(String group, String path, String name) {
        return switch (group) {
            case "AuthController" -> "Public registration/login; refresh requires cookie + CSRF with no Authorization header. Logout requires bearer + matching cookie + CSRF. Login requires X-VaultDrive-Client: 1. Login/refresh/logout reject query strings. Serialize rotation; uncertain refresh requires login. Seven-day absolute refresh family, 30-minute default JWT.";
            case "HealthController" -> "Public process liveness or cached readiness; invalid supplied bearer credentials still fail authentication.";
            case "PublicShareLinkController" -> "Exactly one X-VaultDrive-Share-Token; no query strings. JWT confers no link authority. Missing/invalid/expired/revoked/hidden links and unsupported public routes/methods share 404. Admission may return 429 first.";
            case "MultipartSessionController" -> "Reservation: owner root or accessible destination Owner/Editor; Viewer 403. Existing session: owner or original initiator with current destination Editor; all other callers 404. One Create/Complete/Abort dispatch, immutable part evidence; 202 never authorizes blind retry. See multipart feature contracts.";
            case "EmptyFileController" -> "Owner or destination Editor; status/recovery requires owner or original initiator with current destination Editor. Viewer admission 403; unknown/foreign/hidden 404. Retained key scoped to owner/initiator. Replay never repeats admitted PUT. Recover uses positive evidence only.";
            case "FileCopyController", "FolderCopyController" -> "Owner-only source/destination and retained operation. Visible collaborators 403; missing/foreign/hidden resources or foreign operation 404. Required retained Idempotency-Key on admission; changed normalized input 409. GET performs no storage work. Explicit execute/recover/cancel; folder discard returns disposalOperationId. No autonomous copy worker.";
            case "DeletionOperationController" -> "Original owner-only retained operation, including after removal. Unknown/foreign IDs 404. Logical completion is distinct from physical reclamation; audited operator recovery can return FAILED to PROCESSING.";
            case "SharingController" -> path.contains("grants") ? "Owner-only grant management. Visible collaborator 403, inaccessible/foreign 404. PATCH requires quoted If-Match: missing 428, malformed 400, stale 412. Only VIEWER/EDITOR can be granted. Revoke absent/wrong-target direct grant is a no-op after target authorization." : "Authenticated direct shared entry points with current effective access and private-parent redaction; strongest inherited role applies. Share links alone confer no authenticated access.";
            case "ShareLinkController" -> "Owner-only file links. Visible collaborator 403; hidden/foreign 404. Creation requires active READY file; owner inspection/revocation allowed in Trash before purge. Secret returned once; null expiry defaults 24 hours, maximum seven days. Repeated revoke of matching retained link is idempotent.";
            case "SharingNotificationController" -> "Recipient-only list/count/read. Unknown and foreign notification IDs 404; repeated mark-read 204. Actionable is informational and must be reauthorized; obsolete notifications expose generic history. Delivery is asynchronous.";
            case "ResourceActivityController", "PermissionActivityController", "StorageUsageController" -> "Authenticated owner's history/usage only; no client owner selector. History survives removal and grants no resource access. Unknown/foreign history filters return an empty page. Usage is logical used/reserved accounting, not physical reclaim evidence.";
            case "SearchController" -> "Authenticated owned/effectively shared active folders and READY files, visibility filtered before count/page. Parent null can mean root or private-parent redaction. Literal filename substring; fixed order; all filters AND. See search contract for MIME/date/range validation.";
            case "UserController" -> "Authenticated subject profile only, cookie alone insufficient. PATCH changes displayName only. Unknown user 401. No password/email/account-management endpoint.";
            default -> "Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes.";
        };
    }

    private static String errorDescription(int status) {
        return switch (status) {
            case 400 -> "Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code";
            case 401 -> "Authentication required or invalid credentials/session";
            case 403 -> "Visible resource but insufficient permission, or failed auth transport/CSRF admission";
            case 404 -> "Unknown, foreign or inaccessible resource; public capability failures use Share link not found";
            case 409 -> "Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state";
            case 412 -> "Stale grant If-Match version";
            case 428 -> "Grant update requires If-Match";
            case 429 -> "Bounded process admission rejection, no business work; back off at least one second and retain idempotency key";
            case 503 -> "Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler.";
            default -> "Sanitized common API error; see status and error, not message parsing";
        };
    }

    Map<String, Object> schema(Type type) {
        ResolvableType t = ResolvableType.forType(type);
        Class<?> c = t.resolve(Object.class);
        if (Collection.class.isAssignableFrom(c)) return obj("type", "array", "items", schema(t.getGeneric(0).getType()));
        if (Map.class.isAssignableFrom(c)) return obj("type", "object", "additionalProperties", schema(t.getGeneric(1).getType()));
        if (c == Object.class) return obj("oneOf", List.of(schema(FileResponse.class), schema(FolderResponse.class)));
        if (c == String.class || c == UUID.class || c == Instant.class) {
            var s = obj("type", "string"); if (c != String.class) s.put("format", c == UUID.class ? "uuid" : "date-time"); return s;
        }
        if (c == boolean.class || c == Boolean.class) return obj("type", "boolean");
        if (c == int.class || c == Integer.class || c == long.class || c == Long.class) return obj("type", "integer", "format", c == long.class || c == Long.class ? "int64" : "int32");
        if (c.isEnum()) return obj("type", "string", "enum", Arrays.stream(c.getEnumConstants()).map(Object::toString).toList());
        if (!c.isRecord()) throw new IllegalStateException("Unmodelled API type: " + type);
        String key = c.getName().substring("com.vaultdrive.".length()).replace('.', '_').replace('$', '_');
        if (!schemas.containsKey(key)) {
            schemas.put(key, obj());
            var properties = new LinkedHashMap<String, Object>();
            List<String> required = new ArrayList<>();
            boolean request = c.getSimpleName().endsWith("Request") || Set.of("CreateGrant","UpdateGrant","CreateLink","UpdateProfile").contains(c.getSimpleName());
            for (RecordComponent p : c.getRecordComponents()) {
                var s = new LinkedHashMap<>(schema(p.getGenericType()));
                if (nullable(c, p.getName(), request)) s = obj("anyOf", List.of(s, obj("type", "null")));
                if (!request || !OPTIONAL.contains(p.getName())) required.add(p.getName());
                properties.put(p.getName(), s);
            }
            enrich(c, properties);
            schemas.put(key, obj("type", "object", "properties", properties, "required", required));
        }
        return obj("$ref", "#/components/schemas/" + key);
    }

    private static boolean nullable(Class<?> c, String p, boolean request) {
        if (request) return OPTIONAL.contains(p);
        if (Set.of("folderId","parentFolderId","originalParentId","destinationFolderId","disposalOperationId","purgeRequestedAt","nextMarker",
                "initiationId","uploadId","decisionId","completion","dispatchId","reason","reasonCode","logicallyCompletedAt","revokedAt",
                "cause","evidenceKind","acknowledgedAt","quota","readAt","oldRole","newRole","recipientId","recipientEmail","linkExpiresAt").contains(p)) return true;
        if (p.startsWith("before") || p.startsWith("after")) return true;
        if (c.getSimpleName().equals("Grant") && Set.of("fileId","folderId").contains(p)) return true;
        if (c.getName().contains("SharingNotificationService") && Set.of("resourceType","resourceId","resourceName","role").contains(p)) return true;
        return p.equals("contentType");
    }

    @SuppressWarnings("unchecked")
    private static void enrich(Class<?> c, Map<String, Object> p) {
        String owner = c.getName(), name = c.getSimpleName();
        String[] states = null;
        if (name.equals("Session")) states = new String[]{"RESERVED","INITIATING","OPEN","COMPLETION_DECIDED","ABANDONMENT_DECIDED"};
        if (owner.contains("FileCopyTransactions") && name.equals("Status")) states = new String[]{"RESERVED","UNKNOWN","STORAGE_COMPLETE","READY","BLOCKED","CANCELLED","FAILED"};
        if (owner.contains("FolderCopyTransactions") && name.equals("Status")) states = new String[]{"ACTIVE","BLOCKED","UNKNOWN","FAILED","CANCELLED","COMPLETED"};
        if (owner.contains("EmptyFileTransactions") && name.equals("Status")) states = new String[]{"RESERVED","UNKNOWN","STORAGE_COMPLETE","READY","FAILED","REMOVED"};
        if (states != null) p.put("state", obj("type", "string", "enum", List.of(states)));
        if (name.equals("CompletionInfo")) p.put("disposition", obj("type","string","enum",List.of("PENDING","UNKNOWN","STORAGE_COMPLETE","BLOCKED","READY")));
        if (name.equals("Cancellation")) p.put("disposition", obj("type","string","enum",List.of("PENDING","UNKNOWN","BLOCKED","CANCELLED")));
        if (name.equals("CreateGrant") || name.equals("UpdateGrant")) p.put("role", obj("type","string","enum",List.of("VIEWER","EDITOR")));
        if (name.equals("LoginResponse")) p.put("tokenType",obj("type","string","const","Bearer"));
        if (name.equals("ApiError")) p.put("error",obj("type","string","pattern","^[A-Z][A-Z_]+$"));
        if (name.equals("QuotaMetadata")) p.put("threshold",obj("type","integer","enum",List.of(80,90,100)));
        if (c == com.vaultdrive.folder.dto.TrashPageResponse.Entry.class) {
            p.put("resourceType",obj("type","string","enum",List.of("FILE","FOLDER")));
            p.put("lifecycleState",obj("type","string","enum",List.of("TRASHED","PENDING_PURGE")));
        }
        if (name.equals("SharedEntry")) p.put("resourceType",obj("type","string","enum",List.of("FILE","FOLDER")));
        if (name.equals("RegisterRequest")) {
            p.put("email",obj("type","string","format","email","minLength",1,"maxLength",255));
            p.put("password",obj("type","string","minLength",12,"maxLength",72,"description","BCrypt UTF-8 byte limit also applies"));
            p.put("displayName",obj("type","string","minLength",1,"maxLength",100));
        }
        if (name.equals("LoginRequest")) {
            p.put("email",obj("type","string","format","email","minLength",1));
            p.put("password",obj("type","string","minLength",1));
        }
        if (name.equals("UpdateProfile")) p.put("displayName",obj("type","string","minLength",1,"maxLength",100,"pattern","^[^\\u0000-\\u001f\\u007f-\\u009f]*$"));
        if (name.equals("PartRequest")) { ((Map<String,Object>) p.get("parts")).put("minItems",1); ((Map<String,Object>) p.get("parts")).put("maxItems",100); }
        if (name.equals("PartExpectation")) {
            p.put("partNumber",obj("type","integer","minimum",1,"maximum",640));
            p.put("length",obj("type","integer","format","int64","minimum",1,"maximum",8388608));
            p.put("sha256",obj("type","string","pattern","^[A-Za-z0-9+/]{43}=$","description","Canonical Base64 SHA-256, exactly 32 decoded bytes; final unused bits must be zero"));
        }
        if (owner.contains("MultipartSessionService") && name.equals("Request")) p.put("size",obj("type","integer","format","int64","minimum",1,"maximum",5368709120L));
        if (p.containsKey("name") && (name.endsWith("Request") || name.equals("Request"))) p.put("name",obj("type","string","minLength",1,"description","Nonblank, trimmed then limited to 255 Java UTF-16 units; case-sensitive, no path separators/controls. Rename preserves existing file extension. Raw padding is not part of the normalized length limit."));
    }

    static LinkedHashMap<String, Object> obj(Object... values) {
        var result = new LinkedHashMap<String,Object>();
        for (int i=0; i<values.length; i+=2) result.put((String)values[i],values[i+1]);
        return result;
    }
}
