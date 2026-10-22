package com.vaultdrive.storage;

import com.vaultdrive.storage.config.S3StorageProperties;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3CopyStorageTest {
    final S3StorageProperties properties=new S3StorageProperties("http://localhost:3900","garage","fixture","dummy","dummy");
    final StorageBinding binding=new StorageBinding(properties,"fixture");
    final S3Client s3=mock(S3Client.class);
    final S3ObjectStorageService adapter=new S3ObjectStorageService(s3,properties,binding,mock(S3Presigner.class));
    final CopyStorage.Command command=new CopyStorage.Command(binding.target("source space/+"),binding.target("destination"),UUID.randomUUID(),"\"original\"","text/plain");

    @Test void sourceConditionAndReplacementMarkerAreSentWithBoundedCall() {
        when(s3.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().copyObjectResult(r->r.eTag("etag")).build());
        adapter.copyObject(command);
        var capture=org.mockito.ArgumentCaptor.forClass(CopyObjectRequest.class);verify(s3).copyObject(capture.capture());var r=capture.getValue();
        assertThat(r.sourceKey()).isEqualTo(command.source().key());assertThat(r.destinationKey()).isEqualTo(command.destination().key());
        assertThat(r.copySourceIfMatch()).isEqualTo(command.sourceEtag());assertThat(r.metadataDirective()).isEqualTo(MetadataDirective.REPLACE);
        assertThat(r.metadata()).containsExactly(entry("vaultdrive-copy",command.operationId().toString()));
        assertThat(r.overrideConfiguration().orElseThrow().apiCallTimeout()).contains(Duration.ofSeconds(10));
    }
    @Test void copyErrorEmptyResultAndHeadAbsenceRemainSanitizedUncertain() {
        when(s3.copyObject(any(CopyObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(412).message("private provider detail").build());
        assertThatThrownBy(()->adapter.copyObject(command)).isInstanceOf(CopyStorage.Uncertain.class).hasNoCause().hasMessage("Copy storage outcome unavailable");
        doReturn(CopyObjectResponse.builder().build()).when(s3).copyObject(any(CopyObjectRequest.class));
        assertThatThrownBy(()->adapter.copyObject(command)).isInstanceOf(CopyStorage.Uncertain.class);
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(NoSuchKeyException.builder().message("private key").build());
        assertThatThrownBy(()->adapter.inspectCopyObject(command.destination())).isInstanceOf(CopyStorage.Uncertain.class).hasNoCause();
    }
    @Test void transactionAndBindingGuardsPreventStorageCalls() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(()->adapter.copyObject(command)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->adapter.inspectCopyObject(command.destination())).isInstanceOf(IllegalStateException.class);
        }finally{TransactionSynchronizationManager.setActualTransactionActive(false);}
        var foreign=new StorageTarget("different",command.source().endpoint(),"garage","fixture","source");
        assertThatThrownBy(()->adapter.copyObject(new CopyStorage.Command(foreign,command.destination(),command.operationId(),"etag","text/plain"))).isInstanceOf(CopyStorage.Uncertain.class);
        verifyNoInteractions(s3);
    }
}
