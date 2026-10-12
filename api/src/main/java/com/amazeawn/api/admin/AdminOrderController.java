package com.amazeawn.api.admin;

import com.amazeawn.api.config.ApiException;
import com.amazeawn.api.order.OrderRepository;
import com.amazeawn.api.order.OrderService;
import com.amazeawn.api.order.OrderStatus;
import com.amazeawn.api.order.OrderView;
import com.amazeawn.api.order.Refund;
import com.amazeawn.api.order.RefundRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin: orders and refunds")
public class AdminOrderController {

    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final OrderService orderService;

    public AdminOrderController(OrderRepository orders, RefundRepository refunds, OrderService orderService) {
        this.orders = orders;
        this.refunds = refunds;
        this.orderService = orderService;
    }

    public record StatusRequest(@NotNull OrderStatus status) {}

    public record DecisionRequest(String note) {}

    @GetMapping("/orders")
    public Page<OrderView> orders(@RequestParam(required = false) OrderStatus status,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        PageRequest pr = PageRequest.of(page, Math.min(size, 200));
        return (status == null ? orders.findAllByOrderByCreatedAtDesc(pr) : orders.findByStatusOrderByCreatedAtDesc(status, pr))
                .map(o -> OrderView.of(o, false));
    }

    @GetMapping("/orders/{id}")
    public OrderView order(@PathVariable UUID id) {
        return orders.findWithItemsById(id).map(o -> OrderView.of(o, true)).orElseThrow(() -> ApiException.notFound("Order"));
    }

    @PutMapping("/orders/{id}/status")
    @Operation(summary = "Override fulfilment: CONFIRMED->SHIPPED, CONFIRMED/SHIPPED->DELIVERED")
    public OrderView setStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest req) {
        return orderService.adminTransition(id, req.status());
    }

    @GetMapping("/refunds")
    public Page<OrderView.RefundView> refunds(@RequestParam(required = false) Refund.Status status,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        PageRequest pr = PageRequest.of(page, Math.min(size, 200));
        return (status == null ? refunds.findAllByOrderByCreatedAtDesc(pr) : refunds.findByStatusOrderByCreatedAtDesc(status, pr))
                .map(OrderView.RefundView::of);
    }

    @PostMapping("/refunds/{id}/approve")
    public OrderView.RefundView approve(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest req) {
        return OrderView.RefundView.of(orderService.decideRefund(id, true, req == null ? null : req.note()));
    }

    @PostMapping("/refunds/{id}/reject")
    public OrderView.RefundView reject(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest req) {
        return OrderView.RefundView.of(orderService.decideRefund(id, false, req == null ? null : req.note()));
    }
}
