package com.amazeawn.processor.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Stands in for a payment gateway: configurable latency and failure rate, deterministic test cards. */
@Component
public class PaymentSimulator {

    private final PaymentProperties props;
    private final PaymentRepository payments;
    private final Timer chargeTimer;
    private final Counter approved;
    private final Counter declined;

    public PaymentSimulator(PaymentProperties props, PaymentRepository payments, MeterRegistry registry) {
        this.props = props;
        this.payments = payments;
        this.chargeTimer = Timer.builder("amazeawn.payment.charge").register(registry);
        this.approved = Counter.builder("amazeawn.payment.result").tag("outcome", "approved").register(registry);
        this.declined = Counter.builder("amazeawn.payment.result").tag("outcome", "declined").register(registry);
    }

    public Payment charge(UUID orderId, BigDecimal amount, String currency, String card) {
        return chargeTimer.record(() -> {
            simulateLatency();
            boolean ok = decide(card);
            Payment payment = payments.save(new Payment(orderId, amount, currency, card.substring(card.length() - 4),
                    ok ? Payment.Status.APPROVED : Payment.Status.DECLINED));
            (ok ? approved : declined).increment();
            return payment;
        });
    }

    public void refund(Payment payment) {
        simulateLatency();
        payment.refund();
    }

    private boolean decide(String card) {
        if (card.equals(props.approveCard())) return true;
        if (card.equals(props.declineCard())) return false;
        return ThreadLocalRandom.current().nextDouble() >= props.failureRate();
    }

    private void simulateLatency() {
        long sleep = props.latencyMs() + (props.jitterMs() > 0 ? ThreadLocalRandom.current().nextLong(props.jitterMs() + 1) : 0);
        if (sleep <= 0) return;
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
