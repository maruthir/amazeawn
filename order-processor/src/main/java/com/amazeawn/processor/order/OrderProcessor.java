package com.amazeawn.processor.order;

import com.amazeawn.common.messaging.Commands;
import com.amazeawn.common.messaging.CorrelationId;
import com.amazeawn.common.messaging.Events;
import com.amazeawn.common.outbox.Outbox;
import com.amazeawn.processor.inventory.Inventory;
import com.amazeawn.processor.inventory.InventoryRepository;
import com.amazeawn.processor.payment.Payment;
import com.amazeawn.processor.payment.PaymentRepository;
import com.amazeawn.processor.payment.PaymentSimulator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Business logic behind each command. Every method runs inside the consumer's idempotent transaction,
 * so stock changes, payment rows and the resulting outbox events commit or roll back together.
 */
@Service
public class OrderProcessor {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessor.class);
    private static final TypeReference<List<Commands.OrderLine>> LINES = new TypeReference<>() {};

    private final InventoryRepository inventory;
    private final ProcessedOrderRepository orders;
    private final PaymentRepository payments;
    private final PaymentSimulator paymentSimulator;
    private final Outbox outbox;
    private final ObjectMapper objectMapper;
    private final EntityManager em;
    private final MeterRegistry registry;

    public OrderProcessor(InventoryRepository inventory, ProcessedOrderRepository orders, PaymentRepository payments,
                          PaymentSimulator paymentSimulator, Outbox outbox, ObjectMapper objectMapper,
                          EntityManager em, MeterRegistry registry) {
        this.inventory = inventory;
        this.orders = orders;
        this.payments = payments;
        this.paymentSimulator = paymentSimulator;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.em = em;
        this.registry = registry;
    }

    @Transactional
    public void place(Commands.OrderPlace cmd) {
        var existing = orders.findById(cmd.orderId());
        if (existing.isPresent()) {
            // A cancel beat us here (concurrent consumers): honour it without touching stock or payment.
            if (existing.get().getOutcome() == ProcessedOrder.Outcome.CANCEL_PENDING) {
                existing.get().setOutcome(ProcessedOrder.Outcome.CANCELLED);
                emit(new Events.OrderCancelled(UUID.randomUUID(), cmd.correlationId(), cmd.orderId(), false));
            }
            return;
        }

        // Reserve stock line by line; any shortfall rolls the whole reservation back.
        List<Commands.OrderLine> reserved = new ArrayList<>();
        for (Commands.OrderLine line : cmd.items()) {
            if (inventory.reserve(line.productId(), line.quantity()) == 0) {
                reserved.forEach(r -> inventory.release(r.productId(), r.quantity()));
                orders.save(new ProcessedOrder(cmd.orderId(), ProcessedOrder.Outcome.REJECTED, lines(cmd.items()), cmd.totalAmount(), null));
                emit(new Events.OrderRejected(UUID.randomUUID(), cmd.correlationId(), cmd.orderId(), "OUT_OF_STOCK"));
                outcome("rejected_out_of_stock");
                log.info("Order {} rejected: out of stock for product {}", cmd.orderId(), line.productId());
                return;
            }
            reserved.add(line);
        }

        Payment payment = paymentSimulator.charge(cmd.orderId(), cmd.totalAmount(), cmd.currency(), cmd.paymentCard());
        if (payment.getStatus() == Payment.Status.DECLINED) {
            reserved.forEach(r -> inventory.release(r.productId(), r.quantity()));
            orders.save(new ProcessedOrder(cmd.orderId(), ProcessedOrder.Outcome.REJECTED, lines(cmd.items()), cmd.totalAmount(), payment.getId()));
            emit(new Events.OrderRejected(UUID.randomUUID(), cmd.correlationId(), cmd.orderId(), "PAYMENT_DECLINED"));
            outcome("rejected_payment_declined");
            log.info("Order {} rejected: payment declined", cmd.orderId());
            return;
        }

        orders.save(new ProcessedOrder(cmd.orderId(), ProcessedOrder.Outcome.CONFIRMED, lines(cmd.items()), cmd.totalAmount(), payment.getId()));
        emit(new Events.OrderConfirmed(UUID.randomUUID(), cmd.correlationId(), cmd.orderId(), payment.getId()));
        emitStockLevels(cmd.items().stream().map(Commands.OrderLine::productId).toList(), cmd.correlationId());
        outcome("confirmed");
        log.info("Order {} confirmed with payment {}", cmd.orderId(), payment.getId());
    }

    @Transactional
    public void cancel(Commands.OrderCancel cmd) {
        var existing = orders.findById(cmd.orderId());
        if (existing.isEmpty()) {
            // Place command has not arrived yet; leave a marker so place() turns into a cancel.
            orders.save(new ProcessedOrder(cmd.orderId(), ProcessedOrder.Outcome.CANCEL_PENDING, "[]", java.math.BigDecimal.ZERO, null));
            return;
        }
        ProcessedOrder order = existing.get();
        switch (order.getOutcome()) {
            case CONFIRMED -> {
                List<Commands.OrderLine> lines = lines(order.getLines());
                lines.forEach(l -> inventory.release(l.productId(), l.quantity()));
                boolean refunded = false;
                if (order.getPaymentId() != null) {
                    Payment payment = payments.findById(order.getPaymentId()).orElse(null);
                    if (payment != null && payment.getStatus() == Payment.Status.APPROVED) {
                        paymentSimulator.refund(payment);
                        refunded = true;
                    }
                }
                order.setOutcome(ProcessedOrder.Outcome.CANCELLED);
                emit(new Events.OrderCancelled(UUID.randomUUID(), cmd.correlationId(), cmd.orderId(), refunded));
                emitStockLevels(lines.stream().map(Commands.OrderLine::productId).toList(), cmd.correlationId());
                outcome("cancelled");
            }
            case REJECTED -> emit(new Events.OrderRejected(UUID.randomUUID(), cmd.correlationId(), cmd.orderId(), "ALREADY_REJECTED"));
            case CANCELLED -> emit(new Events.OrderCancelled(UUID.randomUUID(), cmd.correlationId(), cmd.orderId(), order.getPaymentId() != null));
            case REFUNDED -> log.info("Cancel ignored for refunded order {}", cmd.orderId());
            case CANCEL_PENDING -> { }
        }
    }

    @Transactional
    public void refund(Commands.RefundExecute cmd) {
        var existing = orders.findById(cmd.orderId());
        if (existing.isEmpty() || existing.get().getPaymentId() == null) {
            emit(new Events.RefundCompleted(UUID.randomUUID(), cmd.correlationId(), cmd.refundId(), cmd.orderId(), false, "NO_PAYMENT"));
            return;
        }
        ProcessedOrder order = existing.get();
        Payment payment = payments.findById(order.getPaymentId()).orElse(null);
        if (payment == null || payment.getStatus() != Payment.Status.APPROVED) {
            emit(new Events.RefundCompleted(UUID.randomUUID(), cmd.correlationId(), cmd.refundId(), cmd.orderId(), false, "PAYMENT_NOT_REFUNDABLE"));
            return;
        }
        paymentSimulator.refund(payment);
        order.setOutcome(ProcessedOrder.Outcome.REFUNDED);
        emit(new Events.RefundCompleted(UUID.randomUUID(), cmd.correlationId(), cmd.refundId(), cmd.orderId(), true, null));
        outcome("refunded");
    }

    @Transactional
    public void upsertProduct(Commands.ProductUpsert cmd) {
        if (!inventory.existsById(cmd.productId())) {
            inventory.save(new Inventory(cmd.productId(), cmd.sku(), cmd.initialStock()));
            // Confirms the authoritative level back to the catalog mirror.
            emitStockLevels(List.of(cmd.productId()), cmd.correlationId());
        }
    }

    @Transactional
    public void adjust(Commands.InventoryAdjust cmd) {
        Inventory inv = inventory.findById(cmd.productId()).orElse(null);
        if (inv == null) {
            log.warn("Inventory adjust for unknown product {}", cmd.productId());
            return;
        }
        inv.setAvailable(inv.getAvailable() + cmd.delta());
        emitStockLevels(List.of(cmd.productId()), cmd.correlationId());
    }

    @Transactional
    public void restock(Commands.InventoryRestock cmd) {
        if (cmd.productIds() == null || cmd.productIds().isEmpty()) {
            inventory.setAll(cmd.level());
            em.clear();
            emitAllStockLevels(cmd.correlationId());
        } else {
            inventory.setLevels(cmd.productIds(), cmd.level());
            em.clear();
            emitStockLevels(cmd.productIds(), cmd.correlationId());
        }
    }

    @Transactional
    public void reset(Commands.TestReset cmd) {
        // Only rows that predate the reset: product.upsert commands for the reseeded catalog may be
        // consumed before or after this one, and their rows are all created after issuedAt.
        int orders = em.createQuery("delete from ProcessedOrder o where o.createdAt < :t").setParameter("t", cmd.issuedAt()).executeUpdate();
        int payments = em.createQuery("delete from Payment p where p.createdAt < :t").setParameter("t", cmd.issuedAt()).executeUpdate();
        int inventory = em.createQuery("delete from Inventory i where i.createdAt < :t").setParameter("t", cmd.issuedAt()).executeUpdate();
        log.info("Processor state reset: dropped {} orders, {} payments, {} inventory rows created before {} (stock level {} applies to re-announced products)",
                orders, payments, inventory, cmd.issuedAt(), cmd.stockLevel());
    }

    private void emitStockLevels(List<UUID> productIds, String correlationId) {
        em.flush();
        List<Events.StockLevel> levels = inventory.findByProductIdIn(productIds).stream()
                .map(i -> new Events.StockLevel(i.getProductId(), i.getAvailable())).toList();
        if (!levels.isEmpty()) {
            emit(new Events.InventoryChanged(UUID.randomUUID(), correlationId, levels));
        }
    }

    private void emitAllStockLevels(String correlationId) {
        List<Events.StockLevel> levels = inventory.findAll().stream()
                .map(i -> new Events.StockLevel(i.getProductId(), i.getAvailable())).toList();
        // Chunk so a large catalog does not produce one giant message.
        for (int i = 0; i < levels.size(); i += 500) {
            emit(new Events.InventoryChanged(UUID.randomUUID(), correlationId, levels.subList(i, Math.min(i + 500, levels.size()))));
        }
    }

    private void emit(com.amazeawn.common.messaging.Message event) {
        outbox.enqueue(event);
    }

    private void outcome(String name) {
        Counter.builder("amazeawn.orders.outcome").tag("outcome", name).register(registry).increment();
    }

    private String lines(List<Commands.OrderLine> lines) {
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Commands.OrderLine> lines(String json) {
        try {
            return objectMapper.readValue(json, LINES);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
