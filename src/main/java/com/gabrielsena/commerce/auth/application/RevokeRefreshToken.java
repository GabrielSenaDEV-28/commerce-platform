package com.gabrielsena.commerce.auth.application;

import com.gabrielsena.commerce.auth.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RevokeRefreshToken {

    private final RefreshTokenRepository refreshTokenRepository;

    public RevokeRefreshToken(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional
    public void execute(String refreshTokenValue) {
        refreshTokenRepository.revokeIfActive(TokenService.hashToken(refreshTokenValue), Instant.now());
    }
}
