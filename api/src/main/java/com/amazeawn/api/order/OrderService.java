package com.amazeawn.api.order;

import com.amazeawn.api.auth.Address;
import com.amazeawn.api.auth.AddressRepository;
import com.amazeawn.api.cart.Cart;
import com.amazeawn.api.cart.CartItem;
import com.amazeawn.api.cart.CartRepository;
import com.amazeawn.api.config.ApiException;
import com.amazeawn.common.messaging.Commands;
import com.amazeawn.common.messaging.CorrelationId;
import com.amazeawn.common.outbox.Outbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderRepository orders;
    private final RefundRepository refunds;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final CartRepository carts;
    private final AddressRepository addresses;
    private final Outbox outbox;
    private final Counter placed;

    public OrderService(OrderRepository orders, RefundRepository refunds, IdempotencyKeyRepository idempotencyKeys,
                        CartRepository carts, AddressRepository addresses, Outbox outbox, MeterRegistry registry) {
        this.orders = orders;
        this.refunds = refunds;
        this.idempotencyKeys = idempotencyKeys;
        this.carts = carts;
        this.addresses = addresses;
        this.outbox = outbox;
        this.placed = Counter.builder("amazeawn.orders.placed").register(registry);
    }

    public record PlaceResult(UUID orderId, OrderStatus status, boolean replayed) {}

    /**
     * Saves the order as PENDING and an order.place outbox row in one transaction. A duplicate
     * Idempotency-Key for the same customer returns the original order without side effects.
     */
    @Transactional
    public PlaceResult place(UUID userId, String idempotencyKey, String paymentCard, UUID addressId, String inlineAddress) {
        var existing = idempotencyKeys.findById(new IdempotencyKey.Id(idempotencyKey, userId));
        if (existing.isPresent()) {
            Order order = orders.findById(existing.get().getOrderId()).orElseThrow();
            return new PlaceResult(order.getId(), order.getStatus(), true);
        }
        if (paymentCard == null || paymentCard.replaceAll("\\D", "").length() < 12) {
            throw ApiException.badRequest("paymentCard must be a 12-19 digit card number");
        }
        Cart cart = carts.findByUserId(userId).orElseThrow(() -> ApiException.badRequest("Cart is empty"));
        if (cart.getItems().isEmpty()) {
            throw ApiException.badRequest("Cart is empty");
        }
        String shippingAddress = resolveAddress(userId, addressId, inlineAddress);
        String digits = paymentCard.replaceAll("\\D", "");

        BigDecimal total = BigDecimal.ZERO;
        String currency = cart.getItems().get(0).getProduct().getCurrency();
        Order order = new Order(userId, BigDecimal.ZERO, currency, digits.substring(digits.length() - 4), shippingAddress);
        for (CartItem ci : cart.getItems()) {
            var p = ci.getProduct();
            OrderItem item = new OrderItem(order, p.getId(), p.getName(), p.getSku(), p.getPrice(), ci.getQuantity());
            order.getItems().add(item);
            total = total.add(item.lineTotal());
        }
        order.setTotalAmount(total);
        orders.save(order);
        try {
            idempotencyKeys.saveAndFlush(new IdempotencyKey(idempotencyKey, userId, order.getId()));
        } catch (DataIntegrityViolationException race) {
            throw ApiException.conflict("idempotency_race", "Concurrent request with the same Idempotency-Key");
        }
        cart.getItems().clear();
        cart.touch();

        List<Commands.OrderLine> lines = order.getItems().stream()
                .map(i -> new Commands.OrderLine(i.getProductId(), i.getQuantity(), i.getUnitPrice())).toList();
        outbox.enqueue(new Commands.OrderPlace(UUID.randomUUID(), CorrelationId.current(), order.getId(), userId,
                lines, order.getTotalAmount(), currency, digits));
        placed.increment();
        return new PlaceResult(order.getId(), order.getStatus(), false);
    }

    @Transactional
    public OrderView cancel(UUID userId, UUID orderId) {
        Order order = orders.findByIdAndUserId(orderId, userId).orElseThrow(() -> ApiException.notFound("Order"));
        if (!order.getStatus().cancellable()) {
            throw ApiException.conflict("not_cancellable", "Order in status " + order.getStatus() + " cannot be cancelled");
        }
        order.transition(OrderStatus.CANCEL_REQUESTED);
        outbox.enqueue(new Commands.OrderCancel(UUID.randomUUID(), CorrelationId.current(), order.getId()));
        return OrderView.of(order, true);
    }

    @Transactional
    public Refund requestRefund(UUID userId, UUID orderId, String reason) {
        Order order = orders.findByIdAndUserId(orderId, userId).orElseThrow(() -> ApiException.notFound("Order"));
        if (!order.getStatus().refundable()) {
            throw ApiException.conflict("not_refundable", "Order in status " + order.getStatus() + " cannot be refunded");
        }
        refunds.findFirstByOrderIdAndStatusIn(orderId, List.of(Refund.Status.REQUESTED, Refund.Status.APPROVED, Refund.Status.COMPLETED))
                .ifPresent(r -> { throw ApiException.conflict("refund_exists", "A refund is already " + r.getStatus().name().toLowerCase()); });
        return refunds.save(new Refund(orderId, userId, order.getTotalAmount(), reason));
    }

    @Transactional
    public Refund decideRefund(UUID refundId, boolean approve, String note) {
        Refund refund = refunds.findById(refundId).orElseThrow(() -> ApiException.notFound("Refund"));
        if (refund.getStatus() != Refund.Status.REQUESTED) {
            throw ApiException.conflict("refund_decided", "Refund already " + refund.getStatus().name().toLowerCase());
        }
        refund.decide(approve ? Refund.Status.APPROVED : Refund.Status.REJECTED, note);
        if (approve) {
            outbox.enqueue(new Commands.RefundExecute(UUID.randomUUID(), CorrelationId.current(), refund.getId(),
                    refund.getOrderId(), refund.getAmount()));
        }
        return refund;
    }

    /** Admin override of fulfilment status. */
    @Transactional
    public OrderView adminTransition(UUID orderId, OrderStatus next) {
        Order order = orders.findWithItemsById(orderId).orElseThrow(() -> ApiException.notFound("Order"));
        boolean allowed = switch (next) {
            case SHIPPED -> order.getStatus() == OrderStatus.CONFIRMED;
            case DELIVERED -> order.getStatus() == OrderStatus.CONFIRMED || order.getStatus() == OrderStatus.SHIPPED;
            default -> false;
        };
        if (!allowed) {
            throw ApiException.conflict("invalid_transition", order.getStatus() + " -> " + next + " is not allowed");
        }
        order.transition(next);
        return OrderView.of(order, true);
    }

    private String resolveAddress(UUID userId, UUID addressId, String inline) {
        if (inline != null && !inline.isBlank()) {
            return inline.trim();
        }
        if (addressId != null) {
            return addresses.findByIdAndUserId(addressId, userId).map(Address::formatted)
                    .orElseThrow(() -> ApiException.notFound("Address"));
        }
        return addresses.findFirstByUserIdAndIsDefaultTrue(userId).map(Address::formatted)
                .orElseThrow(() -> ApiException.badRequest("No shipping address: pass addressId or shippingAddress"));
    }
}
