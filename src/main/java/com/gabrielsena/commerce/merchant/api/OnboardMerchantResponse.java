package com.gabrielsena.commerce.merchant.api;

import com.gabrielsena.commerce.merchant.application.OnboardMerchantResult;

import java.util.UUID;

public record OnboardMerchantResponse(
        UUID merchantId,
        UUID storeId,
        String merchantName,
        String storeName,
        String subdomain
) {
    public static OnboardMerchantResponse from(OnboardMerchantResult result) {
        return new OnboardMerchantResponse(
                result.merchantId(),
                result.storeId(),
                result.merchantName(),
                result.storeName(),
                result.subdomain()
        );
    }
}
