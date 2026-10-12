package com.amazeawn.common.messaging;

import java.util.List;
import java.util.UUID;

/** Events published by the order processor and consumed by the API. */
public final class Events {
    private Events() {}

    public record OrderConfirmed(UUID messageId, String correlationId, UUID orderId, UUID paymentId) implements Message {
        @Override public String routingKey() { return Routing.ORDER_CONFIRMED; }
    }

    public record OrderRejected(UUID messageId, String correlationId, UUID orderId, String reason) implements Message {
        @Override public String routingKey() { return Routing.ORDER_REJECTED; }
    }

    public record OrderCancelled(UUID messageId, String correlationId, UUID orderId, boolean refunded) implements Message {
        @Override public String routingKey() { return Routing.ORDER_CANCELLED; }
    }

    public record RefundCompleted(UUID messageId, String correlationId, UUID refundId, UUID orderId,
                                  boolean success, String reason) implements Message {
        @Override public String routingKey() { return Routing.REFUND_COMPLETED; }
    }

    public record StockLevel(UUID productId, int available) {}

    public record InventoryChanged(UUID messageId, String correlationId, List<StockLevel> levels) implements Message {
        @Override public String routingKey() { return Routing.INVENTORY_CHANGED; }
    }
}
