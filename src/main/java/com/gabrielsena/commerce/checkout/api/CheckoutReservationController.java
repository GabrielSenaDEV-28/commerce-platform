package com.gabrielsena.commerce.checkout.api;

import com.gabrielsena.commerce.checkout.application.CheckoutReservationService;
import com.gabrielsena.commerce.checkout.application.CheckoutReservationService.Checkout;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/checkout")
public class CheckoutReservationController {
    private final CheckoutReservationService checkout;
    private final PublicStoreResolver stores;

    public CheckoutReservationController(CheckoutReservationService checkout, PublicStoreResolver stores) {
        this.checkout = checkout;
        this.stores = stores;
    }

    @PostMapping
    public ResponseEntity<Checkout> start(HttpServletRequest request,
            @RequestHeader(value = "Cart-Token", required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(checkout.start(stores.resolve(request.getServerName()), token));
    }

    @GetMapping
    public ResponseEntity<Checkout> get(HttpServletRequest request,
            @RequestHeader(value = "Cart-Token", required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(checkout.get(stores.resolve(request.getServerName()), token));
    }

    @PostMapping("/{reservationId}/cancel")
    public ResponseEntity<Checkout> cancel(HttpServletRequest request,
            @RequestHeader(value = "Cart-Token", required = false) String token, @PathVariable UUID reservationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(checkout.cancel(stores.resolve(request.getServerName()), token, reservationId));
    }
}
