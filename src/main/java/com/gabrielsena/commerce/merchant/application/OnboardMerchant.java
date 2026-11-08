package com.gabrielsena.commerce.merchant.application;

import com.gabrielsena.commerce.merchant.MerchantRepository;
import com.gabrielsena.commerce.merchant.domain.Merchant;
import com.gabrielsena.commerce.store.StoreRepository;
import com.gabrielsena.commerce.store.SubdomainAlreadyInUseException;
import com.gabrielsena.commerce.store.domain.Store;
import com.gabrielsena.commerce.store.domain.Subdomain;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardMerchant {

    private final MerchantRepository merchantRepository;
    private final StoreRepository storeRepository;

    public OnboardMerchant(
            MerchantRepository merchantRepository,
            StoreRepository storeRepository
    ) {
        this.merchantRepository = merchantRepository;
        this.storeRepository = storeRepository;
    }

    @Transactional
    public OnboardMerchantResult execute(OnboardMerchantCommand command) {

        Subdomain subdomain = Subdomain.of(command.subdomain());

        if (storeRepository.existsBySubdomain(subdomain)) {
            throw new SubdomainAlreadyInUseException(subdomain.getValue());
        }

        Merchant merchant = Merchant.create(command.merchantName());

        Store store = Store.create(
                merchant.getId(),
                command.storeName(),
                subdomain
        );

        merchantRepository.save(merchant);
        try {
            storeRepository.saveAndFlush(store);
        } catch (DataIntegrityViolationException exception) {
            if (isSubdomainUniqueConstraintViolation(exception)) {
                throw new SubdomainAlreadyInUseException(subdomain.getValue());
            }
            throw exception;
        }

        return new OnboardMerchantResult(
                merchant.getId(),
                store.getId(),
                merchant.getName(),
                store.getName(),
                store.getSubdomain().getValue()
        );
    }

    private boolean isSubdomainUniqueConstraintViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return "uk_stores_subdomain".equals(violation.getConstraintName());
            }
        }
        return false;
    }
}
