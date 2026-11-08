package com.gabrielsena.commerce.catalog.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;

public record VariantPriceRequest(@DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal price) {
}
