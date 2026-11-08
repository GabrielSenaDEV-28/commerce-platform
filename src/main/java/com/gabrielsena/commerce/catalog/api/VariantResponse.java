package com.gabrielsena.commerce.catalog.api;

import com.gabrielsena.commerce.catalog.domain.Product;
import com.gabrielsena.commerce.catalog.domain.ProductVariant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record VariantResponse(UUID id, String color, String size, BigDecimal price, BigDecimal effectivePrice,
                              BigDecimal shippingWeightKg, BigDecimal shippingWidthCm,
                              BigDecimal shippingHeightCm, BigDecimal shippingLengthCm,
                              boolean active, Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy) {
    public static VariantResponse from(ProductVariant v, Product p) {
        return new VariantResponse(v.getId(), v.getColor(), v.getSize(), v.getPrice(),
                v.getPrice() == null ? p.getPrice() : v.getPrice(), v.getShippingWeightKg(),
                v.getShippingWidthCm(), v.getShippingHeightCm(), v.getShippingLengthCm(), v.isActive(),
                v.getCreatedAt(), v.getCreatedBy(), v.getUpdatedAt(), v.getUpdatedBy());
    }
}
