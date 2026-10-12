package com.amazeawn.processor.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knobs for the simulated payment gateway.
 * <ul>
 *   <li>{@code latencyMs} / {@code jitterMs}: every call sleeps latency + random(0..jitter).</li>
 *   <li>{@code failureRate}: 0.0-1.0 chance a non-deterministic card is declined.</li>
 *   <li>{@code approveCard} always approves, {@code declineCard} always declines.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "amazeawn.payment")
public record PaymentProperties(Long latencyMs, Long jitterMs, Double failureRate, String approveCard, String declineCard) {
    public PaymentProperties {
        if (latencyMs == null) latencyMs = 50L;
        if (jitterMs == null) jitterMs = 50L;
        if (failureRate == null) failureRate = 0.05;
        if (approveCard == null) approveCard = "4242424242424242";
        if (declineCard == null) declineCard = "4000000000000002";
    }
}
