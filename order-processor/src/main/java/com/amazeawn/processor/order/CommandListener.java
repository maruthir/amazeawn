package com.amazeawn.processor.order;

import com.amazeawn.common.idempotency.IdempotentHandler;
import com.amazeawn.common.messaging.Commands;
import com.amazeawn.common.messaging.ConsumerSupport;
import com.amazeawn.common.messaging.Message;
import com.amazeawn.common.messaging.Routing;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CommandListener {

    private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

    private final OrderProcessor processor;
    private final IdempotentHandler idempotent;
    private final ConsumerSupport support;

    public CommandListener(OrderProcessor processor, IdempotentHandler idempotent, ConsumerSupport support) {
        this.processor = processor;
        this.idempotent = idempotent;
        this.support = support;
    }

    @RabbitListener(queues = Routing.PROCESSOR_QUEUE)
    public void onCommand(org.springframework.amqp.core.Message raw, Message command, Channel channel) throws IOException {
        support.consume(raw, command, channel, Routing.PROCESSOR_QUEUE, c -> {
            boolean handled = idempotent.handleOnce(c, () -> dispatch(c));
            if (!handled) {
                log.info("Duplicate command {} {} skipped", c.getClass().getSimpleName(), c.messageId());
            }
        });
    }

    private void dispatch(Message command) {
        switch (command) {
            case Commands.OrderPlace c -> processor.place(c);
            case Commands.OrderCancel c -> processor.cancel(c);
            case Commands.RefundExecute c -> processor.refund(c);
            case Commands.ProductUpsert c -> processor.upsertProduct(c);
            case Commands.InventoryAdjust c -> processor.adjust(c);
            case Commands.InventoryRestock c -> processor.restock(c);
            case Commands.TestReset c -> processor.reset(c);
            default -> log.warn("Unhandled command type {}", command.getClass().getName());
        }
    }
}
