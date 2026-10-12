package com.amazeawn.processor.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    /** Atomic decrement that refuses to oversell: returns 0 when stock is insufficient. */
    @Modifying
    @Query("update Inventory i set i.available = i.available - :qty, i.updatedAt = current_timestamp "
            + "where i.productId = :productId and i.available >= :qty")
    int reserve(@Param("productId") UUID productId, @Param("qty") int qty);

    @Modifying
    @Query("update Inventory i set i.available = i.available + :qty, i.updatedAt = current_timestamp where i.productId = :productId")
    int release(@Param("productId") UUID productId, @Param("qty") int qty);

    @Modifying
    @Query("update Inventory i set i.available = :level, i.updatedAt = current_timestamp")
    int setAll(@Param("level") int level);

    @Modifying
    @Query("update Inventory i set i.available = :level, i.updatedAt = current_timestamp where i.productId in :ids")
    int setLevels(@Param("ids") Collection<UUID> ids, @Param("level") int level);

    List<Inventory> findByProductIdIn(Collection<UUID> ids);
}
