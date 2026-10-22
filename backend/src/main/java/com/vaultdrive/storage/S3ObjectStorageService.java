package com.vaultdrive.storage;

import com.vaultdrive.storage.config.S3StorageProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Service
public class S3ObjectStorageService implements ObjectStorageService {

    private final S3Client s3Client;
    private final S3StorageProperties properties;
    private final StorageBinding binding;
    private final S3Presigner presigner;

    public static final Duration DELETE_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DELETE_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

    @Override
    public CopyStorage.ObjectInfo inspectCopyObject(StorageTarget target) {
        requireNoTransaction();
        if (!binding.matches(target)) { throw new CopyStorage.Uncertain(); }
        try {
            var head = s3Client.headObject(HeadObjectRequest.builder().bucket(target.bucket()).key(target.key())
                    .overrideConfiguration(c -> c.apiCallTimeout(DELETE_TIMEOUT).apiCallAttemptTimeout(DELETE_ATTEMPT_TIMEOUT))
                    .build());
            return new CopyStorage.ObjectInfo(head.contentLength(), head.eTag(), head.contentType(), head.metadata());
        } catch (RuntimeException unavailable) { throw new CopyStorage.Uncertain(); }
    }

    @Override
    public void copyObject(CopyStorage.Command command) {
        requireNoTransaction();
        if (!binding.matches(command.source()) || !binding.matches(command.destination())
                || command.source().equals(command.destination())) { throw new CopyStorage.Uncertain(); }
        try {
            // SDK v2 encodes the source bucket/key. REPLACE prevents copying source session metadata.
            var result = s3Client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(command.source().bucket()).sourceKey(command.source().key())
                    .destinationBucket(command.destination().bucket()).destinationKey(command.destination().key())
                    .copySourceIfMatch(command.sourceEtag()).metadataDirective(MetadataDirective.REPLACE)
                    .contentType(command.contentType())
                    .metadata(Map.of("vaultdrive-copy", command.operationId().toString()))
                    .overrideConfiguration(c -> c.apiCallTimeout(DELETE_TIMEOUT).apiCallAttemptTimeout(DELETE_ATTEMPT_TIMEOUT))
                    .build());
            if (result.copyObjectResult() == null || result.copyObjectResult().eTag() == null) {
                throw new CopyStorage.Uncertain();
            }
        } catch (RuntimeException uncertain) { throw new CopyStorage.Uncertain(); }
    }

    public S3ObjectStorageService(
            S3Client s3Client,
            S3StorageProperties properties,
            StorageBinding binding,
            S3Presigner presigner
    ) {
        this.s3Client = s3Client;
        this.properties = properties;
        this.binding = binding;
        this.presigner = presigner;
    }

    @Override
    public String initiateMultipart(StorageTarget target, UUID sessionId, UUID initiationId, String contentType) {
        requireMultipartTarget(target);
        try {
            String uploadId = s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(target.bucket()).key(target.key()).contentType(contentType)
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                    .metadata(Map.of("vaultdrive-session", sessionId.toString(), "vaultdrive-initiation", initiationId.toString()))
                    .overrideConfiguration(c -> c.apiCallTimeout(DELETE_TIMEOUT).apiCallAttemptTimeout(DELETE_ATTEMPT_TIMEOUT))
                    .build()).uploadId();
            if (uploadId == null || uploadId.isBlank() || uploadId.length() > 2048) { throw new IllegalStateException(); }
            return uploadId;
        } catch (RuntimeException failure) {
            // Deliberately omit the SDK cause, provider body, request and URL from public/loggable errors.
            throw new MultipartStorageException();
        }
    }

    @Override
    public MultipartStorage.SignedPart signPart(StorageTarget target, String uploadId, int number,
            long length, String sha256, Instant deadline) {
        requireMultipartTarget(target);
        Duration lifetime = Duration.ofSeconds(Duration.between(Instant.now(), deadline).toSeconds() - 1);
        if (lifetime.isNegative() || lifetime.toSeconds() < 1 || lifetime.compareTo(Duration.ofMinutes(5)) > 0
                || number < 1 || number > 640 || length < 1 || length > 8388608 || uploadId == null) {
            throw new IllegalArgumentException("Invalid multipart signing bounds");
        }
        try {
            var request = UploadPartRequest.builder().bucket(target.bucket()).key(target.key()).uploadId(uploadId)
                    .partNumber(number).contentLength(length).checksumSHA256(sha256).build();
            var signed = presigner.presignUploadPart(b -> b.signatureDuration(lifetime).uploadPartRequest(request));
            var headers = signed.signedHeaders();
            if (!headers.getOrDefault("content-length", java.util.List.of()).equals(java.util.List.of(Long.toString(length)))
                    || !headers.getOrDefault("x-amz-checksum-sha256", java.util.List.of()).equals(java.util.List.of(sha256))
                    || !headers.containsKey("host") || signed.expiration().isAfter(deadline)) {
                throw new IllegalStateException(); // Fail closed on SDK signature behavior drift.
            }
            return new MultipartStorage.SignedPart(signed.url().toExternalForm(), headers, signed.expiration());
        } catch (RuntimeException failure) { throw new MultipartStorageException(); }
    }

    @Override
    public MultipartStorage.Page listParts(StorageTarget target, String uploadId, int marker, int limit) {
        requireMultipartTarget(target);
        if (marker < 0 || marker > 640 || limit < 1 || limit > 100) { throw new IllegalArgumentException("Invalid listing bounds"); }
        try {
            var result = s3Client.listParts(ListPartsRequest.builder().bucket(target.bucket()).key(target.key())
                    .uploadId(uploadId).partNumberMarker(marker).maxParts(limit)
                    .overrideConfiguration(c -> c.apiCallTimeout(DELETE_TIMEOUT).apiCallAttemptTimeout(DELETE_ATTEMPT_TIMEOUT)).build());
            Integer next = Boolean.TRUE.equals(result.isTruncated()) ? result.nextPartNumberMarker() : null;
            if (result.parts().size() > limit || (Boolean.TRUE.equals(result.isTruncated()) && next == null)
                    || (next != null && (next <= marker || next > 640))) { throw new IllegalStateException(); }
            return new MultipartStorage.Page(result.parts().stream().map(p ->
                    new MultipartStorage.Part(p.partNumber(), p.size(), p.eTag(), p.checksumSHA256())).toList(), next);
        } catch (RuntimeException failure) { throw new MultipartStorageException(); }
    }

    @Override
    public MultipartStorage.CompletionAck completeMultipart(StorageTarget target, String uploadId,
            java.util.List<MultipartStorage.ManifestPart> manifest, String compositeSha256) {
        requireMultipartTarget(target);
        if (manifest.isEmpty() || manifest.size() > 640) { throw new IllegalArgumentException("Invalid completion manifest bound"); }
        try {
            var result = s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(target.bucket()).key(target.key()).uploadId(uploadId)
                    .checksumType(ChecksumType.COMPOSITE).checksumSHA256(compositeSha256)
                    .multipartUpload(b -> b.parts(manifest.stream().map(p -> CompletedPart.builder()
                            .partNumber(p.partNumber()).eTag(p.etag()).checksumSHA256(p.sha256()).build()).toList()))
                    .overrideConfiguration(c -> c.apiCallTimeout(DELETE_TIMEOUT).apiCallAttemptTimeout(DELETE_ATTEMPT_TIMEOUT)).build());
            return new MultipartStorage.CompletionAck(result.eTag(), result.checksumSHA256(), result.checksumTypeAsString());
        } catch (RuntimeException failure) { throw new MultipartStorageException(); }
    }

    @Override
    public MultipartStorage.CompletedObject inspectMultipartObject(StorageTarget target) {
        requireMultipartTarget(target);
        try {
            var head = s3Client.headObject(HeadObjectRequest.builder().bucket(target.bucket()).key(target.key())
                    .checksumMode(ChecksumMode.ENABLED)
                    .overrideConfiguration(c -> c.apiCallTimeout(DELETE_TIMEOUT).apiCallAttemptTimeout(DELETE_ATTEMPT_TIMEOUT)).build());
            return new MultipartStorage.CompletedObject(head.contentLength(), head.checksumSHA256(), head.eTag(), head.contentType(), head.metadata());
        } catch (RuntimeException failure) {
            // Includes absent objects. Absence never resolves a dispatched completion.
            throw new MultipartStorageException();
        }
    }

    @Override
    public void abortMultipart(StorageTarget target, String uploadId) {
        requireMultipartTarget(target);
        if (uploadId == null || uploadId.isBlank() || uploadId.length() > 2048) {
            throw new IllegalArgumentException("Invalid upload identity");
        }
        try {
            var result = s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(target.bucket()).key(target.key()).uploadId(uploadId)
                    .overrideConfiguration(c -> c.apiCallTimeout(DELETE_TIMEOUT).apiCallAttemptTimeout(DELETE_ATTEMPT_TIMEOUT)).build());
            if (result.sdkHttpResponse() == null || result.sdkHttpResponse().statusCode() != 204) {
                throw new IllegalStateException();
            }
        } catch (RuntimeException uncertain) { throw new MultipartStorageException(); }
    }

    private void requireMultipartTarget(StorageTarget target) {
        requireNoTransaction();
        if (!binding.matches(target)) { throw new MultipartStorageException(); }
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
