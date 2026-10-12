package com.amazeawn.processor.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The processor's own record of an order: what was reserved and how it ended. Lets a cancel that
 * arrives before (or after) the place command be handled correctly with concurrent consumers.
 */
@Entity
@Table(name = "processed_orders")
public class ProcessedOrder {

    public enum Outcome { CONFIRMED, REJECTED, CANCELLED, CANCEL_PENDING, REFUNDED }

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Outcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lines", nullable = false, columnDefinition = "jsonb")
    private String lines;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProcessedOrder() {}

    public ProcessedOrder(UUID orderId, Outcome outcome, String lines, BigDecimal amount, UUID paymentId) {
        this.orderId = orderId;
        this.outcome = outcome;
        this.lines = lines;
        this.amount = amount;
        this.paymentId = paymentId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getOrderId() { return orderId; }
    public Outcome getOutcome() { return outcome; }
    public String getLines() { return lines; }
    public BigDecimal getAmount() { return amount; }
    public UUID getPaymentId() { return paymentId; }

    public void setOutcome(Outcome outcome) {
        this.outcome = outcome;
        this.updatedAt = Instant.now();
    }
}
