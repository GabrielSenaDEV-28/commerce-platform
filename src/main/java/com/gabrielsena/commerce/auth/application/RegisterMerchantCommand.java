package com.gabrielsena.commerce.auth.application;

public record RegisterMerchantCommand(
        String merchantName,
        String storeName,
        String subdomain,
        String email,
        String password
) {
}
