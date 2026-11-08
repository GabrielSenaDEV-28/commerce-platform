package com.gabrielsena.commerce.order.api;

import com.gabrielsena.commerce.api.PageResponse;
import com.gabrielsena.commerce.order.application.OrderService;
import com.gabrielsena.commerce.order.application.OrderService.Order;
import com.gabrielsena.commerce.order.application.OrderService.OrderSummary;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.gabrielsena.commerce.api.ApiRequestSupport.actor;
import static com.gabrielsena.commerce.api.ApiRequestSupport.pageable;

@RestController
@RequestMapping("/api/orders")
public class MerchantOrderController {
    private final OrderService orders;

    public MerchantOrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public ResponseEntity<PageResponse<OrderSummary>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(PageResponse.from(orders.merchantOrders(actor(jwt), pageable(page, size, "createdAt", "id"))));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Order> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(orders.merchantOrder(actor(jwt), orderId));
    }
}
