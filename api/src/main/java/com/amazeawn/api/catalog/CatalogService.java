package com.amazeawn.api.catalog;

import com.amazeawn.api.config.ApiException;
import com.amazeawn.common.messaging.Commands;
import com.amazeawn.common.messaging.CorrelationId;
import com.amazeawn.common.outbox.Outbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Admin-side catalog changes. Stock-affecting changes go through the processor via commands. */
@Service
public class CatalogService {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final Outbox outbox;

    public CatalogService(ProductRepository products, CategoryRepository categories, Outbox outbox) {
        this.products = products;
        this.categories = categories;
        this.outbox = outbox;
    }

    @Transactional
    public Product create(String sku, String name, String description, BigDecimal price, String currency,
                          UUID categoryId, int initialStock) {
        if (products.existsBySku(sku)) {
            throw ApiException.conflict("sku_taken", "SKU already exists");
        }
        Category category = categories.findById(categoryId).orElseThrow(() -> ApiException.notFound("Category"));
        Product product = products.save(new Product(sku, name, description, price, currency, category, initialStock));
        outbox.enqueue(new Commands.ProductUpsert(UUID.randomUUID(), CorrelationId.current(),
                product.getId(), product.getSku(), initialStock));
        return product;
    }

    @Transactional
    public Product update(UUID id, String name, String description, BigDecimal price, UUID categoryId, Boolean active) {
        Product product = products.findById(id).orElseThrow(() -> ApiException.notFound("Product"));
        if (name != null) product.setName(name);
        if (description != null) product.setDescription(description);
        if (price != null) product.setPrice(price);
        if (categoryId != null) {
            product.setCategory(categories.findById(categoryId).orElseThrow(() -> ApiException.notFound("Category")));
        }
        if (active != null) product.setActive(active);
        return product;
    }

    @Transactional
    public void adjustInventory(UUID productId, int delta) {
        products.findById(productId).orElseThrow(() -> ApiException.notFound("Product"));
        outbox.enqueue(new Commands.InventoryAdjust(UUID.randomUUID(), CorrelationId.current(), productId, delta));
    }

    @Transactional
    public void restock(List<UUID> productIds, int level) {
        outbox.enqueue(new Commands.InventoryRestock(UUID.randomUUID(), CorrelationId.current(), productIds, level));
    }

    @Transactional
    public Category createCategory(String name, String slug) {
        if (categories.existsBySlug(slug)) {
            throw ApiException.conflict("slug_taken", "Category slug already exists");
        }
        return categories.save(new Category(name, slug));
    }

    @Transactional
    public Category updateCategory(UUID id, String name, String slug) {
        Category category = categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
        if (name != null) category.setName(name);
        if (slug != null) category.setSlug(slug);
        return category;
    }
}
