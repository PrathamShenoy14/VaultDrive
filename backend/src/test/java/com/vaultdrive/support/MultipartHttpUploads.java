package com.vaultdrive.support;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit direct-multipart HTTP fixture for the separately gated process harnesses. */
public final class MultipartHttpUploads {
    private MultipartHttpUploads() {}

    public static UUID upload(HttpClient http, ObjectMapper mapper, String base, String token,
            UUID folder, String name, byte[] bytes) throws Exception {
        var reservation = new java.util.HashMap<String, Object>(Map.of(
                "name", name, "size", bytes.length, "contentType", "text/plain"));
        if (folder != null) reservation.put("folderId", folder);
        var response = http.send(api(base + "/upload-sessions", token)
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(reservation))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("multipart reservation (body suppressed)").isEqualTo(201);
        var session = mapper.readTree(response.body());
        UUID file = UUID.fromString(session.get("fileId").asText());
        String path = base + "/upload-sessions/" + session.get("id").asText();
        response = http.send(api(path + "/initiate", token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("multipart initiation (body suppressed)").isEqualTo(200);
        String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        response = http.send(api(path + "/part-urls", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("parts",
                        java.util.List.of(Map.of("partNumber", 1, "length", bytes.length, "sha256", hash)))))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("multipart authorization (body suppressed)").isEqualTo(200);
        var upload = mapper.readTree(response.body()).get(0).get("upload");
        // No API JWT is sent to Garage. Host and Content-Length come from the exact URI/body.
        var put = HttpRequest.newBuilder(URI.create(upload.get("url").asText())).timeout(Duration.ofSeconds(15));
        upload.get("requiredHeaders").properties().forEach(entry -> {
            if (!entry.getKey().equalsIgnoreCase("host") && !entry.getKey().equalsIgnoreCase("content-length")) {
                entry.getValue().forEach(value -> put.header(entry.getKey(), value.asText()));
            }
        });
        var stored = http.send(put.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(stored.statusCode()).as("direct part PUT").isEqualTo(200);
        response = http.send(api(path + "/complete", token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("multipart completion (body suppressed)").isEqualTo(200);
        assertThat(mapper.readTree(response.body()).get("completion").get("disposition").asText()).isEqualTo("READY");
        return file;
    }

    private static HttpRequest.Builder api(String url, String token) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token);
    }
}
