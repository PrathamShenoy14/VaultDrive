package com.vaultdrive.storage;

import com.vaultdrive.storage.config.S3StorageProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.InputStream;
import java.time.Duration;

@Service
public class S3ObjectStorageService implements ObjectStorageService {

    private final S3Client s3Client;
    private final S3StorageProperties properties;
    private final FileStorageIdentityService identity;
    private final StorageBinding binding;

    public static final Duration DELETE_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DELETE_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

    public S3ObjectStorageService(
            S3Client s3Client,
            S3StorageProperties properties,
            FileStorageIdentityService identity,
            StorageBinding binding
    ) {
        this.s3Client = s3Client;
        this.properties = properties;
        this.identity = identity;
        this.binding = binding;
    }

    @Override
    public void upload(
            String storageKey,
            InputStream inputStream,
            long contentLength,
            String contentType
    ) {
        requireNoTransaction();
        StorageTarget target = identity.beginWrite(storageKey); // Commits before the PUT.
        PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                .bucket(target.bucket())
                .key(target.key());

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
        // Existing rejected-upload cleanup API; purge never enters this path.
        delete(binding.target(storageKey));
    }

    @Override
    public void delete(StorageTarget target) {
        requireNoTransaction();
        if (!binding.matches(target)) {
            throw new StorageDeletionException(StorageDeletionException.Kind.BINDING_MISMATCH);
        }
        try {
            s3Client.deleteObject(
                    DeleteObjectRequest.builder()
                            .bucket(target.bucket())
                            .key(target.key())
                            .overrideConfiguration(config -> config.apiCallTimeout(DELETE_TIMEOUT)
                                    .apiCallAttemptTimeout(DELETE_ATTEMPT_TIMEOUT))
                            .build()
            );
        } catch (S3Exception failure) {
            String code = failure.awsErrorDetails() == null ? null : failure.awsErrorDetails().errorCode();
            // Never treat NoSuchBucket, inaccessible buckets or arbitrary 404s as verified absence.
            if (failure.statusCode() == 404 && "NoSuchKey".equals(code)) { return; }
            var kind = failure.statusCode() == 429 || failure.statusCode() >= 500
                    ? StorageDeletionException.Kind.TRANSIENT
                    : failure.statusCode() == 401 || failure.statusCode() == 403
                    ? StorageDeletionException.Kind.ACCESS_DENIED : StorageDeletionException.Kind.STORAGE_FAILURE;
            throw new StorageDeletionException(kind);
        } catch (SdkClientException failure) {
            throw new StorageDeletionException(StorageDeletionException.Kind.TRANSIENT);
        }
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Storage I/O requires no enclosing database transaction");
        }
    }
}
