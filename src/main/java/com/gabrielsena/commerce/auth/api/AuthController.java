package com.gabrielsena.commerce.auth.api;

import com.gabrielsena.commerce.auth.application.AuthenticateMerchant;
import com.gabrielsena.commerce.auth.application.RefreshAuthentication;
import com.gabrielsena.commerce.auth.application.RevokeRefreshToken;
import com.gabrielsena.commerce.auth.application.TokenPair;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticateMerchant authenticateMerchant;
    private final RefreshAuthentication refreshAuthentication;
    private final RevokeRefreshToken revokeRefreshToken;

    public AuthController(
            AuthenticateMerchant authenticateMerchant,
            RefreshAuthentication refreshAuthentication,
            RevokeRefreshToken revokeRefreshToken
    ) {
        this.authenticateMerchant = authenticateMerchant;
        this.refreshAuthentication = refreshAuthentication;
        this.revokeRefreshToken = revokeRefreshToken;
    }

    @PostMapping("/login")
    public AuthTokenResponse login(@Valid @RequestBody LoginRequest request) {
        TokenPair tokens = authenticateMerchant.execute(request.email(), request.password());
        return AuthTokenResponse.from(tokens);
    }

    @PostMapping("/refresh")
    public AuthTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        TokenPair tokens = refreshAuthentication.execute(request.refreshToken());
        return AuthTokenResponse.from(tokens);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        revokeRefreshToken.execute(request.refreshToken());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
