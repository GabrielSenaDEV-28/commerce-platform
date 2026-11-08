package com.gabrielsena.commerce.merchant.application;

import com.gabrielsena.commerce.merchant.MerchantRepository;
import com.gabrielsena.commerce.merchant.domain.Merchant;
import com.gabrielsena.commerce.store.StoreRepository;
import com.gabrielsena.commerce.store.SubdomainAlreadyInUseException;
import com.gabrielsena.commerce.store.domain.Store;
import com.gabrielsena.commerce.store.domain.Subdomain;

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
        storeRepository.save(store);

        return new OnboardMerchantResult(
                merchant.getId(),
                store.getId(),
                merchant.getName(),
                store.getName(),
                store.getSubdomain().getValue()
        );
    }
}
