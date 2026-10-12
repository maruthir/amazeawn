package com.amazeawn.processor.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** The authoritative stock count. Only the processor writes it; the API mirrors it from events. */
@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private int available;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Inventory() {}

    public Inventory(UUID productId, String sku, int available) {
        this.productId = productId;
        this.sku = sku;
        this.available = available;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getProductId() { return productId; }
    public String getSku() { return sku; }
    public int getAvailable() { return available; }

    public void setAvailable(int available) {
        this.available = Math.max(0, available);
        this.updatedAt = Instant.now();
    }
}
