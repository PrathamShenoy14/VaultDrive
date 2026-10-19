package com.vaultdrive.file;

import com.vaultdrive.storage.ObjectStorageService;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UploadProtocolIntegrationTest {
    private static final String SCHEMA = "upload_protocol_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource static void isolate(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/vaultdrive_test?currentSchema=" + SCHEMA);
        r.add("spring.flyway.schemas", () -> SCHEMA);
        r.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired org.springframework.context.ApplicationContext context;
    @MockitoBean ObjectStorageService storage;
    @MockitoBean S3Client s3;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removedMultipartPostCannotReserveMetadataOrCallStorage(boolean authenticated) throws Exception {
        long files = count("files"), allocations = count("file_storage_objects"), sessions = count("upload_sessions");
        var request = multipart("/api/v1/files").file(new MockMultipartFile("file", "legacy.txt", "text/plain", new byte[]{1}))
                .param("folderId", UUID.randomUUID().toString());
        if (authenticated) request.with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())));
        mvc.perform(request).andExpect(authenticated ? status().isMethodNotAllowed() : status().isUnauthorized());
        assertThat(count("files")).isEqualTo(files);
        assertThat(count("file_storage_objects")).isEqualTo(allocations);
        assertThat(count("upload_sessions")).isEqualTo(sessions);
        verifyNoInteractions(storage, s3);
    }

    @Test void removedJsonPostHasNoAdmissionFallback() throws Exception {
        assertThat(context.containsBean("multipartResolver")).isFalse();
        long before = count("file_storage_objects");
        mvc.perform(post("/api/v1/files").with(jwt()).contentType("application/json").content("{}"))
                .andExpect(status().isMethodNotAllowed());
        assertThat(count("file_storage_objects")).isEqualTo(before);
        verifyNoInteractions(storage, s3);
    }

    @Test void supportedReservationCreatesOnlyDirectMultipartIdentityAndReplaysOnce() throws Exception {
        UUID owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "fixture", "Protocol")).getId();
        String key = UUID.randomUUID().toString();
        String body = "{\"name\":\"supported.txt\",\"size\":1,\"contentType\":\"text/plain\"}";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/upload-sessions").with(jwt().jwt(j -> j.subject(owner.toString())))
                            .header("Idempotency-Key", key).contentType("application/json").content(body))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("RESERVED"));
        }
        assertThat(jdbc.queryForList("SELECT upload_protocol FROM file_storage_objects WHERE owner_id = ?", String.class, owner))
                .containsExactly("DIRECT_MULTIPART");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM upload_sessions WHERE owner_id = ?", Long.class, owner)).isEqualTo(1);
        verifyNoInteractions(storage, s3);
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
}
