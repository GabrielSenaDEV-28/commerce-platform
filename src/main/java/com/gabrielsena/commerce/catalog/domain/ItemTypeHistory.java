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
@Table(name = "catalog_item_type_history")
@Getter
public class ItemTypeHistory {
    public enum Action { CREATED, UPDATED, DELETED }

    @Id
    @Column(name = "history_id")
    private UUID id;
    @Column(name = "item_type_id", nullable = false)
    private UUID itemTypeId;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "actor_id", nullable = false)
    private UUID actorId;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Action action;
    @Column(name = "old_name", length = 120)
    private String oldName;
    @Column(name = "old_has_color")
    private Boolean oldHasColor;
    @Column(name = "old_has_size")
    private Boolean oldHasSize;
    @Enumerated(EnumType.STRING)
    @Column(name = "old_size_format", length = 10)
    private SizeFormat oldSizeFormat;
    @Column(name = "new_name", length = 120)
    private String newName;
    @Column(name = "new_has_color")
    private Boolean newHasColor;
    @Column(name = "new_has_size")
    private Boolean newHasSize;
    @Enumerated(EnumType.STRING)
    @Column(name = "new_size_format", length = 10)
    private SizeFormat newSizeFormat;

    protected ItemTypeHistory() {
    }

    public static ItemTypeHistory record(ItemType item, UUID actorId, Instant now, Action action,
                                         ItemTypeValues oldValues, ItemTypeValues newValues) {
        ItemTypeHistory history = new ItemTypeHistory();
        history.id = UUID.randomUUID();
        history.itemTypeId = item.getId();
        history.storeId = item.getStoreId();
        history.actorId = actorId;
        history.occurredAt = now;
        history.action = action;
        if (oldValues != null) {
            history.oldName = oldValues.name();
            history.oldHasColor = oldValues.hasColor();
            history.oldHasSize = oldValues.hasSize();
            history.oldSizeFormat = oldValues.sizeFormat();
        }
        if (newValues != null) {
            history.newName = newValues.name();
            history.newHasColor = newValues.hasColor();
            history.newHasSize = newValues.hasSize();
            history.newSizeFormat = newValues.sizeFormat();
        }
        return history;
    }
}
