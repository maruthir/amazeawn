package com.amazeawn.processor.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessedOrderRepository extends JpaRepository<ProcessedOrder, UUID> {
}
