package com.gabrielsena.commerce.auth.api;

import com.gabrielsena.commerce.auth.application.TokenPair;
import com.gabrielsena.commerce.auth.application.TokenService;

public record AuthTokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {

    public static AuthTokenResponse from(TokenPair tokenPair) {
        return new AuthTokenResponse(
                tokenPair.accessToken(),
                tokenPair.refreshToken(),
                "Bearer",
                TokenService.ACCESS_TOKEN_SECONDS
        );
    }
}
