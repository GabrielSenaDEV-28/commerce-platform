package com.gabrielsena.commerce.auth.application;

import com.gabrielsena.commerce.auth.EmailAlreadyRegisteredException;
import com.gabrielsena.commerce.auth.MerchantAccountRepository;
import com.gabrielsena.commerce.auth.domain.MerchantAccount;
import com.gabrielsena.commerce.merchant.application.OnboardMerchant;
import com.gabrielsena.commerce.merchant.application.OnboardMerchantCommand;
import com.gabrielsena.commerce.merchant.application.OnboardMerchantResult;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class RegisterMerchant {

    private static final String EMAIL_UNIQUE_CONSTRAINT = "uk_merchant_accounts_email";

    private final OnboardMerchant onboardMerchant;
    private final MerchantAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final StoreAccountIdentity identities;

    public RegisterMerchant(
            OnboardMerchant onboardMerchant,
            MerchantAccountRepository accountRepository,
            PasswordEncoder passwordEncoder,
            StoreAccountIdentity identities
    ) {
        this.onboardMerchant = onboardMerchant;
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.identities = identities;
    }

    @Transactional
    public OnboardMerchantResult execute(RegisterMerchantCommand command) {
        String email = normalizeEmail(command.email());
        if (accountRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }

        OnboardMerchantResult merchant = onboardMerchant.execute(
                new OnboardMerchantCommand(
                        command.merchantName(),
                        command.storeName(),
                        command.subdomain()
                )
        );

        if (identities.lockForRegistration(merchant.storeId(), email).customer()) {
            throw new EmailAlreadyRegisteredException(email);
        }

        try {
            accountRepository.saveAndFlush(MerchantAccount.create(
                    merchant.merchantId(),
                    email,
                    passwordEncoder.encode(command.password())
            ));
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueConstraintViolation(exception)) {
                throw new EmailAlreadyRegisteredException(email);
            }
            throw exception;
        }

        return merchant;
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private boolean isEmailUniqueConstraintViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return EMAIL_UNIQUE_CONSTRAINT.equals(violation.getConstraintName());
            }
        }
        return false;
    }
}
