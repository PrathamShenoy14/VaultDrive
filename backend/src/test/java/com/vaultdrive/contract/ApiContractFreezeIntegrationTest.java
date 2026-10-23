package com.vaultdrive.contract;

import com.vaultdrive.storage.*;
import com.vaultdrive.user.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.*;

import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** HTTP bodies are checked against the exported schemas, alongside durable PostgreSQL behavior.
 * Storage uncertainty is injected; these checks make no browser or provider-interoperability claim. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS) @Timeout(40)
class ApiContractFreezeIntegrationTest {
    static final String SCHEMA = "api_freeze_" + UUID.randomUUID().toString().replace("-", "");
    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA); r.add("spring.flyway.default-schema", () -> SCHEMA);
    }
    @Autowired MockMvc mvc;
    @Autowired RequestMappingHandlerMapping mappings;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @MockitoBean ObjectStorageService storage;
    JsonNode spec;
    UUID owner;

    @BeforeEach void setup() {
        reset(storage);
        spec = mapper.valueToTree(new OpenApiDocument().generate(mappings));
        owner = users.saveAndFlush(new User(UUID.randomUUID()+"@example.test", "fixture", "Contract owner")).getId();
    }

    @Test void exportedContractMatchesEveryRegisteredEndpointAndDto() throws Exception {
        Path artifact = Path.of("../docs/openapi.json");
        Path inventory = Path.of("../docs/api-endpoint-inventory.md");
        if (Boolean.getBoolean("updateApiContract")) {
            Files.writeString(artifact, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(spec)+"\n");
            Files.writeString(inventory, inventory());
        }
        assertThat(mapper.readTree(Files.readString(artifact))).as("Regenerate intentionally with -DupdateApiContract=true").isEqualTo(spec);
        assertThat(Files.readString(inventory)).isEqualTo(inventory());
        Set<String> ids = new HashSet<>();
        spec.path("paths").properties().forEach(path -> path.getValue().properties().forEach(op ->
                assertThat(ids.add(op.getValue().path("operationId").asText())).as("Unique operationId").isTrue()));
        assertThat(spec.path("paths").has("/api/v1/files")).isTrue();
        assertThat(spec.at("/paths/~1api~1v1~1files").has("post")).isFalse();
    }

    @Test void publicAuthenticationErrorsAndProfileMatchExport() throws Exception {
        check(get("/api/v1/health"),"/api/v1/health","get",200);
        check(get("/api/v1/health/ready"),"/api/v1/health/ready","get",503);
        check(get("/api/v1/users/me"),"/api/v1/users/me","get",401);
        check(post("/api/v1/auth/login").servletPath("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"),"/api/v1/auth/login","post",403);
        String email = UUID.randomUUID()+"@example.test";
        check(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email",email,"password","ContractPassword123!","displayName","Frontend"))),"/api/v1/auth/register","post",201);
        var login = mvc.perform(post("/api/v1/auth/login").servletPath("/api/v1/auth/login").header("X-VaultDrive-Client","1").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email",email,"password","ContractPassword123!")))).andReturn();
        validateResponse(login,"/api/v1/auth/login","post",200);
        assertThat(login.getResponse().getHeader("Set-Cookie")).contains("Secure","HttpOnly","SameSite=Strict","Path=/");
        assertThat(login.getResponse().getHeader("X-VaultDrive-CSRF")).isNotBlank();
        check(get("/api/v1/users/me").with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/users/me","get",200);
        check(patch("/api/v1/users/me").with(jwt().jwt(j->j.subject(owner.toString()))).contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"\"}"),"/api/v1/users/me","patch",400);
    }

    @Test void hierarchyPagesRedactionAndGrantPreconditionsMatchExport() throws Exception {
        UUID folder = folder("Private");
        var viewer = users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","fixture","Viewer"));
        String target = "/api/v1/folders/"+folder;
        JsonNode grant = check(post(target+"/grants").with(jwt().jwt(j->j.subject(owner.toString()))).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email",viewer.getEmail(),"role","VIEWER"))),"/api/v1/folders/{id}/grants","post",201);
        check(patch(target+"/grants/"+grant.path("id").asText()).with(jwt().jwt(j->j.subject(owner.toString()))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"EDITOR\"}"),"/api/v1/folders/{id}/grants/{grantId}","patch",428);
        check(patch(target+"/grants/"+grant.path("id").asText()).with(jwt().jwt(j->j.subject(owner.toString()))).header("If-Match","\"9\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"EDITOR\"}"),"/api/v1/folders/{id}/grants/{grantId}","patch",412);
        check(patch(target).with(jwt().jwt(j->j.subject(viewer.getId().toString()))).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Denied\"}"),"/api/v1/folders/{folderId}","patch",403);
        check(get(target).with(jwt().jwt(j->j.subject(UUID.randomUUID().toString()))),"/api/v1/folders/{folderId}","get",404);
        JsonNode page = check(get("/api/v1/folders").param("page","2147483647").param("size","100").with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/folders","get",200);
        assertThat(page.path("content").isEmpty()).isTrue(); assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        check(get("/api/v1/folders").param("size","101").with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/folders","get",400);
        check(get("/api/v1/folders").accept("application/xml").with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/folders","get",406);
    }

    @Test void retainedMultipartIdempotencyAndUnknownInitiationAreExplicit() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"name\":\"upload.bin\",\"size\":1}";
        JsonNode reserved = reserve(key,body,201);
        assertThat(reserve(key,body,201)).isEqualTo(reserved);
        reserve(key,"{\"name\":\"changed.bin\",\"size\":1}",409);
        String session = "/api/v1/upload-sessions/"+reserved.path("id").asText();
        when(storage.initiateMultipart(any(),any(),any(),any())).thenThrow(new IllegalStateException("synthetic lost response"));
        JsonNode unknown = check(post(session+"/initiate").with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/upload-sessions/{id}/initiate","post",202);
        assertThat(unknown.path("state").asText()).isEqualTo("INITIATING");
        check(post(session+"/initiate").with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/upload-sessions/{id}/initiate","post",202);
        verify(storage,times(1)).initiateMultipart(any(),any(),any(),any());
        JsonNode cancelled = check(post(session+"/cancel").with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/upload-sessions/{id}/cancel","post",202);
        assertThat(cancelled.path("disposition").asText()).isEqualTo("BLOCKED");
        assertThat(cancelled.path("reason").asText()).isEqualTo("UPLOAD_ID_UNKNOWN");
        check(get(session).with(jwt().jwt(j->j.subject(UUID.randomUUID().toString()))),"/api/v1/upload-sessions/{id}","get",404);
    }

    @Test void copyProgressCancellationAndDeletionAcceptanceUseDifferentContracts() throws Exception {
        UUID source = folder("Copy source");
        String path = "/api/v1/folders/"+source+"/copy";
        JsonNode accepted = check(post(path).header("Idempotency-Key",UUID.randomUUID().toString()).with(jwt().jwt(j->j.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Copy destination\"}"),"/api/v1/folders/{sourceId}/copy","post",202);
        assertThat(accepted.path("state").asText()).isEqualTo("ACTIVE");
        String job = "/api/v1/folder-copy-operations/"+accepted.path("id").asText();
        JsonNode cancelled = check(post(job+"/cancel").with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/folder-copy-operations/{id}/cancel","post",200);
        assertThat(cancelled.path("state").asText()).isEqualTo("CANCELLED");
        JsonNode discarded = check(post(job+"/discard").with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/folder-copy-operations/{id}/discard","post",200);
        String operation = discarded.path("disposalOperationId").asText();
        JsonNode deletion = check(get("/api/v1/deletion-operations/"+operation).with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/deletion-operations/{operationId}","get",200);
        assertThat(deletion.path("state").asText()).isEqualTo("ACCEPTED");
        verifyNoInteractions(storage);
    }

    @Test void discoveryUsageNotificationsHistoryAndPublicCapabilityErrorsMatchExport() throws Exception {
        folder("Find me");
        for (String path : List.of("/api/v1/search", "/api/v1/shared-with-me", "/api/v1/files", "/api/v1/files/trash", "/api/v1/trash/folders", "/api/v1/activity", "/api/v1/permission-activity", "/api/v1/notifications", "/api/v1/notifications/unread-count", "/api/v1/users/me/storage")) {
            var request = get(path).with(jwt().jwt(j->j.subject(owner.toString())));
            if (path.endsWith("search")) request.param("q","Find");
            check(request,path,"get",200);
        }
        check(get("/api/v1/search").param("q","Find").param("resourceType","FOLDER").param("minSizeBytes","1").with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/search","get",400);
        check(get("/api/v1/public/share-links/"+UUID.randomUUID()),"/api/v1/public/share-links/{linkId}","get",404);
    }

    @Test void emptyCreationRecoveryAndFileCopyReturnPublicationStates() throws Exception {
        Map<String,CopyStorage.ObjectInfo> objects=new HashMap<>();
        doAnswer(call->{
            StorageTarget target=call.getArgument(0); UUID id=call.getArgument(1);
            objects.put(target.key(),new CopyStorage.ObjectInfo(0,"\"empty\"",call.getArgument(2),Map.of("vaultdrive-empty",id.toString())));
            return null;
        }).when(storage).putEmpty(any(),any(),any());
        // The first creation sees no HEAD evidence, even though its one write succeeded.
        when(storage.inspectCopyObject(any())).thenReturn(null);
        String key=UUID.randomUUID().toString();
        var request=post("/api/v1/files/empty").header("Idempotency-Key",key).with(jwt().jwt(j->j.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"empty.txt\"}");
        JsonNode pending=check(request,"/api/v1/files/empty","post",202);
        assertThat(pending.path("state").asText()).isEqualTo("UNKNOWN");
        when(storage.inspectCopyObject(any())).thenAnswer(call->objects.get(((StorageTarget)call.getArgument(0)).key()));
        JsonNode ready=check(post("/api/v1/empty-file-creations/"+pending.path("id").asText()+"/recover").with(jwt().jwt(j->j.subject(owner.toString()))),
                "/api/v1/empty-file-creations/{id}/recover","post",200);
        assertThat(ready.path("state").asText()).isEqualTo("READY");
        verify(storage,times(1)).putEmpty(any(),any(),any());
        doAnswer(call->{
            CopyStorage.Command command=call.getArgument(0);
            objects.put(command.destination().key(),new CopyStorage.ObjectInfo(0,"\"copy\"",command.contentType(),Map.of("vaultdrive-copy",command.operationId().toString())));
            return null;
        }).when(storage).copyObject(any());
        JsonNode copied=check(post("/api/v1/files/"+ready.path("fileId").asText()+"/copy").header("Idempotency-Key",UUID.randomUUID().toString())
                .with(jwt().jwt(j->j.subject(owner.toString()))).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"copied.txt\"}"),
                "/api/v1/files/{sourceId}/copy","post",200);
        assertThat(copied.path("state").asText()).isEqualTo("READY");
        assertThat(copied.path("storageVerified").asBoolean()).isTrue();
        check(get("/api/v1/files/"+copied.path("fileId").asText()).with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/files/{fileId}","get",200);
    }

    @Test void linkManagementIncludesNullableExpiryRevocationAndSecretFreeLists() throws Exception {
        Map<String,CopyStorage.ObjectInfo> objects=new HashMap<>();
        doAnswer(call->{
            StorageTarget target=call.getArgument(0); UUID id=call.getArgument(1);
            objects.put(target.key(),new CopyStorage.ObjectInfo(0,"\"empty\"",call.getArgument(2),Map.of("vaultdrive-empty",id.toString())));
            return null;
        }).when(storage).putEmpty(any(),any(),any());
        when(storage.inspectCopyObject(any())).thenAnswer(call->objects.get(((StorageTarget)call.getArgument(0)).key()));
        JsonNode file=check(post("/api/v1/files/empty").header("Idempotency-Key",UUID.randomUUID().toString()).with(jwt().jwt(j->j.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"linked.txt\"}"),"/api/v1/files/empty","post",200);
        String path="/api/v1/files/"+file.path("fileId").asText()+"/share-links";
        JsonNode link=check(post(path).with(jwt().jwt(j->j.subject(owner.toString()))).contentType(MediaType.APPLICATION_JSON).content("{}"),"/api/v1/files/{fileId}/share-links","post",201);
        JsonNode list=check(get(path).with(jwt().jwt(j->j.subject(owner.toString()))),"/api/v1/files/{fileId}/share-links","get",200);
        assertThat(list.toString()).doesNotContain(link.path("secret").asText());
        check(get("/api/v1/public/share-links/"+link.path("link").path("id").asText()).header("X-VaultDrive-Share-Token",link.path("secret").asText()),
                "/api/v1/public/share-links/{linkId}","get",200);
    }

    private UUID folder(String name) throws Exception {
        return UUID.fromString(check(post("/api/v1/folders").with(jwt().jwt(j->j.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("name",name))),"/api/v1/folders","post",201).path("folderId").asText());
    }
    private JsonNode reserve(String key,String body,int status) throws Exception {
        return check(post("/api/v1/upload-sessions").with(jwt().jwt(j->j.subject(owner.toString()))).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body),"/api/v1/upload-sessions","post",status);
    }
    private JsonNode check(MockHttpServletRequestBuilder request,String path,String verb,int status) throws Exception {
        return validateResponse(mvc.perform(request).andReturn(),path,verb,status);
    }
    private JsonNode validateResponse(MvcResult result,String path,String verb,int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(path+" "+result.getResponse().getContentAsString()).isEqualTo(status);
        JsonNode response = spec.path("paths").path(path).path(verb).path("responses").path(Integer.toString(status));
        assertThat(response.isMissingNode()).as("Documented status").isFalse();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        validate(body,response.path("content").path("application/json").path("schema"));
        return body;
    }
    private void validate(JsonNode value,JsonNode schema) {
        if (schema.has("$ref")) { validate(value,spec.at(schema.path("$ref").asText().substring(1))); return; }
        String union = schema.has("anyOf") ? "anyOf" : schema.has("oneOf") ? "oneOf" : null;
        if (union != null) {
            int successes=0;
            for (JsonNode option : schema.path(union)) { try { validate(value,option); successes++; } catch (AssertionError ignored) { } }
            assertThat(successes).as("Schema union for "+value).isGreaterThan(0);
            if (union.equals("oneOf")) assertThat(successes).isEqualTo(1);
            return;
        }
        String type=schema.path("type").asText();
        assertThat(switch(type) { case "object"->value.isObject(); case "array"->value.isArray(); case "string"->value.isTextual(); case "integer"->value.isIntegralNumber(); case "boolean"->value.isBoolean(); case "null"->value.isNull(); default->false; }).as("Expected "+type+" for "+value).isTrue();
        if (schema.has("enum")) assertThat(schema.path("enum")).anyMatch(value::equals);
        if (schema.has("const")) assertThat(value).isEqualTo(schema.path("const"));
        if (type.equals("string")) {
            if (schema.has("pattern")) assertThat(java.util.regex.Pattern.compile(schema.path("pattern").asText()).matcher(value.asText()).find()).isTrue();
            int length=value.asText().codePointCount(0,value.asText().length());
            if (schema.has("minLength")) assertThat(length).isGreaterThanOrEqualTo(schema.path("minLength").asInt());
            if (schema.has("maxLength")) assertThat(length).isLessThanOrEqualTo(schema.path("maxLength").asInt());
        }
        if (type.equals("integer")) {
            if (schema.has("minimum")) assertThat(value.asLong()).isGreaterThanOrEqualTo(schema.path("minimum").asLong());
            if (schema.has("maximum")) assertThat(value.asLong()).isLessThanOrEqualTo(schema.path("maximum").asLong());
        }
        if (type.equals("object")) {
            for (JsonNode required:schema.path("required")) assertThat(value.has(required.asText())).as("Required "+required).isTrue();
            value.properties().forEach(p->{ if (schema.path("properties").has(p.getKey())) validate(p.getValue(),schema.path("properties").path(p.getKey()));
                else if (schema.has("additionalProperties")) validate(p.getValue(),schema.path("additionalProperties"));
                else fail("Undocumented response property: "+p.getKey()); });
        }
        if (type.equals("array")) {
            if (schema.has("minItems")) assertThat(value.size()).isGreaterThanOrEqualTo(schema.path("minItems").asInt());
            if (schema.has("maxItems")) assertThat(value.size()).isLessThanOrEqualTo(schema.path("maxItems").asInt());
            for (JsonNode item:value) validate(item,schema.path("items"));
        }
        if (schema.path("format").asText().equals("uuid")) assertThatCode(()->UUID.fromString(value.asText())).doesNotThrowAnyException();
        if (schema.path("format").asText().equals("date-time")) assertThatCode(()->java.time.Instant.parse(value.asText())).doesNotThrowAnyException();
    }

    private String inventory() {
        StringBuilder out=new StringBuilder("# MVP endpoint inventory\n\nGenerated from Spring MVC mappings and record DTOs by `ApiContractFreezeIntegrationTest`.\nEvery path below is relative to the same HTTPS application origin. Request/response\nschema names refer to [OpenAPI components](openapi.json); `—` means no body.\nSecurity and authorization descriptions are per operation in that artifact.\nCommon errors: `{status,error,message}`; detailed error/status/retry semantics and\noperation states are in [the frontend guide](frontend-integration.md).\nImplicit Spring HEAD/OPTIONS behavior is not a separate supported operation.\n\n| Method | Path | Authentication | Request schema | Success: response schema |\n|---|---|---|---|---|\n");
        spec.path("paths").properties().forEach(path->path.getValue().properties().forEach(entry->{
            JsonNode op=entry.getValue(); String auth=path.getKey().contains("/public/")?"Share token":op.has("security")&&op.path("security").isEmpty()?"Public":"Bearer";
            if (path.getKey().endsWith("/refresh")) auth="Cookie + CSRF";
            if (path.getKey().endsWith("/logout")) auth="Bearer + cookie + CSRF";
            StringJoiner responses=new StringJoiner("; ");
            op.path("responses").properties().forEach(r->{if(r.getKey().startsWith("2")) responses.add(r.getKey()+": "+label(r.getValue().path("content").path("application/json").path("schema")));});
            String responseLabel=path.getKey().endsWith("/download") ? "200: binary attachment" : responses.toString();
            out.append("| ").append(entry.getKey().toUpperCase(Locale.ROOT)).append(" | `").append(path.getKey()).append("` | ").append(auth).append(" | ")
                    .append(label(op.path("requestBody").path("content").path("application/json").path("schema"))).append(" | ").append(responseLabel).append(" |\n");
        }));
        return out.toString();
    }
    private static String label(JsonNode schema) {
        if(schema.has("$ref")) return "`"+schema.path("$ref").asText().replace("#/components/schemas/","")+"`";
        if(schema.path("type").asText().equals("array")) return "array of "+label(schema.path("items"));
        return "—";
    }
}
