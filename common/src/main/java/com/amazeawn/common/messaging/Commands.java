package com.amazeawn.common.messaging;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Commands published by the API and consumed by the order processor. */
public final class Commands {
    private Commands() {}

    public record OrderLine(UUID productId, int quantity, BigDecimal unitPrice) {}

    public record OrderPlace(UUID messageId, String correlationId, UUID orderId, UUID customerId,
                             List<OrderLine> items, BigDecimal totalAmount, String currency,
                             String paymentCard) implements Message {
        @Override public String routingKey() { return Routing.ORDER_PLACE; }
    }

    public record OrderCancel(UUID messageId, String correlationId, UUID orderId) implements Message {
        @Override public String routingKey() { return Routing.ORDER_CANCEL; }
    }

    public record RefundExecute(UUID messageId, String correlationId, UUID refundId, UUID orderId,
                                BigDecimal amount) implements Message {
        @Override public String routingKey() { return Routing.REFUND_EXECUTE; }
    }

    /** Creates the inventory row for a new product; initialStock is only applied on first sight. */
    public record ProductUpsert(UUID messageId, String correlationId, UUID productId, String sku,
                                int initialStock) implements Message {
        @Override public String routingKey() { return Routing.PRODUCT_UPSERT; }
    }

    public record InventoryAdjust(UUID messageId, String correlationId, UUID productId, int delta) implements Message {
        @Override public String routingKey() { return Routing.INVENTORY_ADJUST; }
    }

    /** Sets available stock to {@code level} for the listed products, or for every product when the list is null. */
    public record InventoryRestock(UUID messageId, String correlationId, List<UUID> productIds, int level) implements Message {
        @Override public String routingKey() { return Routing.INVENTORY_RESTOCK; }
    }

    public record TestReset(UUID messageId, String correlationId, int stockLevel) implements Message {
        @Override public String routingKey() { return Routing.TEST_RESET; }
    }
}
