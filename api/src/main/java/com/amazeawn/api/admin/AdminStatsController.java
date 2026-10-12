package com.amazeawn.api.admin;

import com.amazeawn.api.auth.Role;
import com.amazeawn.api.auth.UserRepository;
import com.amazeawn.api.catalog.ProductRepository;
import com.amazeawn.api.order.OrderRepository;
import com.amazeawn.api.order.OrderStatus;
import com.amazeawn.api.order.Refund;
import com.amazeawn.api.order.RefundRepository;
import com.amazeawn.common.outbox.OutboxRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/stats")
@Tag(name = "Admin: stats")
public class AdminStatsController {

    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final ProductRepository products;
    private final UserRepository users;
    private final OutboxRepository outbox;

    public AdminStatsController(OrderRepository orders, RefundRepository refunds, ProductRepository products,
                                UserRepository users, OutboxRepository outbox) {
        this.orders = orders;
        this.refunds = refunds;
        this.products = products;
        this.users = users;
        this.outbox = outbox;
    }

    public record Stats(Map<OrderStatus, Long> ordersByStatus, BigDecimal revenue, long customers, long activeProducts,
                        long outOfStockProducts, long refundsRequested, long outboxPending) {}

    @GetMapping
    public Stats stats() {
        Map<OrderStatus, Long> byStatus = new EnumMap<>(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) byStatus.put(s, 0L);
        for (Object[] row : orders.countGroupedByStatus()) byStatus.put((OrderStatus) row[0], (Long) row[1]);
        BigDecimal revenue = orders.sumTotalByStatusIn(List.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderStatus.DELIVERED));
        return new Stats(byStatus, revenue, users.countByRole(Role.CUSTOMER), products.countByActiveTrue(),
                products.countByStockLessThanEqual(0), refunds.countByStatus(Refund.Status.REQUESTED),
                outbox.countByPublishedAtIsNull());
    }
}
