package com.gabrielsena.commerce.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "catalog_item_types")
@Getter
public class ItemType {
    @Id
    @Column(name = "item_type_id", nullable = false)
    private UUID id;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(name = "has_color", nullable = false)
    private boolean hasColor;
    @Column(name = "has_size", nullable = false)
    private boolean hasSize;
    @Enumerated(EnumType.STRING)
    @Column(name = "size_format", length = 10)
    private SizeFormat sizeFormat;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;
    @Column(nullable = false)
    private boolean active = true;

    protected ItemType() {
    }

    public static ItemType create(UUID storeId, UUID actorId, ItemTypeValues values, Instant now) {
        ItemType item = new ItemType();
        item.id = UUID.randomUUID();
        item.storeId = storeId;
        item.createdAt = now;
        item.createdBy = actorId;
        item.update(values, actorId, now);
        return item;
    }

    public void update(ItemTypeValues values, UUID actorId, Instant now) {
        this.name = values.name();
        this.hasColor = values.hasColor();
        this.hasSize = values.hasSize();
        this.sizeFormat = values.sizeFormat();
        this.updatedBy = actorId;
        this.updatedAt = now;
    }

    public ItemTypeValues values() {
        return new ItemTypeValues(name, hasColor, hasSize, sizeFormat);
    }

    public void setActive(boolean active, UUID actorId, Instant now) {
        this.active = active;
        this.updatedBy = actorId;
        this.updatedAt = now;
    }
}
