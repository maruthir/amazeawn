package com.amazeawn.api.cart;

import com.amazeawn.api.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cart")
@Tag(name = "Cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    public record AddRequest(@NotNull UUID productId, @Min(1) int quantity) {}

    public record QuantityRequest(@Min(0) int quantity) {}

    @GetMapping
    public CartService.CartView view() {
        return cartService.view(CurrentUser.id());
    }

    @PostMapping("/items")
    public CartService.CartView add(@Valid @RequestBody AddRequest req) {
        return cartService.add(CurrentUser.id(), req.productId(), req.quantity());
    }

    @PutMapping("/items/{itemId}")
    public CartService.CartView update(@PathVariable UUID itemId, @Valid @RequestBody QuantityRequest req) {
        return cartService.updateQuantity(CurrentUser.id(), itemId, req.quantity());
    }

    @DeleteMapping("/items/{itemId}")
    public CartService.CartView remove(@PathVariable UUID itemId) {
        return cartService.remove(CurrentUser.id(), itemId);
    }

    @DeleteMapping
    public CartService.CartView clear() {
        return cartService.clear(CurrentUser.id());
    }
}
