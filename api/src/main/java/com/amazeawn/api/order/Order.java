package com.amazeawn.api.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "card_last4", nullable = false, length = 4)
    private String cardLast4;

    @Column(name = "shipping_address", nullable = false, columnDefinition = "text")
    private String shippingAddress;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "payment_id")
    private UUID paymentId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected Order() {}

    public Order(UUID userId, BigDecimal totalAmount, String currency, String cardLast4, String shippingAddress) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.status = OrderStatus.PENDING;
        this.totalAmount = totalAmount;
        this.currency = currency;
        this.cardLast4 = cardLast4;
        this.shippingAddress = shippingAddress;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public String getCardLast4() { return cardLast4; }
    public String getShippingAddress() { return shippingAddress; }
    public String getRejectionReason() { return rejectionReason; }
    public UUID getPaymentId() { return paymentId; }
    public List<OrderItem> getItems() { return items; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getShippedAt() { return shippedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getCancelledAt() { return cancelledAt; }

    public void transition(OrderStatus next) {
        this.status = next;
        this.updatedAt = Instant.now();
        switch (next) {
            case CONFIRMED -> confirmedAt = updatedAt;
            case SHIPPED -> shippedAt = updatedAt;
            case DELIVERED -> deliveredAt = updatedAt;
            case CANCELLED -> cancelledAt = updatedAt;
            default -> { }
        }
    }

    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public void setRejectionReason(String reason) { this.rejectionReason = reason; }
    public void setPaymentId(UUID paymentId) { this.paymentId = paymentId; }
}
