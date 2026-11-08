package com.gabrielsena.commerce.order.api;

import com.gabrielsena.commerce.order.application.OrderService;
import com.gabrielsena.commerce.order.application.OrderService.Order;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PublicOrderController {
    private final OrderService orders;
    private final PublicStoreResolver stores;

    public PublicOrderController(OrderService orders, PublicStoreResolver stores) {
        this.orders = orders;
        this.stores = stores;
    }

    public record CreateOrderRequest(@NotBlank @Size(max = 150) String customerName,
                                     @NotBlank @Email @Size(max = 320) String customerEmail,
                                     @NotNull Boolean acceptPartial) { }

    @PostMapping("/api/public/checkout/{reservationId}/orders")
    public ResponseEntity<Order> create(HttpServletRequest request,
            @RequestHeader(value = "Cart-Token", required = false) String token,
            @PathVariable UUID reservationId, @Valid @RequestBody CreateOrderRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(orders.create(
                stores.resolve(request.getServerName()), token, reservationId, body.customerName(),
                body.customerEmail(), body.acceptPartial()));
    }

    @GetMapping("/api/public/orders/{orderId}")
    public ResponseEntity<Order> get(HttpServletRequest request,
            @RequestHeader(value = "Cart-Token", required = false) String token, @PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orders.customerOrder(stores.resolve(request.getServerName()), token, orderId));
    }
}
