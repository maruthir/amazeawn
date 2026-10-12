package com.amazeawn.api.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Full-order refund: customer requests, admin approves or rejects, processor executes. */
@Entity
@Table(name = "refunds")
public class Refund {

    public enum Status { REQUESTED, APPROVED, REJECTED, COMPLETED, FAILED }

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "admin_note", columnDefinition = "text")
    private String adminNote;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Refund() {}

    public Refund(UUID orderId, UUID userId, BigDecimal amount, String reason) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.userId = userId;
        this.amount = amount;
        this.reason = reason;
        this.status = Status.REQUESTED;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getUserId() { return userId; }
    public BigDecimal getAmount() { return amount; }
    public Status getStatus() { return status; }
    public String getReason() { return reason; }
    public String getAdminNote() { return adminNote; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void decide(Status status, String adminNote) {
        this.status = status;
        this.adminNote = adminNote;
        this.decidedAt = Instant.now();
    }

    public void complete(boolean success, String failureReason) {
        this.status = success ? Status.COMPLETED : Status.FAILED;
        this.failureReason = failureReason;
        this.completedAt = Instant.now();
    }

    public boolean isOpen() {
        return status == Status.REQUESTED || status == Status.APPROVED;
    }
}
