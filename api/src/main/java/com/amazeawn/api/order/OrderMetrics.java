package com.amazeawn.api.order;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gauges for long-running observation: orders per status and "stale" orders that have sat in
 * PENDING / CANCEL_REQUESTED longer than expected, which is the signature of a lost message.
 * Sampled on a timer so the metrics endpoint never queries the database.
 */
@Component
public class OrderMetrics {

    static final Duration STALE_AFTER = Duration.ofMinutes(2);

    private final OrderRepository orders;
    private final Map<OrderStatus, AtomicLong> byStatus = new EnumMap<>(OrderStatus.class);
    private final AtomicLong stalePending = new AtomicLong();

    public OrderMetrics(OrderRepository orders, MeterRegistry registry) {
        this.orders = orders;
        for (OrderStatus s : OrderStatus.values()) {
            AtomicLong value = new AtomicLong();
            byStatus.put(s, value);
            Gauge.builder("amazeawn.orders.by_status", value, AtomicLong::get).tag("status", s.name()).register(registry);
        }
        Gauge.builder("amazeawn.orders.stale_pending", stalePending, AtomicLong::get)
                .description("Orders waiting for the processor longer than " + STALE_AFTER).register(registry);
    }

    @Scheduled(fixedDelayString = "${amazeawn.metrics.sample-interval:PT15S}")
    public void sample() {
        byStatus.values().forEach(v -> v.set(0));
        for (Object[] row : orders.countGroupedByStatus()) {
            byStatus.get((OrderStatus) row[0]).set((Long) row[1]);
        }
        stalePending.set(orders.countByStatusInAndCreatedAtBefore(
                List.of(OrderStatus.PENDING, OrderStatus.CANCEL_REQUESTED), Instant.now().minus(STALE_AFTER)));
    }
}
