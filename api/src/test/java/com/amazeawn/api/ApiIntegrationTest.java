package com.amazeawn.api;

import com.amazeawn.common.messaging.Commands;
import com.amazeawn.common.messaging.Events;
import com.amazeawn.common.messaging.Routing;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Drives the API over HTTP with real Postgres and RabbitMQ. The processor is not running, so the
 * test consumes the API's commands straight from the queue and injects the processor's events.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "amazeawn.seed.catalog-size=20",
        "amazeawn.seed.initial-stock=5",
        "amazeawn.auth.bcrypt-cost=4",
        "amazeawn.fulfilment.enabled=false",
        "spring.profiles.active=loadtest"
})
class ApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired TestRestTemplate http;
    @Autowired RabbitTemplate rabbitTemplate;

    @Test
    void placeOrderFlowsThroughOutboxAndEvents() {
        String email = "it-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> reg = http.postForEntity("/api/v1/auth/register",
                Map.of("email", email, "password", "secret123", "fullName", "IT User"), Map.class);
        assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map login = http.postForObject("/api/v1/auth/login", Map.of("email", email, "password", "secret123"), Map.class);
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth((String) login.get("accessToken"));

        Map products = http.getForObject("/api/v1/products?size=1", Map.class);
        String productId = (String) ((Map) ((List) products.get("content")).get(0)).get("id");

        ResponseEntity<Map> cart = http.exchange("/api/v1/cart/items", HttpMethod.POST,
                new HttpEntity<>(Map.of("productId", productId, "quantity", 2), auth), Map.class);
        assertThat(cart.getBody().get("itemCount")).isEqualTo(2);

        HttpHeaders orderHeaders = new HttpHeaders();
        orderHeaders.putAll(auth);
        orderHeaders.set("Idempotency-Key", "key-" + UUID.randomUUID());
        Map<String, Object> body = Map.of("paymentCard", "4242424242424242", "shippingAddress", "1 Test St");
        ResponseEntity<Map> placed = http.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(body, orderHeaders), Map.class);
        assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String orderId = (String) placed.getBody().get("orderId");
        assertThat(placed.getBody().get("status")).isEqualTo("PENDING");

        // Same key -> same order, no new outbox row.
        ResponseEntity<Map> replay = http.exchange("/api/v1/orders", HttpMethod.POST, new HttpEntity<>(body, orderHeaders), Map.class);
        assertThat(replay.getBody().get("orderId")).isEqualTo(orderId);
        assertThat(replay.getBody().get("replayed")).isEqualTo(true);

        // The outbox relay must publish exactly one order.place for this order.
        Commands.OrderPlace command = await().atMost(10, SECONDS).until(() -> drainUntilOrderPlace(orderId), c -> c != null);
        assertThat(command.items()).hasSize(1);
        assertThat(command.items().get(0).quantity()).isEqualTo(2);
        assertThat(command.paymentCard()).isEqualTo("4242424242424242");

        // Act as the processor: confirm it, then change stock.
        rabbitTemplate.convertAndSend(Routing.EXCHANGE, Routing.ORDER_CONFIRMED,
                new Events.OrderConfirmed(UUID.randomUUID(), command.correlationId(), command.orderId(), UUID.randomUUID()));
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Map order = http.exchange("/api/v1/orders/" + orderId, HttpMethod.GET, new HttpEntity<>(auth), Map.class).getBody();
            assertThat(order.get("status")).isEqualTo("CONFIRMED");
        });

        UUID stockMsg = UUID.randomUUID();
        Events.InventoryChanged changed = new Events.InventoryChanged(stockMsg, "t", List.of(new Events.StockLevel(UUID.fromString(productId), 3)));
        rabbitTemplate.convertAndSend(Routing.EXCHANGE, Routing.INVENTORY_CHANGED, changed);
        rabbitTemplate.convertAndSend(Routing.EXCHANGE, Routing.INVENTORY_CHANGED, changed); // duplicate must be a no-op
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Map product = http.getForObject("/api/v1/products/" + productId, Map.class);
            assertThat(product.get("stock")).isEqualTo(3);
        });

        // Cancel publishes order.cancel; the cancelled event lands the order in CANCELLED.
        ResponseEntity<Map> cancel = http.exchange("/api/v1/orders/" + orderId + "/cancel", HttpMethod.POST, new HttpEntity<>(auth), Map.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(cancel.getBody().get("status")).isEqualTo("CANCEL_REQUESTED");
        Commands.OrderCancel cancelCmd = await().atMost(10, SECONDS).until(() -> drainUntil(Commands.OrderCancel.class, orderId), c -> c != null);
        rabbitTemplate.convertAndSend(Routing.EXCHANGE, Routing.ORDER_CANCELLED,
                new Events.OrderCancelled(UUID.randomUUID(), cancelCmd.correlationId(), cancelCmd.orderId(), true));
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Map order = http.exchange("/api/v1/orders/" + orderId, HttpMethod.GET, new HttpEntity<>(auth), Map.class).getBody();
            assertThat(order.get("status")).isEqualTo("CANCELLED");
        });
    }

    @Test
    void adminEndpointsRequireAdminRole() {
        ResponseEntity<String> anon = http.getForEntity("/api/v1/admin/stats", String.class);
        assertThat(anon.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Map login = http.postForObject("/api/v1/auth/login", Map.of("email", "admin@amazeawn.local", "password", "admin123"), Map.class);
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth((String) login.get("accessToken"));
        ResponseEntity<Map> stats = http.exchange("/api/v1/admin/stats", HttpMethod.GET, new HttpEntity<>(auth), Map.class);
        assertThat(stats.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stats.getBody()).containsKey("ordersByStatus");
    }

    private Commands.OrderPlace drainUntilOrderPlace(String orderId) {
        return drainUntil(Commands.OrderPlace.class, orderId);
    }

    @SuppressWarnings("unchecked")
    private <T> T drainUntil(Class<T> type, String orderId) {
        Object msg;
        while ((msg = rabbitTemplate.receiveAndConvert(Routing.PROCESSOR_QUEUE, 200)) != null) {
            if (type.isInstance(msg)) {
                UUID id = msg instanceof Commands.OrderPlace p ? p.orderId() : msg instanceof Commands.OrderCancel c ? c.orderId() : null;
                if (id != null && id.toString().equals(orderId)) {
                    return (T) msg;
                }
            }
        }
        return null;
    }
}
