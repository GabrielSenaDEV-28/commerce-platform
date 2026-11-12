package com.gabrielsena.commerce.auth.application;

import com.gabrielsena.commerce.auth.RefreshTokenRepository;
import com.gabrielsena.commerce.auth.domain.MerchantAccount;
import com.gabrielsena.commerce.auth.domain.RefreshToken;
import com.gabrielsena.commerce.security.OpaqueTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class TokenService {

    public static final long ACCESS_TOKEN_SECONDS = 15 * 60L;
    private static final long REFRESH_TOKEN_DAYS = 7;

    private final JwtEncoder jwtEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final String issuer;

    public TokenService(
            JwtEncoder jwtEncoder,
            RefreshTokenRepository refreshTokenRepository,
            @Value("${spring.application.name}") String issuer
    ) {
        this.jwtEncoder = jwtEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.issuer = issuer;
    }

    @Transactional
    public TokenPair issue(MerchantAccount account) {
        Instant now = Instant.now();
        String refreshToken = OpaqueTokens.generate();
        refreshTokenRepository.save(RefreshToken.create(
                account.getId(),
                hashToken(refreshToken),
                now.plus(REFRESH_TOKEN_DAYS, ChronoUnit.DAYS)
        ));
        return new TokenPair(createAccessToken(account, now), refreshToken);
    }

    public String accessToken(MerchantAccount account) {
        return createAccessToken(account, Instant.now());
    }

    private String createAccessToken(MerchantAccount account, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(ACCESS_TOKEN_SECONDS))
                .subject(account.getId().toString())
                .claim("merchant_id", account.getMerchantId().toString())
                .claim("email", account.getEmail())
                .build();

        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(),
                claims
        )).getTokenValue();
    }

    public static String hashToken(String token) {
        return OpaqueTokens.hash(token);
    }
}
