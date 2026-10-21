package com.vaultdrive.consumer;

import com.vaultdrive.sharing.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SharingNotificationIntegrationTest extends SharingNotificationFixtures {
    private static final String QUOTA_TEST_SCHEMA = "sharing_m6_test_" + java.util.UUID.randomUUID().toString().replace("-", "");
    @org.springframework.test.context.DynamicPropertySource
    static void quotaIsolatedSchema(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + QUOTA_TEST_SCHEMA);
        r.add("spring.flyway.schemas", () -> QUOTA_TEST_SCHEMA);
        r.add("spring.flyway.default-schema", () -> QUOTA_TEST_SCHEMA);
    }

    @Autowired MockMvc mvc;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean PermissionEventIngestor delivery;

    @Test void directRecipientsOnlyForThreeGrantTypesIncludingFoldersAndNoLinkOrOwnerFanOut() {
        var g = grant(); grants.update(owner, false, file, g.id(), ResourceRole.EDITOR, 0L);
        grants.revoke(owner, false, file, g.id());
        grants.create(owner, true, parent, email, ResourceRole.VIEWER);
        var link = links.create(owner, file, null); links.revoke(owner, file, link.link().id());
        deliverAll(); deliverAll();
        assertThat(page(recipient).content()).hasSize(4).extracting(SharingNotificationService.Notification::eventType)
                .contains(SharingNotificationService.Type.values());
        assertThat(page(owner).totalElements()).isZero(); assertThat(page(stranger).totalElements()).isZero();
        assertThat(notifications.unreadCount(recipient).unreadCount()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM background_jobs WHERE id IN (SELECT id FROM permission_activity_events WHERE owner_id = ?)",
                Long.class, owner)).isZero();
        assertThat(page(recipient).content().stream().filter(SharingNotificationService.Notification::actionable).toList())
                .singleElement().satisfies(n -> assertThat(n.resourceType()).isEqualTo(PermissionActivityService.ResourceType.FOLDER));
    }

    @Test void concurrentConsumersDeduplicateAndReplayPreservesReadState() throws Exception {
        grant(); var e = event(events().getFirst()); var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Void> delivery = () -> { barrier.await(5, TimeUnit.SECONDS); ingestor.accept(e); return null; };
            var a = executor.submit(delivery); var b = executor.submit(delivery);
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        assertThat(page(recipient).content()).hasSize(1);
        UUID id = page(recipient).content().getFirst().id();
        notifications.markRead(recipient, id); Instant read = page(recipient).content().getFirst().readAt();
        ingestor.accept(e); notifications.markRead(recipient, id);
        assertThat(page(recipient).content()).singleElement().satisfies(n -> assertThat(n.readAt()).isEqualTo(read));
        assertThat(notifications.unreadCount(recipient).unreadCount()).isZero();
    }

    @Test void concurrentReadRequestsAreMonotonic() throws Exception {
        grant(); deliverAll(); UUID id = page(recipient).content().getFirst().id();
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Void> read = () -> { barrier.await(5, TimeUnit.SECONDS); notifications.markRead(recipient, id); return null; };
            var a = executor.submit(read); var b = executor.submit(read);
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        assertThat(notifications.unreadCount(recipient).unreadCount()).isZero();
        assertThat(page(recipient).content().getFirst().readAt()).isNotNull();
    }

    @Test void reorderedEventsAndRegrantNeverReactivateOldActionsOrHistoricalDetails() {
        var g = grant(); grants.update(owner, false, file, g.id(), ResourceRole.EDITOR, 0L);
        deliverAll();
        assertThat(page(recipient).content().stream().filter(SharingNotificationService.Notification::actionable))
                .singleElement().satisfies(n -> assertThat(n.newRole()).isEqualTo(ResourceRole.EDITOR));
        grants.revoke(owner, false, file, g.id()); deliverAll();
        assertGeneric(); assertThatThrownBy(() -> reads.file(recipient, file)).isInstanceOf(RuntimeException.class);
        grant(); deliverAll();
        assertThat(page(recipient).content().stream().filter(SharingNotificationService.Notification::actionable))
                .singleElement().satisfies(n -> assertThat(n.eventType()).isEqualTo(SharingNotificationService.Type.GRANT_CREATED));
        assertThat(page(recipient).content().stream().filter(n -> !n.actionable()))
                .allSatisfy(n -> { assertThat(n.resourceId()).isNull(); assertThat(n.resourceName()).isNull(); assertThat(n.newRole()).isNull(); });
    }

    @Test void snapshotsExcludePrivateAncestorsActorRecipientAndOtherGrantDetails() throws Exception {
        grant(); fileService.renameFile(owner, file, "renamed.txt"); deliverAll();
        assertThat(page(recipient).content()).singleElement().satisfies(n -> {
            assertThat(n.actionable()).isTrue(); assertThat(n.resourceName()).isEqualTo("snapshot.txt");
        });
        String body = mvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject(recipient.toString()))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private-parent", parent.toString(), "Private actor", email,
                "actorId", "ownerId", "recipientId", "oldRole", "subjectId", "storageKey");
    }

    @Test void ancestorTrashPendingPurgeAndPhysicalDeletionRedactRetainedNotifications() {
        grant(); deliverAll();
        jdbc.update("UPDATE folders SET deleted_at = clock_timestamp() WHERE id = ?", parent); assertGeneric();
        jdbc.update("UPDATE folders SET purge_requested_at = clock_timestamp() WHERE id = ?", parent); assertGeneric();
        jdbc.update("DELETE FROM files WHERE id = ?", file); assertGeneric();
        assertThat(notifications.unreadCount(recipient).unreadCount()).isEqualTo(1);
        assertThatThrownBy(() -> reads.file(recipient, file)).isInstanceOf(RuntimeException.class);
    }

    @Test void inheritedAccessAfterDirectRevocationDoesNotReviveOldDirectNotification() {
        var g = grant(); grants.create(owner, true, parent, email, ResourceRole.EDITOR);
        grants.revoke(owner, false, file, g.id()); deliverAll();
        assertThat(reads.file(recipient, file).role()).isEqualTo(ResourceRole.EDITOR);
        assertThat(page(recipient).content().stream().filter(n -> n.resourceType() == PermissionActivityService.ResourceType.FILE)).isEmpty();
    }

    @Test void recipientScopePrecedesFiltersCountsPaginationAndReadUpdates() throws Exception {
        var g = grant(); grants.update(owner, false, file, g.id(), ResourceRole.EDITOR, 0L);
        grants.create(owner, true, parent, users.findById(stranger).orElseThrow().getEmail(), ResourceRole.VIEWER);
        deliverAll();
        var first = notifications.list(recipient, true, null, 0, 1);
        var second = notifications.list(recipient, true, null, 1, 1);
        assertThat(first.totalElements()).isEqualTo(2); assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content().getFirst().id()).isNotEqualTo(second.content().getFirst().id());
        assertThat(notifications.list(recipient, null, null, 20, 1).totalElements()).isEqualTo(2);
        assertThat(notifications.list(recipient, null, null, 20, 1).content()).isEmpty();
        UUID id = first.content().getFirst().id();
        for (UUID wrong : List.of(owner, stranger)) {
            mvc.perform(patch("/api/v1/notifications/{id}/read", id).with(jwt().jwt(j -> j.subject(wrong.toString()))))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(patch("/api/v1/notifications/{id}/read", UUID.randomUUID()).with(jwt().jwt(j -> j.subject(recipient.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/notifications/{id}/read", id).with(jwt().jwt(j -> j.subject(recipient.toString()))))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/notifications/unread-count").with(jwt().jwt(j -> j.subject(recipient.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1));
        assertThat(notifications.list(recipient, false, null, 0, 50).totalElements()).isEqualTo(1);
        assertThat(notifications.list(recipient, null, SharingNotificationService.Type.GRANT_CREATED, 0, 50).totalElements()).isEqualTo(1);
        assertThat(page(owner).totalElements()).isZero();
        assertThat(notifications.unreadCount(stranger).unreadCount()).isEqualTo(1);
    }

    @Test void authenticationAndValidationContracts() throws Exception {
        for (String route : List.of("/api/v1/notifications", "/api/v1/notifications/unread-count")) {
            mvc.perform(get(route)).andExpect(status().isUnauthorized());
            mvc.perform(get(route).header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        }
        mvc.perform(patch("/api/v1/notifications/{id}/read", UUID.randomUUID())).andExpect(status().isUnauthorized());
        for (String query : List.of("page=-1", "size=0", "size=101", "eventType=LINK_CREATED", "unread=invalid")) {
            mvc.perform(get("/api/v1/notifications?" + query).with(jwt().jwt(j -> j.subject(recipient.toString()))))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void databaseGuardsIdentityRecipientEventShapeAndMonotonicReadState() {
        grant(); deliverAll(); var n = page(recipient).content().getFirst();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sharing_notifications(id,source_event_id,recipient_user_id,event_type) VALUES (?,?,?,'GRANT_CREATED')",
                UUID.randomUUID(), n.sourceEventId(), stranger)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE sharing_notifications SET recipient_user_id = ? WHERE id = ?", stranger, n.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        notifications.markRead(recipient, n.id());
        assertThatThrownBy(() -> jdbc.update("UPDATE sharing_notifications SET read_at = NULL WHERE id = ?", n.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO sharing_notifications(id,source_event_id,recipient_user_id,event_type) VALUES (?,?,?,'GRANT_CREATED')",
                UUID.randomUUID(), n.sourceEventId(), recipient)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test void unknownOrMismatchedDurableSourceCannotCreateNotification() {
        grant(); var e = event(events().getFirst());
        assertThatThrownBy(() -> ingestor.accept(new PermissionEvent(e.id(), Instant.EPOCH)))
                .isInstanceOf(InvalidNotification.class);
        assertThatThrownBy(() -> ingestor.accept(new PermissionEvent(UUID.randomUUID(), Instant.EPOCH)))
                .isInstanceOf(InvalidNotification.class);
        assertThat(page(recipient).content()).isEmpty();
    }

    @Test void delayedDeliveryAfterDeletionRetainsOnlyGenericRecipientHistory() {
        var g = grant(); grants.revoke(owner, false, file, g.id());
        jdbc.update("DELETE FROM files WHERE id = ?", file);
        deliverAll(); deliverAll();
        assertThat(page(recipient).content()).hasSize(2); assertGeneric();
        notifications.markRead(recipient, page(recipient).content().getFirst().id());
        assertThat(notifications.unreadCount(recipient).unreadCount()).isEqualTo(1);
    }

    @Test void equalDeliveryTimestampsHaveStableUuidOrderAndEmptyRecipientPagesDoNotLeakTotals() {
        var g = grant(); grants.update(owner, false, file, g.id(), ResourceRole.EDITOR, 0L);
        // Create equal timestamps on insertion; identities are immutable afterwards.
        var time = java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        for (var e : events()) jdbc.update("""
                INSERT INTO sharing_notifications(id,source_event_id,recipient_user_id,event_type,created_at)
                VALUES (?,?,?,?,?)
                """, UUID.randomUUID(), e.id(), recipient, e.eventType().name(), time);
        var expected = jdbc.queryForList("SELECT id FROM sharing_notifications WHERE recipient_user_id = ? ORDER BY id DESC", UUID.class, recipient);
        assertThat(notifications.list(recipient, null, null, 0, 1).content().getFirst().id()).isEqualTo(expected.getFirst());
        assertThat(notifications.list(recipient, null, null, 1, 1).content().getFirst().id()).isEqualTo(expected.getLast());
        assertThat(notifications.list(stranger, true, SharingNotificationService.Type.GRANT_CREATED, 20, 1).totalElements()).isZero();
        assertThat(notifications.unreadCount(stranger).unreadCount()).isZero();
    }

    @Test void uncommittedSourceIsInvisibleAndRollbackCannotCreateDeliverableNotification() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(s -> {
                grant(); var pending = event(events().getFirst());
                try {
                    executor.submit(() -> {
                        assertThatThrownBy(() -> ingestor.accept(pending)).isInstanceOf(InvalidNotification.class);
                        assertThat(page(recipient).content()).isEmpty();
                    }).get(5, TimeUnit.SECONDS);
                } catch (Exception failure) { throw new AssertionError(failure); }
                s.setRollbackOnly();
            });
        }
        assertThat(events()).isEmpty(); assertThat(page(recipient).content()).isEmpty();
        assertThatThrownBy(() -> reads.file(recipient, file)).isInstanceOf(RuntimeException.class);
    }

    @Test void databaseFailureAfterInsertRollsBackDeliveryWithoutRollingBackPermissionMutation() throws Exception {
        grant(); var e = event(events().getFirst());
        var target = org.springframework.test.util.AopTestUtils.<PermissionEventIngestor>getUltimateTargetObject(delivery);
        org.mockito.Mockito.doAnswer(call -> {
            call.callRealMethod(); // actual INSERT, then rollback inside the transaction proxy
            throw new org.springframework.dao.DataAccessResourceFailureException("private database failure");
        }).when(target).accept(org.mockito.ArgumentMatchers.any());
        var channel = org.mockito.Mockito.mock(com.rabbitmq.client.Channel.class);
        try {
            assertThatThrownBy(() -> new PermissionEventListener(delivery, mapper, 1).onMessage(message(e), channel))
                    .hasMessage("DATABASE_UNAVAILABLE").hasNoCause();
            org.mockito.Mockito.verify(target, org.mockito.Mockito.times(3)).accept(org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.verifyNoInteractions(channel);
            assertThat(page(recipient).content()).isEmpty();
            assertThat(reads.file(recipient, file)).isNotNull(); assertThat(events()).hasSize(1);
        } finally { org.mockito.Mockito.reset(target); }
        ingestor.accept(e); assertThat(page(recipient).content()).hasSize(1);
    }

    private void assertGeneric() {
        assertThat(page(recipient).content()).isNotEmpty().allSatisfy(n -> {
            assertThat(n.actionable()).isFalse(); assertThat(n.resourceType()).isNull();
            assertThat(n.resourceId()).isNull(); assertThat(n.resourceName()).isNull(); assertThat(n.newRole()).isNull();
        });
    }
}
