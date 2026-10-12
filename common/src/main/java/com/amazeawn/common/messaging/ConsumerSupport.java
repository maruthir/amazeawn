package com.amazeawn.common.messaging;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Wraps a listener body with manual ack, bounded retry and dead-lettering.
 * <p>
 * On failure the message is re-published to the same queue with an incremented attempt header
 * and the original is acked; once {@code maxAttempts} is reached it is rejected without requeue,
 * which the broker routes to the queue's dead-letter queue.
 */
@Component
public class ConsumerSupport {

    private static final Logger log = LoggerFactory.getLogger(ConsumerSupport.class);

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties props;
    private final MeterRegistry registry;

    public ConsumerSupport(RabbitTemplate rabbitTemplate, MessagingProperties props, MeterRegistry registry) {
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
        this.registry = registry;
    }

    public void consume(org.springframework.amqp.core.Message raw, Message message, Channel channel,
                        String queue, Consumer<Message> body) throws IOException {
        MessageProperties mp = raw.getMessageProperties();
        long tag = mp.getDeliveryTag();
        String type = message.getClass().getSimpleName();
        CorrelationId.set(message.correlationId());
        try {
            body.accept(message);
            channel.basicAck(tag, false);
            counter("amazeawn.consumer.handled", queue, type).increment();
        } catch (Exception ex) {
            int attempt = attemptOf(mp);
            if (attempt < props.maxAttempts()) {
                log.warn("{} {} failed on attempt {}/{}; retrying: {}", type, message.messageId(), attempt,
                        props.maxAttempts(), ex.toString());
                mp.setHeader(Routing.HEADER_ATTEMPT, attempt + 1);
                rabbitTemplate.send("", queue, raw);
                channel.basicAck(tag, false);
                counter("amazeawn.consumer.retried", queue, type).increment();
            } else {
                log.error("{} {} failed on final attempt {}; dead-lettering", type, message.messageId(), attempt, ex);
                channel.basicReject(tag, false);
                counter("amazeawn.consumer.dead_lettered", queue, type).increment();
            }
        } finally {
            CorrelationId.clear();
        }
    }

    private static int attemptOf(MessageProperties mp) {
        Object header = mp.getHeader(Routing.HEADER_ATTEMPT);
        return header instanceof Number n ? n.intValue() : 1;
    }

    private Counter counter(String name, String queue, String type) {
        return Counter.builder(name).tag("queue", queue).tag("type", type).register(registry);
    }
}
