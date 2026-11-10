package com.gabrielsena.commerce.auth.application;

import com.gabrielsena.commerce.auth.InvalidCredentialsException;
import com.gabrielsena.commerce.customer.application.CustomerAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class AuthenticateStoreAccount {
    private final StoreAccountIdentity identities;
    private final AuthenticateMerchant merchants;
    private final CustomerAuthService customers;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    public record Result(TokenPair tokens, String accountKind) { }

    public AuthenticateStoreAccount(StoreAccountIdentity identities, AuthenticateMerchant merchants,
                                    CustomerAuthService customers, PasswordEncoder passwords) {
        this.identities = identities;
        this.merchants = merchants;
        this.customers = customers;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Result execute(UUID store, String email, String password) {
        String normalized = RegisterMerchant.normalizeEmail(email);
        var identity = identities.resolve(store, normalized);
        if (identity.merchant() && identity.customer()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ambiguous store account. Contact support to resolve the email conflict.");
        }
        if (identity.merchant()) {
            return new Result(merchants.execute(normalized, password), "merchant");
        }
        if (identity.customer()) {
            return new Result(customers.login(store, normalized, password), "customer");
        }
        passwords.matches(password, dummyHash);
        throw new InvalidCredentialsException();
    }
}
