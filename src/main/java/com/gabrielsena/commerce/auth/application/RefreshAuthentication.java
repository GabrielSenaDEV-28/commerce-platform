package com.gabrielsena.commerce.auth.application;

import com.gabrielsena.commerce.auth.InvalidRefreshTokenException;
import com.gabrielsena.commerce.auth.MerchantAccountRepository;
import com.gabrielsena.commerce.auth.RefreshTokenRepository;
import com.gabrielsena.commerce.auth.domain.MerchantAccount;
import com.gabrielsena.commerce.auth.domain.RefreshToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class RefreshAuthentication {

    private final RefreshTokenRepository refreshTokenRepository;
    private final MerchantAccountRepository accountRepository;
    private final TokenService tokenService;

    public RefreshAuthentication(
            RefreshTokenRepository refreshTokenRepository,
            MerchantAccountRepository accountRepository,
            TokenService tokenService
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.accountRepository = accountRepository;
        this.tokenService = tokenService;
    }

    @Transactional
    public TokenPair execute(String refreshTokenValue) {
        Instant now = Instant.now();
        String tokenHash = TokenService.hashToken(refreshTokenValue);
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::new);

        int revoked = refreshTokenRepository.revokeIfActive(tokenHash, now);
        if (revoked != 1) {
            throw new InvalidRefreshTokenException();
        }

        MerchantAccount account = accountRepository.findById(refreshToken.getAccountId())
                .orElseThrow(InvalidRefreshTokenException::new);
        return tokenService.issue(account);
    }
}
