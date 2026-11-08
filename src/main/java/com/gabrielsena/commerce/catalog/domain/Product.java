package com.gabrielsena.commerce.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "catalog_products")
@Getter
public class Product {
    @Id @Column(name = "product_id")
    private UUID id;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "item_type_id", nullable = false)
    private UUID itemTypeId;
    @Column(nullable = false, length = 100)
    private String sku;
    @Column(nullable = false, length = 150)
    private String name;
    @Column(nullable = false, columnDefinition = "text")
    private String description;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    protected Product() {
    }

    public static Product create(UUID storeId, UUID typeId, String sku, String name, String description,
                                 BigDecimal price, UUID actor, Instant now) {
        Product product = new Product();
        product.id = UUID.randomUUID();
        product.storeId = storeId;
        product.itemTypeId = typeId;
        product.active = true;
        product.createdAt = now;
        product.createdBy = actor;
        product.update(sku, name, description, price, actor, now);
        return product;
    }

    public void update(String sku, String name, String description, BigDecimal price, UUID actor, Instant now) {
        this.sku = sku.strip();
        this.name = name.strip();
        this.description = description.strip();
        this.price = price;
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    public void setActive(boolean active, UUID actor, Instant now) {
        this.active = active;
        this.updatedBy = actor;
        this.updatedAt = now;
    }
}
