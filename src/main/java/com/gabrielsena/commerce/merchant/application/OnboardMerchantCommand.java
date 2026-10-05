package com.gabrielsena.commerce.merchant.application;

public record OnboardMerchantCommand(
        String merchantName,
        String storeName,
        String subdomain
) {
}
