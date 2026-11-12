package com.gabrielsena.commerce.auth.application;

import com.gabrielsena.commerce.auth.InvalidCredentialsException;
import com.gabrielsena.commerce.auth.MerchantAccountRepository;
import com.gabrielsena.commerce.auth.domain.MerchantAccount;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuthenticateMerchant {

    private final MerchantAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final String dummyPasswordHash;

    public AuthenticateMerchant(
            MerchantAccountRepository accountRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService
    ) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public TokenPair execute(String email, String password) {
        return tokenService.issue(authenticate(email, password));
    }

    @Transactional(readOnly = true)
    public MerchantAccount authenticate(String email, String password) {
        String normalizedEmail = RegisterMerchant.normalizeEmail(email);
        MerchantAccount account = accountRepository.findByEmail(normalizedEmail).orElse(null);
        String passwordHash = account == null || account.getPasswordHash() == null
                ? dummyPasswordHash
                : account.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(password, passwordHash);

        if (account == null || account.getPasswordHash() == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }

        return account;
    }
}
