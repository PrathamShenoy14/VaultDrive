package com.vaultdrive.storage;

import com.vaultdrive.storage.config.S3StorageConfig;
import com.vaultdrive.storage.config.S3StorageProperties;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3MultipartStorageTest {
    final S3StorageProperties properties = new S3StorageProperties("http://localhost:3900", "garage", "fixture", "dummy", "dummy");
    final StorageBinding binding = new StorageBinding(properties, "fixture");
    final S3Client client = mock(S3Client.class);
    final S3Presigner signer = mock(S3Presigner.class);
    final StorageTarget target = binding.target("test key");
    final String sha = Base64.getEncoder().encodeToString(new byte[32]);
    final S3ObjectStorageService adapter = new S3ObjectStorageService(client, properties, mock(FileStorageIdentityService.class), binding, signer);

    @Test void initiateUsesExactBindingAndChecksumMetadataAndBoundedIo() {
        when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class))).thenReturn(CreateMultipartUploadResponse.builder().uploadId("exact-id").build());
        UUID session = UUID.randomUUID(), initiation = UUID.randomUUID();
        assertThat(adapter.initiateMultipart(target, session, initiation, "text/plain")).isEqualTo("exact-id");
        var capture = org.mockito.ArgumentCaptor.forClass(CreateMultipartUploadRequest.class);
        verify(client).createMultipartUpload(capture.capture());
        var request = capture.getValue();
        assertThat(request.key()).isEqualTo(target.key()); assertThat(request.bucket()).isEqualTo(target.bucket());
        assertThat(request.checksumAlgorithm()).isEqualTo(ChecksumAlgorithm.SHA256);
        assertThat(request.metadata()).containsEntry("vaultdrive-session", session.toString()).containsEntry("vaultdrive-initiation", initiation.toString());
        assertThat(request.overrideConfiguration().orElseThrow().apiCallTimeout()).contains(Duration.ofSeconds(10));
    }

    @Test void uncertainInitiationAndInvalidResultHaveNoLoggableUpstreamDetails() {
        when(client.createMultipartUpload(any(CreateMultipartUploadRequest.class))).thenThrow(SdkClientException.create("sensitive detail"));
        assertThatThrownBy(() -> adapter.initiateMultipart(target, UUID.randomUUID(), UUID.randomUUID(), "text/plain"))
                .isInstanceOf(MultipartStorageException.class).hasNoCause().hasMessage("Multipart storage outcome unavailable");
        doReturn(CreateMultipartUploadResponse.builder().build()).when(client).createMultipartUpload(any(CreateMultipartUploadRequest.class));
        assertThatThrownBy(() -> adapter.initiateMultipart(target, UUID.randomUUID(), UUID.randomUUID(), "text/plain"))
                .isInstanceOf(MultipartStorageException.class);
    }

    @Test void realSdkSignerBindsLengthChecksumAndExactIdentity() {
        try (var realSigner = new S3StorageConfig().s3Presigner(properties)) {
            var real = new S3ObjectStorageService(client, properties, mock(FileStorageIdentityService.class), binding, realSigner);
            Instant deadline = Instant.now().plusSeconds(120);
            var signed = real.signPart(target, "exact+id/=", 2, 1234, sha, deadline);
            assertThat(signed.requiredHeaders()).containsEntry("content-length", List.of("1234"))
                    .containsEntry("x-amz-checksum-sha256", List.of(sha)).containsKey("host");
            assertThat(signed.expiresAt()).isBeforeOrEqualTo(deadline);
            String decoded = java.net.URLDecoder.decode(signed.url(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(decoded.contains("uploadId=exact+id/=")).isTrue();
            assertThat(decoded.contains("partNumber=2")).isTrue();
            assertThat(decoded.contains("/fixture/test key")).isTrue();
            assertThat(signed.toString()).doesNotContain("http", "X-Amz");
            verifyNoInteractions(client);
        }
    }

    @Test void signerBehaviorDriftFailsClosedAndSanitizes() {
        var response = mock(PresignedUploadPartRequest.class);
        when(response.signedHeaders()).thenReturn(Map.of("host", List.of("localhost:3900")));
        when(signer.presignUploadPart(any(java.util.function.Consumer.class))).thenReturn(response);
        assertThatThrownBy(() -> adapter.signPart(target, "id", 1, 1, sha, Instant.now().plusSeconds(120)))
                .isInstanceOf(MultipartStorageException.class).hasNoCause();
    }

    @Test void signingLifetimeAndGeometryAreBounded() {
        for (Instant deadline : List.of(Instant.now().minusSeconds(1), Instant.now().plusSeconds(400))) {
            assertThatThrownBy(() -> adapter.signPart(target, "id", 1, 1, sha, deadline)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> adapter.signPart(target, "id", 0, 1, sha, Instant.now().plusSeconds(120))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.signPart(target, "id", 1, 8388609, sha, Instant.now().plusSeconds(120))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(signer, client);
    }

    @Test void bindingDriftAndAmbientTransactionsRejectEveryMultipartOperation() {
        var other = new StorageTarget(target.namespace(), target.endpoint(), target.region(), "other", target.key());
        for (var bad : List.of(other, target)) {
            if (bad == target) { TransactionSynchronizationManager.setActualTransactionActive(true); }
            try {
                assertThatThrownBy(() -> adapter.initiateMultipart(bad, UUID.randomUUID(), UUID.randomUUID(), "text/plain")).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> adapter.signPart(bad, "id", 1, 1, sha, Instant.now().plusSeconds(120))).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> adapter.listParts(bad, "id", 0, 1)).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> adapter.completeMultipart(bad, "id", List.of(part()), sha)).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> adapter.inspectMultipartObject(bad)).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(() -> adapter.abortMultipart(bad, "id")).isInstanceOf(RuntimeException.class);
            } finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        }
        verifyNoInteractions(client, signer);
    }

    @Test void abortUsesExactIdentityAndAcceptsOnlyPositive204WithoutRetry() {
        var response = AbortMultipartUploadResponse.builder();
        response.sdkHttpResponse(software.amazon.awssdk.http.SdkHttpResponse.builder().statusCode(204).build());
        when(client.abortMultipartUpload(any(AbortMultipartUploadRequest.class))).thenReturn(response.build());
        adapter.abortMultipart(target, "exact-upload");
        var capture = org.mockito.ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(client).abortMultipartUpload(capture.capture());
        var request = capture.getValue();
        assertThat(request.bucket()).isEqualTo(target.bucket()); assertThat(request.key()).isEqualTo(target.key());
        assertThat(request.uploadId()).isEqualTo("exact-upload");
        assertThat(request.overrideConfiguration().orElseThrow().apiCallTimeout()).contains(Duration.ofSeconds(10));
        assertThat(request.overrideConfiguration().orElseThrow().apiCallAttemptTimeout()).contains(Duration.ofSeconds(5));
        doThrow(S3Exception.builder().statusCode(404).message("sensitive NoSuchUpload").build()).when(client).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        assertThatThrownBy(() -> adapter.abortMultipart(target, "exact-upload"))
                .isInstanceOf(MultipartStorageException.class).hasNoCause().hasMessage("Multipart storage outcome unavailable");
        verify(client, times(2)).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void malformedAbortAcknowledgementDoesNotInventSuccess() {
        when(client.abortMultipartUpload(any(AbortMultipartUploadRequest.class))).thenReturn(AbortMultipartUploadResponse.builder().build());
        assertThatThrownBy(() -> adapter.abortMultipart(target, "id")).isInstanceOf(MultipartStorageException.class);
        assertThatThrownBy(() -> adapter.abortMultipart(target, "")).isInstanceOf(IllegalArgumentException.class);
        verify(client, times(1)).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
    }

    @Test void completeDispatchesOnlyExactFrozenPartsChecksumAndBoundedRequest() {
        when(client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class))).thenReturn(CompleteMultipartUploadResponse.builder()
                .eTag("opaque-final").checksumSHA256(sha).checksumType(ChecksumType.COMPOSITE).build());
        var result = adapter.completeMultipart(target, "retained-id", List.of(part()), sha);
        assertThat(result).isEqualTo(new MultipartStorage.CompletionAck("opaque-final", sha, "COMPOSITE"));
        var capture = org.mockito.ArgumentCaptor.forClass(CompleteMultipartUploadRequest.class);
        verify(client, times(1)).completeMultipartUpload(capture.capture());
        var request = capture.getValue();
        assertThat(request.key()).isEqualTo(target.key()); assertThat(request.bucket()).isEqualTo(target.bucket());
        assertThat(request.uploadId()).isEqualTo("retained-id"); assertThat(request.checksumSHA256()).isEqualTo(sha);
        assertThat(request.checksumType()).isEqualTo(ChecksumType.COMPOSITE);
        assertThat(request.multipartUpload().parts()).singleElement().satisfies(p -> {
            assertThat(p.partNumber()).isEqualTo(1); assertThat(p.eTag()).isEqualTo("opaque"); assertThat(p.checksumSHA256()).isEqualTo(sha);
        });
        assertThat(request.overrideConfiguration().orElseThrow().apiCallTimeout()).contains(Duration.ofSeconds(10));
        verifyNoInteractions(signer);
    }

    @Test void completionNoSuchUploadAndHeadAbsenceRemainSanitizedUnavailableOutcomes() {
        doThrow(NoSuchUploadException.builder().message("private provider body").build()).when(client)
                .completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        assertThatThrownBy(() -> adapter.completeMultipart(target, "id", List.of(part()), sha))
                .isInstanceOf(MultipartStorageException.class).hasNoCause();
        doThrow(NoSuchKeyException.builder().message("private provider body").build()).when(client).headObject(any(HeadObjectRequest.class));
        assertThatThrownBy(() -> adapter.inspectMultipartObject(target)).isInstanceOf(MultipartStorageException.class).hasNoCause();
        verify(client, times(1)).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
        verify(client, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test void completedObjectInspectionRequestsChecksumsAndPreservesPositiveMetadata() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder().contentLength(123L)
                .eTag("opaque-final").contentType("text/plain").checksumSHA256(sha).metadata(Map.of("session", "identity")).build());
        assertThat(adapter.inspectMultipartObject(target)).isEqualTo(new MultipartStorage.CompletedObject(123, sha, "opaque-final", "text/plain", Map.of("session", "identity")));
        var capture = org.mockito.ArgumentCaptor.forClass(HeadObjectRequest.class); verify(client).headObject(capture.capture());
        assertThat(capture.getValue().checksumMode()).isEqualTo(ChecksumMode.ENABLED);
        assertThat(capture.getValue().key()).isEqualTo(target.key());
        assertThat(capture.getValue().overrideConfiguration().orElseThrow().apiCallTimeout()).contains(Duration.ofSeconds(10));
    }

    private MultipartStorage.ManifestPart part() { return new MultipartStorage.ManifestPart(1, 123, sha, UUID.randomUUID(), "opaque"); }

    @Test void listMapsAuthoritativeOpaquePartsAndPagination() {
        when(client.listParts(any(ListPartsRequest.class))).thenReturn(ListPartsResponse.builder().isTruncated(true).nextPartNumberMarker(1)
                .parts(Part.builder().partNumber(1).size(42L).eTag("opaque").checksumSHA256(sha).build()).build());
        var result = adapter.listParts(target, "id", 0, 1);
        assertThat(result.nextMarker()).isEqualTo(1);
        assertThat(result.parts()).containsExactly(new MultipartStorage.Part(1, 42, "opaque", sha));
        var capture = org.mockito.ArgumentCaptor.forClass(ListPartsRequest.class);
        verify(client).listParts(capture.capture());
        assertThat(capture.getValue().key()).isEqualTo(target.key()); assertThat(capture.getValue().uploadId()).isEqualTo("id");
        assertThat(capture.getValue().maxParts()).isEqualTo(1);
    }

    @Test void listRejectsUnboundedInputInvalidProviderPaginationAndNoSuchUpload() {
        assertThatThrownBy(() -> adapter.listParts(target, "id", 0, 101)).isInstanceOf(IllegalArgumentException.class);
        when(client.listParts(any(ListPartsRequest.class))).thenReturn(ListPartsResponse.builder().isTruncated(true).nextPartNumberMarker(0).build());
        assertThatThrownBy(() -> adapter.listParts(target, "id", 0, 1)).isInstanceOf(MultipartStorageException.class);
        doThrow(NoSuchUploadException.builder().message("private provider body").build()).when(client).listParts(any(ListPartsRequest.class));
        assertThatThrownBy(() -> adapter.listParts(target, "id", 0, 1)).isInstanceOf(MultipartStorageException.class).hasNoCause();
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }
}
