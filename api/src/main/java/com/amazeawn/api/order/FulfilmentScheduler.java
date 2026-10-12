package com.amazeawn.api.order;

import com.amazeawn.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Advances CONFIRMED -> SHIPPED -> DELIVERED on timers (FULFILMENT_SHIP_AFTER / FULFILMENT_DELIVER_AFTER).
 * Admin status changes override this at any point.
 */
@Component
public class FulfilmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(FulfilmentScheduler.class);

    private final OrderRepository orders;
    private final AppProperties.Fulfilment config;

    public FulfilmentScheduler(OrderRepository orders, AppProperties props) {
        this.orders = orders;
        this.config = props.fulfilment();
    }

    @Scheduled(fixedDelayString = "${amazeawn.fulfilment.poll-interval:PT5S}")
    @Transactional
    public void advance() {
        if (!config.enabled()) {
            return;
        }
        Instant now = Instant.now();
        List<Order> toShip = orders.findTop500ByStatusAndConfirmedAtBefore(OrderStatus.CONFIRMED, now.minus(config.shipAfter()));
        toShip.forEach(o -> o.transition(OrderStatus.SHIPPED));
        List<Order> toDeliver = orders.findTop500ByStatusAndShippedAtBefore(OrderStatus.SHIPPED, now.minus(config.deliverAfter()));
        toDeliver.forEach(o -> o.transition(OrderStatus.DELIVERED));
        if (!toShip.isEmpty() || !toDeliver.isEmpty()) {
            log.debug("Fulfilment: shipped {}, delivered {}", toShip.size(), toDeliver.size());
        }
    }
}
