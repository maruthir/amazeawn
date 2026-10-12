package com.amazeawn.common.messaging;

import java.util.UUID;

/**
 * Every command and event carries a unique message id (used for consumer idempotency)
 * and a correlation id that follows a request through the API, the queue and the processor.
 */
public interface Message {
    UUID messageId();

    String correlationId();

    /** The routing key this message is published with. */
    String routingKey();
}
