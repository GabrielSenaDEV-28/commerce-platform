package com.gabrielsena.commerce.catalog.api;

import com.gabrielsena.commerce.catalog.domain.Product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(UUID id, UUID itemTypeId, String sku, String name, String description,
                              BigDecimal price, boolean active, Instant createdAt, UUID createdBy,
                              Instant updatedAt, UUID updatedBy) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getItemTypeId(), p.getSku(), p.getName(), p.getDescription(),
                p.getPrice(), p.isActive(), p.getCreatedAt(), p.getCreatedBy(), p.getUpdatedAt(), p.getUpdatedBy());
    }
}
