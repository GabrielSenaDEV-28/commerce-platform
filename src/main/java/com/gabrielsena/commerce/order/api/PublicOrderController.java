package com.gabrielsena.commerce.order.api;

import com.gabrielsena.commerce.order.application.OrderService;
import com.gabrielsena.commerce.order.application.OrderCheckoutService;
import com.gabrielsena.commerce.order.application.OrderService.Order;
import com.gabrielsena.commerce.order.application.OrderService.OrderSummary;
import com.gabrielsena.commerce.order.application.OrderService.FulfillmentType;
import com.gabrielsena.commerce.customer.application.CustomerAuthService;
import com.gabrielsena.commerce.api.PageResponse;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import com.gabrielsena.commerce.shipping.application.ShippingEstimateService;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import static com.gabrielsena.commerce.api.ApiRequestSupport.pageable;

@RestController
public class PublicOrderController {
    private final OrderService orders;
    private final PublicStoreResolver stores;
    private final CustomerAuthService customers;
    private final OrderCheckoutService checkout;

    public PublicOrderController(OrderService orders, PublicStoreResolver stores, CustomerAuthService customers,
                                 OrderCheckoutService checkout) {
        this.orders = orders;
        this.stores = stores;
        this.customers = customers;
        this.checkout = checkout;
    }

    public record CreateOrderRequest(@NotNull Boolean acceptPartial, UUID addressId, FulfillmentType fulfillmentType,
                                     UUID quoteId, Integer shippingServiceId) {
        @AssertTrue
        public boolean isFulfillmentValid() {
            return fulfillmentType == FulfillmentType.PICKUP
                    ? addressId == null && quoteId == null && shippingServiceId == null : addressId != null;
        }
    }

    @PostMapping("/api/public/checkout/{reservationId}/orders")
    public ResponseEntity<Order> create(HttpServletRequest request,
            @RequestHeader(value = "Cart-Token", required = false) String token,
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID reservationId, @Valid @RequestBody CreateOrderRequest body) {
        UUID store = stores.resolve(request.getServerName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(checkout.create(
                store, token, reservationId, customers.customer(store, jwt), body.acceptPartial(), body.addressId(),
                body.fulfillmentType() == null ? FulfillmentType.DELIVERY : body.fulfillmentType(),
                body.quoteId(), body.shippingServiceId()));
    }

    public record ShippingChangedResponse(String code, ShippingEstimateService.CheckoutQuote quote) { }
    public record ProviderError(String code) { }
    public record ItemErrors(String code, java.util.List<ShippingEstimateService.ItemIssue> items) { }

    @ExceptionHandler(OrderCheckoutService.ShippingChanged.class)
    public ResponseEntity<ShippingChangedResponse> shippingChanged(OrderCheckoutService.ShippingChanged exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(new ShippingChangedResponse("SHIPPING_QUOTE_CHANGED", exception.quote()));
    }

    @ExceptionHandler(MelhorEnvioException.class)
    public ResponseEntity<ProviderError> shippingError(MelhorEnvioException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(new ProviderError("SHIPPING_TEMPORARILY_UNAVAILABLE"));
    }

    @ExceptionHandler(ShippingEstimateService.InvalidItems.class)
    public ResponseEntity<ItemErrors> invalidItems(ShippingEstimateService.InvalidItems exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(new ItemErrors("ITEMS_NOT_QUOTABLE", exception.items()));
    }

    @GetMapping("/api/public/orders/{orderId}")
    public ResponseEntity<Order> get(HttpServletRequest request,
            @RequestHeader(value = "Cart-Token", required = false) String token, @PathVariable UUID orderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orders.customerOrder(stores.resolve(request.getServerName()), token, orderId));
    }

    @GetMapping("/api/public/customer/orders")
    public ResponseEntity<PageResponse<OrderSummary>> list(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        UUID store = stores.resolve(request.getServerName());
        var customer = customers.customer(store, jwt);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PageResponse.from(
                orders.customerOrders(store, customer.id(), pageable(page, size, "createdAt", "id"))));
    }

    @GetMapping("/api/public/customer/orders/{orderId}")
    public ResponseEntity<Order> owned(HttpServletRequest request, @AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        UUID store = stores.resolve(request.getServerName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(orders.ownedOrder(store, customers.customer(store, jwt).id(), orderId));
    }
}
