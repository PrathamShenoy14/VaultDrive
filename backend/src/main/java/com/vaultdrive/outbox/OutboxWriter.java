package com.vaultdrive.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxWriter {

    private final OutboxEventRepository outboxEventRepository;

    public OutboxWriter(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    // Never start an independent transaction: the business transition and
    // notification must commit or roll back on the same JPA connection.
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent append(OutboxEvent event) {
        return outboxEventRepository.saveAndFlush(event);
    }
}
