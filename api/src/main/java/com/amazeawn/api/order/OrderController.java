package com.amazeawn.api.order;

import com.amazeawn.api.auth.CurrentUser;
import com.amazeawn.api.config.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orders;
    private final RefundRepository refunds;

    public OrderController(OrderService orderService, OrderRepository orders, RefundRepository refunds) {
        this.orderService = orderService;
        this.orders = orders;
        this.refunds = refunds;
    }

    public record PlaceRequest(@NotBlank String paymentCard, UUID addressId, String shippingAddress) {}

    public record RefundRequest(String reason) {}

    @PostMapping
    @Operation(summary = "Place an order from the cart",
            description = "Returns 202; the order starts PENDING and is settled asynchronously. Poll GET /orders/{id}. "
                    + "Idempotency-Key is required and makes retries safe.")
    public ResponseEntity<Map<String, Object>> place(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                     @Valid @RequestBody PlaceRequest req) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw ApiException.badRequest("Idempotency-Key must be 1-128 characters");
        }
        OrderService.PlaceResult result = orderService.place(CurrentUser.id(), idempotencyKey, req.paymentCard(),
                req.addressId(), req.shippingAddress());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header("Location", "/api/v1/orders/" + result.orderId())
                .body(Map.of("orderId", result.orderId(), "status", result.status(), "replayed", result.replayed()));
    }

    @GetMapping
    public Page<OrderView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return orders.findByUserIdOrderByCreatedAtDesc(CurrentUser.id(), PageRequest.of(page, Math.min(size, 100)))
                .map(o -> OrderView.of(o, false));
    }

    @GetMapping("/{id}")
    public OrderView detail(@PathVariable UUID id) {
        return orders.findByIdAndUserId(id, CurrentUser.id()).map(o -> OrderView.of(o, true))
                .orElseThrow(() -> ApiException.notFound("Order"));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderView> cancel(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(orderService.cancel(CurrentUser.id(), id));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<OrderView.RefundView> requestRefund(@PathVariable UUID id, @RequestBody(required = false) RefundRequest req) {
        Refund refund = orderService.requestRefund(CurrentUser.id(), id, req == null ? null : req.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderView.RefundView.of(refund));
    }

    @GetMapping("/{id}/refund")
    public List<OrderView.RefundView> refundStatus(@PathVariable UUID id) {
        orders.findByIdAndUserId(id, CurrentUser.id()).orElseThrow(() -> ApiException.notFound("Order"));
        return refunds.findByOrderIdOrderByCreatedAtDesc(id).stream().map(OrderView.RefundView::of).toList();
    }
}
