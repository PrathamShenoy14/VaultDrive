package com.vaultdrive.sharing;

import com.vaultdrive.file.*;
import com.vaultdrive.security.JwtService;
import com.vaultdrive.storage.config.S3StorageProperties;
import com.vaultdrive.user.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Real HTTP/JWT, PostgreSQL and Garage; deletes only this test's uniquely named object/metadata. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ShareLinkGarageIntegrationTest {
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired StoredFileRepository files;
    @Autowired ShareLinkService links;
    @Autowired FileService fileService;
    @Autowired S3Client s3;
    @Autowired S3StorageProperties properties;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test void backendStreamsExactGarageBytesAndRevalidatesTrashRestoreAndRevocation() throws Exception {
        UUID owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "Private owner")).getId();
        UUID file = UUID.randomUUID();
        String key = "integration-tests/share-links/" + UUID.randomUUID();
        byte[] bytes = new byte[256 * 1024]; new Random(41).nextBytes(bytes);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try {
            s3.putObject(b -> b.bucket(properties.bucket()).key(key).contentType("text/html"), RequestBody.fromBytes(bytes));
            var f = new StoredFile(file, owner, null, "download.html", key, "text/html", bytes.length);
            f.markReady(); files.saveAndFlush(f);
            URI ownerPath = URI.create("http://localhost:" + port + "/api/v1/files/" + file + "/share-links");
            var created = client.send(HttpRequest.newBuilder(ownerPath).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + jwt.generateAccessToken(owner)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(created.statusCode()).isEqualTo(201);
            assertThat(created.headers().firstValue("Cache-Control")).contains("no-store");
            var json = mapper.readTree(created.body()); String token = json.get("secret").asString();
            UUID link = UUID.fromString(json.get("link").get("id").asString());
            String path = "http://localhost:" + port + ShareLinkIntegrationTest.publicPath(link);
            var metadata = client.send(request(path, token), HttpResponse.BodyHandlers.ofString());
            assertThat(metadata.statusCode()).isEqualTo(200);
            assertThat(metadata.body()).doesNotContain(owner.toString(), file.toString(), key, token);
            var download = client.send(request(path + "/download", token), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(download.statusCode()).isEqualTo(200); assertThat(download.body()).isEqualTo(bytes);
            assertThat(download.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;");
            assertThat(download.headers().firstValue("Content-Type")).contains("application/octet-stream");
            assertThat(download.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(download.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(download.headers().firstValue("Location")).isEmpty();
            fileService.trashFile(owner, file);
            assertThat(client.send(request(path + "/download", token), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
            fileService.restoreFile(owner, file);
            assertThat(client.send(request(path + "/download", token), HttpResponse.BodyHandlers.ofByteArray()).body()).isEqualTo(bytes);
            var revoked = client.send(HttpRequest.newBuilder(URI.create(ownerPath + "/" + link)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + jwt.generateAccessToken(owner)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(revoked.statusCode()).isEqualTo(204);
            assertThat(client.send(request(path + "/download", token), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
            assertThat(client.send(request(path, token), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        } finally {
            s3.deleteObject(b -> b.bucket(properties.bucket()).key(key));
            jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", file);
            files.deleteById(file); files.flush(); users.deleteById(owner);
        }
    }

    private static HttpRequest request(String path, String secret) {
        return HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(15))
                .header(ShareLinkHttpFilter.TOKEN_HEADER, secret).GET().build();
    }
}
