package com.amazeawn.api.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductView(UUID id, String sku, String name, String description, BigDecimal price, String currency,
                          UUID categoryId, String categoryName, int stock, boolean active, Instant updatedAt) {
    public static ProductView of(Product p) {
        return new ProductView(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(), p.getCurrency(),
                p.getCategory().getId(), p.getCategory().getName(), p.getStock(), p.isActive(), p.getUpdatedAt());
    }
}
