package com.amazeawn.processor;

import com.amazeawn.common.messaging.Commands;
import com.amazeawn.common.messaging.Events;
import com.amazeawn.common.messaging.Routing;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/** Sends commands into the processor's queue and asserts on the events it publishes. */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "amazeawn.payment.latency-ms=0",
        "amazeawn.payment.jitter-ms=0",
        "amazeawn.payment.failure-rate=0"
})
class ProcessorIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired RabbitTemplate rabbitTemplate;

    @Test
    void reservesStockChargesAndRejectsWhenOutOfStockOrDeclined() {
        UUID product = announceProduct("SKU-T", 3);

        // 1) Two units of three: confirmed, stock event says 1 left.
        UUID order1 = UUID.randomUUID();
        send(Routing.ORDER_PLACE, place(order1, product, 2, "4242424242424242"));
        Events.OrderConfirmed confirmed = awaitEvent(Events.OrderConfirmed.class, e -> e.orderId().equals(order1));
        assertThat(confirmed.paymentId()).isNotNull();
        Events.InventoryChanged stock = awaitEvent(Events.InventoryChanged.class, e -> e.levels().get(0).productId().equals(product));
        assertThat(stock.levels().get(0).available()).isEqualTo(1);

        // 2) Two more units: only one left -> OUT_OF_STOCK, and stock is untouched.
        UUID order2 = UUID.randomUUID();
        send(Routing.ORDER_PLACE, place(order2, product, 2, "4242424242424242"));
        Events.OrderRejected rejected = awaitEvent(Events.OrderRejected.class, e -> e.orderId().equals(order2));
        assertThat(rejected.reason()).isEqualTo("OUT_OF_STOCK");

        // 3) Decline card: stock reserved then released, PAYMENT_DECLINED.
        UUID order3 = UUID.randomUUID();
        send(Routing.ORDER_PLACE, place(order3, product, 1, "4000000000000002"));
        Events.OrderRejected declined = awaitEvent(Events.OrderRejected.class, e -> e.orderId().equals(order3));
        assertThat(declined.reason()).isEqualTo("PAYMENT_DECLINED");

        // 4) Duplicate delivery of order1's command must not charge or reserve again.
        send(Routing.ORDER_PLACE, place(order1, product, 2, "4242424242424242"));

        // 5) Cancel order1: stock back to 1 + 2 = 3, refunded = true.
        send(Routing.ORDER_CANCEL, new Commands.OrderCancel(UUID.randomUUID(), "t", order1));
        Events.OrderCancelled cancelled = awaitEvent(Events.OrderCancelled.class, e -> e.orderId().equals(order1));
        assertThat(cancelled.refunded()).isTrue();
        Events.InventoryChanged after = awaitEvent(Events.InventoryChanged.class, e -> e.levels().get(0).available() == 3);
        assertThat(after.levels().get(0).productId()).isEqualTo(product);
    }

    @Test
    void refundExecutesAgainstApprovedPayment() {
        UUID product = announceProduct("SKU-R", 10);
        UUID order = UUID.randomUUID();
        send(Routing.ORDER_PLACE, place(order, product, 1, "4242424242424242"));
        awaitEvent(Events.OrderConfirmed.class, e -> e.orderId().equals(order));

        UUID refundId = UUID.randomUUID();
        send(Routing.REFUND_EXECUTE, new Commands.RefundExecute(UUID.randomUUID(), "t", refundId, order, new BigDecimal("10.00")));
        Events.RefundCompleted done = awaitEvent(Events.RefundCompleted.class, e -> e.refundId().equals(refundId));
        assertThat(done.success()).isTrue();

        // A second refund for the same payment fails.
        UUID refund2 = UUID.randomUUID();
        send(Routing.REFUND_EXECUTE, new Commands.RefundExecute(UUID.randomUUID(), "t", refund2, order, new BigDecimal("10.00")));
        Events.RefundCompleted again = awaitEvent(Events.RefundCompleted.class, e -> e.refundId().equals(refund2));
        assertThat(again.success()).isFalse();
        assertThat(again.reason()).isEqualTo("PAYMENT_NOT_REFUNDABLE");
    }

    /** Commands are consumed concurrently, so wait for the inventory row before ordering against it. */
    private UUID announceProduct(String sku, int stock) {
        UUID product = UUID.randomUUID();
        send(Routing.PRODUCT_UPSERT, new Commands.ProductUpsert(UUID.randomUUID(), "t", product, sku, stock));
        Events.InventoryChanged created = awaitEvent(Events.InventoryChanged.class,
                e -> e.levels().get(0).productId().equals(product) && e.levels().get(0).available() == stock);
        assertThat(created.levels()).hasSize(1);
        return product;
    }

    private static Commands.OrderPlace place(UUID orderId, UUID product, int qty, String card) {
        BigDecimal unit = new BigDecimal("10.00");
        return new Commands.OrderPlace(UUID.randomUUID(), "t", orderId, UUID.randomUUID(),
                List.of(new Commands.OrderLine(product, qty, unit)), unit.multiply(BigDecimal.valueOf(qty)), "USD", card);
    }

    private void send(String key, Object msg) {
        rabbitTemplate.convertAndSend(Routing.EXCHANGE, key, msg);
    }

    private final List<Object> seen = new ArrayList<>();

    /** Reads api.events until a matching event shows up; unrelated events are kept for later matches. */
    @SuppressWarnings("unchecked")
    private <T> T awaitEvent(Class<T> type, Predicate<T> matches) {
        return await().atMost(15, SECONDS).until(() -> {
            Object msg;
            while ((msg = rabbitTemplate.receiveAndConvert(Routing.API_QUEUE, 100)) != null) {
                seen.add(msg);
            }
            for (int i = 0; i < seen.size(); i++) {
                Object m = seen.get(i);
                if (type.isInstance(m) && matches.test((T) m)) {
                    seen.remove(i);
                    return (T) m;
                }
            }
            return null;
        }, m -> m != null);
    }
}
