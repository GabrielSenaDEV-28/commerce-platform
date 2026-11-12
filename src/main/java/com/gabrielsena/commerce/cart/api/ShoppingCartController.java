package com.gabrielsena.commerce.cart.api;

import com.gabrielsena.commerce.cart.application.ShoppingCartService;
import com.gabrielsena.commerce.cart.application.ShoppingCartService.Cart;
import com.gabrielsena.commerce.cart.application.ShoppingCartService.CreatedCart;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/cart")
public class ShoppingCartController {
    private final ShoppingCartService carts;
    private final PublicStoreResolver stores;

    public ShoppingCartController(ShoppingCartService carts, PublicStoreResolver stores) {
        this.carts = carts;
        this.stores = stores;
    }

    public record QuantityRequest(@NotNull @Min(1) @Digits(integer = 10, fraction = 0)
                                  @DecimalMax("2147483647") BigDecimal quantity) { }

    @PostMapping
    public ResponseEntity<CreatedCart> create(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(carts.create(stores.resolve(request.getServerName())));
    }

    @PostMapping("/customer")
    public ResponseEntity<ShoppingCartService.CustomerCart> recover(HttpServletRequest request,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Cart-Token", required = false) String sourceToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(carts.recover(stores.resolve(request.getServerName()), jwt, sourceToken));
    }

    @GetMapping
    public ResponseEntity<Cart> get(HttpServletRequest request,
                                    @RequestHeader(value = "Cart-Token", required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(carts.get(stores.resolve(request.getServerName()), token));
    }

    @PutMapping("/items/{productId}/{variantId}")
    public ResponseEntity<Cart> setQuantity(HttpServletRequest request,
            @RequestHeader(value = "Cart-Token", required = false) String token, @PathVariable UUID productId,
            @PathVariable UUID variantId, @Valid @RequestBody QuantityRequest quantity) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(carts.setQuantity(
                stores.resolve(request.getServerName()), token, productId, variantId, quantity.quantity().intValueExact()));
    }

    @DeleteMapping("/items/{productId}/{variantId}")
    public ResponseEntity<Cart> remove(HttpServletRequest request,
            @RequestHeader(value = "Cart-Token", required = false) String token, @PathVariable UUID productId,
            @PathVariable UUID variantId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(carts.remove(stores.resolve(request.getServerName()), token, productId, variantId));
    }
}
