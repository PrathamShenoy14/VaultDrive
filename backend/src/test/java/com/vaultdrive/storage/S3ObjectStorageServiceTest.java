package com.vaultdrive.storage;

import com.vaultdrive.storage.config.S3StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;


import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3ObjectStorageServiceTest {
    private final S3Client client = mock(S3Client.class);
    private final S3StorageProperties properties = new S3StorageProperties(
            "http://localhost:3900", "garage", "fixture-bucket", "unused", "unused");
    private final StorageBinding binding = new StorageBinding(properties, "fixture-cluster");
    private S3ObjectStorageService storage;

    @BeforeEach void setup() { storage = new S3ObjectStorageService(client, properties, binding, org.mockito.Mockito.mock(software.amazon.awssdk.services.s3.presigner.S3Presigner.class)); }

    @Test void availabilityUsesOnlyBoundedHeadBucketOutsideTransactions() {
        storage.checkAvailability();
        var capture=org.mockito.ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.HeadBucketRequest.class);
        verify(client).headBucket(capture.capture());
        assertThat(capture.getValue().bucket()).isEqualTo("fixture-bucket");
        assertThat(capture.getValue().overrideConfiguration().orElseThrow().apiCallTimeout()).contains(S3ObjectStorageService.DELETE_TIMEOUT);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(storage::checkAvailability).isInstanceOf(IllegalStateException.class);}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        verifyNoMoreInteractions(client);
    }
    @Test void interruptedDownloadAbortsButCompletedDownloadClosesNormally() throws Exception {
        for(boolean complete:new boolean[]{false,true}){
            @SuppressWarnings("unchecked") software.amazon.awssdk.core.ResponseInputStream<software.amazon.awssdk.services.s3.model.GetObjectResponse> stream=mock(software.amazon.awssdk.core.ResponseInputStream.class);
            when(stream.read(any(byte[].class),anyInt(),anyInt())).thenReturn(-1);
            when(client.getObject(any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class))).thenReturn(stream);
            try(var input=storage.download("fixture").inputStream()){if(complete)assertThat(input.read(new byte[1])).isEqualTo(-1);}
            if(complete){verify(stream).close();verify(stream,never()).abort();}
            else{verify(stream).abort();verify(stream,never()).close();}
        }
    }

    @Test void deleteUsesExactTargetWithBoundedRequestTimeouts() {
        StorageTarget target = binding.target("fixture-key");
        storage.delete(target);
        var capture = org.mockito.ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(client).deleteObject(capture.capture());
        assertThat(capture.getValue().bucket()).isEqualTo(target.bucket());
        assertThat(capture.getValue().key()).isEqualTo(target.key());
        var config = capture.getValue().overrideConfiguration().orElseThrow();
        assertThat(config.apiCallTimeout()).contains(S3ObjectStorageService.DELETE_TIMEOUT);
        assertThat(config.apiCallAttemptTimeout()).contains(S3ObjectStorageService.DELETE_ATTEMPT_TIMEOUT);
    }

    @ParameterizedTest
    @CsvSource({"other,http://localhost:3900,garage,fixture-bucket",
            "fixture-cluster,http://localhost:3901,garage,fixture-bucket",
            "fixture-cluster,http://localhost:3900,other,fixture-bucket",
            "fixture-cluster,http://localhost:3900,garage,other"})
    void changedBindingsFailBeforeAnyStorageCall(String namespace, String endpoint, String region, String bucket) {
        assertThatThrownBy(() -> storage.delete(new StorageTarget(namespace, endpoint, region, bucket, "key")))
                .isInstanceOf(StorageDeletionException.class)
                .hasMessage("BINDING_MISMATCH");
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({"404,NoSuchKey,SUCCESS", "404,NoSuchBucket,STORAGE_FAILURE",
            "404,Unknown,STORAGE_FAILURE", "403,AccessDenied,ACCESS_DENIED",
            "500,InternalError,TRANSIENT", "503,ServiceUnavailable,TRANSIENT", "429,SlowDown,TRANSIENT"})
    void classifiesFailuresWithoutTreatingMissingBucketAsObjectAbsence(int status, String code, String expected) {
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(S3Exception.builder()
                .statusCode(status).message("private provider detail")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build());
        if (expected.equals("SUCCESS")) { storage.delete(binding.target("key")); }
        else {
            assertThatThrownBy(() -> storage.delete(binding.target("key")))
                    .isInstanceOf(StorageDeletionException.class).hasMessage(expected)
                    .satisfies(error -> assertThat(((StorageDeletionException) error).retryable())
                            .isEqualTo(expected.equals("TRANSIENT")));
        }
    }

    @Test void uncertainTransportOutcomeIsRetryable() {
        when(client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(SdkClientException.create("private"));
        assertThatThrownBy(() -> storage.delete(binding.target("key")))
                .isInstanceOf(StorageDeletionException.class).hasMessage("TRANSIENT");
    }

    @Test void storageRejectsAnEnclosingDatabaseTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> storage.delete(binding.target("key"))).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(client);
        } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
    }
}
