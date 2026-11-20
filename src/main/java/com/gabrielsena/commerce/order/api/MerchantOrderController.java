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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.format.annotation.DateTimeFormat;
import java.time.LocalDate;

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
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String status, @RequestParam(required = false) String cpf,
            @RequestParam(required = false) OrderService.FulfillmentType fulfillmentType,
            @RequestParam(required = false) UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(PageResponse.from(orders.merchantOrders(actor(jwt), pageable(page, size, "createdAt", "id"),
                        new OrderService.Filters(startDate, endDate, status, cpf, fulfillmentType, orderId))));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Order> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(orders.merchantOrder(actor(jwt), orderId));
    }

    @PostMapping("/{orderId}/complete-pickup")
    public ResponseEntity<Order> completePickup(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(orders.completePickup(actor(jwt), orderId));
    }
}
