package com.amazeawn.api.order;

public enum OrderStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    CANCEL_REQUESTED,
    CANCELLED,
    SHIPPED,
    DELIVERED,
    REFUNDED;

    public boolean cancellable() {
        return this == PENDING || this == CONFIRMED;
    }

    public boolean refundable() {
        return this == CONFIRMED || this == SHIPPED || this == DELIVERED;
    }
}
