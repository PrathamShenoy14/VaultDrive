package com.vaultdrive.sharing;

import com.vaultdrive.hierarchy.HierarchyCoordinator;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractConcurrencyIntegrationTest {
    @Autowired UserRepository users;
    @Autowired HierarchyCoordinator hierarchy;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/files/trash", "/api/v1/trash/folders"})
    void trashLockWaitIsBoundedAndReturnsSanitizedServiceUnavailable(String route) throws Exception {
        UUID owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Owner")).getId();
        var tx = new TransactionTemplate(manager);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holderPid = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> tx.executeWithoutResult(status -> {
                hierarchy.acquireExclusive(owner);
                holderPid.set(((Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue());
                held.countDown();
                try { assertThat(release.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            var request = executor.submit(() -> mvc.perform(get(route).with(jwt().jwt(j -> j.subject(owner.toString()))))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"))
                    .andExpect(jsonPath("$.message").value("Service temporarily unavailable"))
                    .andExpect(header().string("Retry-After", "2")));
            boolean waiting = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!request.isDone() && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(tx.execute(status -> em.createNativeQuery("""
                        SELECT EXISTS (SELECT 1 FROM pg_locks l WHERE NOT l.granted AND l.locktype = 'advisory'
                            AND :holder = ANY(pg_blocking_pids(l.pid)))
                        """).setParameter("holder", holderPid.get()).getSingleResult()));
                if (waiting) break;
                Thread.yield();
            }
            assertThat(waiting).as("HTTP read waits on the actual owner hierarchy lock").isTrue();
            request.get(6, TimeUnit.SECONDS);
            release.countDown(); holder.get(5, TimeUnit.SECONDS);
            assertThat(users.existsById(owner)).isTrue();
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            users.deleteById(owner);
        }
    }
}
