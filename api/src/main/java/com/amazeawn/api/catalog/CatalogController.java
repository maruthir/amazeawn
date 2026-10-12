package com.amazeawn.api.catalog;

import com.amazeawn.api.config.ApiException;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Catalog")
public class CatalogController {

    private final ProductRepository products;
    private final CategoryRepository categories;

    public CatalogController(ProductRepository products, CategoryRepository categories) {
        this.products = products;
        this.categories = categories;
    }

    @GetMapping("/products")
    public Page<ProductView> list(@RequestParam(required = false) String q,
                                  @RequestParam(required = false) UUID categoryId,
                                  @RequestParam(required = false) BigDecimal minPrice,
                                  @RequestParam(required = false) BigDecimal maxPrice,
                                  @RequestParam(defaultValue = "false") boolean inStock,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(defaultValue = "name,asc") String sort) {
        String[] parts = sort.split(",");
        String field = switch (parts[0]) { case "price", "createdAt", "name" -> parts[0]; default -> "name"; };
        Sort.Direction dir = parts.length > 1 && parts[1].equalsIgnoreCase("desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return products.search(pattern, categoryId, minPrice, maxPrice, inStock,
                PageRequest.of(page, Math.min(size, 100), Sort.by(dir, field))).map(ProductView::of);
    }

    @GetMapping("/products/{id}")
    public ProductView detail(@PathVariable UUID id) {
        return products.findByIdAndActiveTrue(id).map(ProductView::of).orElseThrow(() -> ApiException.notFound("Product"));
    }

    @GetMapping("/categories")
    public List<Map<String, Object>> categories() {
        return categories.findAll(Sort.by("name")).stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getName(), "slug", c.getSlug()))
                .toList();
    }
}
