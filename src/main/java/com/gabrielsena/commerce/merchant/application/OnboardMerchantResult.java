package com.gabrielsena.commerce.merchant.application;

import java.util.UUID;

public record OnboardMerchantResult(
        UUID merchantId,
        UUID storeId,
        String merchantName,
        String sotoreName,
        String subdomain
) {
}
