package com.gabrielsena.commerce.catalog.domain;

public record ItemTypeValues(String name, boolean hasColor, boolean hasSize, SizeFormat sizeFormat) {
    public ItemTypeValues {
        if (name == null || name.isBlank() || name.strip().length() > 120) {
            throw new IllegalArgumentException("Invalid item type name");
        }
        name = name.strip();
        if (hasSize != (sizeFormat != null)) {
            throw new IllegalArgumentException("Size format must be provided exactly when size is enabled");
        }
    }
}
