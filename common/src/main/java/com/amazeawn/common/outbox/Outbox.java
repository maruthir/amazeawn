package com.amazeawn.common.outbox;

import com.amazeawn.common.messaging.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Call {@link #enqueue(Message)} inside the business transaction. The message is persisted
 * with the data change and published only after both have committed.
 */
@Service
public class Outbox {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public Outbox(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(Message message) {
        try {
            String payload = objectMapper.writeValueAsString(message);
            repository.save(new OutboxEvent(message.messageId(), message.routingKey(),
                    message.getClass().getName(), message.correlationId(), payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise outbox message " + message, e);
        }
    }
}
