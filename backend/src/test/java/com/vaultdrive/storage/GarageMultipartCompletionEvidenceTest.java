package com.vaultdrive.storage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.services.s3.model.*;

import java.security.MessageDigest;
import java.util.*;

import static com.vaultdrive.storage.GarageMultipartFixture.sha;
import static org.assertj.core.api.Assertions.*;

/** M3 gate: characterize the pinned Garage composite checksum, rather than assuming AWS formatting. */
@EnabledIfEnvironmentVariable(named = "GARAGE_MULTIPART_COMPLETION_TEST", matches = "true")
@Timeout(60)
class GarageMultipartCompletionEvidenceTest {
    @Test void completeAndHeadExposeExactCompositeChecksumAndMetadataForVerifiedBytes() throws Exception {
        try (var f = new GarageMultipartFixture()) {
            var a = f.create(true);
            byte[] first = new byte[8388608], last = new byte[1234];
            new Random(1).nextBytes(first); new Random(2).nextBytes(last);
            var one = f.upload(a, 1, first, true); var two = f.upload(a, 2, last, true);
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update(Base64.getDecoder().decode(sha(first)));
            hash.update(Base64.getDecoder().decode(sha(last)));
            String composite = Base64.getEncoder().encodeToString(hash.digest());
            assertThat(f.list(a, 0, 100).parts()).hasSize(2);
            a.completionDispatched = true; f.event("COMPLETE_DISPATCH", a.key);
            CompleteMultipartUploadResponse result;
            try {
                result = f.s3.completeMultipartUpload(f.completion(a, List.of(one, two)).toBuilder()
                        .checksumSHA256(composite).checksumType(ChecksumType.COMPOSITE).build());
                a.completed = true; f.event("COMPLETE_HTTP_200", "composite_checksum_received=true");
            } catch (RuntimeException failure) { a.uncertain = true; throw GarageMultipartFixture.sanitized(failure); }
            assertThat(result.checksumSHA256()).isEqualTo(composite);
            assertThat(result.checksumType()).isEqualTo(ChecksumType.COMPOSITE);
            var head = f.s3.headObject(b -> b.bucket(f.properties.bucket()).key(a.key).checksumMode(ChecksumMode.ENABLED));
            assertThat(head.contentLength()).isEqualTo(first.length + last.length);
            assertThat(head.checksumSHA256()).isEqualTo(composite);
            assertThat(head.eTag()).isEqualTo(result.eTag());
            assertThat(head.metadata()).containsEntry("vaultdrive-m0-run", f.run);
            byte[] expected = Arrays.copyOf(first, first.length + last.length);
            System.arraycopy(last, 0, expected, first.length, last.length);
            f.assertBytes(a, expected);
            f.event("M3_EVIDENCE_GATE", "parts=2 checksum=SHA256_COMPOSITE_BASE64_NO_SUFFIX metadata=true exactBytes=true");
        }
    }
}
