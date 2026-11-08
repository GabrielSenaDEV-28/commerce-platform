package com.gabrielsena.commerce.shipping.api;

import com.gabrielsena.commerce.shipping.application.ShippingEstimateService;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioClient.QuoteResult;
import com.gabrielsena.commerce.shipping.melhorenvio.MelhorEnvioException;
import com.gabrielsena.commerce.store.PublicStoreResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ShippingEstimateController {
    private final PublicStoreResolver stores;
    private final ShippingEstimateService estimates;

    public ShippingEstimateController(PublicStoreResolver stores, ShippingEstimateService estimates) {
        this.stores = stores;
        this.estimates = estimates;
    }

    public record Destination(@NotNull @Pattern(regexp = "[0-9]{8}") String postalCode) { }
    public record ProductDestination(@NotNull @Pattern(regexp = "[0-9]{8}") String postalCode,
                                     @NotNull @Min(1) Integer quantity) { }
    public record ItemErrors(String code, List<ShippingEstimateService.ItemIssue> items) { }
    public record ProviderError(String code) { }

    @PostMapping("/api/public/catalog/products/{productId}/variants/{variantId}/shipping-estimates")
    public ResponseEntity<QuoteResult> product(HttpServletRequest request, @PathVariable UUID productId,
            @PathVariable UUID variantId, @Valid @RequestBody ProductDestination destination) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(estimates.product(
                stores.resolve(request.getServerName()), productId, variantId, destination.quantity(), destination.postalCode()));
    }

    @PostMapping("/api/public/cart/shipping-estimates")
    public ResponseEntity<QuoteResult> cart(HttpServletRequest request,
            @RequestHeader(value = "Cart-Token", required = false) String token,
            @Valid @RequestBody Destination destination) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(estimates.cart(
                stores.resolve(request.getServerName()), token, destination.postalCode()));
    }

    @PostMapping("/api/public/checkout/{reservationId}/shipping-estimates")
    public ResponseEntity<ShippingEstimateService.CheckoutQuote> checkout(HttpServletRequest request,
            @PathVariable UUID reservationId, @RequestHeader(value = "Cart-Token", required = false) String token,
            @Valid @RequestBody Destination destination) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(estimates.checkout(
                stores.resolve(request.getServerName()), token, reservationId, destination.postalCode()));
    }

    @ExceptionHandler(ShippingEstimateService.InvalidItems.class)
    public ResponseEntity<ItemErrors> invalidItems(ShippingEstimateService.InvalidItems exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore())
                .body(new ItemErrors("ITEMS_NOT_QUOTABLE", exception.items()));
    }

    @ExceptionHandler(MelhorEnvioException.class)
    public ResponseEntity<ProviderError> providerError(MelhorEnvioException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .body(new ProviderError("SHIPPING_TEMPORARILY_UNAVAILABLE"));
    }
}
