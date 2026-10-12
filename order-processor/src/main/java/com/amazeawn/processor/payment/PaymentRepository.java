package com.amazeawn.processor.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    Optional<Payment> findFirstByOrderIdAndStatus(UUID orderId, Payment.Status status);
}
