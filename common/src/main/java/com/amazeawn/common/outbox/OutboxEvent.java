package com.amazeawn.common.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A message written in the same transaction as the business change it describes.
 * The relay publishes it to RabbitMQ afterwards; the row id doubles as the message id.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "routing_key", nullable = false)
    private String routingKey;

    @Column(name = "message_type", nullable = false)
    private String messageType;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {}

    public OutboxEvent(UUID id, String routingKey, String messageType, String correlationId, String payload) {
        this.id = id;
        this.routingKey = routingKey;
        this.messageType = messageType;
        this.correlationId = correlationId;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getRoutingKey() { return routingKey; }
    public String getMessageType() { return messageType; }
    public String getCorrelationId() { return correlationId; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void markPublished() { this.publishedAt = Instant.now(); }
}
