package com.gabrielsena.commerce.merchant.api;

import jakarta.validation.constraints.NotBlank;

public record OnboardMerchantRequest(
        @NotBlank String merchantName,
        @NotBlank String storeName,
        @NotBlank String subdomain
) {
}
