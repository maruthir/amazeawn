package com.amazeawn.api.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Maps (user, Idempotency-Key) to the order it created so a retried POST /orders returns the same order. */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "idem_key")
        private String key;

        @Column(name = "user_id")
        private UUID userId;

        protected Id() {}

        public Id(String key, UUID userId) {
            this.key = key;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Id other && Objects.equals(key, other.key) && Objects.equals(userId, other.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, userId);
        }
    }

    @EmbeddedId
    private Id id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyKey() {}

    public IdempotencyKey(String key, UUID userId, UUID orderId) {
        this.id = new Id(key, userId);
        this.orderId = orderId;
        this.createdAt = Instant.now();
    }

    public UUID getOrderId() { return orderId; }
}
