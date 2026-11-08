package com.gabrielsena.commerce.catalog.api;

import com.gabrielsena.commerce.api.PageResponse;
import com.gabrielsena.commerce.catalog.application.ProductService;
import com.gabrielsena.commerce.catalog.domain.Product;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.gabrielsena.commerce.api.ApiRequestSupport.actor;
import static com.gabrielsena.commerce.api.ApiRequestSupport.pageable;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody ProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.from(service.create(actor(jwt), request)));
    }

    @GetMapping
    public PageResponse<ProductResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "true") boolean active,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(service.list(actor(jwt), active, pageable(page, size, "name", "id"))
                .map(ProductResponse::from));
    }

    @GetMapping("/{id}")
    public ProductResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ProductResponse.from(service.get(actor(jwt), id));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                  @Valid @RequestBody ProductUpdateRequest request) {
        return ProductResponse.from(service.update(actor(jwt), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.setActive(actor(jwt), id, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reactivate")
    public ProductResponse reactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ProductResponse.from(service.setActive(actor(jwt), id, true));
    }

    @GetMapping("/{id}/variants")
    public PageResponse<VariantResponse> variants(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "true") boolean active,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        UUID actor = actor(jwt);
        Product product = service.get(actor, id);
        return PageResponse.from(service.variants(actor, id, active, pageable(page, size, "id"))
                .map(v -> VariantResponse.from(v, product)));
    }

    @PostMapping("/{id}/variants")
    public ResponseEntity<VariantResponse> addVariant(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                       @Valid @RequestBody VariantRequest request) {
        UUID actor = actor(jwt);
        var variant = service.addVariant(actor, id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(VariantResponse.from(variant, service.get(actor, id)));
    }

    @PutMapping("/{id}/variants/{variantId}")
    public VariantResponse updateVariant(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                         @PathVariable UUID variantId,
                                         @Valid @RequestBody VariantPriceRequest request) {
        UUID actor = actor(jwt);
        var variant = service.updateVariantPrice(actor, id, variantId, request.price());
        return VariantResponse.from(variant, service.get(actor, id));
    }

    @PutMapping("/{id}/variants/{variantId}/shipping")
    public VariantResponse updateVariantShipping(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                  @PathVariable UUID variantId,
                                                  @Valid @RequestBody VariantShippingRequest request) {
        UUID actor = actor(jwt);
        var variant = service.updateVariantShipping(actor, id, variantId, request);
        return VariantResponse.from(variant, service.get(actor, id));
    }

    @DeleteMapping("/{id}/variants/{variantId}")
    public ResponseEntity<Void> deactivateVariant(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                   @PathVariable UUID variantId) {
        service.setVariantActive(actor(jwt), id, variantId, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/variants/{variantId}/reactivate")
    public VariantResponse reactivateVariant(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                              @PathVariable UUID variantId) {
        UUID actor = actor(jwt);
        var variant = service.setVariantActive(actor, id, variantId, true);
        return VariantResponse.from(variant, service.get(actor, id));
    }

    @GetMapping("/{id}/history")
    public PageResponse<ProductHistoryResponse> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(service.history(actor(jwt), id, pageable(page, size, "occurredAt", "id"))
                .map(ProductHistoryResponse::from));
    }

}
