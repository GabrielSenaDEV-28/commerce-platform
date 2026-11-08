package com.gabrielsena.commerce.merchant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record OnboardMerchantRequest(
        @NotBlank String merchantName,
        @NotBlank String storeName,
        @NotBlank String subdomain,
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 12, max = 128) String password
) {
}
