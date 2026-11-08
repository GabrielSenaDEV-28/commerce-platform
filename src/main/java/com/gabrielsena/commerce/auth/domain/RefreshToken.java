package com.gabrielsena.commerce.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
public class RefreshToken {

    @Id
    @Column(name = "refresh_token_id", nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {
    }

    private RefreshToken(UUID id, UUID accountId, String tokenHash, Instant expiresAt) {
        if (id == null || accountId == null || expiresAt == null) {
            throw new IllegalArgumentException("Refresh token fields must not be null");
        }
        if (tokenHash == null || tokenHash.isBlank()) {
            throw new IllegalArgumentException("Refresh token hash must not be blank");
        }

        this.id = id;
        this.accountId = accountId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public static RefreshToken create(UUID accountId, String tokenHash, Instant expiresAt) {
        return new RefreshToken(UUID.randomUUID(), accountId, tokenHash, expiresAt);
    }
}
