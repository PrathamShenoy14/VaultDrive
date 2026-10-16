package com.vaultdrive.hierarchy;

import com.vaultdrive.file.StoredFile;
import com.vaultdrive.file.StoredFileRepository;
import com.vaultdrive.folder.Folder;
import com.vaultdrive.folder.FolderRepository;
import com.vaultdrive.folder.FolderService;
import com.vaultdrive.security.JwtService;
import com.vaultdrive.storage.FileStorageIdentityService;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** Real embedded HTTP server and services; fixture transactions commit before each request. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=hierarchy_http_test",
        "spring.flyway.schemas=hierarchy_http_test", "spring.flyway.default-schema=hierarchy_http_test",
        "vaultdrive.outbox.publisher.enabled=false", "vaultdrive.purge.consumer.enabled=false",
        "vaultdrive.jobs.worker.enabled=false"
})
@ActiveProfiles("test")
abstract class HierarchyHttpTestSupport {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired FolderRepository folders;
    @Autowired StoredFileRepository files;
    @Autowired FileStorageIdentityService identity;
    @Autowired FolderService service;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    @MockitoSpyBean PostgresHierarchyCoordinator hierarchy;
    TransactionTemplate tx;
    HttpClient client;
    UUID owner, otherOwner, root, trashed, pending, activeFile, trashedFile, pendingFile;
    final List<TxState> states = new CopyOnWriteArrayList<>();

    record TxState(boolean active, boolean joined, boolean nativeJoined,
                   boolean nativeActive, boolean sameEntityManager, boolean extendedWrapper) { }

    @BeforeEach
    void committedFixturesAndTransactionProbe() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(Arrays.stream(((Advised) service).getAdvisors())
                .anyMatch(a -> a.getAdvice() instanceof TransactionInterceptor)).isTrue();
        for (String flag : List.of("vaultdrive.outbox.publisher.enabled", "vaultdrive.purge.consumer.enabled",
                "vaultdrive.jobs.worker.enabled")) {
            assertThat(environment.getProperty(flag, Boolean.class)).isFalse();
        }
        tx = new TransactionTemplate(manager);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        tx.executeWithoutResult(s -> {
            owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "HTTP owner")).getId();
            otherOwner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "test-hash", "Other owner")).getId();
            root = folder("Destination", false, false);
            trashed = folder("Restore", true, false);
            pending = folder("Pending", true, true);
            activeFile = file("active.txt", false, false);
            trashedFile = file("restore.txt", true, false);
            pendingFile = file("pending.txt", true, true);
        });
        doAnswer(call -> { recordState(); return call.callRealMethod(); }).when(hierarchy).acquireExclusive(any());
        doAnswer(call -> { recordState(); return call.callRealMethod(); }).when(hierarchy).acquireShared(any());
        doAnswer(call -> { recordState(); return call.callRealMethod(); }).when(hierarchy).acquireNamespaceExclusive(any(), any());
    }

    void recordState() {
        var holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(emf);
        var nativeEm = (EntityManager) em.getDelegate();
        var state = new TxState(TransactionSynchronizationManager.isActualTransactionActive(),
                em.isJoinedToTransaction(), nativeEm.isJoinedToTransaction(), nativeEm.getTransaction().isActive(),
                holder != null && nativeEm == holder.getEntityManager().getDelegate(),
                holder != null && holder.getEntityManager() instanceof org.springframework.orm.jpa.EntityManagerProxy);
        states.add(state);
        // Boolean diagnostics only: no JWTs, SQL parameter values, connection URLs or credentials.
        System.out.printf("HTTP_TX osiv=%s active=%s joined=%s nativeJoined=%s nativeActive=%s sameEntityManager=%s extendedWrapper=%s%n",
                environment.getProperty("spring.jpa.open-in-view"), state.active(), state.joined(),
                state.nativeJoined(), state.nativeActive(), state.sameEntityManager(), state.extendedWrapper());
    }

    void assertJoinedRequests() {
        assertThat(states).isNotEmpty().allSatisfy(state -> {
            assertThat(state).isEqualTo(new TxState(true, true, true, true, true, false));
        });
    }

    UUID folder(String name, boolean deleted, boolean purge) {
        var folder = new Folder(owner, null, name);
        if (deleted) folder.softDelete();
        if (purge) folder.requestPermanentDeletion();
        return folders.saveAndFlush(folder).getId();
    }

    UUID file(String name, boolean deleted, boolean purge) {
        UUID id = UUID.randomUUID();
        var file = new StoredFile(id, owner, null, name, "users/" + owner + "/files/" + id, "text/plain", 10L);
        file.markReady();
        if (deleted) file.softDelete();
        if (purge) file.requestPermanentDeletion();
        files.saveAndFlush(file);
        identity.reserve(file);
        // Committed READY fixture represents an already admitted write; no worker is enabled.
        jdbc.update("UPDATE file_storage_objects SET write_started = true WHERE file_id = ?", id);
        return id;
    }

    HttpResponse<String> request(String method, String path, String body, UUID principal) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15));
        if (principal != null) builder.header("Authorization", "Bearer " + jwt.generateAccessToken(principal));
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> request(String method, String path, String body, int status) throws Exception {
        var response = request(method, path, body, owner);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return response;
    }

    String restore(UUID id) { return "/trash/folders/" + id + "/restore"; }
    String purge(UUID id) { return "/trash/folders/" + id + "/permanent"; }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE owner_id = ?", Long.class, owner);
    }

    long events() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE payload->>'ownerId' = ?", Long.class, owner.toString());
    }

    /** Full resource, intent, manifest and allocation rows, including timestamps/versions. */
    List<String> snapshot() {
        List<String> result = new ArrayList<>();
        for (String table : List.of("folders", "files", "file_storage_objects", "file_purge_targets", "folder_purge_runs")) {
            result.addAll(jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table
                    + " t WHERE owner_id = ? ORDER BY row_to_json(t)::text", String.class, owner));
        }
        result.addAll(jdbc.queryForList("SELECT row_to_json(t)::text FROM outbox_events t"
                + " WHERE payload->>'ownerId' = ? ORDER BY id", String.class, owner.toString()));
        return result;
    }

    @AfterEach
    void removeOnlyDisposableFixtures() {
        if (client != null) client.close();
        if (owner == null) return;
        tx.executeWithoutResult(s -> {
            for (String table : List.of("folder_purge_runs", "file_purge_targets", "files", "file_storage_objects", "folders")) {
                jdbc.update("DELETE FROM " + table + " WHERE owner_id = ?", owner);
            }
            jdbc.update("DELETE FROM outbox_events WHERE payload->>'ownerId' = ?", owner.toString());
            jdbc.update("DELETE FROM users WHERE id IN (?, ?)", owner, otherOwner);
        });
    }
}
