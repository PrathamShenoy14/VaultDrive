package com.vaultdrive.consumer;

import com.vaultdrive.file.*;
import com.vaultdrive.folder.*;
import com.vaultdrive.sharing.*;
import com.vaultdrive.user.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.time.Instant;
import java.util.*;

abstract class SharingNotificationFixtures {
    @Autowired GrantService grants;
    @Autowired ShareLinkService links;
    @Autowired PermissionEventIngestor ingestor;
    @Autowired SharingNotificationService notifications;
    @Autowired PermissionActivityService activity;
    @Autowired ResourceReadService reads;
    @Autowired FileService fileService;
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired FolderRepository folders;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    UUID owner, recipient, stranger, parent, file;
    String email;

    @BeforeEach void fixture() {
        owner = user(); recipient = user(); stranger = user();
        email = users.findById(recipient).orElseThrow().getEmail();
        parent = folders.saveAndFlush(new Folder(owner, null, "private-parent")).getId();
        var f = new StoredFile(UUID.randomUUID(), owner, parent, "snapshot.txt", UUID.randomUUID().toString(), "text/plain", 3);
        f.markReady(); file = files.saveAndFlush(f).getId();
    }

    @AfterEach void cleanup() {
        // Retain immutable activity/notifications; remove only our mutable UUID fixtures.
        tx(() -> {
            jdbc.update("DELETE FROM outbox_events WHERE id IN (SELECT id FROM permission_activity_events WHERE owner_id = ?)", owner);
            jdbc.update("DELETE FROM files WHERE id = ?", file);
            jdbc.update("DELETE FROM folders WHERE id = ?", parent);
            for (UUID id : List.of(owner, recipient, stranger)) jdbc.update("DELETE FROM users WHERE id = ?", id);
        });
    }

    UUID user() { return users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Private actor")).getId(); }
    GrantService.Grant grant() { return grants.create(owner, false, file, email, ResourceRole.VIEWER); }
    List<PermissionActivityService.Activity> events() { return activity.list(owner, null, null, null, 0, 100).content(); }
    PermissionEvent event(PermissionActivityService.Activity e) { return new PermissionEvent(e.id(), e.occurredAt()); }
    void deliverAll() { events().forEach(e -> ingestor.accept(event(e))); }
    SharingNotificationService.Page page(UUID actor) { return notifications.list(actor, null, null, 0, 100); }
    void tx(Runnable action) { new TransactionTemplate(manager).executeWithoutResult(s -> action.run()); }
    Message message(PermissionEvent event) {
        var properties = new MessageProperties();
        properties.setMessageId(event.id().toString());
        properties.setType("PERMISSION_ACTIVITY_RECORDED");
        return new Message(mapper.writeValueAsBytes(Map.of("eventId", event.id(),
                "eventType", "PERMISSION_ACTIVITY_RECORDED", "aggregateType", "PERMISSION_ACTIVITY",
                "aggregateId", event.id(), "payloadVersion", 1, "createdAt", event.createdAt().toString(),
                "payload", Map.of("activityEventId", event.id()))), properties);
    }
}
