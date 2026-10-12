package com.amazeawn.common.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "amazeawn.messaging")
public record MessagingProperties(
        /* Number of concurrent consumers on this service's queue. */
        Integer consumerConcurrency,
        /* Unacked messages each consumer may hold. */
        Integer prefetch,
        /* Delivery attempts before a message is dead-lettered. */
        Integer maxAttempts,
        /* How often the outbox relay polls, in milliseconds. */
        Long outboxPollMs,
        /* Rows the relay publishes per poll. */
        Integer outboxBatchSize
) {
    public MessagingProperties {
        if (consumerConcurrency == null) consumerConcurrency = 4;
        if (prefetch == null) prefetch = 20;
        if (maxAttempts == null) maxAttempts = 3;
        if (outboxPollMs == null) outboxPollMs = 200L;
        if (outboxBatchSize == null) outboxBatchSize = 200;
    }
}
