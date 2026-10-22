package com.vaultdrive.consumer;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QuotaEventTest {
    final ObjectMapper mapper=new ObjectMapper();
    final UUID id=UUID.randomUUID();
    final Instant time=Instant.parse("2026-10-22T12:00:00Z");
    ObjectNode valid() { return (ObjectNode)mapper.valueToTree(Map.of("eventId",id,"eventType","STORAGE_QUOTA_THRESHOLD_CROSSED",
            "aggregateType","STORAGE_QUOTA_EVENT","aggregateId",id,"payloadVersion",1,"createdAt",time.toString(),"payload",Map.of("quotaEventId",id))); }
    Message message(ObjectNode root) {
        var p=new MessageProperties();p.setType("STORAGE_QUOTA_THRESHOLD_CROSSED");p.setMessageId(id.toString());p.setDeliveryTag(7);
        return new Message(mapper.writeValueAsBytes(root),p);
    }
    @Test void validEnvelopeUsesImmutableSourcePointerOnly() {
        var root=valid();root.put("future","ignored");
        assertThat(QuotaEvent.parse(message(root),mapper)).isEqualTo(new QuotaEvent(id,time));
    }
    @ParameterizedTest @ValueSource(strings={"eventId","eventType","aggregateType","aggregateId","payloadVersion","createdAt","payload"})
    void missingFieldsAreRejected(String field) {
        var root=valid();root.remove(field);
        assertThatThrownBy(()->QuotaEvent.parse(message(root),mapper)).isInstanceOf(InvalidNotification.class).hasNoCause();
    }
    @Test void wrongSourceTypeVersionAndPointerAreRejected() {
        var root=valid();root.put("eventType","PERMISSION_ACTIVITY_RECORDED");reject(root);
        root=valid();root.put("payloadVersion",2);reject(root);
        root=valid();((ObjectNode)root.get("payload")).put("quotaEventId",UUID.randomUUID().toString());reject(root);
        root=valid();root.put("aggregateId",UUID.randomUUID().toString());reject(root);
        root=valid();root.put("aggregateType","PERMISSION_ACTIVITY");reject(root);
    }
    @Test void quotaDeliveryRetriesThreeTimesBeforeAckAndDoesNotCallPermissionIngest() throws Exception {
        var ingestor=mock(PermissionEventIngestor.class);var channel=mock(Channel.class);
        doThrow(new DataAccessResourceFailureException("private")).doThrow(new DataAccessResourceFailureException("private"))
                .doNothing().when(ingestor).acceptQuota(any());
        new PermissionEventListener(ingestor,mapper,1).onMessage(message(valid()),channel);
        var order=inOrder(ingestor,channel);order.verify(ingestor,times(3)).acceptQuota(new QuotaEvent(id,time));order.verify(channel).basicAck(7,false);
        verify(ingestor,never()).accept(any());
    }
    @Test void quotaDatabaseExhaustionStopsUnacknowledged() {
        var ingestor=mock(PermissionEventIngestor.class);var channel=mock(Channel.class);
        doThrow(new DataAccessResourceFailureException("private")).when(ingestor).acceptQuota(any());
        assertThatThrownBy(()->new PermissionEventListener(ingestor,mapper,1).onMessage(message(valid()),channel)).hasMessage("DATABASE_UNAVAILABLE").hasNoCause();
        verify(ingestor,times(3)).acceptQuota(any());verifyNoInteractions(channel);
    }
    void reject(ObjectNode root) { assertThatThrownBy(()->QuotaEvent.parse(message(root),mapper)).isInstanceOf(InvalidNotification.class).hasNoCause(); }
}
