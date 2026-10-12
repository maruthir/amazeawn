package com.amazeawn.api.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    List<Refund> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    Optional<Refund> findFirstByOrderIdAndStatusIn(UUID orderId, List<Refund.Status> statuses);

    Page<Refund> findByStatusOrderByCreatedAtDesc(Refund.Status status, Pageable pageable);

    Page<Refund> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(Refund.Status status);
}
