package com.amazeawn.api.cart;

import com.amazeawn.api.catalog.Product;
import com.amazeawn.api.catalog.ProductRepository;
import com.amazeawn.api.config.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class CartService {

    private final CartRepository carts;
    private final ProductRepository products;

    public CartService(CartRepository carts, ProductRepository products) {
        this.carts = carts;
        this.products = products;
    }

    public record ItemView(UUID itemId, UUID productId, String name, String sku, BigDecimal unitPrice, int quantity,
                           BigDecimal lineTotal, int stock) {}

    public record CartView(UUID id, List<ItemView> items, BigDecimal subtotal, String currency, int itemCount) {}

    @Transactional
    public Cart cartFor(UUID userId) {
        return carts.findByUserId(userId).orElseGet(() -> carts.save(new Cart(userId)));
    }

    @Transactional(readOnly = true)
    public CartView view(UUID userId) {
        return carts.findByUserId(userId).map(CartService::toView)
                .orElseGet(() -> new CartView(null, List.of(), BigDecimal.ZERO, "USD", 0));
    }

    @Transactional
    public CartView add(UUID userId, UUID productId, int quantity) {
        if (quantity < 1) throw ApiException.badRequest("quantity must be >= 1");
        Cart cart = cartFor(userId);
        Product product = products.findByIdAndActiveTrue(productId).orElseThrow(() -> ApiException.notFound("Product"));
        cart.getItems().stream().filter(i -> i.getProduct().getId().equals(productId)).findFirst()
                .ifPresentOrElse(item -> item.setQuantity(item.getQuantity() + quantity),
                        () -> cart.getItems().add(new CartItem(cart, product, quantity)));
        cart.touch();
        return toView(cart);
    }

    @Transactional
    public CartView updateQuantity(UUID userId, UUID itemId, int quantity) {
        Cart cart = cartFor(userId);
        CartItem item = cart.getItems().stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Cart item"));
        if (quantity <= 0) {
            cart.getItems().remove(item);
        } else {
            item.setQuantity(quantity);
        }
        cart.touch();
        return toView(cart);
    }

    @Transactional
    public CartView remove(UUID userId, UUID itemId) {
        return updateQuantity(userId, itemId, 0);
    }

    @Transactional
    public CartView clear(UUID userId) {
        Cart cart = cartFor(userId);
        cart.getItems().clear();
        cart.touch();
        return toView(cart);
    }

    static CartView toView(Cart cart) {
        List<ItemView> items = cart.getItems().stream().map(i -> {
            Product p = i.getProduct();
            return new ItemView(i.getId(), p.getId(), p.getName(), p.getSku(), p.getPrice(), i.getQuantity(),
                    p.getPrice().multiply(BigDecimal.valueOf(i.getQuantity())), p.getStock());
        }).toList();
        BigDecimal subtotal = items.stream().map(ItemView::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = cart.getItems().isEmpty() ? "USD" : cart.getItems().get(0).getProduct().getCurrency();
        return new CartView(cart.getId(), items, subtotal, currency, items.stream().mapToInt(ItemView::quantity).sum());
    }
}
