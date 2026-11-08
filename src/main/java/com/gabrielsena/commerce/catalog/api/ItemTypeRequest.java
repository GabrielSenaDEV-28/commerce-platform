package com.gabrielsena.commerce.catalog.api;

import com.gabrielsena.commerce.catalog.domain.ItemTypeValues;
import com.gabrielsena.commerce.catalog.domain.SizeFormat;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ItemTypeRequest(
        @NotBlank @Size(max = 120) String name,
        @NotNull Boolean hasColor,
        @NotNull Boolean hasSize,
        SizeFormat sizeFormat
) {
    @AssertTrue(message = "sizeFormat must be provided exactly when hasSize is true")
    public boolean isSizeConfigurationValid() {
        return hasSize == null || hasSize == (sizeFormat != null);
    }

    public ItemTypeValues values() {
        return new ItemTypeValues(name, hasColor, hasSize, sizeFormat);
    }
}
