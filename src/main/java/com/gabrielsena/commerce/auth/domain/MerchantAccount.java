package com.gabrielsena.commerce.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.util.UUID;

@Entity
@Table(name = "merchant_accounts")
@Getter
public class MerchantAccount {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, unique = true)
    private UUID merchantId;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", columnDefinition = "text")
    private String passwordHash;

    protected MerchantAccount() {
    }

    private MerchantAccount(UUID id, UUID merchantId, String email, String passwordHash) {
        if (id == null || merchantId == null) {
            throw new IllegalArgumentException("Account and merchant ids must not be null");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        if (passwordHash != null && passwordHash.isBlank()) {
            throw new IllegalArgumentException("Password hash must not be blank when provided");
        }

        this.id = id;
        this.merchantId = merchantId;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    public static MerchantAccount create(UUID merchantId, String email, String passwordHash) {
        return new MerchantAccount(UUID.randomUUID(), merchantId, email, passwordHash);
    }
}
