package com.amazeawn.common.idempotency;

import com.amazeawn.common.messaging.Message;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a handler exactly once per message id. The dedup row and the handler's own writes share
 * one transaction, so a crash after handling but before the ack simply replays into a no-op.
 */
@Component
public class IdempotentHandler {

    private final ProcessedMessageRepository repository;

    public IdempotentHandler(ProcessedMessageRepository repository) {
        this.repository = repository;
    }

    /** @return true when the handler ran, false when the message was a duplicate. */
    @Transactional
    public boolean handleOnce(Message message, Runnable handler) {
        if (repository.insertIfAbsent(message.messageId()) == 0) {
            return false;
        }
        handler.run();
        return true;
    }
}
