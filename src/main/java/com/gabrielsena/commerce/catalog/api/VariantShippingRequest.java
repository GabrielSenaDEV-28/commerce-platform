package com.gabrielsena.commerce.catalog.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record VariantShippingRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 5, fraction = 3) BigDecimal weightKg,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 2) BigDecimal widthCm,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 2) BigDecimal heightCm,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 6, fraction = 2) BigDecimal lengthCm
) { }
