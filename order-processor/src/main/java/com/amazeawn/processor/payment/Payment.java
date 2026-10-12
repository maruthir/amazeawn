package com.amazeawn.processor.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    public enum Status { APPROVED, DECLINED, REFUNDED }

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "card_last4", nullable = false, length = 4)
    private String cardLast4;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    protected Payment() {}

    public Payment(UUID orderId, BigDecimal amount, String currency, String cardLast4, Status status) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.amount = amount;
        this.currency = currency;
        this.cardLast4 = cardLast4;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public Status getStatus() { return status; }

    public void refund() {
        this.status = Status.REFUNDED;
        this.refundedAt = Instant.now();
    }
}
