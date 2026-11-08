package com.gabrielsena.commerce.catalog.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record VariantRequest(
        @Size(max = 80) String color,
        @Size(max = 40) String size,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price
) {
}
