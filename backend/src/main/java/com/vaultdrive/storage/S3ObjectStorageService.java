package com.vaultdrive.storage;

import com.vaultdrive.storage.config.S3StorageProperties;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.InputStream;

@Service
public class S3ObjectStorageService implements ObjectStorageService {

    private final S3Client s3Client;
    private final S3StorageProperties properties;

    public S3ObjectStorageService(
            S3Client s3Client,
            S3StorageProperties properties
    ) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public void upload(
            String storageKey,
            InputStream inputStream,
            long contentLength,
            String contentType
    ) {
        PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(storageKey);

        if (contentType != null && !contentType.isBlank()) {
            requestBuilder.contentType(contentType);
        }

        s3Client.putObject(
                requestBuilder.build(),
                RequestBody.fromInputStream(inputStream, contentLength)
        );
    }

    @Override
    public StorageObject download(String storageKey) {

        GetObjectRequest request =
                GetObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(storageKey)
                        .build();

        ResponseInputStream<GetObjectResponse> responseStream =
                s3Client.getObject(request);

        return new StorageObject(responseStream);
    }

    @Override
    public void delete(String storageKey) {
        s3Client.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(storageKey)
                        .build()
        );
    }
}
