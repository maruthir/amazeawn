package com.amazeawn.api.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderView(UUID id, UUID userId, OrderStatus status, BigDecimal totalAmount, String currency,
                        String cardLast4, String shippingAddress, String rejectionReason, UUID paymentId,
                        List<Item> items, Instant createdAt, Instant updatedAt, Instant confirmedAt,
                        Instant shippedAt, Instant deliveredAt, Instant cancelledAt) {

    public record Item(UUID productId, String name, String sku, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {}

    public static OrderView of(Order o, boolean withItems) {
        List<Item> items = withItems
                ? o.getItems().stream().map(i -> new Item(i.getProductId(), i.getProductName(), i.getSku(),
                        i.getUnitPrice(), i.getQuantity(), i.lineTotal())).toList()
                : null;
        return new OrderView(o.getId(), o.getUserId(), o.getStatus(), o.getTotalAmount(), o.getCurrency(), o.getCardLast4(),
                o.getShippingAddress(), o.getRejectionReason(), o.getPaymentId(), items, o.getCreatedAt(), o.getUpdatedAt(),
                o.getConfirmedAt(), o.getShippedAt(), o.getDeliveredAt(), o.getCancelledAt());
    }

    public record RefundView(UUID id, UUID orderId, BigDecimal amount, Refund.Status status, String reason, String adminNote,
                             String failureReason, Instant createdAt, Instant decidedAt, Instant completedAt) {
        public static RefundView of(Refund r) {
            return new RefundView(r.getId(), r.getOrderId(), r.getAmount(), r.getStatus(), r.getReason(), r.getAdminNote(),
                    r.getFailureReason(), r.getCreatedAt(), r.getDecidedAt(), r.getCompletedAt());
        }
    }
}
