package com.gabrielsena.commerce.catalog.api;

import com.gabrielsena.commerce.catalog.domain.ItemType;
import com.gabrielsena.commerce.catalog.domain.SizeFormat;

import java.time.Instant;
import java.util.UUID;

public record ItemTypeResponse(UUID id, String name, boolean hasColor, boolean hasSize, SizeFormat sizeFormat,
                               boolean active, Instant createdAt, UUID createdBy, Instant updatedAt, UUID updatedBy) {
    public static ItemTypeResponse from(ItemType item) {
        return new ItemTypeResponse(item.getId(), item.getName(), item.isHasColor(), item.isHasSize(),
                item.getSizeFormat(), item.isActive(), item.getCreatedAt(), item.getCreatedBy(), item.getUpdatedAt(), item.getUpdatedBy());
    }
}
