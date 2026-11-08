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
@Table(name = "catalog_product_variants")
@Getter
public class ProductVariant {
    @Id @Column(name = "variant_id")
    private UUID id;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "product_id", nullable = false)
    private UUID productId;
    @Column(length = 80)
    private String color;
    @Column(length = 40)
    private String size;
    @Column(precision = 12, scale = 2)
    private BigDecimal price;
    @Column(name = "shipping_weight_kg", precision = 8, scale = 3)
    private BigDecimal shippingWeightKg;
    @Column(name = "shipping_width_cm", precision = 8, scale = 2)
    private BigDecimal shippingWidthCm;
    @Column(name = "shipping_height_cm", precision = 8, scale = 2)
    private BigDecimal shippingHeightCm;
    @Column(name = "shipping_length_cm", precision = 8, scale = 2)
    private BigDecimal shippingLengthCm;
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

    protected ProductVariant() {
    }

    public static ProductVariant create(Product product, String color, String size, BigDecimal price,
                                        UUID actor, Instant now) {
        ProductVariant variant = new ProductVariant();
        variant.id = UUID.randomUUID();
        variant.storeId = product.getStoreId();
        variant.productId = product.getId();
        variant.color = color == null ? null : color.strip();
        variant.size = size == null ? null : size.strip();
        variant.price = price;
        variant.active = true;
        variant.createdAt = now;
        variant.createdBy = actor;
        variant.updatedAt = now;
        variant.updatedBy = actor;
        return variant;
    }

    public void setPrice(BigDecimal price, UUID actor, Instant now) {
        this.price = price;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public void setShippingMeasurements(BigDecimal weightKg, BigDecimal widthCm, BigDecimal heightCm,
                                        BigDecimal lengthCm, UUID actor, Instant now) {
        this.shippingWeightKg = weightKg;
        this.shippingWidthCm = widthCm;
        this.shippingHeightCm = heightCm;
        this.shippingLengthCm = lengthCm;
        this.updatedAt = now;
        this.updatedBy = actor;
    }

    public void setActive(boolean active, UUID actor, Instant now) {
        this.active = active;
        this.updatedAt = now;
        this.updatedBy = actor;
    }
}
