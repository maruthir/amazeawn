package com.amazeawn.api.admin;

import com.amazeawn.api.catalog.CatalogService;
import com.amazeawn.api.catalog.Category;
import com.amazeawn.api.catalog.ProductRepository;
import com.amazeawn.api.catalog.ProductView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin: catalog")
public class AdminCatalogController {

    private final CatalogService catalogService;
    private final ProductRepository products;

    public AdminCatalogController(CatalogService catalogService, ProductRepository products) {
        this.catalogService = catalogService;
        this.products = products;
    }

    public record CreateProduct(@NotBlank String sku, @NotBlank String name, String description,
                                @NotNull @DecimalMin("0.00") BigDecimal price, String currency,
                                @NotNull UUID categoryId, @Min(0) int initialStock) {}

    public record UpdateProduct(String name, String description, @DecimalMin("0.00") BigDecimal price,
                                UUID categoryId, Boolean active) {}

    public record CategoryRequest(@NotBlank String name, @NotBlank String slug) {}

    public record AdjustRequest(int delta) {}

    public record RestockRequest(List<UUID> productIds, @Min(0) int level) {}

    @GetMapping("/products")
    public Page<ProductView> products(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return products.findAllBy(PageRequest.of(page, Math.min(size, 500), Sort.by("sku"))).map(ProductView::of);
    }

    @PostMapping("/products")
    public ResponseEntity<ProductView> createProduct(@Valid @RequestBody CreateProduct req) {
        var p = catalogService.create(req.sku(), req.name(), req.description(), req.price(),
                req.currency() == null ? "USD" : req.currency(), req.categoryId(), req.initialStock());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductView.of(p));
    }

    @PutMapping("/products/{id}")
    public ProductView updateProduct(@PathVariable UUID id, @Valid @RequestBody UpdateProduct req) {
        return ProductView.of(catalogService.update(id, req.name(), req.description(), req.price(), req.categoryId(), req.active()));
    }

    @DeleteMapping("/products/{id}")
    @Operation(summary = "Soft-delete: marks the product inactive so order history stays intact")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        catalogService.update(id, null, null, null, null, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/products/{id}/inventory")
    @Operation(summary = "Adjust stock by a delta (applied by the processor; catalog stock updates when the event lands)")
    public ResponseEntity<Map<String, String>> adjust(@PathVariable UUID id, @RequestBody AdjustRequest req) {
        catalogService.adjustInventory(id, req.delta());
        return ResponseEntity.accepted().body(Map.of("status", "queued"));
    }

    @PostMapping("/inventory/restock")
    @Operation(summary = "Bulk restock: set every product (or the listed ones) to the given stock level")
    public ResponseEntity<Map<String, String>> restock(@Valid @RequestBody RestockRequest req) {
        catalogService.restock(req.productIds(), req.level());
        return ResponseEntity.accepted().body(Map.of("status", "queued"));
    }

    @PostMapping("/categories")
    public ResponseEntity<Map<String, Object>> createCategory(@Valid @RequestBody CategoryRequest req) {
        Category c = catalogService.createCategory(req.name(), req.slug());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", c.getId(), "name", c.getName(), "slug", c.getSlug()));
    }

    @PutMapping("/categories/{id}")
    public Map<String, Object> updateCategory(@PathVariable UUID id, @RequestBody CategoryRequest req) {
        Category c = catalogService.updateCategory(id, req.name(), req.slug());
        return Map.of("id", c.getId(), "name", c.getName(), "slug", c.getSlug());
    }
}
