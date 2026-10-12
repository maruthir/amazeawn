package com.amazeawn.api.order;

import com.amazeawn.api.catalog.ProductRepository;
import com.amazeawn.common.idempotency.IdempotentHandler;
import com.amazeawn.common.messaging.ConsumerSupport;
import com.amazeawn.common.messaging.Events;
import com.amazeawn.common.messaging.Message;
import com.amazeawn.common.messaging.Routing;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Applies processor events to orders, refunds and the catalog's stock mirror. */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final ProductRepository products;
    private final IdempotentHandler idempotent;
    private final ConsumerSupport support;

    public OrderEventListener(OrderRepository orders, RefundRepository refunds, ProductRepository products,
                              IdempotentHandler idempotent, ConsumerSupport support) {
        this.orders = orders;
        this.refunds = refunds;
        this.products = products;
        this.idempotent = idempotent;
        this.support = support;
    }

    @RabbitListener(queues = Routing.API_QUEUE)
    public void onEvent(org.springframework.amqp.core.Message raw, Message event, Channel channel) throws IOException {
        support.consume(raw, event, channel, Routing.API_QUEUE, e -> {
            boolean handled = idempotent.handleOnce(e, () -> apply(e));
            if (!handled) {
                log.info("Duplicate event {} {} skipped", e.getClass().getSimpleName(), e.messageId());
            }
        });
    }

    private void apply(Message event) {
        switch (event) {
            case Events.OrderConfirmed e -> orders.findById(e.orderId()).ifPresent(order -> {
                if (order.getStatus() == OrderStatus.PENDING) {
                    order.setPaymentId(e.paymentId());
                    order.transition(OrderStatus.CONFIRMED);
                } else if (order.getStatus() == OrderStatus.CANCEL_REQUESTED) {
                    // Confirmation raced with a cancel; the processor will still handle the cancel.
                    order.setPaymentId(e.paymentId());
                }
            });
            case Events.OrderRejected e -> orders.findById(e.orderId()).ifPresent(order -> {
                if (order.getStatus() == OrderStatus.PENDING || order.getStatus() == OrderStatus.CANCEL_REQUESTED) {
                    order.setRejectionReason(e.reason());
                    order.transition(OrderStatus.REJECTED);
                }
            });
            case Events.OrderCancelled e -> orders.findById(e.orderId()).ifPresent(order -> {
                if (order.getStatus() == OrderStatus.CANCEL_REQUESTED || order.getStatus() == OrderStatus.PENDING
                        || order.getStatus() == OrderStatus.CONFIRMED) {
                    order.transition(OrderStatus.CANCELLED);
                }
            });
            case Events.RefundCompleted e -> refunds.findById(e.refundId()).ifPresent(refund -> {
                if (refund.getStatus() == Refund.Status.APPROVED) {
                    refund.complete(e.success(), e.reason());
                    if (e.success()) {
                        orders.findById(e.orderId()).ifPresent(order -> order.transition(OrderStatus.REFUNDED));
                    }
                }
            });
            case Events.InventoryChanged e -> e.levels().forEach(l -> products.updateStock(l.productId(), l.available()));
            default -> log.warn("Unhandled event type {}", event.getClass().getName());
        }
    }
}
