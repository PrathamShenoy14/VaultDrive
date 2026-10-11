package com.vaultdrive.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.vaultdrive.storage.config.S3StorageProperties;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.util.UUID;

@SpringBootTest
@ActiveProfiles("test")
class S3StorageIntegrationTest {

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3StorageProperties properties;

    @Autowired
    private ObjectStorageService objectStorageService;

    @Test
    void shouldPutGetAndDeleteObject() {

        String key = "integration-tests/" + UUID.randomUUID() + "/hello.txt";

        byte[] originalContent =
                "Hello from VaultDrive".getBytes(StandardCharsets.UTF_8);

        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType("text/plain")
                            .build(),
                    RequestBody.fromBytes(originalContent)
            );

            ResponseBytes<GetObjectResponse> downloaded =
                    s3Client.getObjectAsBytes(
                            GetObjectRequest.builder()
                                    .bucket(properties.bucket())
                                    .key(key)
                                    .build()
                    );

            assertEquals("text/plain", downloaded.response().contentType());
            assertArrayEquals(originalContent, downloaded.asByteArray());

            s3Client.deleteObject(builder -> builder
                    .bucket(properties.bucket())
                    .key(key)
            );

            assertThrows(
                    NoSuchKeyException.class,
                    () -> s3Client.getObjectAsBytes(
                            GetObjectRequest.builder()
                                    .bucket(properties.bucket())
                                    .key(key)
                                    .build()
                    )
            );
        } finally {
            s3Client.deleteObject(builder -> builder.bucket(properties.bucket()).key(key));
        }
    }

    @Test
    void shouldDownloadObjectThroughStorageService()
            throws Exception {
    
        String key =
                "integration-tests/" + UUID.randomUUID() + "/storage-service-download.txt";
    
        byte[] originalContent =
                "VaultDrive download integration test"
                        .getBytes(StandardCharsets.UTF_8);
    
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(properties.bucket())
                            .key(key)
                            .contentType("text/plain")
                            .build(),
                    RequestBody.fromBytes(originalContent)
            );

            StorageObject storageObject =
                    objectStorageService.download(key);
    
            try (InputStream inputStream =
                         storageObject.inputStream()) {
    
                byte[] downloadedContent =
                        inputStream.readAllBytes();
    
                assertArrayEquals(
                        originalContent,
                        downloadedContent
                );
            }
        } finally {
            s3Client.deleteObject(builder -> builder
                    .bucket(properties.bucket())
                    .key(key)
            );
        }
    }
}
