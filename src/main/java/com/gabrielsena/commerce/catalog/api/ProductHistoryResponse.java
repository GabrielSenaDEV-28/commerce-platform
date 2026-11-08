package com.gabrielsena.commerce.catalog.api;

import com.gabrielsena.commerce.catalog.domain.ProductHistory;

import java.time.Instant;
import java.util.UUID;

public record ProductHistoryResponse(UUID id, UUID variantId, UUID actorId, Instant occurredAt,
                                     String action, String field, String before, String after) {
    public static ProductHistoryResponse from(ProductHistory h) {
        return new ProductHistoryResponse(h.getId(), h.getVariantId(), h.getActorId(), h.getOccurredAt(),
                h.getAction(), h.getFieldName(), h.getOldValue(), h.getNewValue());
    }
}
