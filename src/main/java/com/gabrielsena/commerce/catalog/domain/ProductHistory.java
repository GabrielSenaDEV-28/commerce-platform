package com.gabrielsena.commerce.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "catalog_product_history")
@Getter
public class ProductHistory {
    @Id @Column(name = "history_id")
    private UUID id;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "product_id", nullable = false)
    private UUID productId;
    @Column(name = "variant_id")
    private UUID variantId;
    @Column(name = "actor_id", nullable = false)
    private UUID actorId;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(nullable = false, length = 16)
    private String action;
    @Column(name = "field_name", nullable = false, length = 40)
    private String fieldName;
    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;
    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    protected ProductHistory() {
    }

    public static ProductHistory record(Product product, UUID variantId, UUID actor, Instant now,
                                        String action, String field, Object before, Object after) {
        ProductHistory history = new ProductHistory();
        history.id = UUID.randomUUID();
        history.storeId = product.getStoreId();
        history.productId = product.getId();
        history.variantId = variantId;
        history.actorId = actor;
        history.occurredAt = now;
        history.action = action;
        history.fieldName = field;
        history.oldValue = before == null ? null : before.toString();
        history.newValue = after == null ? null : after.toString();
        return history;
    }
}
