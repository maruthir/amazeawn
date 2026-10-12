package com.amazeawn.common.outbox;

import com.amazeawn.common.messaging.MessagingProperties;
import com.amazeawn.common.messaging.Routing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Polls the outbox table and publishes claimed rows. Publishing happens inside the claiming
 * transaction so a crash mid-batch leaves rows unpublished and they are retried; the consumer's
 * idempotency check absorbs the resulting duplicates.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties props;
    private final Counter published;

    public OutboxRelay(OutboxRepository repository, RabbitTemplate rabbitTemplate,
                       MessagingProperties props, MeterRegistry registry) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
        this.published = Counter.builder("amazeawn.outbox.published").register(registry);
        registry.gauge("amazeawn.outbox.pending", repository, r -> r.countByPublishedAtIsNull());
    }

    @Scheduled(fixedDelayString = "${amazeawn.messaging.outbox-poll-ms:200}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repository.claimUnpublished(PageRequest.of(0, props.outboxBatchSize()));
        if (batch.isEmpty()) {
            return;
        }
        // One channel for the batch; waitForConfirmsOrDie throws (rolling back the claim) if the broker
        // did not confirm every publish, so a row is only marked published once the broker has it.
        rabbitTemplate.invoke(ops -> {
            for (OutboxEvent event : batch) {
                MessageProperties mp = new MessageProperties();
                mp.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                mp.setContentEncoding(StandardCharsets.UTF_8.name());
                mp.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                mp.setMessageId(event.getId().toString());
                mp.setHeader(DefaultJackson2JavaTypeMapper.DEFAULT_CLASSID_FIELD_NAME, event.getMessageType());
                mp.setHeader(Routing.HEADER_CORRELATION_ID, event.getCorrelationId());
                mp.setHeader(Routing.HEADER_ATTEMPT, 1);
                ops.send(Routing.EXCHANGE, event.getRoutingKey(),
                        MessageBuilder.withBody(event.getPayload().getBytes(StandardCharsets.UTF_8)).andProperties(mp).build());
            }
            ops.waitForConfirmsOrDie(5_000);
            return null;
        });
        for (OutboxEvent event : batch) {
            event.markPublished();
        }
        published.increment(batch.size());
        log.debug("Relayed {} outbox messages", batch.size());
    }

    @Scheduled(fixedDelayString = "${amazeawn.messaging.outbox-cleanup-ms:60000}")
    @Transactional
    public void cleanup() {
        int removed = repository.deletePublishedBefore(Instant.now().minus(Duration.ofMinutes(10)));
        if (removed > 0) {
            log.debug("Purged {} published outbox rows", removed);
        }
    }
}
