package com.amazeawn.api.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    @EntityGraph(attributePaths = "category")
    @Query("""
            select p from Product p
            where p.active = true
              and (:pattern is null or lower(p.name) like :pattern or lower(p.sku) like :pattern)
              and (:categoryId is null or p.category.id = :categoryId)
              and (:minPrice is null or p.price >= :minPrice)
              and (:maxPrice is null or p.price <= :maxPrice)
              and (:inStock = false or p.stock > 0)
            """)
    /** {@code pattern} is a ready-made lower-case LIKE pattern (e.g. {@code %kettle%}) or null for no text filter. */
    Page<Product> search(@Param("pattern") String pattern, @Param("categoryId") UUID categoryId,
                         @Param("minPrice") BigDecimal minPrice, @Param("maxPrice") BigDecimal maxPrice,
                         @Param("inStock") boolean inStock, Pageable pageable);

    @EntityGraph(attributePaths = "category")
    Optional<Product> findByIdAndActiveTrue(UUID id);

    @EntityGraph(attributePaths = "category")
    Page<Product> findAllBy(Pageable pageable);

    List<Product> findByIdIn(List<UUID> ids);

    boolean existsBySku(String sku);

    long countByActiveTrue();

    long countByStockLessThanEqual(int threshold);

    @Modifying
    @Query("update Product p set p.stock = :stock, p.updatedAt = current_timestamp where p.id = :id")
    int updateStock(@Param("id") UUID id, @Param("stock") int stock);
}
